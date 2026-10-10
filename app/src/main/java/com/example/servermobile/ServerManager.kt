package com.example.servermobile

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.http.content.*
import io.ktor.serialization.gson.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ApiEndpoint(val path: String, val response: String)

data class ServerSettings(
    val port: Int = 8080,
    val maxConnections: Int = 32,
    val uploadLimitKbps: Int = 0
)

data class ConnectedClient(val address: String, val activeRequests: Int, val lastSeenAt: Long)

data class BandwidthStats(val uploadBytesPerSecond: Long = 0L, val totalUploadedBytes: Long = 0L)

data class SharedFileMetadata(
    val lastModified: Long,
    val creationTime: Long,
    val mimeType: String
)

data class SharedFile(
    val document: DocumentFile,
    val relativePath: String
) {
    val name: String get() = document.name ?: relativePath.substringAfterLast('/')
    val isDirectory: Boolean get() = document.isDirectory
    val isFile: Boolean get() = document.isFile
    val size: Long get() = document.length()
}

class ServerManager(context: Context) {

    private val appContext = context.applicationContext
    private val contentResolver = appContext.contentResolver
    private val preferences = appContext.getSharedPreferences("server_settings", Context.MODE_PRIVATE)

    @Volatile
    private var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null
    val isRunning: Boolean get() = server != null

    private val _settings = MutableStateFlow(
        ServerSettings(
            port = preferences.getInt("port", 8080),
            maxConnections = preferences.getInt("max_connections", 32),
            uploadLimitKbps = preferences.getInt("upload_limit_kbps", 0)
        )
    )
    val settings: StateFlow<ServerSettings> = _settings

    // State Flows for UI
    private val _activeConnections = MutableStateFlow(0)
    val activeConnections: StateFlow<Int> = _activeConnections

    private val _connectedClients = MutableStateFlow<List<ConnectedClient>>(emptyList())
    val connectedClients: StateFlow<List<ConnectedClient>> = _connectedClients

    private val _bandwidth = MutableStateFlow(BandwidthStats())
    val bandwidth: StateFlow<BandwidthStats> = _bandwidth

    private val _serverStartTime = MutableStateFlow(0L)
    val serverStartTime: StateFlow<Long> = _serverStartTime

    private val _runningPort = MutableStateFlow(0)
    val runningPort: StateFlow<Int> = _runningPort

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs

    private val _devices = MutableStateFlow<Set<String>>(emptySet())
    val devices: StateFlow<Set<String>> = _devices

    private val _customApis = MutableStateFlow<List<ApiEndpoint>>(emptyList())
    val customApis: StateFlow<List<ApiEndpoint>> = _customApis

    private val connectionLock = Any()
    private val activeRequestsByAddress = mutableMapOf<String, Int>()
    private val lastSeenByAddress = mutableMapOf<String, Long>()
    private val bandwidthLock = Any()
    private val uploadSamples = ArrayDeque<Pair<Long, Int>>()
    private var nextUploadNanos = 0L
    private var totalUploadedBytes = 0L

    private val sharedTreeUri: Uri?
        get() = preferences.getString("shared_folder_uri", null)?.let(Uri::parse)

    private fun requireSharedFolder(): DocumentFile =
        sharedTreeUri?.let { DocumentFile.fromTreeUri(appContext, it) }
            ?.takeIf { it.isDirectory }
            ?: error("Escolha uma pasta compartilhada antes de iniciar o servidor.")

    private fun excludedSharedPaths(): Set<String> =
        preferences.getStringSet("excluded_shared_paths", emptySet()) ?: emptySet()

    private fun isExcludedFromSharing(file: SharedFile): Boolean =
        excludedSharedPaths().any { excludedPath ->
            file.relativePath == excludedPath || file.relativePath.startsWith("$excludedPath/")
        }

    private fun removeExcludedPath(file: SharedFile) {
        val path = file.relativePath
        val updatedPaths = excludedSharedPaths()
            .filterNot { it == path || it.startsWith("$path/") }
            .toSet()
        preferences.edit().putStringSet("excluded_shared_paths", updatedPaths).apply()
    }

    private fun metadataPreferenceKey(type: String, file: SharedFile): String =
        "file_${type}:${file.relativePath}"

    private fun saveFileMetadata(
        file: SharedFile,
        mimeType: String?,
        lastModified: Long?,
        creationTime: Long?
    ) {
        val editor = preferences.edit()
        if (mimeType.isNullOrBlank()) {
            editor.remove(metadataPreferenceKey("mime", file))
        } else {
            editor.putString(metadataPreferenceKey("mime", file), mimeType)
        }
        if (lastModified == null) editor.remove(metadataPreferenceKey("last_modified", file))
        else editor.putLong(metadataPreferenceKey("last_modified", file), lastModified)
        if (creationTime == null) editor.remove(metadataPreferenceKey("creation_time", file))
        else editor.putLong(metadataPreferenceKey("creation_time", file), creationTime)
        editor.apply()
    }

    private fun removeFileMetadata(file: SharedFile) {
        val relativePath = file.relativePath
        val editor = preferences.edit()
        preferences.all.keys
            .filter { key ->
                val path = key.substringAfter(':', "")
                key.startsWith("file_") &&
                    (path == relativePath || path.startsWith("$relativePath/"))
            }
            .forEach(editor::remove)
        editor.apply()
    }

    fun getFileMetadata(file: SharedFile): SharedFileMetadata {
        val modified = preferences.getLong(
            metadataPreferenceKey("last_modified", file),
            file.document.lastModified()
        )
        return SharedFileMetadata(
            lastModified = modified,
            creationTime = preferences.getLong(metadataPreferenceKey("creation_time", file), modified),
            mimeType = preferences.getString(metadataPreferenceKey("mime", file), null)
                ?.takeUnless { it == ContentType.Application.OctetStream.toString() }
                ?: file.document.type
                ?: ContentType.Application.OctetStream.toString()
        )
    }

    fun getSharedFolderPath(): String =
        preferences.getString("shared_folder_name", null) ?: "Nenhuma pasta selecionada"

    fun hasSharedFolder(): Boolean {
        val uri = sharedTreeUri ?: return false
        return contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }
    }

    fun useFolderAsShared(treeUri: Uri): Boolean {
        val previousUri = sharedTreeUri
        return try {
            contentResolver.takePersistableUriPermission(
                treeUri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            val folder = DocumentFile.fromTreeUri(appContext, treeUri)
            if (folder == null || !folder.isDirectory || !folder.canRead() || !folder.canWrite()) {
                if (previousUri != treeUri) releaseFolderGrant(treeUri)
                return false
            }
            preferences.edit()
                .putString("shared_folder_uri", treeUri.toString())
                .putString("shared_folder_name", folder.name)
                .apply()
            if (previousUri != null && previousUri != treeUri) {
                releaseFolderGrant(previousUri)
            }
            true
        } catch (e: Exception) {
            if (previousUri != treeUri) releaseFolderGrant(treeUri)
            addLog("Falha ao conceder acesso à pasta: ${e.message}")
            false
        }
    }

    private fun releaseFolderGrant(uri: Uri) {
        try {
            val grant = contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri } ?: return
            var flags = 0
            if (grant.isReadPermission) flags = flags or android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            if (grant.isWritePermission) flags = flags or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            if (flags != 0) contentResolver.releasePersistableUriPermission(uri, flags)
        } catch (error: Exception) {
            addLog("Não foi possível revogar o acesso antigo à pasta: ${error.message}")
        }
    }

    fun addLog(message: String) {
        val currentLogs = _logs.value.toMutableList()
        currentLogs.add(0, "[${getCurrentTime()}] $message")
        if (currentLogs.size > 100) currentLogs.removeAt(currentLogs.lastIndex)
        _logs.value = currentLogs
    }

    fun updateSettings(settings: ServerSettings) {
        require(settings.port in 1..65535) { "A porta deve estar entre 1 e 65535." }
        require(settings.maxConnections in 1..100) { "O limite de conexões deve estar entre 1 e 100." }
        require(settings.uploadLimitKbps in 0..102400) { "O limite de upload deve estar entre 0 e 102400 KB/s." }

        preferences.edit()
            .putInt("port", settings.port)
            .putInt("max_connections", settings.maxConnections)
            .putInt("upload_limit_kbps", settings.uploadLimitKbps)
            .apply()
        _settings.value = settings
    }

    @Synchronized
    fun startServer(port: Int = _settings.value.port) {
        if (server != null) return
        requireSharedFolder()

        _serverStartTime.value = System.currentTimeMillis()
        _logs.value = listOf("[${getCurrentTime()}] Servidor iniciado na porta $port")
        _devices.value = emptySet()
        _activeConnections.value = 0
        synchronized(connectionLock) {
            activeRequestsByAddress.clear()
            lastSeenByAddress.clear()
            _connectedClients.value = emptyList()
        }
        synchronized(bandwidthLock) {
            uploadSamples.clear()
            totalUploadedBytes = 0L
            nextUploadNanos = 0L
            _bandwidth.value = BandwidthStats()
        }

        server = embeddedServer(Netty, port = port) {
            install(CORS) {
                anyHost()
                allowHeader(HttpHeaders.ContentType)
                allowMethod(HttpMethod.Options)
                allowMethod(HttpMethod.Put)
                allowMethod(HttpMethod.Delete)
                allowMethod(HttpMethod.Patch)
            }
            install(ContentNegotiation) {
                gson {
                    setPrettyPrinting()
                }
            }
            // Connection and log tracking
            intercept(ApplicationCallPipeline.Monitoring) {
                val ip = call.request.origin.remoteHost
                val path = call.request.uri
                if (!beginRequest(ip, _settings.value.maxConnections)) {
                    call.respond(HttpStatusCode.ServiceUnavailable, "Limite de conexões atingido")
                    finish()
                    return@intercept
                }

                addLog("$ip -> $path")

                try {
                    proceed()
                } finally {
                    refreshBandwidth()
                    endRequest(ip)
                }
            }

            routing {
                // Rota raiz - Serve a interface web
                get("/") {
                    call.respondText(
                        FrontendUI.HTML_CONTENT,
                        ContentType.Text.Html
                    )
                }

                // API: Listar arquivos na pasta solicitada
                get("/api/files") {
                    val directory = withContext(Dispatchers.IO) {
                        resolveSharedPath(call.request.queryParameters["path"])
                            ?.takeIf { it.isDirectory }
                    }
                    if (directory == null) {
                        call.respond(HttpStatusCode.NotFound, "Pasta não encontrada")
                        return@get
                    }
                    val files = withContext(Dispatchers.IO) {
                        listChildren(directory).map { child ->
                            val metadata = getFileMetadata(child)
                            mapOf(
                                "name" to child.name,
                                "isDirectory" to child.isDirectory,
                                "size" to child.size,
                                "mimeType" to if (child.isDirectory) "" else metadata.mimeType,
                                "lastModified" to metadata.lastModified,
                                "creationTime" to metadata.creationTime
                            )
                        }
                    }
                    call.respond(files)
                }

                // Browser uploads go to the root of the folder selected by the user.
                post("/api/upload") {
                    val fileName = call.request.queryParameters["filename"]
                    if (fileName == null || !isValidChildName(fileName)) {
                        call.respond(HttpStatusCode.BadRequest, "Nome de arquivo inválido")
                        return@post
                    }
                    if (fileName.startsWith(TEMP_UPLOAD_PREFIX)) {
                        call.respond(HttpStatusCode.BadRequest, "Nome de arquivo reservado")
                        return@post
                    }
                    val sourceLastModified = call.request.headers["X-File-Last-Modified"]
                        ?.toLongOrNull()
                        ?.takeIf { it >= 0L }
                    val sourceMimeType = call.request.headers[HttpHeaders.ContentType]
                        ?.let {
                            try {
                                ContentType.parse(it).toString()
                            } catch (_: IllegalArgumentException) {
                                null
                            }
                        }
                    if (call.request.headers["X-File-Last-Modified"] != null && sourceLastModified == null) {
                        call.respond(HttpStatusCode.BadRequest, "Data de modificação inválida")
                        return@post
                    }
                    if (call.request.headers[HttpHeaders.ContentType] != null && sourceMimeType == null) {
                        call.respond(HttpStatusCode.BadRequest, "Tipo de arquivo inválido")
                        return@post
                    }

                    val directory = withContext(Dispatchers.IO) { requireSharedFolder() }
                    if (withContext(Dispatchers.IO) { directory.findFile(fileName) != null }) {
                        call.respond(HttpStatusCode.Conflict, "Já existe um arquivo com esse nome")
                        return@post
                    }

                    var temporaryFile: DocumentFile? = null
                    try {
                        val uploadFile = withContext(Dispatchers.IO) {
                            directory.createFile(
                                sourceMimeType ?: ContentType.Application.OctetStream.toString(),
                                "$TEMP_UPLOAD_PREFIX${java.util.UUID.randomUUID()}.part"
                            )
                        } ?: error("Não foi possível criar o arquivo temporário.")
                        temporaryFile = uploadFile
                        call.receiveStream().use { input ->
                            withContext(Dispatchers.IO) {
                                contentResolver.openOutputStream(uploadFile.uri, "w")
                                    ?.buffered(64 * 1024)?.use { output ->
                                    input.copyTo(output, bufferSize = 64 * 1024)
                                } ?: error("Não foi possível abrir o arquivo para gravação.")
                            }
                        }
                        if (!withContext(Dispatchers.IO) { uploadFile.renameTo(fileName) }) {
                            error("Não foi possível finalizar o arquivo enviado.")
                        }
                        temporaryFile = null
                        val destination = withContext(Dispatchers.IO) { directory.findFile(fileName) }
                            ?: error("O arquivo enviado não foi encontrado após a gravação.")
                        saveFileMetadata(
                            SharedFile(destination, fileName),
                            sourceMimeType,
                            sourceLastModified,
                            null
                        )
                        addLog("Arquivo recebido: $fileName")
                        call.respond(HttpStatusCode.Created, "Arquivo enviado")
                    } catch (e: Exception) {
                        addLog("Falha ao receber $fileName: ${e.message ?: "erro desconhecido"}")
                        call.respond(HttpStatusCode.InternalServerError, "Não foi possível salvar o arquivo")
                    } finally {
                        withContext(Dispatchers.IO) { temporaryFile?.delete() }
                    }
                }

                // API: Download de arquivo
                get("/download") {
                    val path = call.request.queryParameters["path"]
                    if (path == null) {
                        call.respond(HttpStatusCode.BadRequest, "Nome de arquivo não fornecido")
                        return@get
                    }
                    val file = withContext(Dispatchers.IO) {
                        resolveSharedPath(path)?.takeIf { it.isFile && !isExcludedFromSharing(it) }
                    }
                    if (file != null) {
                        respondFile(call, file, attachment = true)
                    } else {
                        call.respond(HttpStatusCode.NotFound, "Arquivo não encontrado")
                    }
                }

                // API: Streaming / Visualização
                get("/file") {
                    val path = call.request.queryParameters["path"]
                    if (path == null) {
                        call.respond(HttpStatusCode.BadRequest, "Nome de arquivo não fornecido")
                        return@get
                    }
                    val file = withContext(Dispatchers.IO) {
                        resolveSharedPath(path)?.takeIf { it.isFile && !isExcludedFromSharing(it) }
                    }
                    if (file != null) {
                        respondFile(call, file)
                    } else {
                        call.respond(HttpStatusCode.NotFound, "Arquivo não encontrado")
                    }
                }

                // Compatibilidade com links antigos para arquivos na raiz.
                get("/download/{filename}") {
                    val filename = call.parameters["filename"] ?: return@get call.respond(
                        HttpStatusCode.BadRequest, "Nome de arquivo não fornecido"
                    )
                    val file = withContext(Dispatchers.IO) {
                        resolveSharedPath(filename)?.takeIf { it.isFile && !isExcludedFromSharing(it) }
                    }
                    if (file != null) {
                        respondFile(call, file, attachment = true)
                    } else {
                        call.respond(HttpStatusCode.NotFound, "Arquivo não encontrado")
                    }
                }
                get("/file/{filename}") {
                    val filename = call.parameters["filename"] ?: return@get call.respond(
                        HttpStatusCode.BadRequest, "Nome de arquivo não fornecido"
                    )
                    val file = withContext(Dispatchers.IO) {
                        resolveSharedPath(filename)?.takeIf { it.isFile && !isExcludedFromSharing(it) }
                    }
                    if (file != null) {
                        respondFile(call, file)
                    } else {
                        call.respond(HttpStatusCode.NotFound, "Arquivo não encontrado")
                    }
                }

                // API: Custom APIs dinâmicas
                get("/api/custom/{path...}") {
                    val reqPath = call.parameters.getAll("path")?.joinToString("/") ?: ""
                    val api = _customApis.value.find { it.path == reqPath }
                    if (api != null) {
                        call.respondText(api.response, ContentType.Application.Json)
                    } else {
                        call.respond(HttpStatusCode.NotFound, "API não encontrada")
                    }
                }
            }
        }.start(wait = false)
        _runningPort.value = port
    }

    @Synchronized
    fun stopServer() {
        server?.stop(1000, 2000)
        server = null
        
        val currentLogs = _logs.value.toMutableList()
        currentLogs.add(0, "[${getCurrentTime()}] Servidor parado")
        _logs.value = currentLogs
        
        _activeConnections.value = 0
        synchronized(connectionLock) {
            activeRequestsByAddress.clear()
            lastSeenByAddress.clear()
            _connectedClients.value = emptyList()
            _devices.value = emptySet()
        }
        _serverStartTime.value = 0L
        _runningPort.value = 0
    }

    fun refreshBandwidth() {
        synchronized(bandwidthLock) {
            updateBandwidthLocked(System.nanoTime())
        }
    }

    private fun beginRequest(address: String, maxConnections: Int): Boolean = synchronized(connectionLock) {
        if (_activeConnections.value >= maxConnections) return@synchronized false
        activeRequestsByAddress[address] = (activeRequestsByAddress[address] ?: 0) + 1
        lastSeenByAddress[address] = System.currentTimeMillis()
        _activeConnections.value += 1
        publishClients()
        true
    }

    private fun endRequest(address: String) {
        synchronized(connectionLock) {
            val requestCount = activeRequestsByAddress[address] ?: 0
            if (requestCount <= 1) activeRequestsByAddress.remove(address)
            else activeRequestsByAddress[address] = requestCount - 1
            _activeConnections.value = (_activeConnections.value - 1).coerceAtLeast(0)
            publishClients()
        }
    }

    private fun publishClients() {
        _connectedClients.value = lastSeenByAddress.map { (address, lastSeenAt) ->
            ConnectedClient(address, activeRequestsByAddress[address] ?: 0, lastSeenAt)
        }.sortedByDescending { it.lastSeenAt }
        _devices.value = lastSeenByAddress.keys.toSet()
    }

    private fun recordUpload(byteCount: Int, now: Long) {
        synchronized(bandwidthLock) {
            uploadSamples.addLast(now to byteCount)
            totalUploadedBytes += byteCount
            updateBandwidthLocked(now)
        }
    }

    private fun updateBandwidthLocked(now: Long) {
        val windowStart = now - 1_000_000_000L
        while (uploadSamples.isNotEmpty() && uploadSamples.first().first < windowStart) {
            uploadSamples.removeFirst()
        }
        val bytesInWindow = uploadSamples.sumOf { it.second.toLong() }
        _bandwidth.value = BandwidthStats(bytesInWindow, totalUploadedBytes)
    }

    private suspend fun paceUpload(byteCount: Int) {
        val limitBytesPerSecond = _settings.value.uploadLimitKbps.toLong() * 1024L
        if (limitBytesPerSecond <= 0L) return

        val now = System.nanoTime()
        val waitNanos = synchronized(bandwidthLock) {
            val scheduledAt = maxOf(now, nextUploadNanos)
            val interval = (byteCount * 1_000_000_000.0 / limitBytesPerSecond).toLong()
            nextUploadNanos = scheduledAt + interval
            scheduledAt - now
        }
        if (waitNanos > 0L) delay((waitNanos + 999_999L) / 1_000_000L)
    }

    private suspend fun respondFile(
        call: ApplicationCall,
        file: SharedFile,
        attachment: Boolean = false
    ) {
        val (size, metadata, fileName) = withContext(Dispatchers.IO) {
            Triple(file.size, getFileMetadata(file), file.name)
        }
        val rangeHeader = call.request.headers[HttpHeaders.Range]
        val range = rangeHeader?.let { parseByteRange(it, size) }
        if (rangeHeader != null && range == null) {
            call.response.header(HttpHeaders.ContentRange, "bytes */$size")
            call.respond(HttpStatusCode(416, "Range Not Satisfiable"))
            return
        }

        val start = range?.first ?: 0L
        val end = range?.last ?: (size - 1L)
        val responseLength = if (size == 0L) 0L else end - start + 1L
        val status = if (range == null) HttpStatusCode.OK else HttpStatusCode.PartialContent
        val mimeType = metadata.mimeType
        val lastModifiedHeader = DateTimeFormatter.RFC_1123_DATE_TIME
            .withZone(ZoneOffset.UTC)
            .format(Instant.ofEpochMilli(metadata.lastModified))
        call.respond(object : OutgoingContent.WriteChannelContent() {
            override val contentType = ContentType.parse(mimeType)
            override val contentLength = responseLength
            override val status = status
            override val headers = Headers.build {
                append(HttpHeaders.AcceptRanges, "bytes")
                append(HttpHeaders.LastModified, lastModifiedHeader)
                append("X-File-Creation-Time", metadata.creationTime.toString())
                if (range != null) append(HttpHeaders.ContentRange, "bytes $start-$end/$size")
                if (attachment) {
                    append(
                        HttpHeaders.ContentDisposition,
                        ContentDisposition.Attachment
                            .withParameter(ContentDisposition.Parameters.FileName, fileName)
                            .toString()
                    )
                }
            }

            override suspend fun writeTo(channel: ByteWriteChannel) {
                withContext(Dispatchers.IO) {
                    openFileInput(file).buffered(64 * 1024).use { input ->
                        skipFully(input, start)
                        var remaining = responseLength
                        val buffer = ByteArray(64 * 1024)
                        while (remaining > 0L) {
                            val currentLimitBytesPerSecond = _settings.value.uploadLimitKbps.toLong() * 1024L
                            val configuredChunkSize = if (currentLimitBytesPerSecond == 0L) {
                                buffer.size
                            } else {
                                minOf(buffer.size.toLong(), maxOf(1024L, currentLimitBytesPerSecond / 4L)).toInt()
                            }
                            val count = input.read(
                                buffer,
                                0,
                                minOf(configuredChunkSize.toLong(), remaining).toInt()
                            )
                            if (count < 0) break
                            paceUpload(count)
                            channel.writeFully(buffer, 0, count)
                            recordUpload(count, System.nanoTime())
                            remaining -= count
                        }
                    }
                }
            }
        })
    }

    private fun parseByteRange(value: String, size: Long): LongRange? {
        if (size <= 0L || !value.startsWith("bytes=") || value.contains(',')) return null
        val range = value.removePrefix("bytes=").trim()
        val separator = range.indexOf('-')
        if (separator < 0) return null
        val firstText = range.substring(0, separator)
        val lastText = range.substring(separator + 1)
        val last = if (lastText.isEmpty()) null else lastText.toLongOrNull() ?: return null
        if (firstText.isEmpty()) {
            val suffixLength = last ?: return null
            if (suffixLength <= 0L) return null
            return (size - suffixLength.coerceAtMost(size)) until size
        }
        val first = firstText.toLongOrNull() ?: return null
        if (first < 0L || first >= size) return null
        val end = (last ?: (size - 1L)).coerceAtMost(size - 1L)
        if (end < first) return null
        return first..end
    }

    private fun skipFully(input: InputStream, byteCount: Long) {
        var remaining = byteCount
        while (remaining > 0L) {
            val skipped = input.skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
            } else {
                if (input.read() < 0) error("O arquivo terminou antes do intervalo solicitado.")
                remaining--
            }
        }
    }

    private fun getCurrentTime(): String {
        return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
    }

    fun getLocalFiles(): List<SharedFile> {
        if (sharedTreeUri == null) return emptyList()
        val folder = requireSharedFolder()
        return listChildren(SharedFile(folder, ""))
    }

    private fun listChildren(directory: SharedFile): List<SharedFile> =
        directory.document.listFiles().mapNotNull { document ->
            val name = document.name ?: return@mapNotNull null
            if (!isValidChildName(name) || name.startsWith(TEMP_UPLOAD_PREFIX)) return@mapNotNull null
            val path = if (directory.relativePath.isEmpty()) name else "${directory.relativePath}/$name"
            SharedFile(document, path).takeUnless(::isExcludedFromSharing)
        }

    fun openFileInput(file: SharedFile): InputStream =
        contentResolver.openInputStream(file.document.uri)
            ?: error("Não foi possível abrir ${file.name} para leitura.")

    fun copyToPrivateFile(source: SharedFile, destination: File) {
        require(source.isFile || source.isDirectory) { "O item compartilhado não está disponível." }
        if (source.isDirectory) {
            check(!destination.exists() && destination.mkdirs()) {
                "Não foi possível criar a pasta temporária para o BitTorrent."
            }
            listChildren(source).forEach { child ->
                copyToPrivateFile(child, File(destination, child.name))
            }
        } else {
            check(!destination.exists()) { "O destino temporário já existe." }
            destination.parentFile?.let { parent ->
                check(parent.exists() || parent.mkdirs()) {
                    "Não foi possível criar a pasta temporária para o BitTorrent."
                }
            }
            openFileInput(source).buffered().use { input ->
                FileOutputStream(destination).buffered().use { output ->
                    input.copyTo(output, bufferSize = 64 * 1024)
                }
            }
        }
    }

    fun deleteLocalFile(fileName: String, deleteFromStorage: Boolean): Boolean {
        val file = resolveSharedPath(fileName) ?: return false
        if (!deleteFromStorage) {
            val updatedPaths = excludedSharedPaths().toMutableSet().apply {
                add(file.relativePath)
            }
            preferences.edit().putStringSet("excluded_shared_paths", updatedPaths).apply()
            return true
        }
        val deleted = file.document.delete()
        if (deleted) {
            removeExcludedPath(file)
            removeFileMetadata(file)
        }
        return deleted
    }

    fun renameLocalFile(oldName: String, newName: String): Boolean {
        val oldFile = resolveSharedPath(oldName) ?: return false
        if (newName.isBlank() || newName == "." || newName == ".." ||
            newName.contains('/') || newName.contains('\\')
        ) return false
        val parentPath = oldFile.relativePath.substringBeforeLast('/', "")
        val newPath = if (parentPath.isEmpty()) newName else "$parentPath/$newName"
        val root = requireSharedFolder()
        val parent = if (parentPath.isEmpty()) root else resolveSharedPath(parentPath)?.document
            ?: return false
        if (parent.findFile(newName) != null) return false

        val metadataToMove = preferences.all.filterKeys { key ->
            val path = key.substringAfter(':', "")
            key.startsWith("file_") && (
                path == oldFile.relativePath || path.startsWith("${oldFile.relativePath}/")
            )
        }
        val excludedToMove = excludedSharedPaths()
            .filter { it == oldFile.relativePath || it.startsWith("${oldFile.relativePath}/") }
        val renamed = oldFile.document.renameTo(newName)
        if (renamed) {
            val editor = preferences.edit()
            metadataToMove.forEach { (key, value) ->
                val type = key.substringBefore(':')
                val path = key.substringAfter(':')
                val destinationKey = "$type:$newPath${path.removePrefix(oldFile.relativePath)}"
                editor.remove(key)
                when (value) {
                    is String -> editor.putString(destinationKey, value)
                    is Long -> editor.putLong(destinationKey, value)
                    is Int -> editor.putInt(destinationKey, value)
                    is Boolean -> editor.putBoolean(destinationKey, value)
                    is Float -> editor.putFloat(destinationKey, value)
                }
            }
            val updatedExcluded = excludedSharedPaths().toMutableSet().apply {
                removeAll(excludedToMove.toSet())
                addAll(excludedToMove.map { newPath + it.removePrefix(oldFile.relativePath) })
            }
            editor.putStringSet("excluded_shared_paths", updatedExcluded)
            editor.apply()
        }
        return renamed
    }

    private fun isValidChildName(name: String): Boolean =
        name.isNotBlank() && name != "." && name != ".." &&
            !name.contains('/') && !name.contains('\\')

    private fun resolveSharedPath(path: String?): SharedFile? {
        val root = requireSharedFolder()
        if (path.isNullOrBlank()) return SharedFile(root, "")

        val segments = path.split('/')
        if (segments.any { !isValidChildName(it) || it.startsWith(TEMP_UPLOAD_PREFIX) }) return null
        var current = root
        var relativePath = ""
        for (segment in segments) {
            if (!current.isDirectory) return null
            current = current.findFile(segment) ?: return null
            relativePath = if (relativePath.isEmpty()) segment else "$relativePath/$segment"
        }
        return SharedFile(current, relativePath)
    }

    fun addApi(path: String, response: String) {
        val current = _customApis.value.toMutableList()
        current.removeAll { it.path == path } // substitui se já existir
        current.add(ApiEndpoint(path, response))
        _customApis.value = current
    }

    fun removeApi(path: String) {
        _customApis.value = _customApis.value.filter { it.path != path }
    }

    companion object {
        private const val TEMP_UPLOAD_PREFIX = ".servermobile-upload-"
    }
}
