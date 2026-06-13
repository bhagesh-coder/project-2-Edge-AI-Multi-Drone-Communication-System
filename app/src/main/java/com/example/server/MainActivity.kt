package com.example.droneswarm.server

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.Color
import org.webrtc.*
import com.example.droneswarm.server.webrtc.WebRTCManager
import kotlinx.coroutines.*

class MainActivity : ComponentActivity() {

    private lateinit var nearbyManager: NearbyManager
    private lateinit var drone1WebRtcManager: WebRTCManager
    private lateinit var drone2WebRtcManager: WebRTCManager

    private val connectedDronesList = mutableStateListOf<DroneDevice>()
    private val logsList = mutableStateListOf<String>()
    private val statusText = mutableStateOf("Offline")

    private val drone1VideoTrackState = mutableStateOf<VideoTrack?>(null)
    private val drone2VideoTrackState = mutableStateOf<VideoTrack?>(null)

    private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        logsList.add(0, "SYSTEM: BOOTING_COMMAND_CENTER...")

        setupPermissions()
        initializeWebRTC()
        initializeNearbyConnections()

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0A0A0A)) {
                    TacticalServerScreen(
                        nearbyManager = nearbyManager,
                        logs = logsList,
                        status = statusText.value,
                        connectedDrones = connectedDronesList,
                        onNavigateHome = { finish() },
                        drone1WebRtcManager = drone1WebRtcManager,
                        drone2WebRtcManager = drone2WebRtcManager,
                        drone1VideoTrack = drone1VideoTrackState.value,
                        drone2VideoTrack = drone2VideoTrackState.value,
                        d2FrameBitmap = null
                    )
                }
            }
        }
    }

    private fun initializeWebRTC() {
        try {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(applicationContext)
                    .setEnableInternalTracer(true)
                    .createInitializationOptions()
            )
        } catch (e: Exception) {
            Log.e("WebRTC", "Init failed: ${e.message}")
        }

        // ── Drone1 WebRTC Manager ──
        drone1WebRtcManager = WebRTCManager(
            context = this,
            onIceCandidateGenerated = { ice ->
                activityScope.launch(Dispatchers.IO) {
                    val drone1Id = nearbyManager.getConnectedEndpoints()
                        .entries.firstOrNull { it.value.contains("Drone1", ignoreCase = true) }?.key
                    nearbyManager.sendMessage(
                        "REMOTE_ICE|${ice.sdpMid}|${ice.sdpMLineIndex}|${ice.sdp}",
                        drone1Id
                    )
                }
            },
            onSessionDescriptionGenerated = { sdp ->
                activityScope.launch(Dispatchers.IO) {
                    val drone1Id = nearbyManager.getConnectedEndpoints()
                        .entries.firstOrNull { it.value.contains("Drone1", ignoreCase = true) }?.key
                    nearbyManager.sendMessage("ANSWER|${sdp.description}", drone1Id)
                }
            },
            onRemoteVideoTrack = { videoTrack ->
                activityScope.launch(Dispatchers.Main) {
                    Log.d("WebRTC", "✅ Drone1 VideoTrack received")
                    drone1VideoTrackState.value = videoTrack
                    logsList.add(0, "SYSTEM: DRONE1_VIDEO_UPLINK_ESTABLISHED")
                }
            }
        )

        // ── Drone2 Relay WebRTC Manager ──
        drone2WebRtcManager = WebRTCManager(
            context = this,
            onIceCandidateGenerated = { ice ->
                activityScope.launch(Dispatchers.IO) {
                    val drone1Id = nearbyManager.getConnectedEndpoints()
                        .entries.firstOrNull { it.value.contains("Drone1", ignoreCase = true) }?.key
                    nearbyManager.sendMessage(
                        "D2_RELAY_ICE_BACK|${ice.sdpMid}|${ice.sdpMLineIndex}|${ice.sdp}",
                        drone1Id
                    )
                }
            },
            onSessionDescriptionGenerated = { sdp ->
                activityScope.launch(Dispatchers.IO) {
                    val drone1Id = nearbyManager.getConnectedEndpoints()
                        .entries.firstOrNull { it.value.contains("Drone1", ignoreCase = true) }?.key
                    nearbyManager.sendMessage("D2_RELAY_ANSWER|${sdp.description}", drone1Id)
                }
            },
            onRemoteVideoTrack = { videoTrack ->
                activityScope.launch(Dispatchers.Main) {
                    Log.d("WebRTC", "✅ Drone2 relay VideoTrack received")
                    drone2VideoTrackState.value = videoTrack
                    logsList.add(0, "SYSTEM: DRONE2_VIDEO_RELAY_ESTABLISHED")
                }
            }
        )
    }

    private fun initializeNearbyConnections() {
        nearbyManager = NearbyManager(
            context = this,
            serviceId = "com.example.droneswarm.SERVER_CHANNEL",
            onMessageReceived = { endpointId, msg ->
                activityScope.launch(Dispatchers.Default) {
                    handleSignaling(endpointId, msg)
                }
            },
            onStatusChange = { status ->
                activityScope.launch(Dispatchers.Main) {
                    statusText.value = status
                }
            },
            onDeviceConnected = { id, name ->
                activityScope.launch(Dispatchers.Main) {
                    val isDrone2 = name.contains("Drone2", ignoreCase = true)
                    val droneId = if (isDrone2) "Drone2" else "Drone1"

                    if (!connectedDronesList.any { it.id == droneId }) {
                        connectedDronesList.add(
                            DroneDevice(
                                id = droneId,
                                battery = 100,
                                status = if (isDrone2) "RELAY" else "DIRECT",
                                hopPath = if (isDrone2) "Drone2 → Drone1 → Server"
                                else "Drone1 → Server"
                            )
                        )
                    }
                    logsList.add(0, "SYSTEM: NODE_CONNECTED_$droneId")

                    if (!isDrone2) {
                        nearbyManager.sendMessage("D2_START_STREAM", id)
                        logsList.add(0, "SYSTEM: D2_START_STREAM_SENT")
                    }
                }
            }
        )

        nearbyManager.startDiscovery()
    }

    private suspend fun handleSignaling(endpointId: String, msg: String) {
        when {
            msg.startsWith("OFFER|") -> {
                withContext(Dispatchers.Main) {
                    drone1VideoTrackState.value = null
                    logsList.add(0, "SIGNAL: DRONE1_OFFER_RECEIVED")
                }
                drone1WebRtcManager.handleRemoteOffer(msg.substringAfter("OFFER|"))
            }

            msg.startsWith("REMOTE_ICE|") -> {
                val parts = msg.split("|")
                if (parts.size >= 4) {
                    try {
                        drone1WebRtcManager.addRemoteIceCandidate(
                            IceCandidate(parts[1], parts[2].toInt(), parts[3])
                        )
                    } catch (e: Exception) {
                        Log.e("SERVER", "Drone1 ICE parse error: ${e.message}")
                    }
                }
            }

            msg.startsWith("D2_SERVER_OFFER|") -> {
                withContext(Dispatchers.Main) {
                    drone2VideoTrackState.value = null
                    logsList.add(0, "SIGNAL: DRONE2_RELAY_OFFER_RECEIVED")
                }
                drone2WebRtcManager.handleRemoteOffer(msg.substringAfter("D2_SERVER_OFFER|"))
            }

            msg.startsWith("D2_SERVER_ICE|") -> {
                val parts = msg.split("|")
                if (parts.size >= 4) {
                    try {
                        drone2WebRtcManager.addRemoteIceCandidate(
                            IceCandidate(parts[1], parts[2].toInt(), parts[3])
                        )
                    } catch (e: Exception) {
                        Log.e("SERVER", "Drone2 relay ICE parse error: ${e.message}")
                    }
                }
            }

            msg.startsWith("NODE_JOINED|") -> {
                withContext(Dispatchers.Main) {
                    if (!connectedDronesList.any { it.id == "Drone2" }) {
                        connectedDronesList.add(
                            DroneDevice(
                                id = "Drone2",
                                battery = 100,
                                status = "RELAY",
                                hopPath = "Drone2 → Drone1 → Server"
                            )
                        )
                    }
                    logsList.add(0, "SYSTEM: NODE_DISCOVERED_Drone2")
                }
            }

            msg.startsWith("DATA|") -> {
                withContext(Dispatchers.Main) {
                    val battMatch = Regex("BATT:(\\d+)").find(msg)
                        ?.groupValues?.get(1)?.toIntOrNull()
                    if (battMatch != null) {
                        val index = connectedDronesList.indexOfFirst { it.id == endpointId }
                        if (index != -1) {
                            connectedDronesList[index] =
                                connectedDronesList[index].copy(battery = battMatch)
                        }
                    }
                }
            }

            msg == "STREAM_STOPPED" -> {
                withContext(Dispatchers.Main) {
                    drone1VideoTrackState.value = null
                    logsList.add(0, "SYSTEM: DRONE1_STREAM_TERMINATED")
                }
            }

            msg == "D2_STREAM_STOPPED" -> {
                withContext(Dispatchers.Main) {
                    drone2VideoTrackState.value = null
                    logsList.add(0, "SYSTEM: DRONE2_STREAM_TERMINATED")
                }
            }

            else -> {
                withContext(Dispatchers.Main) {
                    logsList.add(0, "[$endpointId]: $msg")
                }
            }
        }
    }

    private fun setupPermissions() {
        val permissions = mutableListOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.NEARBY_WIFI_DEVICES
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            permissions.addAll(listOf(
                android.Manifest.permission.BLUETOOTH_SCAN,
                android.Manifest.permission.BLUETOOTH_ADVERTISE,
                android.Manifest.permission.BLUETOOTH_CONNECT
            ))
        }
        requestPermissions(permissions.toTypedArray(), 101)
    }

    override fun onDestroy() {
        super.onDestroy()
        activityScope.cancel()
        drone1WebRtcManager.close()
        drone2WebRtcManager.close()
        nearbyManager.stopAll()
    }
}