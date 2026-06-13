package com.example.droneswarm.drone1

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import org.webrtc.IceCandidate

class MainActivity : ComponentActivity() {

    private lateinit var nearbyManager: NearbyManager
    private lateinit var yoloDetector: YOLODetector
    private lateinit var webRtcManager: WebRTCDroneManager
    private lateinit var drone2RelayManager: Drone2RelayManager

    private val detectionLogs = mutableStateListOf<String>()
    private val connectionStatus = mutableStateOf("Ready")
    private val shouldStartCamera = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        drone2RelayManager = Drone2RelayManager(
            context = this,
            onIceCandidateGenerated = { ice ->
                // Drone2 ko ICE bhejo
                nearbyManager.forwardToTarget(
                    "D2_RELAY_ICE_BACK|${ice.sdpMid}|${ice.sdpMLineIndex}|${ice.sdp}",
                    "Drone 2"
                )
            },
            onAnswerGenerated = { answer ->
                // Drone2 ko answer bhejo
                nearbyManager.forwardToTarget(
                    "D2_RELAY_ANSWER|${answer.description}",
                    "Drone 2"
                )
            }
        )

        webRtcManager = WebRTCDroneManager(
            context = this,
            onIceCandidateGenerated = { ice ->
                nearbyManager.sendMessage("REMOTE_ICE|${ice.sdpMid}|${ice.sdpMLineIndex}|${ice.sdp}")
            },
            onSessionDescriptionGenerated = { sdp ->
                nearbyManager.sendMessage("OFFER|${sdp.description}")
            }
        )

        try {
            yoloDetector = YOLODetector(this)
        } catch (e: Exception) {
            connectionStatus.value = "Model Error"
        }

        nearbyManager = NearbyManager(
            context = this,
            serviceId = "com.example.droneswarm.SERVER_CHANNEL",
            onStatusChange = { status ->
                connectionStatus.value = status
            },
            onMessageReceived = { id, msg ->
                when {
                    // ── Drone1 ka apna stream ──
                    msg == "CMD_STREAM_ON" || msg == "START_STREAM" -> {
                        webRtcManager.startStreaming()
                        shouldStartCamera.value = true
                        detectionLogs.add(0, "SYSTEM: STARTING_LIVE_FEED")
                    }

                    msg.startsWith("ANSWER|") -> {
                        webRtcManager.handleRemoteAnswer(msg.substringAfter("ANSWER|"))
                        detectionLogs.add(0, "SYSTEM: UPLINK_HANDSHAKE_OK")
                    }

                    msg.startsWith("REMOTE_ICE|") -> {
                        val parts = msg.split("|")
                        if (parts.size >= 4) {
                            try {
                                webRtcManager.addRemoteIceCandidate(
                                    IceCandidate(parts[1], parts[2].toInt(), parts[3])
                                )
                            } catch (e: Exception) {
                                Log.e("DRONE1", "ICE parse error")
                            }
                        }
                    }

                    // ── Drone2 relay signaling ──
                    msg.startsWith("D2_RELAY_OFFER|") -> {
                        val sdp = msg.substringAfter("D2_RELAY_OFFER|")
                        detectionLogs.add(0, "SYSTEM: D2_RELAY_OFFER_RECEIVED")
                        drone2RelayManager.handleDrone2Offer(
                            sdpString = sdp,
                            onServerIce = { ice ->
                                // ✅ FIX: D2_SERVER_ICE — D2_RELAY_ICE se alag key
                                nearbyManager.sendMessage(
                                    "D2_SERVER_ICE|${ice.sdpMid}|${ice.sdpMLineIndex}|${ice.sdp}"
                                )
                            },
                            onServerOfferReady = { offer ->
                                // ✅ FIX: D2_SERVER_OFFER — D2_RELAY_OFFER se alag key
                                nearbyManager.sendMessage("D2_SERVER_OFFER|${offer.description}")
                            }
                        )
                    }

                    msg.startsWith("D2_RELAY_ICE|") -> {
                        val parts = msg.split("|")
                        if (parts.size >= 4) {
                            try {
                                drone2RelayManager.addDrone2IceCandidate(
                                    IceCandidate(parts[1], parts[2].toInt(), parts[3])
                                )
                            } catch (e: Exception) {
                                Log.e("DRONE1", "D2 ICE parse error")
                            }
                        }
                    }

                    // ✅ Server ka D2 relay answer — key D2_RELAY_ANSWER
                    msg.startsWith("D2_RELAY_ANSWER|") -> {
                        drone2RelayManager.handleServerAnswer(
                            msg.substringAfter("D2_RELAY_ANSWER|")
                        )
                        detectionLogs.add(0, "SYSTEM: D2_RELAY_HANDSHAKE_OK")
                    }

                    // ✅ Server ka D2 relay ICE — key D2_RELAY_ICE_BACK
                    msg.startsWith("D2_RELAY_ICE_BACK|") -> {
                        val parts = msg.split("|")
                        if (parts.size >= 4) {
                            try {
                                drone2RelayManager.addServerIceCandidate(
                                    IceCandidate(parts[1], parts[2].toInt(), parts[3])
                                )
                            } catch (e: Exception) {
                                Log.e("DRONE1", "Server ICE parse error")
                            }
                        }
                    }

                    msg == "CMD_LAND_NOW" -> {
                        detectionLogs.add(0, "CRITICAL: EMERGENCY_LANDING_INITIATED")
                    }

                    else -> {
                        detectionLogs.add(0, msg)
                    }
                }
            }
        )

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Drone1Screen(
                        nearbyManager = nearbyManager,
                        yoloDetector = yoloDetector,
                        logs = detectionLogs,
                        webRtcManager = webRtcManager,
                        status = connectionStatus.value,
                        shouldStartCamera = shouldStartCamera.value,
                        onCameraStarted = { shouldStartCamera.value = false },
                        onServerClick = { nearbyManager.startAdvertising() },
                        onNodesClick = { nearbyManager.startDroneDiscovery() }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        webRtcManager.stopStreaming()
        drone2RelayManager.stop()
        nearbyManager.stopAll()
    }
}