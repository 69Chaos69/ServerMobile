package com.example.servermobile

import android.content.Context
import com.example.servermobile.AppLogger as Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Accepts copy/pasted WebRTC offers and serves files over an authenticated DataChannel.
 * The offer and answer include gathered ICE candidates, so no signaling service is needed.
 */
class WebRTCP2PManager(
    context: Context,
    private val serverManager: ServerManager,
    private val serverKey: String
) {
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val peerConnections = ConcurrentHashMap<String, PeerConnection>()
    private val dataChannels = ConcurrentHashMap<String, DataChannel>()
    private val authenticatedClients = ConcurrentHashMap.newKeySet<String>()
    private val messageQueues = ConcurrentHashMap<String, Channel<String>>()
    private val messageWorkers = ConcurrentHashMap<String, Job>()
    private val peerConnectionFactory: PeerConnectionFactory

    init {
        val options = PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)
        peerConnectionFactory = PeerConnectionFactory.builder().createPeerConnectionFactory()
    }

    suspend fun createAnswer(offerText: String): String = withContext(Dispatchers.IO) {
        val offer = JSONObject(offerText)
        require(offer.optString("protocol") == PROTOCOL) { "A oferta não é compatível com este app." }
        val sessionId = offer.getString("sessionId")
        val sdp = offer.getString("sdp")
        require(sessionId.isNotBlank() && sdp.isNotBlank()) { "A oferta está incompleta." }

        peerConnections.remove(sessionId)?.let { it.close(); it.dispose() }
        dataChannels.remove(sessionId)?.let { it.close(); it.dispose() }
        authenticatedClients.remove(sessionId)
        messageQueues.remove(sessionId)?.close()
        messageWorkers.remove(sessionId)?.cancel()

        val peerConnection = createPeerConnection(sessionId)
            ?: error("Não foi possível criar a conexão WebRTC.")
        peerConnections[sessionId] = peerConnection

        peerConnection.setRemoteDescriptionAwait(
            SessionDescription(SessionDescription.Type.OFFER, sdp)
        )
        val answer = peerConnection.createDescription(isOffer = false)
        peerConnection.setLocalDescriptionAwait(answer)
        peerConnection.awaitIceGathering()

        JSONObject()
            .put("protocol", PROTOCOL)
            .put("sessionId", sessionId)
            .put("sdp", peerConnection.localDescription?.description ?: answer.description)
            .toString()
    }

    private fun createPeerConnection(sessionId: String): PeerConnection? {
        val configuration = PeerConnection.RTCConfiguration(
            listOf(IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        ).apply {
            iceTransportsType = PeerConnection.IceTransportsType.ALL
        }

        return peerConnectionFactory.createPeerConnection(configuration, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                Log.d(TAG, "ICE $sessionId: $state")
                if (state == PeerConnection.IceConnectionState.FAILED ||
                    state == PeerConnection.IceConnectionState.DISCONNECTED
                ) {
                    serverManager.addLog("P2P: conexão WebRTC $state ($sessionId)")
                }
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                if (state == PeerConnection.IceGatheringState.COMPLETE) {
                    notifyGatheringComplete(peerConnections[sessionId])
                }
            }
            override fun onIceCandidate(candidate: IceCandidate?) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(channel: DataChannel?) {
                if (channel != null) {
                    dataChannels[sessionId] = channel
                    setupDataChannel(sessionId, channel)
                }
            }
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
        })
    }

    private fun setupDataChannel(sessionId: String, channel: DataChannel) {
        val queue = Channel<String>(Channel.UNLIMITED)
        messageQueues.put(sessionId, queue)?.close()
        messageWorkers.put(sessionId, scope.launch {
            for (message in queue) {
                try {
                    handleMessage(sessionId, channel, JSONObject(message))
                } catch (e: Exception) {
                    reportMessageError(sessionId, channel, e)
                }
            }
        })?.cancel()

        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() {
                if (channel.state() == DataChannel.State.OPEN) {
                    serverManager.addLog("P2P: canal de dados aberto ($sessionId)")
                }
            }
            override fun onMessage(buffer: DataChannel.Buffer?) {
                buffer ?: return
                if (buffer.binary) return
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                val message = String(bytes, Charsets.UTF_8)
                if (!queue.trySend(message).isSuccess) {
                    serverManager.addLog("P2P: mensagem ignorada porque a sessão foi encerrada.")
                }
            }

        })
    }

    private fun reportMessageError(sessionId: String, channel: DataChannel, error: Exception) {
        Log.e(TAG, "Falha ao processar mensagem P2P", error)
        serverManager.addLog("P2P: erro ao processar solicitação: ${error.message}")
        if (channel.state() == DataChannel.State.OPEN) {
            try {
                sendText(
                    channel,
                    JSONObject().put("type", "error")
                        .put("message", error.message ?: "Solicitação não concluída.")
                        .toString()
                )
            } catch (sendError: Exception) {
                Log.e(TAG, "Falha ao informar erro ao cliente ($sessionId)", sendError)
            }
        }
    }

    private suspend fun handleMessage(sessionId: String, channel: DataChannel, message: JSONObject) {
        when (message.getString("type")) {
            "auth" -> {
                val authenticated = message.optString("key") == serverKey
                if (authenticated) {
                    authenticatedClients.add(sessionId)
                    serverManager.addLog("P2P: cliente autenticado ($sessionId)")
                }
                sendText(channel, JSONObject().put("type", "auth_result").put("success", authenticated).toString())
            }
            "get_files" -> {
                requireAuthenticated(sessionId)
                val files = org.json.JSONArray()
                serverManager.getLocalFiles().forEach { file ->
                    val metadata = serverManager.getFileMetadata(file)
                    files.put(
                        JSONObject()
                                .put("name", file.name)
                                .put("size", file.size)
                                .put("isDirectory", file.isDirectory)
                                .put("lastModified", metadata.lastModified)
                                .put("creationTime", metadata.creationTime)
                                .put("mimeType", metadata.mimeType)
                    )
                }
                sendText(channel, JSONObject().put("type", "files").put("files", files).toString())
            }
            "download" -> {
                requireAuthenticated(sessionId)
                val name = message.getString("name")
                val file = serverManager.getLocalFiles().firstOrNull {
                    it.name == name && it.isFile
                } ?: error("Arquivo indisponível.")
                sendFile(channel, file)
            }
            else -> error("Tipo de mensagem desconhecido.")
        }
    }

    private fun requireAuthenticated(sessionId: String) {
        check(sessionId in authenticatedClients) { "Cliente não autenticado." }
    }

    private suspend fun sendFile(channel: DataChannel, file: SharedFile) {
        val metadata = serverManager.getFileMetadata(file)
        sendText(
            channel,
            JSONObject()
                .put("type", "file_start")
                .put("name", file.name)
                .put("size", file.size)
                .put("lastModified", metadata.lastModified)
                .put("creationTime", metadata.creationTime)
                .put("mimeType", metadata.mimeType)
                .toString()
        )

        serverManager.openFileInput(file).buffered().use { input ->
            val bytes = ByteArray(FILE_CHUNK_SIZE)
            while (true) {
                val count = input.read(bytes)
                if (count < 0) break
                val buffer = ByteBuffer.allocateDirect(count)
                buffer.put(bytes, 0, count)
                buffer.flip()
                waitForChannelCapacity(channel)
                check(channel.send(DataChannel.Buffer(buffer, true))) { "Falha ao enviar parte do arquivo." }
            }
        }
        sendText(channel, JSONObject().put("type", "file_end").toString())
        serverManager.addLog("P2P: arquivo enviado: ${file.name}")
    }

    private suspend fun waitForChannelCapacity(channel: DataChannel) {
        while (channel.state() == DataChannel.State.OPEN &&
            channel.bufferedAmount() > MAX_BUFFERED_BYTES
        ) {
            delay(20)
        }
        check(channel.state() == DataChannel.State.OPEN) { "Conexão encerrada durante o envio." }
    }

    private fun sendText(channel: DataChannel, text: String) {
        check(channel.state() == DataChannel.State.OPEN) { "Canal de dados ainda não está aberto." }
        val bytes = text.toByteArray(Charsets.UTF_8)
        val buffer = ByteBuffer.allocateDirect(bytes.size)
        buffer.put(bytes)
        buffer.flip()
        check(channel.send(DataChannel.Buffer(buffer, false))) { "Não foi possível enviar a mensagem." }
    }

    fun close() {
        dataChannels.values.forEach { it.close(); it.dispose() }
        peerConnections.values.forEach { it.close(); it.dispose() }
        dataChannels.clear()
        peerConnections.clear()
        authenticatedClients.clear()
        messageQueues.values.forEach { it.close() }
        messageQueues.clear()
        messageWorkers.values.forEach { it.cancel() }
        messageWorkers.clear()
        peerConnectionFactory.dispose()
        scope.cancel()
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
                    continuation.resumeWithException(IllegalArgumentException(error ?: "Oferta WebRTC inválida."))
                }
            }, description)
        }
    }

    private suspend fun PeerConnection.awaitIceGathering() {
        withTimeout(30_000L) {
            if (iceGatheringState() == PeerConnection.IceGatheringState.COMPLETE) return@withTimeout
            suspendCancellableCoroutine<Unit> { continuation ->
                registerGatheringWaiter(this@awaitIceGathering, continuation)
                continuation.invokeOnCancellation {
                    unregisterGatheringWaiter(this@awaitIceGathering, continuation)
                }
                if (iceGatheringState() == PeerConnection.IceGatheringState.COMPLETE) {
                    notifyGatheringComplete(this@awaitIceGathering)
                }
            }
        }
    }

    companion object {
        private const val TAG = "WebRTCP2PServer"
        private const val PROTOCOL = "servermobile-manual-webrtc-v1"
        private const val FILE_CHUNK_SIZE = 16 * 1024
        private const val MAX_BUFFERED_BYTES = 256 * 1024L
        private val gatheringWaiters =
            ConcurrentHashMap<PeerConnection, kotlinx.coroutines.CancellableContinuation<Unit>>()

        internal fun registerGatheringWaiter(
            peerConnection: PeerConnection,
            continuation: kotlinx.coroutines.CancellableContinuation<Unit>
        ) {
            gatheringWaiters[peerConnection] = continuation
        }

        internal fun unregisterGatheringWaiter(
            peerConnection: PeerConnection,
            continuation: kotlinx.coroutines.CancellableContinuation<Unit>
        ) {
            gatheringWaiters.remove(peerConnection, continuation)
        }

        internal fun notifyGatheringComplete(peerConnection: PeerConnection?) {
            if (peerConnection == null) return
            gatheringWaiters.remove(peerConnection)?.let { continuation ->
                if (continuation.isActive) continuation.resume(Unit)
            }
        }
    }
}
