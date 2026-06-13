package com.example.droneswarm.drone1

import android.content.Context
import android.util.Log
import org.webrtc.*

class Drone2RelayManager(
    private val context: Context,
    private val onIceCandidateGenerated: (IceCandidate) -> Unit,
    private val onAnswerGenerated: (SessionDescription) -> Unit
) {
    private val TAG = "D2_Relay"
    private val eglBase = EglBase.create()

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var incomingPeerConnection: PeerConnection? = null
    private var outgoingPeerConnection: PeerConnection? = null

    private var localVideoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var videoSender: RtpSender? = null
    private var drone2VideoTrack: VideoTrack? = null

    // FIX 1: ICE candidate buffer — outgoingPC ready hone se pehle aane waale candidates store karo
    private val pendingServerIceCandidates = mutableListOf<IceCandidate>()

    // FIX 2: Sink reference store karo taaki stop() mein properly remove kar sakein
    private var drone2VideoSink: VideoSink? = null

    private var onServerIceCandidate: ((IceCandidate) -> Unit)? = null
    private var onServerOffer: ((SessionDescription) -> Unit)? = null

    init {
        Log.d(TAG, "Initializing Drone2RelayManager...")
        initFactory()
    }

    private fun initFactory() {
        try {
            val options = PeerConnectionFactory.InitializationOptions
                .builder(context.applicationContext)
                .setEnableInternalTracer(true)
                .createInitializationOptions()
            PeerConnectionFactory.initialize(options)

            val encoderFactory = H264OnlyVideoEncoderFactory(eglBase.eglBaseContext)

            // ← CHANGE: SoftwareDecoder avoid karo, sirf hardware use karo
            val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

            peerConnectionFactory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .setOptions(PeerConnectionFactory.Options().apply {
                    disableEncryption = false
                    disableNetworkMonitor = true
                })
                .createPeerConnectionFactory()

            Log.d(TAG, "✅ Factory initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Factory init failed: ${e.message}")
            e.printStackTrace()
        }
    }

    fun handleDrone2Offer(
        sdpString: String,
        onServerIce: (IceCandidate) -> Unit,
        onServerOfferReady: (SessionDescription) -> Unit
    ) {
        Log.d(TAG, "handleDrone2Offer called")
        onServerIceCandidate = onServerIce
        onServerOffer = onServerOfferReady

        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        incomingPeerConnection = peerConnectionFactory?.createPeerConnection(
            rtcConfig,
            object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate) {
                    Log.d(TAG, "Incoming ICE generated -> sending to Drone2")
                    onIceCandidateGenerated(candidate)
                }

                override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                    Log.d(TAG, "onAddTrack called")
                    val videoTrack = receiver?.track() as? VideoTrack
                    if (videoTrack != null) {
                        Log.d(TAG, "✅ Drone2 video track received -> piping to server")
                        pipeTrackToServer(videoTrack)
                    } else {
                        Log.w(TAG, "Track is not a VideoTrack")
                    }
                }

                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                    Log.d(TAG, "Drone2 ICE state: $state")
                }

                override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
                override fun onIceConnectionReceivingChange(p0: Boolean) {}
                override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
                override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
                override fun onAddStream(p0: MediaStream?) {}
                override fun onRemoveStream(p0: MediaStream?) {}
                override fun onDataChannel(p0: DataChannel?) {}
                override fun onRenegotiationNeeded() {}
            }
        )

        val remoteSdp = SessionDescription(SessionDescription.Type.OFFER, sdpString)
        incomingPeerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                Log.d(TAG, "✅ Drone2 remote offer set -> creating answer")
                createAnswerForDrone2()
            }
            override fun onSetFailure(err: String?) {
                Log.e(TAG, "setRemoteDescription failed: $err")
            }
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
        }, remoteSdp)
    }

    private fun createAnswerForDrone2() {
        val constraints = MediaConstraints()
        incomingPeerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription) {
                incomingPeerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        Log.d(TAG, "✅ Answer created -> sending to Drone2")
                        onAnswerGenerated(desc)
                    }
                    override fun onSetFailure(err: String?) {
                        Log.e(TAG, "setLocalDescription failed: $err")
                    }
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                }, desc)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(err: String?) {
                Log.e(TAG, "createAnswer failed: $err")
            }
            override fun onSetFailure(p0: String?) {}
        }, constraints)
    }

    private fun pipeTrackToServer(drone2Track: VideoTrack) {
        Log.d(TAG, "pipeTrackToServer called")
        drone2VideoTrack = drone2Track

        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        surfaceTextureHelper = SurfaceTextureHelper.create("D2RelayThread", eglBase.eglBaseContext)
        localVideoSource = peerConnectionFactory?.createVideoSource(false)
        localVideoTrack = peerConnectionFactory?.createVideoTrack("D2_RELAY_TRACK", localVideoSource)

        // FIX 2: Sink ko variable mein store karo
        val sink = VideoSink { frame ->
            Log.d("D2_Relay", "Frame piping: ${frame.buffer.width}x${frame.buffer.height}") // ← ADD
            frame.retain()
            localVideoSource?.capturerObserver?.onFrameCaptured(frame)
            frame.release()
        }
        drone2VideoSink = sink
        drone2Track.addSink(sink)

        outgoingPeerConnection = peerConnectionFactory?.createPeerConnection(
            rtcConfig,
            object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate) {
                    Log.d(TAG, "Outgoing ICE generated -> sending to server")
                    onServerIceCandidate?.invoke(candidate)
                }
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                    Log.d(TAG, "Server ICE state: $state")
                }
                override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
                override fun onIceConnectionReceivingChange(p0: Boolean) {}
                override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
                override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
                override fun onAddStream(p0: MediaStream?) {}
                override fun onRemoveStream(p0: MediaStream?) {}
                override fun onDataChannel(p0: DataChannel?) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
            }
        )

        videoSender = outgoingPeerConnection?.addTrack(localVideoTrack, listOf("D2_RELAY_STREAM"))
        Log.d(TAG, "✅ Local video track added to outgoing PC")

        // FIX 1: outgoingPC ready hai — ab buffered ICE candidates flush karo
        flushPendingServerIceCandidates()

        createOfferForServer()
    }

    private fun createOfferForServer() {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
        }

        outgoingPeerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription) {
                outgoingPeerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        Log.d(TAG, "✅ Server offer ready -> sending to server")
                        onServerOffer?.invoke(desc)
                    }
                    override fun onSetFailure(err: String?) {
                        Log.e(TAG, "Server offer setLocalDescription failed: $err")
                    }
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                }, desc)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(err: String?) {
                Log.e(TAG, "createOffer for server failed: $err")
            }
            override fun onSetFailure(p0: String?) {}
        }, constraints)
    }

    fun handleServerAnswer(sdpString: String) {
        Log.d(TAG, "handleServerAnswer called")
        val sdp = SessionDescription(SessionDescription.Type.ANSWER, sdpString)
        outgoingPeerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                Log.d(TAG, "✅ Server answer set -> relay active")
                applyBitrateLimit(300_000)
            }
            override fun onSetFailure(err: String?) {
                Log.e(TAG, "Server answer failed: $err")
            }
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
        }, sdp)
    }

    fun addDrone2IceCandidate(candidate: IceCandidate) {
        incomingPeerConnection?.addIceCandidate(candidate)
    }

    // FIX 1: Buffer karo agar outgoingPC abhi ready nahi, warna seedha add karo
    fun addServerIceCandidate(candidate: IceCandidate) {
        val pc = outgoingPeerConnection
        if (pc != null) {
            Log.d(TAG, "Server ICE candidate added directly")
            pc.addIceCandidate(candidate)
        } else {
            Log.d(TAG, "Server ICE candidate buffered (outgoingPC not ready yet)")
            pendingServerIceCandidates.add(candidate)
        }
    }

    // FIX 1: outgoingPC ready hone ke baad buffered candidates apply karo
    private fun flushPendingServerIceCandidates() {
        if (pendingServerIceCandidates.isNotEmpty()) {
            Log.d(TAG, "Flushing ${pendingServerIceCandidates.size} buffered server ICE candidates")
            pendingServerIceCandidates.forEach { outgoingPeerConnection?.addIceCandidate(it) }
            pendingServerIceCandidates.clear()
        }
    }

    private fun applyBitrateLimit(maxBps: Int) {
        videoSender?.let { sender ->
            val params = sender.parameters
            if (params.encodings.isNotEmpty()) {
                params.encodings.forEach { it.maxBitrateBps = maxBps }
                sender.parameters = params
                Log.d(TAG, "✅ Bitrate limit applied: $maxBps bps")
            }
        }
    }

    private inner class H264OnlyVideoEncoderFactory(
        eglContext: EglBase.Context
    ) : VideoEncoderFactory {
        private val delegate = DefaultVideoEncoderFactory(eglContext, true, true)

        override fun createEncoder(info: VideoCodecInfo): VideoEncoder? =
            if (isH265(info)) null else delegate.createEncoder(info)

        override fun getSupportedCodecs(): Array<VideoCodecInfo> =
            delegate.supportedCodecs.filterNot { isH265(it) }.toTypedArray()

        private fun isH265(info: VideoCodecInfo) =
            info.name.contains("H265", ignoreCase = true) ||
                    info.name.contains("HEVC", ignoreCase = true)
    }

    fun stop() {
        Log.d(TAG, "Stopping Drone2RelayManager...")
        try {
            // FIX 2: Stored sink reference se properly remove karo
            drone2VideoSink?.let { drone2VideoTrack?.removeSink(it) }
            drone2VideoSink = null
            drone2VideoTrack = null

            pendingServerIceCandidates.clear()

            localVideoTrack?.dispose()
            localVideoTrack = null

            localVideoSource?.dispose()
            localVideoSource = null

            surfaceTextureHelper?.dispose()
            surfaceTextureHelper = null

            incomingPeerConnection?.close()
            incomingPeerConnection?.dispose()
            incomingPeerConnection = null

            outgoingPeerConnection?.close()
            outgoingPeerConnection?.dispose()
            outgoingPeerConnection = null

            Log.d(TAG, "✅ Relay stopped")
        } catch (e: Exception) {
            Log.e(TAG, "stop() error: ${e.message}")
            e.printStackTrace()
        }
    }
}