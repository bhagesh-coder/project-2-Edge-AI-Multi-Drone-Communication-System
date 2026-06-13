package com.example.droneswarm.server.webrtc

import android.content.Context
import android.util.Log
import org.webrtc.*
import java.io.BufferedReader
import java.io.StringReader

class WebRTCManager(
    private val context: Context,
    private val onIceCandidateGenerated: (IceCandidate) -> Unit,
    private val onSessionDescriptionGenerated: (SessionDescription) -> Unit,
    private val onRemoteVideoTrack: (VideoTrack) -> Unit
) {
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    val eglBase: EglBase = EglBase.create()

    private val TAG = "WebRTC_Server"
    private val pendingIceCandidates = mutableListOf<IceCandidate>()
    private var isRemoteSdpSet = false
    private var videoTrackDelivered = false

    companion object {
        // Static flag — ek baar initialize hoga, chahe kitne bhi instances ho
        @Volatile private var isFactoryInitialized = false
    }

    init { initFactory() }

    private fun initFactory() {
        try {
            // Factory initialize — ek baar per process
            if (!isFactoryInitialized) {
                val options = PeerConnectionFactory.InitializationOptions
                    .builder(context.applicationContext)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions()
                PeerConnectionFactory.initialize(options)
                isFactoryInitialized = true
                Log.d(TAG, "✅ PeerConnectionFactory.initialize() called")
            }

            // Har instance ka apna factory object — alag EGL context
            val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
            val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

            peerConnectionFactory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .setOptions(PeerConnectionFactory.Options().apply {
                    disableEncryption = false
                    disableNetworkMonitor = true
                })
                .createPeerConnectionFactory()

            Log.d(TAG, "✅ PeerConnectionFactory instance created")
        } catch (e: Exception) {
            Log.e(TAG, "Factory init failed: ${e.message}")
        }
    }

    private fun deliverVideoTrack(track: VideoTrack, source: String) {
        if (videoTrackDelivered) {
            Log.d(TAG, "Track already delivered — ignoring duplicate from $source")
            return
        }
        videoTrackDelivered = true
        track.setEnabled(true)
        Log.d(TAG, "✅ Video track delivered to UI [source=$source]")
        onRemoteVideoTrack(track)
    }

    fun handleRemoteOffer(sdpDescription: String) {
        Log.d(TAG, "Raw offer received, patching SDP...")
        videoTrackDelivered = false
        isRemoteSdpSet = false
        pendingIceCandidates.clear()

        try {
            val patchedSdp = patchSdp(sdpDescription)
            Log.d(TAG, "Patched SDP:\n$patchedSdp")

            if (patchedSdp.isBlank()) {
                Log.e(TAG, "patchSdp returned blank — aborting")
                return
            }

            // Purana PC close karo agar tha
            peerConnection?.close()
            peerConnection?.dispose()
            peerConnection = null

            val rtcConfig = PeerConnection.RTCConfiguration(
                listOf(
                    PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
                    PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
                )
            ).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
                tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
            }

            peerConnection = peerConnectionFactory?.createPeerConnection(rtcConfig, peerConnectionObserver)

            val remoteSdp = SessionDescription(SessionDescription.Type.OFFER, patchedSdp)
            peerConnection?.setRemoteDescription(object : SdpObserver {
                override fun onCreateSuccess(p0: SessionDescription?) {}
                override fun onSetSuccess() {
                    Log.d(TAG, "✅ Remote SDP set — flushing ICE")
                    isRemoteSdpSet = true
                    flushPendingIceCandidates()
                    createAnswer()
                }
                override fun onCreateFailure(p0: String?) {}
                override fun onSetFailure(err: String?) {
                    Log.e(TAG, "❌ Remote SDP set failed: $err")
                }
            }, remoteSdp)

        } catch (e: Exception) {
            Log.e(TAG, "handleRemoteOffer exception: ${e.message}")
        }
    }

    // H265 lines SDP se hata do — Drone1 already H264-only offer bhejta hai
    // lekin extra safety ke liye yahan bhi patch hai
    private fun patchSdp(sdp: String): String {
        val h265PayloadTypes = mutableSetOf<String>()
        BufferedReader(StringReader(sdp)).forEachLine { line ->
            val l = line.trim()
            if (l.startsWith("a=rtpmap:")) {
                if (l.contains("H265", ignoreCase = true) ||
                    l.contains("HEVC", ignoreCase = true)) {
                    Regex("""a=rtpmap:(\d+)""").find(l)
                        ?.groupValues?.get(1)
                        ?.let { h265PayloadTypes.add(it) }
                }
            }
        }
        Log.d(TAG, "H265 payload types found: $h265PayloadTypes")

        val sb = StringBuilder()
        BufferedReader(StringReader(sdp)).forEachLine { line ->
            val l = line.trim()
            if (l.isEmpty()) return@forEachLine
            when {
                l.startsWith("a=rtpmap:") ||
                        l.startsWith("a=fmtp:") ||
                        l.startsWith("a=rtcp-fb:") -> {
                    val pt = Regex("""^a=[a-z-]+:(\d+)""").find(l)?.groupValues?.get(1)
                    if (pt != null && pt in h265PayloadTypes) return@forEachLine
                    sb.append(l).append("\r\n")
                }
                l.startsWith("m=video") -> {
                    val tokens = l.split(" ").filter { it !in h265PayloadTypes }
                    sb.append(tokens.joinToString(" ")).append("\r\n")
                }
                else -> sb.append(l).append("\r\n")
            }
        }
        return sb.toString()
    }

    private fun createAnswer() {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
        }

        peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                sdp?.let { desc ->
                    peerConnection?.setLocalDescription(object : SdpObserver {
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onSetSuccess() {
                            Log.d(TAG, "✅ Local answer set — sending to drone")
                            onSessionDescriptionGenerated(desc)
                        }
                        override fun onCreateFailure(p0: String?) {}
                        override fun onSetFailure(err: String?) {
                            Log.e(TAG, "❌ Local answer set failed: $err")
                        }
                    }, desc)
                }
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(err: String?) {
                Log.e(TAG, "❌ createAnswer failed: $err")
            }
            override fun onSetFailure(p0: String?) {}
        }, constraints)
    }

    fun addRemoteIceCandidate(candidate: IceCandidate) {
        if (!isRemoteSdpSet || peerConnection == null) {
            Log.d(TAG, "ICE candidate queued (SDP not set yet)")
            pendingIceCandidates.add(candidate)
            return
        }
        peerConnection?.addIceCandidate(candidate)
        Log.d(TAG, "✅ ICE candidate added: ${candidate.sdp}")
    }

    private fun flushPendingIceCandidates() {
        if (pendingIceCandidates.isEmpty()) return
        Log.d(TAG, "Flushing ${pendingIceCandidates.size} queued ICE candidates")
        pendingIceCandidates.forEach { peerConnection?.addIceCandidate(it) }
        pendingIceCandidates.clear()
    }

    fun close() {
        isRemoteSdpSet = false
        videoTrackDelivered = false
        pendingIceCandidates.clear()
        try {
            peerConnection?.close()
            peerConnection?.dispose()
            peerConnection = null
            peerConnectionFactory?.dispose()
            peerConnectionFactory = null
            eglBase.release()
            Log.d(TAG, "✅ WebRTCManager closed")
        } catch (e: Exception) {
            Log.e(TAG, "close() error: ${e.message}")
        }
    }

    private val peerConnectionObserver = object : PeerConnection.Observer {

        override fun onIceCandidate(candidate: IceCandidate) {
            Log.d(TAG, "ICE candidate generated: ${candidate.sdp}")
            onIceCandidateGenerated(candidate)
        }

        override fun onTrack(transceiver: RtpTransceiver?) {
            Log.d(TAG, "🔥 onTrack — direction=${transceiver?.direction} mediaType=${transceiver?.mediaType}")
            val track = transceiver?.receiver?.track()
            if (track is VideoTrack) {
                deliverVideoTrack(track, "onTrack")
            }
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            Log.d(TAG, "ICE state: $state")
            if (state == PeerConnection.IceConnectionState.CONNECTED) {
                // Fallback: agar onTrack miss ho gaya toh yahan deliver karo
                peerConnection?.transceivers?.forEach { transceiver ->
                    val track = transceiver.receiver?.track()
                    if (track is VideoTrack) {
                        deliverVideoTrack(track, "ice-connected-fallback")
                    }
                }
            }
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState?) {
            Log.d(TAG, "Signaling state: $state")
        }

        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
            Log.d(TAG, "onAddTrack — kind=${receiver?.track()?.kind()}")
            val track = receiver?.track()
            if (track is VideoTrack) {
                deliverVideoTrack(track, "onAddTrack")
            }
        }

        override fun onIceConnectionReceivingChange(p0: Boolean) {}
        override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {
            Log.d(TAG, "ICE gathering: $p0")
        }
        override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
        override fun onAddStream(p0: MediaStream?) {}
        override fun onRemoveStream(p0: MediaStream?) {}
        override fun onDataChannel(p0: DataChannel?) {}
        override fun onRenegotiationNeeded() {}
    }
}