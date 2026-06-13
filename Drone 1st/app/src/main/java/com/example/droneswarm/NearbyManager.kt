package com.example.droneswarm.drone1

import android.content.Context
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import java.nio.charset.StandardCharsets

class NearbyManager(
    private val context: Context,
    private val serviceId: String,
    private val onStatusChange: (String) -> Unit,
    private val onMessageReceived: (String, String) -> Unit
) {
    private val connectionsClient = Nearby.getConnectionsClient(context)
    private val STRATEGY = Strategy.P2P_CLUSTER
    private val connectedEndpoints = mutableMapOf<String, String>()

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return

            val receivedMsg = try {
                String(bytes, StandardCharsets.UTF_8)
            } catch (e: Exception) {
                ""
            }

            when {
                // Drone2 ka video frame — raw bytes Server ko forward karo
                receivedMsg.startsWith("D2_FRAME|") -> {
                    forwardBytesToTarget(bytes, "Server")
                }

                // Drone2 se aaya offer/ICE — MainActivity ko do (Drone2RelayManager handle karega)
                receivedMsg.startsWith("D2_RELAY_OFFER|") ||
                        receivedMsg.startsWith("D2_RELAY_ICE|") -> {
                    onMessageReceived(endpointId, receivedMsg)
                }

                // ✅ FIX: Drone1 → Server relay offer/ICE — Server ko forward karo
                receivedMsg.startsWith("D2_SERVER_OFFER|") ||
                        receivedMsg.startsWith("D2_SERVER_ICE|") -> {
                    forwardToTarget(receivedMsg, "Server")
                }

                receivedMsg.startsWith("NODE_JOINED|") -> {
                    forwardToTarget(receivedMsg, "Server")
                }

                // Server se aaya Drone2 ke liye — Drone2 ko forward karo
                receivedMsg.startsWith("D2_RELAY_ANSWER|") ||
                        receivedMsg.startsWith("D2_RELAY_ICE_BACK|") ||
                        receivedMsg == "D2_START_STREAM" ||
                        receivedMsg == "D2_STOP_STREAM" -> {
                    forwardToTarget(receivedMsg, "Drone 2")
                }

                // Baaki messages normal processing
                else -> {
                    onMessageReceived(receivedMsg.split("|").firstOrNull() ?: "Remote", receivedMsg)
                    if (receivedMsg.split("|").firstOrNull()
                            ?.contains("Drone 2", ignoreCase = true) == true) {
                        forwardToTarget(receivedMsg, "Server")
                    }
                }
            }
        }

        override fun onPayloadTransferUpdate(id: String, u: PayloadTransferUpdate) {}
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            connectedEndpoints[endpointId] = info.endpointName ?: "Unknown"
            connectionsClient.acceptConnection(endpointId, payloadCallback)
            onStatusChange("Linking: ${info.endpointName}")
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                onStatusChange("Connected")
                val connectedName = connectedEndpoints[endpointId] ?: "Unknown"
                if (connectedName.contains("Drone 2", ignoreCase = true)) {
                    forwardToTarget("NODE_JOINED|Drone2|Drone2 → Drone1 → Server", "Server")
                }
            } else {
                connectedEndpoints.remove(endpointId)
                onStatusChange("Failed")
            }
        }

        override fun onDisconnected(endpointId: String) {
            connectedEndpoints.remove(endpointId)
            onStatusChange(if (connectedEndpoints.isEmpty()) "Disconnected" else "Nodes: ${connectedEndpoints.size}")
        }
    }

    fun startAdvertising() {
        val options = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startAdvertising(
            "Drone 1",
            "com.example.droneswarm.SERVER_CHANNEL",
            connectionLifecycleCallback,
            options
        )
            .addOnSuccessListener { onStatusChange("Broadcasting for Server...") }
            .addOnFailureListener { onStatusChange("Adv Error") }
    }

    fun startDroneDiscovery() {
        val options = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startDiscovery(
            "com.example.droneswarm.RELAY_CHANNEL",
            object : EndpointDiscoveryCallback() {
                override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
                    if (info.endpointName.contains("Drone 2", ignoreCase = true)) {
                        connectionsClient.requestConnection(
                            "Drone 1",
                            endpointId,
                            connectionLifecycleCallback
                        )
                    }
                }
                override fun onEndpointLost(id: String) {}
            },
            options
        ).addOnSuccessListener { onStatusChange("Scanning for Nodes...") }
    }

    fun sendMessage(msg: String) {
        forwardToTarget(msg, "Server")
    }

    fun forwardToTarget(message: String, targetName: String) {
        for ((id, name) in connectedEndpoints) {
            if (name.contains(targetName, ignoreCase = true)) {
                connectionsClient.sendPayload(
                    id,
                    Payload.fromBytes(message.toByteArray(StandardCharsets.UTF_8))
                )
            }
        }
    }

    fun forwardBytesToTarget(bytes: ByteArray, targetName: String) {
        for ((id, name) in connectedEndpoints) {
            if (name.contains(targetName, ignoreCase = true)) {
                connectionsClient.sendPayload(
                    id,
                    Payload.fromBytes(bytes)
                )
            }
        }
    }

    fun stopAll() {
        try {
            connectionsClient.stopAdvertising()
            connectionsClient.stopDiscovery()
            connectionsClient.stopAllEndpoints()
            connectedEndpoints.clear()
            onStatusChange("Offline")
            Log.d("NearbyManager", "All Nearby services stopped.")
        } catch (e: Exception) {
            Log.e("NearbyManager", "Error in stopAll: ${e.message}")
        }
    }
}