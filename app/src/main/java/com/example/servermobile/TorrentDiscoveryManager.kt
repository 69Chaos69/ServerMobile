package com.example.servermobile

import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Environment
import com.frostwire.jlibtorrent.AlertListener
import com.frostwire.jlibtorrent.SessionManager
import com.frostwire.jlibtorrent.SessionParams
import com.frostwire.jlibtorrent.TorrentBuilder
import com.frostwire.jlibtorrent.TorrentHandle
import com.frostwire.jlibtorrent.alerts.Alert
import com.frostwire.jlibtorrent.alerts.AlertType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

data class TorrentTransfer(
    val infoHash: String,
    val name: String,
    val progress: Float,
    val peerCount: Int,
    val downloadBytesPerSecond: Int,
    val uploadBytesPerSecond: Int,
    val isSeeding: Boolean,
    val error: String
)

/**
 * Manages trackerless BitTorrent transfers. DHT and LSD are session-level;
 * PEX is provided by libtorrent's default ut_pex plugin for non-private torrents.
 */
class TorrentDiscoveryManager(context: Context) {
    private val appContext = context.applicationContext
    private val sessionManager = SessionManager()
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _transfers = MutableStateFlow<List<TorrentTransfer>>(emptyList())
    val transfers: StateFlow<List<TorrentTransfer>> = _transfers
    private val _networkStatus = MutableStateFlow("Descoberta BitTorrent parada")
    val networkStatus: StateFlow<String> = _networkStatus
    private var multicastLock: WifiManager.MulticastLock? = null
    private var refreshJob: Job? = null
    private val lifecycleLock = Any()
    @Volatile private var closed = false
    private var starting = false
    @Volatile private var started = false

    suspend fun start() = withContext(Dispatchers.IO) {
        synchronized(lifecycleLock) {
            check(!closed) { "A descoberta BitTorrent foi encerrada." }
            if (started || starting) return@withContext
            starting = true
        }
        val params = SessionParams()
        params.getSettings().apply {
            setEnableDht(true)
            setEnableLsd(true)
            setDhtBootstrapNodes(DEFAULT_DHT_BOOTSTRAP_NODES)
        }
        sessionManager.addListener(object : AlertListener {
            override fun types(): IntArray? = null
            override fun alert(alert: Alert<*>) {
                if (alert.type() == AlertType.DHT_BOOTSTRAP) {
                    _networkStatus.value = "DHT conectado; descoberta local ativa"
                }
            }
        })
        val acquiredMulticastLock = try {
            wifiManager.createMulticastLock("ServerMobile-LSD").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (error: Exception) {
            synchronized(lifecycleLock) { starting = false }
            throw error
        }
        multicastLock = acquiredMulticastLock
        try {
            sessionManager.start(params)
        } catch (error: Throwable) {
            synchronized(lifecycleLock) { starting = false }
            if (acquiredMulticastLock.isHeld) acquiredMulticastLock.release()
            multicastLock = null
            throw error
        }
        val stopImmediately = synchronized(lifecycleLock) {
            starting = false
            if (closed) {
                true
            } else {
                started = true
                _networkStatus.value = "DHT iniciado; procurando nós bootstrap. LSD ativo na rede local"
                refreshJob = scope.launch {
                    while (true) {
                        refreshTransfers()
                        delay(1_000L)
                    }
                }
                false
            }
        }
        if (stopImmediately) {
            try {
                sessionManager.stop()
            } finally {
                if (acquiredMulticastLock.isHeld) acquiredMulticastLock.release()
                multicastLock = null
            }
            return@withContext
        }
    }

    suspend fun addMagnet(magnetUri: String) = withContext(Dispatchers.IO) {
        check(started) { "Inicie a descoberta antes de adicionar um magnet." }
        addValidatedMagnet(validateMagnet(magnetUri))
    }

    suspend fun addConnectionFile(contents: String) = withContext(Dispatchers.IO) {
        check(started) { "Inicie a descoberta antes de importar um arquivo." }
        val connectionFile = JSONObject(contents)
        require(connectionFile.optString("format") == CONNECTION_FILE_FORMAT) {
            "Este arquivo não é um arquivo de conexão ServerMobile compatível."
        }
        require(connectionFile.optInt("version") == CONNECTION_FILE_VERSION) {
            "A versão deste arquivo de conexão não é compatível."
        }
        require(connectionFile.optString("name").isNotBlank()) {
            "O arquivo de conexão não contém um nome válido."
        }
        val magnetUri = validateMagnet(connectionFile.optString("magnet"))
        require(
            connectionFile.optString("infoHash")
                .equals(magnetTopic(magnetUri), ignoreCase = true)
        ) {
            "O identificador do arquivo não corresponde ao magnet."
        }
        addValidatedMagnet(magnetUri)
    }

    fun createConnectionFile(name: String, magnetUri: String): String {
        val safeMagnet = validateMagnet(magnetUri)
        val safeName = name.trim()
        require(safeName.isNotBlank()) { "O nome do conteúdo está vazio." }
        return JSONObject()
            .put("format", CONNECTION_FILE_FORMAT)
            .put("version", CONNECTION_FILE_VERSION)
            .put("name", safeName)
            .put("infoHash", magnetTopic(safeMagnet))
            .put("magnet", safeMagnet)
            .toString(2)
    }

    private suspend fun addValidatedMagnet(magnetUri: String) {
        check(started) { "Inicie a descoberta antes de adicionar um magnet." }
        val downloadDirectory = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(appContext.filesDir, "downloads")
        check(downloadDirectory.exists() || downloadDirectory.mkdirs()) {
            "Não foi possível criar a pasta de downloads."
        }
        sessionManager.download(
            magnetUri.trim(),
            downloadDirectory,
            com.frostwire.jlibtorrent.swig.torrent_flags_t()
        )
    }

    private fun validateMagnet(value: String): String {
        val parsedMagnet = Uri.parse(value.trim())
        val topic = parsedMagnet.getQueryParameter("xt").orEmpty()
        require(
            parsedMagnet.scheme.equals("magnet", ignoreCase = true) &&
                SUPPORTED_TOPIC_PATTERN.matches(topic)
        ) {
            "Informe um magnet BitTorrent válido (urn:btih ou urn:btmh)."
        }
        val displayName = parsedMagnet.getQueryParameter("dn")
        return Uri.Builder()
            .scheme("magnet")
            .appendQueryParameter("xt", topic)
            .apply {
                if (!displayName.isNullOrBlank()) {
                    appendQueryParameter("dn", displayName.take(MAX_DISPLAY_NAME_LENGTH))
                }
            }
            .build()
            .toString()
    }

    private fun magnetTopic(magnetUri: String): String =
        Uri.parse(magnetUri).getQueryParameter("xt").orEmpty()

    suspend fun createAndSeed(file: File): String = withContext(Dispatchers.IO) {
        check(started) { "Inicie a descoberta antes de criar um magnet." }
        require(file.exists() && (file.isFile || file.isDirectory)) { "O item compartilhado não existe." }
        require(file.length() != 0L || file.isDirectory) { "Não é possível compartilhar um arquivo vazio." }
        val result = TorrentBuilder()
            .path(file)
            .creator("ServerMobile")
            .listener(object : TorrentBuilder.Listener {
                override fun accept(filename: String): Boolean =
                    !File(filename).name.startsWith(".")

                override fun progress(pieceIndex: Int, numPieces: Int) {}
            })
            .generate()
        val torrentInfo = com.frostwire.jlibtorrent.TorrentInfo(result.entry().bencode())
        val magnetUri = torrentInfo.makeMagnetUri()
        check(!magnetUri.isNullOrBlank()) { "Não foi possível gerar o magnet." }
        sessionManager.download(torrentInfo, file.absoluteFile.parentFile)
        magnetUri
    }

    suspend fun removeTransfer(infoHash: String) = withContext(Dispatchers.IO) {
        val handle = findHandle(infoHash) ?: return@withContext
        sessionManager.remove(handle)
        refreshTransfers()
    }

    fun close() {
        val (stopSession, startPending) = synchronized(lifecycleLock) {
            if (closed) return
            closed = true
            refreshJob?.cancel()
            refreshJob = null
            val shouldStop = started
            started = false
            shouldStop to starting
        }
        val activeMulticastLock = multicastLock
        if (stopSession) {
            multicastLock = null
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    sessionManager.stop()
                } finally {
                    if (activeMulticastLock?.isHeld == true) activeMulticastLock.release()
                }
            }
        } else if (!startPending) {
            multicastLock = null
            if (activeMulticastLock?.isHeld == true) {
                activeMulticastLock.release()
            }
        }
        if (stopSession || !startPending) {
            _transfers.value = emptyList()
            _networkStatus.value = "Descoberta BitTorrent parada"
        }
        scope.cancel()
    }

    private fun refreshTransfers() {
        _transfers.value = sessionManager.getTorrentHandles().mapNotNull { handle ->
            val infoHash = handle.infoHash().toHex()
            try {
                val status = handle.status()
                TorrentTransfer(
                    infoHash = infoHash,
                    name = status.name(),
                    progress = status.progress(),
                    peerCount = status.numPeers(),
                    downloadBytesPerSecond = status.downloadRate(),
                    uploadBytesPerSecond = status.uploadRate(),
                    isSeeding = status.isSeeding(),
                    error = status.errorCode().message()
                )
            } catch (error: Exception) {
                AppLogger.e(TAG, "Falha ao atualizar estado do torrent", error)
                TorrentTransfer(
                    infoHash = infoHash,
                    name = infoHash,
                    progress = 0f,
                    peerCount = 0,
                    downloadBytesPerSecond = 0,
                    uploadBytesPerSecond = 0,
                    isSeeding = false,
                    error = error.message ?: "Não foi possível ler o estado deste torrent."
                )
            }
        }
    }

    private fun findHandle(infoHash: String): TorrentHandle? =
        sessionManager.getTorrentHandles().firstOrNull {
            it.infoHash().toHex().equals(infoHash, ignoreCase = true)
        }

    companion object {
        private const val TAG = "TorrentDiscovery"
        private const val CONNECTION_FILE_FORMAT = "servermobile-connection"
        private const val CONNECTION_FILE_VERSION = 1
        private const val MAX_DISPLAY_NAME_LENGTH = 200
        private const val DEFAULT_DHT_BOOTSTRAP_NODES =
            "router.bittorrent.com:6881,router.utorrent.com:6881,dht.transmissionbt.com:6881"
        private val SUPPORTED_TOPIC_PATTERN =
            Regex("(?i)^urn:(?:btih:(?:[a-f0-9]{40}|[a-z2-7]{32})|btmh:1220[a-f0-9]{64})$")
    }
}
