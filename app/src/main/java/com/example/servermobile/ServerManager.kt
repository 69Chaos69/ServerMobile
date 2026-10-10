package com.example.servermobile

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
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
import java.io.RandomAccessFile
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
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

class ServerManager(context: Context) {

    private val preferences = context.applicationContext.getSharedPreferences("server_settings", Context.MODE_PRIVATE)

    private var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null

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

    private val serverFolder: File
        get() {
            val configuredPath = preferences.getString("shared_folder_path", null)
            return configuredPath?.takeIf { it.isNotBlank() }?.let(::File)
                ?: defaultServerFolder()
        }

    private fun defaultServerFolder(): File =
        File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "ServerMobile"
        )

    private fun excludedSharedPaths(): Set<String> =
        preferences.getStringSet("excluded_shared_paths", emptySet()) ?: emptySet()

    private fun isExcludedFromSharing(file: File): Boolean {
        val path = file.canonicalPath
        return excludedSharedPaths().any { excludedPath ->
            path == excludedPath || path.startsWith(excludedPath + File.separator)
        }
    }

    private fun removeExcludedPath(file: File) {
        val path = file.canonicalPath
        val updatedPaths = excludedSharedPaths()
            .filterNot { it == path || it.startsWith(path + File.separator) }
            .toSet()
        preferences.edit().putStringSet("excluded_shared_paths", updatedPaths).apply()
    }

    private fun mimeTypePreferenceKey(file: File): String =
        "file_mime:${file.canonicalPath}"

    private fun saveMimeType(file: File, mimeType: String?) {
        val editor = preferences.edit()
        if (mimeType.isNullOrBlank()) {
            editor.remove(mimeTypePreferenceKey(file))
        } else {
            editor.putString(mimeTypePreferenceKey(file), mimeType)
        }
        editor.apply()
    }

    private fun removeMimeTypes(file: File) {
        val pathPrefix = mimeTypePreferenceKey(file)
        val editor = preferences.edit()
        preferences.all.keys
            .filter { it == pathPrefix || it.startsWith("$pathPrefix${File.separator}") }
            .forEach(editor::remove)
        editor.apply()
    }

    fun getFileMetadata(file: File): SharedFileMetadata {
        val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
        return SharedFileMetadata(
            lastModified = attributes.lastModifiedTime().toMillis(),
            creationTime = attributes.creationTime().toMillis(),
            mimeType = preferences.getString(mimeTypePreferenceKey(file), null)
                ?.takeUnless { it == ContentType.Application.OctetStream.toString() }
                ?: getMimeType(file)
        )
    }

    private fun applyFileTimes(file: File, lastModified: Long?, creationTime: Long?) {
        if (lastModified != null && !file.setLastModified(lastModified)) {
            addLog("Aviso: não foi possível manter a data de modificação de ${file.name}")
        }
        if (creationTime != null) {
            try {
                val view = Files.getFileAttributeView(
                    file.toPath(),
                    java.nio.file.attribute.BasicFileAttributeView::class.java
                )
                view.setTimes(
                    null,
                    null,
                    java.nio.file.attribute.FileTime.fromMillis(creationTime)
                )
            } catch (e: Exception) {
                addLog("Aviso: o armazenamento não permite manter a data de criação de ${file.name}")
            }
        }
    }

    fun getSharedFolderPath(): String = serverFolder.absolutePath

    fun useFolderAsShared(treeUri: Uri): Boolean {
        if (treeUri.authority != "com.android.externalstorage.documents") return false

        return try {
            val documentId = DocumentsContract.getTreeDocumentId(treeUri)
            val separator = documentId.indexOf(':')
            if (separator <= 0) return false

            val volume = documentId.substring(0, separator)
            val relativePath = documentId.substring(separator + 1)
            if (volume != "primary" && !volume.matches(Regex("[A-Za-z0-9_-]+"))) return false
            val pathParts = relativePath.split('/').filter { it.isNotEmpty() }
            if (pathParts.any { it == "." || it == ".." || it.contains('\\') }) return false

            val volumeRoot = if (volume == "primary") {
                Environment.getExternalStorageDirectory()
            } else {
                File("/storage", volume)
            }.canonicalFile
            val selectedFolder = File(volumeRoot, relativePath).canonicalFile
            if (!selectedFolder.isDirectory ||
                (selectedFolder != volumeRoot &&
                    !selectedFolder.path.startsWith(volumeRoot.path + File.separator))
            ) {
                return false
            }

            preferences.edit().putString("shared_folder_path", selectedFolder.path).apply()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun addLog(message: String) {
        val currentLogs = _logs.value.toMutableList()
        currentLogs.add(0, "[${getCurrentTime()}] $message")
        if (currentLogs.size > 100) currentLogs.removeLast()
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

    fun startServer(port: Int = _settings.value.port) {
        if (server != null) return

        // Garantir que a pasta existe
        if (!serverFolder.exists()) {
            serverFolder.mkdirs()
        }

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
                    }
                    if (directory == null || !directory.isDirectory) {
                        call.respond(HttpStatusCode.NotFound, "Pasta não encontrada")
                        return@get
                    }
                    val files = withContext(Dispatchers.IO) {
                        directory.listFiles()?.map {
                            if (isExcludedFromSharing(it)) return@map null
                            val metadata = getFileMetadata(it)
                            mapOf(
                                "name" to it.name,
                                "isDirectory" to it.isDirectory,
                                "size" to it.length(),
                                "mimeType" to if (it.isDirectory) "" else metadata.mimeType,
                                "lastModified" to metadata.lastModified,
                                "creationTime" to metadata.creationTime
                            )
                        }?.filterNotNull()
                    } ?: emptyList()
                    call.respond(files)
                }

                // Browser uploads always go to the app's default Downloads folder.
                post("/api/upload") {
                    val fileName = call.request.queryParameters["filename"]
                    if (fileName == null || !isValidChildName(fileName)) {
                        call.respond(HttpStatusCode.BadRequest, "Nome de arquivo inválido")
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

                    val directory = withContext(Dispatchers.IO) {
                        defaultServerFolder().also {
                            if (!it.exists()) it.mkdirs()
                        }
                    }
                    if (!directory.isDirectory) {
                        call.respond(HttpStatusCode.InternalServerError, "Não foi possível criar a pasta de destino")
                        return@post
                    }

                    val destination = File(directory, fileName)
                    if (destination.exists()) {
                        call.respond(HttpStatusCode.Conflict, "Já existe um arquivo com esse nome")
                        return@post
                    }

                    var temporaryFile: File? = null
                    try {
                        val uploadFile = withContext(Dispatchers.IO) {
                            File.createTempFile(".up-", ".part", directory)
                        }
                        temporaryFile = uploadFile
                        call.receiveStream().use { input ->
                            withContext(Dispatchers.IO) {
                                uploadFile.outputStream().buffered(64 * 1024).use { output ->
                                    input.copyTo(output, bufferSize = 64 * 1024)
                                }
                            }
                        }
                        withContext(Dispatchers.IO) {
                            Files.move(uploadFile.toPath(), destination.toPath())
                            applyFileTimes(destination, sourceLastModified, null)
                            saveMimeType(destination, sourceMimeType)
                        }
                        addLog("Arquivo recebido: ${destination.relativeTo(directory).path}")
                        call.respond(HttpStatusCode.Created, "Arquivo enviado")
                    } catch (_: FileAlreadyExistsException) {
                        call.respond(HttpStatusCode.Conflict, "Já existe um arquivo com esse nome")
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
                    val file = withContext(Dispatchers.IO) { resolveSharedPath(path) }
                    if (file != null && file.exists() && !file.isDirectory &&
                        !withContext(Dispatchers.IO) { isExcludedFromSharing(file) }
                    ) {
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
                    val file = withContext(Dispatchers.IO) { resolveSharedPath(path) }
                    if (file != null && file.exists() && !file.isDirectory &&
                        !withContext(Dispatchers.IO) { isExcludedFromSharing(file) }
                    ) {
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
                    val file = withContext(Dispatchers.IO) { resolveSharedPath(filename) }
                    if (file != null && file.exists() && file.isFile &&
                        !withContext(Dispatchers.IO) { isExcludedFromSharing(file) }
                    ) {
                        respondFile(call, file, attachment = true)
                    } else {
                        call.respond(HttpStatusCode.NotFound, "Arquivo não encontrado")
                    }
                }
                get("/file/{filename}") {
                    val filename = call.parameters["filename"] ?: return@get call.respond(
                        HttpStatusCode.BadRequest, "Nome de arquivo não fornecido"
                    )
                    val file = withContext(Dispatchers.IO) { resolveSharedPath(filename) }
                    if (file != null && file.exists() && file.isFile &&
                        !withContext(Dispatchers.IO) { isExcludedFromSharing(file) }
                    ) {
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
        file: File,
        attachment: Boolean = false
    ) {
        val size = file.length()
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
        val metadata = withContext(Dispatchers.IO) { getFileMetadata(file) }
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
                            .withParameter(ContentDisposition.Parameters.FileName, file.name)
                            .toString()
                    )
                }
            }

            override suspend fun writeTo(channel: ByteWriteChannel) {
                withContext(Dispatchers.IO) {
                    RandomAccessFile(file, "r").use { input ->
                        input.seek(start)
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

    private fun getCurrentTime(): String {
        return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
    }

    private fun getMimeType(file: File): String {
        val extension = file.extension.lowercase(Locale.getDefault())
        val mimeMap = mapOf(
            "mp4" to "video/mp4",
            "mkv" to "video/x-matroska",
            "webm" to "video/webm",
            "mp3" to "audio/mpeg",
            "wav" to "audio/wav",
            "ogg" to "audio/ogg",
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "png" to "image/png",
            "gif" to "image/gif",
            "svg" to "image/svg+xml",
            "pdf" to "application/pdf"
        )
        if (mimeMap.containsKey(extension)) return mimeMap[extension]!!

        try {
            val bytes = ByteArray(32)
            file.inputStream().buffered().use { it.read(bytes) }
            
            if (bytes.size >= 4 && bytes[0] == '%'.code.toByte() && bytes[1] == 'P'.code.toByte() && bytes[2] == 'D'.code.toByte() && bytes[3] == 'F'.code.toByte()) return "application/pdf"
            if (bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte()) return "image/png"
            if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) return "image/jpeg"
            if (bytes.size >= 3 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte()) return "image/gif"
            
            val header = String(bytes, Charsets.ISO_8859_1)
            if (header.contains("ftyp")) return "video/mp4"
            if (header.contains("matroska") || header.contains("webm")) return "video/webm"
            
            if (bytes.size >= 3 && bytes[0] == 'I'.code.toByte() && bytes[1] == 'D'.code.toByte() && bytes[2] == '3'.code.toByte()) return "audio/mpeg"
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        return "application/octet-stream"
    }

    fun getLocalFiles(): List<File> {
        return serverFolder.listFiles()?.filterNot(::isExcludedFromSharing) ?: emptyList()
    }

    fun deleteLocalFile(fileName: String, deleteFromStorage: Boolean): Boolean {
        val file = resolveSharedPath(fileName) ?: return false
        if (!file.exists()) return false
        if (!deleteFromStorage) {
            val updatedPaths = excludedSharedPaths().toMutableSet().apply {
                add(file.canonicalPath)
            }
            preferences.edit().putStringSet("excluded_shared_paths", updatedPaths).apply()
            return true
        }
        val deleted = file.deleteRecursively()
        if (deleted) {
            removeExcludedPath(file)
            removeMimeTypes(file)
        }
        return deleted
    }

    fun renameLocalFile(oldName: String, newName: String): Boolean {
        val oldFile = resolveSharedPath(oldName) ?: return false
        if (newName.isBlank() || newName == "." || newName == ".." ||
            newName.contains('/') || newName.contains('\\')
        ) return false
        val newFile = resolveSharedPath(newName) ?: return false
        if (!oldFile.exists() || newFile.exists()) return false
        val mimeType = preferences.getString(mimeTypePreferenceKey(oldFile), null)
        val renamed = oldFile.renameTo(newFile)
        if (renamed && mimeType != null) {
            saveMimeType(oldFile, null)
            saveMimeType(newFile, mimeType)
        }
        return renamed
    }

    private fun isValidChildName(name: String): Boolean =
        name.isNotBlank() && name != "." && name != ".." &&
            !name.contains('/') && !name.contains('\\')

    private fun resolveSharedPath(path: String?): File? {
        val root = serverFolder.canonicalFile
        val target = if (path.isNullOrBlank()) root else File(root, path).canonicalFile
        return if (target == root || target.path.startsWith(root.path + File.separator)) target else null
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
}
