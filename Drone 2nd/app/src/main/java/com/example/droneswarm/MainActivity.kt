package com.example.droneswarm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import android.util.Log
import org.webrtc.IceCandidate

class MainActivity : ComponentActivity() {

    private lateinit var nearbyManager: NearbyManager
    private lateinit var yoloDetector: YOLODetector
    private lateinit var cameraManager: CameraManager
    private lateinit var webRtcManager: WebRTCDroneManager  // NEW

    private val detectionLogs = mutableStateListOf<String>()
    private val connectionStatus = mutableStateOf("Ready")
    private val humanDetected = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            yoloDetector = YOLODetector(this)
        } catch (e: Exception) {
            connectionStatus.value = "Model Error"
        }

        // WebRTC pehle banao — NearbyManager se pehle
        webRtcManager = WebRTCDroneManager(
            context = this,
            onIceCandidateGenerated = { ice ->
                nearbyManager.sendMessage("D2_RELAY_ICE|${ice.sdpMid}|${ice.sdpMLineIndex}|${ice.sdp}")
            },
            onSessionDescriptionGenerated = { sdp ->
                nearbyManager.sendMessage("D2_RELAY_OFFER|${sdp.description}")
            }
        )

        cameraManager = CameraManager(
            context = this,
            onFrameBytes = { /* fallback — use nahi hoga WebRTC mode mein */ },
            onFrameForWebRTC = { imageProxy, rotation ->  // NEW
                webRtcManager.pushFrame(imageProxy, rotation)
            },
            onHumanDetected = { isHuman -> humanDetected.value = isHuman },
            yoloDetector = yoloDetector
        )

        nearbyManager = NearbyManager(
            context = this,
            serviceId = "com.example.droneswarm.RELAY_CHANNEL",
            onStatusChange = { status -> connectionStatus.value = status },
            onMessageReceived = { id, msg ->
                when {
                    msg == "D2_START_STREAM" -> {
                        cameraManager.startCamera(this@MainActivity)
                        webRtcManager.onCameraReady()
                        webRtcManager.startStreaming()
                        detectionLogs.add(0, "SYSTEM: STARTING_WEBRTC_STREAM")
                    }
                    msg == "D2_STOP_STREAM" -> {
                        webRtcManager.stopStreaming()
                        detectionLogs.add(0, "SYSTEM: STREAM_STOPPED")
                    }
                    // WebRTC answer — Drone1 relay se aayega
                    msg.startsWith("D2_RELAY_ANSWER|") -> {
                        webRtcManager.handleRemoteAnswer(msg.substringAfter("D2_RELAY_ANSWER|"))
                        detectionLogs.add(0, "SYSTEM: WEBRTC_HANDSHAKE_OK")
                    }
                    msg.startsWith("D2_RELAY_ICE_BACK|") -> {
                        val parts = msg.split("|")
                        if (parts.size >= 4) {
                            try {
                                webRtcManager.addRemoteIceCandidate(
                                    IceCandidate(parts[1], parts[2].toInt(), parts[3])
                                )
                            } catch (e: Exception) { Log.e("DRONE2", "ICE parse error") }
                        }
                    }
                    else -> detectionLogs.add(0, "[$id]: $msg")
                }
            }
        )

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Drone2Screen(
                        nearbyManager = nearbyManager,
                        yoloDetector = yoloDetector,
                        cameraManager = cameraManager,
                        logs = detectionLogs,
                        status = connectionStatus.value,
                        humanDetected = humanDetected.value,
                        onCameraToggle = { shouldStart ->
                            if (shouldStart) {
                                cameraManager.startCamera(this@MainActivity)
                                webRtcManager.onCameraReady()  // NEW
                            } else {
                                webRtcManager.stopStreaming()
                                cameraManager.stopCamera()
                            }
                        }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        webRtcManager.stopStreaming()
        cameraManager.stopCamera()
        nearbyManager.stopAll()
    }
}