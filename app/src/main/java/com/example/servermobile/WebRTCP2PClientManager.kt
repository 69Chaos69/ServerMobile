package com.example.servermobile

import android.content.Context
import android.os.Environment
import com.example.servermobile.AppLogger as Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnection.IceServer
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.FileTime
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Initiates a WebRTC connection using offer/answer text exchanged by the users.
 */
class WebRTCP2PClientManager(
    context: Context,
    private val serverKey: String
) {
    private val downloadMetadataPreferences =
        context.applicationContext.getSharedPreferences("download_metadata", Context.MODE_PRIVATE)
    private val sessionId = UUID.randomUUID().toString()
    private val peerConnectionFactory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null
    private var currentFileOutputStream: FileOutputStream? = null
    private var currentFile: File? = null
    private var expectedBytes = 0L
    private var bytesReceived = 0L
    private var receivedLastModified: Long? = null
    private var receivedCreationTime: Long? = null
    private var receivedMimeType: String? = null
    private val downloadDirectory =
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: File(context.filesDir, "downloads")

    private val _connectionState = MutableStateFlow("Desconectado")
    val connectionState: StateFlow<String> = _connectionState
    private val _remoteFiles = MutableStateFlow<List<RemoteFile>>(emptyList())
    val remoteFiles: StateFlow<List<RemoteFile>> = _remoteFiles
    private val _downloadStatus = MutableStateFlow("")
    val downloadStatus: StateFlow<String> = _downloadStatus

    init {
        val options = PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)
        peerConnectionFactory = PeerConnectionFactory.builder().createPeerConnectionFactory()
    }

    suspend fun createOffer(): String = withContext(Dispatchers.IO) {
        check(peerConnection == null) { "Já existe uma tentativa de conexão." }
        _connectionState.value = "Preparando oferta..."
        val configuration = PeerConnection.RTCConfiguration(
            listOf(IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        ).apply {
            iceTransportsType = PeerConnection.IceTransportsType.ALL
        }
        peerConnection = peerConnectionFactory.createPeerConnection(configuration, createObserver())
            ?: error("Não foi possível iniciar o WebRTC.")

        dataChannel = peerConnection?.createDataChannel("servermobile-files", DataChannel.Init())
            ?: error("Não foi possível criar o canal de dados.")
        setupDataChannel(requireNotNull(dataChannel))

        val offer = requireNotNull(peerConnection).createDescription(isOffer = true)
        requireNotNull(peerConnection).setLocalDescriptionAwait(offer)
        requireNotNull(peerConnection).awaitIceGathering()
        _connectionState.value = "Oferta pronta. Envie-a ao servidor."
        JSONObject()
            .put("protocol", PROTOCOL)
            .put("sessionId", sessionId)
            .put("sdp", peerConnection?.localDescription?.description ?: offer.description)
            .toString()
    }

    suspend fun applyAnswer(answerText: String) = withContext(Dispatchers.IO) {
        val answer = JSONObject(answerText)
        require(answer.optString("protocol") == PROTOCOL) { "A resposta não é compatível com este app." }
        require(answer.optString("sessionId") == sessionId) {
            "Esta resposta não corresponde à oferta gerada neste aparelho."
        }
        val sdp = answer.getString("sdp")
        require(sdp.isNotBlank()) { "A resposta está incompleta." }
        _connectionState.value = "Conectando..."
        requireNotNull(peerConnection).setRemoteDescriptionAwait(
            SessionDescription(SessionDescription.Type.ANSWER, sdp)
        )
    }

    private fun createObserver() = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            Log.d(TAG, "ICE: $state")
            when (state) {
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED -> _connectionState.value = "Conectado"
                PeerConnection.IceConnectionState.FAILED -> _connectionState.value =
                    "Falha de conexão: esta rede pode exigir TURN."
                PeerConnection.IceConnectionState.DISCONNECTED -> _connectionState.value = "Conexão interrompida"
                else -> Unit
            }
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
            if (state == PeerConnection.IceGatheringState.COMPLETE) {
                WebRTCP2PManager.notifyGatheringComplete(peerConnection)
            }
        }
        override fun onIceCandidate(candidate: IceCandidate?) {}
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
        override fun onAddStream(stream: MediaStream?) {}
        override fun onRemoveStream(stream: MediaStream?) {}
        override fun onDataChannel(channel: DataChannel?) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
    }

    private fun setupDataChannel(channel: DataChannel) {
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() {
                if (channel.state() == DataChannel.State.OPEN) {
                    _connectionState.value = "Autenticando..."
                    sendText(JSONObject().put("type", "auth").put("key", serverKey).toString())
                }
            }
            override fun onMessage(buffer: DataChannel.Buffer?) {
                buffer ?: return
                if (buffer.binary) {
                    handleFileChunk(buffer.data)
                } else {
                    val bytes = ByteArray(buffer.data.remaining())
                    buffer.data.get(bytes)
                    try {
                        handleMessage(JSONObject(String(bytes, Charsets.UTF_8)))
                    } catch (e: Exception) {
                        Log.e(TAG, "Falha ao processar resposta P2P", e)
                        _connectionState.value = "Erro: ${e.message}"
                    }
                }
            }
        })
    }

    private fun handleMessage(message: JSONObject) {
        when (message.getString("type")) {
            "auth_result" -> {
                if (message.optBoolean("success")) {
                    _connectionState.value = "Pronto."
                    sendText(JSONObject().put("type", "get_files").toString())
                } else {
                    _connectionState.value = "Erro: chave do servidor inválida."
                }
            }
            "files" -> {
                val files = message.getJSONArray("files")
                _remoteFiles.value = (0 until files.length()).map { index ->
                    val item = files.getJSONObject(index)
                    RemoteFile(
                        name = item.getString("name"),
                        size = item.getLong("size"),
                        isDirectory = item.getBoolean("isDirectory")
                    )
                }
            }
            "file_start" -> startFile(message)
            "file_end" -> finishFile(completed = true)
            "error" -> {
                finishFile()
                _downloadStatus.value =
                    "Erro do servidor: ${message.optString("message", "solicitação não concluída.")}"
            }
            else -> error("Resposta desconhecida do servidor.")
        }
    }

    private fun startFile(message: JSONObject) {
        finishFile()
        val name = message.getString("name")
        require(name.isNotBlank() && name != "." && name != ".." &&
            !name.contains('/') && !name.contains('\\')
        ) { "Nome de arquivo inválido recebido do servidor." }

        check(downloadDirectory.exists() || downloadDirectory.mkdirs()) {
            "Não foi possível criar a pasta de downloads."
        }
        val destination = File(downloadDirectory, name)
        currentFile = destination
        currentFileOutputStream = FileOutputStream(destination)
        expectedBytes = message.getLong("size")
        receivedLastModified = message.optLong("lastModified").takeIf { it > 0L }
        receivedCreationTime = message.optLong("creationTime").takeIf { it > 0L }
        receivedMimeType = message.optString("mimeType").takeIf { it.isNotBlank() }
        bytesReceived = 0
        _downloadStatus.value = "Baixando $name..."
    }

    private fun handleFileChunk(buffer: ByteBuffer) {
        val stream = currentFileOutputStream ?: return
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        try {
            stream.write(bytes)
            bytesReceived += bytes.size
            val name = currentFile?.name ?: "arquivo"
            _downloadStatus.value = if (expectedBytes > 0) {
                "Baixando $name: ${bytesReceived * 100 / expectedBytes}%"
            } else {
                "Baixando $name..."
            }
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao gravar arquivo recebido", e)
            _downloadStatus.value = "Erro ao salvar ${currentFile?.name}: ${e.message}"
            finishFile()
        }
    }

    private fun finishFile(completed: Boolean = false) {
        try {
            currentFileOutputStream?.close()
            val destination = currentFile
            if (destination != null) {
                if (completed && bytesReceived == expectedBytes) {
                    val metadataWarnings = mutableListOf<String>()
                    try {
                        val view = Files.getFileAttributeView(
                            destination.toPath(),
                            BasicFileAttributeView::class.java
                        )
                        view.setTimes(
                            receivedLastModified?.let { FileTime.fromMillis(it) },
                            null,
                            receivedCreationTime?.let { FileTime.fromMillis(it) }
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Não foi possível manter todas as datas do arquivo recebido", e)
                        metadataWarnings.add("as datas do arquivo não puderam ser totalmente mantidas")
                    }
                    if (receivedLastModified != null && destination.lastModified() != receivedLastModified) {
                        metadataWarnings.add("a data de modificação não pôde ser mantida")
                    }
                    if (receivedCreationTime != null) {
                        val actualCreationTime = Files.readAttributes(
                            destination.toPath(),
                            java.nio.file.attribute.BasicFileAttributes::class.java
                        ).creationTime().toMillis()
                        if (actualCreationTime != receivedCreationTime) {
                            metadataWarnings.add("a data de criação não pôde ser mantida")
                        }
                    }
                    receivedMimeType?.let { mimeType ->
                        downloadMetadataPreferences.edit()
                            .putString("mime:${destination.absolutePath}", mimeType)
                            .apply()
                    }
                    _downloadStatus.value = if (metadataWarnings.isEmpty()) {
                        "Arquivo salvo em ${destination.absolutePath}"
                    } else {
                        "Arquivo salvo em ${destination.absolutePath}, mas ${metadataWarnings.distinct().joinToString(" e ")}."
                    }
                } else {
                    destination.delete()
                    if (completed) _downloadStatus.value = "Download incompleto: os dados recebidos não correspondem ao tamanho informado."
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao finalizar arquivo", e)
            _downloadStatus.value = "Erro ao finalizar o download: ${e.message}"
        } finally {
            currentFileOutputStream = null
            currentFile = null
            expectedBytes = 0
            bytesReceived = 0
            receivedLastModified = null
            receivedCreationTime = null
            receivedMimeType = null
        }
    }

    private fun sendText(text: String) {
        val channel = dataChannel ?: error("Canal de dados indisponível.")
        check(channel.state() == DataChannel.State.OPEN) { "Canal de dados ainda não está aberto." }
        val bytes = text.toByteArray(Charsets.UTF_8)
        val buffer = ByteBuffer.allocateDirect(bytes.size)
        buffer.put(bytes)
        buffer.flip()
        check(channel.send(DataChannel.Buffer(buffer, false))) { "Não foi possível enviar a solicitação." }
    }

    fun requestDownload(fileName: String) {
        try {
            _downloadStatus.value = "Solicitando $fileName..."
            sendText(JSONObject().put("type", "download").put("name", fileName).toString())
        } catch (e: Exception) {
            _downloadStatus.value = "Erro: ${e.message}"
            Log.e(TAG, "Falha ao solicitar download", e)
        }
    }

    fun disconnect() {
        finishFile()
        try {
            dataChannel?.close()
            dataChannel?.dispose()
            peerConnection?.close()
            peerConnection?.dispose()
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao fechar a conexão", e)
        } finally {
            dataChannel = null
            peerConnection = null
            peerConnectionFactory.dispose()
            _connectionState.value = "Desconectado"
            _remoteFiles.value = emptyList()
        }
    }

    private suspend fun PeerConnection.createDescription(isOffer: Boolean): SessionDescription =
        suspendCancellableCoroutine { continuation ->
            val observer = object : SdpObserver {
                override fun onCreateSuccess(description: SessionDescription?) {
                    if (description == null) {
                        continuation.resumeWithException(IllegalStateException("WebRTC retornou uma descrição vazia."))
                    } else {
                        continuation.resume(description)
                    }
                }
                override fun onSetSuccess() {}
                override fun onCreateFailure(error: String?) {
                    continuation.resumeWithException(IllegalStateException(error ?: "Falha ao criar SDP."))
                }
                override fun onSetFailure(error: String?) {}
            }
            if (isOffer) createOffer(observer, MediaConstraints()) else createAnswer(observer, MediaConstraints())
        }

    private suspend fun PeerConnection.setLocalDescriptionAwait(description: SessionDescription) {
        suspendCancellableCoroutine<Unit> { continuation ->
            setLocalDescription(object : SdpObserver {
                override fun onCreateSuccess(description: SessionDescription?) {}
                override fun onSetSuccess() { continuation.resume(Unit) }
                override fun onCreateFailure(error: String?) {}
                override fun onSetFailure(error: String?) {
                    continuation.resumeWithException(IllegalStateException(error ?: "Falha ao definir SDP local."))
                }
            }, description)
        }
    }

    private suspend fun PeerConnection.setRemoteDescriptionAwait(description: SessionDescription) {
        suspendCancellableCoroutine<Unit> { continuation ->
            setRemoteDescription(object : SdpObserver {
                override fun onCreateSuccess(description: SessionDescription?) {}
                override fun onSetSuccess() { continuation.resume(Unit) }
                override fun onCreateFailure(error: String?) {}
                override fun onSetFailure(error: String?) {
                    continuation.resumeWithException(IllegalArgumentException(error ?: "Resposta WebRTC inválida."))
                }
            }, description)
        }
    }

    private suspend fun PeerConnection.awaitIceGathering() {
        withTimeout(30_000L) {
            if (iceGatheringState() == PeerConnection.IceGatheringState.COMPLETE) return@withTimeout
            kotlinx.coroutines.suspendCancellableCoroutine<Unit> { continuation ->
                WebRTCP2PManager.registerGatheringWaiter(this@awaitIceGathering, continuation)
                continuation.invokeOnCancellation {
                    WebRTCP2PManager.unregisterGatheringWaiter(this@awaitIceGathering, continuation)
                }
                if (iceGatheringState() == PeerConnection.IceGatheringState.COMPLETE) {
                    WebRTCP2PManager.notifyGatheringComplete(this@awaitIceGathering)
                }
            }
        }
    }

    companion object {
        private const val TAG = "WebRTCP2PClient"
        private const val PROTOCOL = "servermobile-manual-webrtc-v1"
    }
}
