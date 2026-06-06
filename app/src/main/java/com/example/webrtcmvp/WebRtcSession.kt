package com.example.webrtcmvp

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.nio.ByteBuffer
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.CopyOnWriteArrayList

class WebRtcSession(
    private val context: Context,
    private val role: Role,
    private val room: String,
    private val signalingUrl: String,
    private val onLog: (String) -> Unit
) {
    enum class Role { CLIENT, RELAY }

    private lateinit var factory: PeerConnectionFactory
    private var pc: PeerConnection? = null
    private var dc: DataChannel? = null
    private var ws: WebSocket? = null
    private val http = OkHttpClient()

    private var remoteSet = false
    private var offered = false
    private val pendingRemoteCandidates = CopyOnWriteArrayList<IceCandidate>()
    private var pingTimer: Timer? = null

    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:relay.trickora.com:3478").createIceServer(),
        PeerConnection.IceServer.builder("turn:relay.trickora.com:3478")
            .setUsername("webrtc").setPassword("Tr1ckoraTurn2026").createIceServer(),
        PeerConnection.IceServer.builder("turn:relay.trickora.com:3478?transport=tcp")
            .setUsername("webrtc").setPassword("Tr1ckoraTurn2026").createIceServer()
    )

    fun start() {
        initFactory()
        createPeerConnection()
        connectSignaling()
    }

    fun stop() {
        pingTimer?.cancel()
        try { dc?.close() } catch (_: Exception) {}
        try { pc?.close() } catch (_: Exception) {}
        try { ws?.close(1000, "bye") } catch (_: Exception) {}
    }

    private fun log(m: String) = onLog(m)

    private fun initFactory() {
        if (!initialized) {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context)
                    .createInitializationOptions()
            )
            initialized = true
        }
        factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
    }

    private fun createPeerConnection() {
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            iceTransportsType = PeerConnection.IceTransportsType.RELAY
        }
        pc = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) { log("ICE: $s") }
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(c: IceCandidate?) {
                if (c == null) return
                ws?.send(
                    JSONObject()
                        .put("type", "ice")
                        .put("sdpMid", c.sdpMid)
                        .put("sdpMLineIndex", c.sdpMLineIndex)
                        .put("candidate", c.sdp)
                        .toString()
                )
            }
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
            override fun onAddStream(s: MediaStream?) {}
            override fun onRemoveStream(s: MediaStream?) {}
            override fun onDataChannel(channel: DataChannel?) {
                dc = channel
                registerDc(channel)
                log("DataChannel received")
            }
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(r: RtpReceiver?, s: Array<out MediaStream>?) {}
            override fun onConnectionChange(s: PeerConnection.PeerConnectionState?) {
                log("PC: $s")
                if (s == PeerConnection.PeerConnectionState.CONNECTED) log(">>> PEER CONNECTED <<<")
            }
        })

        if (role == Role.CLIENT) {
            dc = pc?.createDataChannel("data", DataChannel.Init())
            registerDc(dc)
        }
    }

    private fun registerDc(channel: DataChannel?) {
        channel ?: return
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(amount: Long) {}
            override fun onStateChange() {
                val state = channel.state()
                log("DC state: $state")
                if (state == DataChannel.State.OPEN) {
                    log(">>> DATACHANNEL OPEN <<<")
                    if (role == Role.CLIENT) startPing()
                }
            }
            override fun onMessage(buffer: DataChannel.Buffer?) {
                buffer ?: return
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                val text = String(bytes, Charsets.UTF_8)
                log("RECV: $text")
                if (role == Role.RELAY && text == "ping") sendData("pong")
            }
        })
    }

    private fun startPing() {
        pingTimer = Timer()
        pingTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() { sendData("ping") }
        }, 0, 2000)
    }

    private fun sendData(text: String) {
        val buf = DataChannel.Buffer(ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8)), false)
        dc?.send(buf)
        log("SEND: $text")
    }

    private fun connectSignaling() {
        val req = Request.Builder().url(signalingUrl).build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                log("WS connected")
                webSocket.send(JSONObject().put("type", "join").put("room", room).toString())
            }
            override fun onMessage(webSocket: WebSocket, text: String) { handleSignal(text) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                log("WS error: ${t.message}")
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { log("WS closed") }
        })
    }

    private fun handleSignal(text: String) {
        val msg = JSONObject(text)
        when (msg.optString("type")) {
            "joined" -> {
                val peers = msg.optInt("peers", 1)
                log("joined (peers=$peers)")
                if (role == Role.CLIENT && peers >= 2) makeOffer()
            }
            "peer-joined" -> { log("peer joined"); if (role == Role.CLIENT) makeOffer() }
            "peer-left" -> log("peer left")
            "offer" -> if (role == Role.RELAY) onRemoteOffer(msg.getString("sdp"))
            "answer" -> if (role == Role.CLIENT) onRemoteAnswer(msg.getString("sdp"))
            "ice" -> onRemoteIce(msg)
        }
    }

    private fun makeOffer() {
        if (offered) return
        offered = true
        pc?.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                pc?.setLocalDescription(SimpleSdpObserver(), sdp)
                ws?.send(JSONObject().put("type", "offer").put("sdp", sdp.description).toString())
                log("offer sent")
            }
        }, MediaConstraints())
    }

    private fun onRemoteOffer(sdp: String) {
        val desc = SessionDescription(SessionDescription.Type.OFFER, sdp)
        pc?.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                remoteSet = true
                flushCandidates()
                pc?.createAnswer(object : SimpleSdpObserver() {
                    override fun onCreateSuccess(answer: SessionDescription) {
                        pc?.setLocalDescription(SimpleSdpObserver(), answer)
                        ws?.send(JSONObject().put("type", "answer").put("sdp", answer.description).toString())
                        log("answer sent")
                    }
                }, MediaConstraints())
            }
        }, desc)
    }

    private fun onRemoteAnswer(sdp: String) {
        val desc = SessionDescription(SessionDescription.Type.ANSWER, sdp)
        pc?.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() { remoteSet = true; flushCandidates() }
        }, desc)
    }

    private fun onRemoteIce(msg: JSONObject) {
        val candidate = IceCandidate(
            msg.optString("sdpMid"),
            msg.optInt("sdpMLineIndex"),
            msg.optString("candidate")
        )
        if (remoteSet) pc?.addIceCandidate(candidate) else pendingRemoteCandidates.add(candidate)
    }

    private fun flushCandidates() {
        for (c in pendingRemoteCandidates) pc?.addIceCandidate(c)
        pendingRemoteCandidates.clear()
    }

    companion object {
        @Volatile private var initialized = false
    }
}

open class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String?) {}
    override fun onSetFailure(error: String?) {}
}
