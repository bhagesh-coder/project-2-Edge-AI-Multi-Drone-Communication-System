package com.example.droneswarm

import android.content.Context
import android.util.Log
import androidx.camera.core.ImageProxy
import org.webrtc.*

private class H264OnlyVideoEncoderFactory(
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

class WebRTCDroneManager(
    private val context: Context,
    private val onIceCandidateGenerated: (IceCandidate) -> Unit,
    private val onSessionDescriptionGenerated: (SessionDescription) -> Unit
) {
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var cameraXCapturer: CameraXVideoCapturer? = null
    private var localVideoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var videoSender: RtpSender? = null

    private val eglBase = EglBase.create()
    private val TAG = "WebRTC_Drone_Core"

    @Volatile private var isStreaming = false
    @Volatile private var isCameraReady = false
    @Volatile private var streamRequestedBeforeCamera = false

    init { initializeFactory() }

    private fun initializeFactory() {
        try {
            val options = PeerConnectionFactory.InitializationOptions
                .builder(context.applicationContext)
                .setEnableInternalTracer(true)
                .createInitializationOptions()
            PeerConnectionFactory.initialize(options)

            val encoderFactory = H264OnlyVideoEncoderFactory(eglBase.eglBaseContext)
            val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

            peerConnectionFactory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .setOptions(PeerConnectionFactory.Options().apply {
                    disableEncryption = false
                    disableNetworkMonitor = true
                })
                .createPeerConnectionFactory()

            Log.d(TAG, "✅ PeerConnectionFactory initialized (H264-only encoder)")
        } catch (e: Exception) {
            Log.e(TAG, "Factory Init Failed: ${e.message}")
        }
    }

    fun onCameraReady() {
        Log.d(TAG, "Camera ready signal received")
        isCameraReady = true
        if (streamRequestedBeforeCamera) {
            Log.d(TAG, "Camera now ready — starting deferred stream")
            streamRequestedBeforeCamera = false
            startStreamingInternal()
        }
    }

    fun startStreaming() {
        if (isStreaming) return
        if (!isCameraReady) {
            Log.d(TAG, "Stream requested before camera — deferring")
            streamRequestedBeforeCamera = true
            return
        }
        startStreamingInternal()
    }

    private fun startStreamingInternal() {
        if (isStreaming) return

        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        )

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
        }

        peerConnection = peerConnectionFactory?.createPeerConnection(
            rtcConfig,
            object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate) {
                    Log.d(TAG, "ICE candidate: ${candidate.sdp}")
                    onIceCandidateGenerated(candidate)
                }
                override fun onSignalingChange(state: PeerConnection.SignalingState?) {
                    Log.d(TAG, "Signaling: $state")
                }
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                    Log.d(TAG, "ICE connection: $state")
                }
                override fun onIceConnectionReceivingChange(p0: Boolean) {}
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                    Log.d(TAG, "ICE gathering: $state")
                }
                override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
                override fun onAddStream(p0: MediaStream?) {}
                override fun onRemoveStream(p0: MediaStream?) {}
                override fun onDataChannel(p0: DataChannel?) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
            }
        )

        setupVideoTrack()
        createOffer()
        isStreaming = true
        Log.d(TAG, "✅ Streaming started (camera was ready)")
    }

    private fun setupVideoTrack() {
        // FIX: CameraXVideoCapturer initialize karo — startCapture() mat karo
        // CameraManager already camera chala raha hai aur pushFrame() se frames dega
        // YOLO detection bhi CameraManager se hi hogi — koi conflict nahi
        cameraXCapturer = CameraXVideoCapturer()
        localVideoSource = peerConnectionFactory?.createVideoSource(false)
        surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)

        cameraXCapturer?.initialize(surfaceTextureHelper, context, localVideoSource?.capturerObserver)
        // ❌ cameraXCapturer?.startCapture(640, 480, 30) — REMOVED
        // CameraManager pushFrame() se frames aayenge — startCapture() conflict karega

        localVideoTrack = peerConnectionFactory?.createVideoTrack("DRONE_VIDEO_TRACK", localVideoSource)
        localVideoTrack?.setEnabled(true)
        videoSender = peerConnection?.addTrack(localVideoTrack, listOf("DRONE_STREAM_ID"))

        Log.d(TAG, "✅ Video track added to PeerConnection")
    }

    // CameraManager se har frame yahan aata hai — YOLO alag thread pe, WebRTC yahan
    fun pushFrame(imageProxy: ImageProxy, rotationDegrees: Int) {
        Log.d(TAG, "pushFrame called, isCapturing=${cameraXCapturer != null}")
        cameraXCapturer?.pushFrame(imageProxy, rotationDegrees)
    }

    private fun applyBitrateLimit(maxBps: Int) {
        try {
            videoSender?.let { sender ->
                val params = sender.parameters
                if (params.encodings.isNotEmpty()) {
                    params.encodings.forEach { it.maxBitrateBps = maxBps }
                    sender.parameters = params
                    Log.d(TAG, "✅ Bitrate capped to ${maxBps / 1000} kbps")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Bitrate cap failed: ${e.message}")
        }
    }

    private fun createOffer() {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
        }

        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription) {
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        Log.d(TAG, "✅ Local offer set — sending to server")
                        onSessionDescriptionGenerated(desc)
                    }
                    override fun onSetFailure(err: String?) {
                        Log.e(TAG, "❌ Local offer set failed: $err")
                    }
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                }, desc)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(err: String?) {
                Log.e(TAG, "❌ createOffer failed: $err")
            }
            override fun onSetFailure(p0: String?) {}
        }, constraints)
    }

    fun handleRemoteAnswer(sdpString: String) {
        // Guard: already stable hai toh duplicate answer ignore karo
        if (peerConnection?.signalingState() == PeerConnection.SignalingState.STABLE) {
            Log.w(TAG, "Already stable — ignoring duplicate answer")
            return
        }
        val sdp = SessionDescription(SessionDescription.Type.ANSWER, sdpString)
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                Log.d(TAG, "✅ Remote answer set")
                applyBitrateLimit(300_000)
            }
            override fun onSetFailure(err: String?) {
                Log.e(TAG, "❌ Remote answer failed: $err")
            }
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
        }, sdp)
    }

    fun addRemoteIceCandidate(candidate: IceCandidate) {
        try {
            peerConnection?.addIceCandidate(candidate)
            Log.d(TAG, "✅ Remote ICE candidate added: ${candidate.sdp}")
        } catch (e: Exception) {
            Log.e(TAG, "addRemoteIceCandidate failed: ${e.message}")
        }
    }

    fun stopStreaming() {
        isStreaming = false
        isCameraReady = false
        streamRequestedBeforeCamera = false
        try {
            // startCapture() nahi kiya tha toh stopCapture() bhi nahi — sirf dispose
            cameraXCapturer?.dispose()
            cameraXCapturer = null
            localVideoTrack?.dispose()
            localVideoTrack = null
            localVideoSource?.dispose()
            localVideoSource = null
            surfaceTextureHelper?.dispose()
            surfaceTextureHelper = null
            peerConnection?.close()
            peerConnection?.dispose()
            peerConnection = null
            Log.d(TAG, "✅ Streaming stopped, resources released")
        } catch (e: Exception) {
            Log.e(TAG, "stopStreaming error: ${e.message}")
        }
    }
}