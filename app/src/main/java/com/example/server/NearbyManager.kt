package com.example.droneswarm.server

import android.content.Context
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.*

class NearbyManager(
    private val context: Context,
    private val serviceId: String,
    private val onMessageReceived: (String, String) -> Unit,
    private val onStatusChange: (String) -> Unit,
    private val onDeviceConnected: (String, String) -> Unit
) {
    private val connectionsClient = Nearby.getConnectionsClient(context)
    private val connectedEndpoints = mutableMapOf<String, String>()
    private val managerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // ✅ Constructor mein hi discovery auto-start
    init {
        startDiscovery()
    }

    fun startDiscovery() {
        // Pehle stop karo taaki duplicate callbacks na aayein
        connectionsClient.stopDiscovery()

        val discoveryOptions = DiscoveryOptions.Builder()
            .setStrategy(Strategy.P2P_CLUSTER)
            .build()

        connectionsClient.startDiscovery(
            serviceId,
            object : EndpointDiscoveryCallback() {
                override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
                    Log.d("NEARBY_SERVER", "Drone Found: ${info.endpointName}")
                    onStatusChange("NODE_FOUND: ${info.endpointName}")

                    connectionsClient.requestConnection(
                        "Server",
                        endpointId,
                        connectionLifecycleCallback
                    ).addOnSuccessListener {
                        Log.d("NEARBY_SERVER", "Connection requested to: ${info.endpointName}")
                    }.addOnFailureListener {
                        Log.e("NEARBY_SERVER", "Request failed: ${it.message}")
                        onStatusChange("CONNECT_FAILED")
                    }
                }

                override fun onEndpointLost(endpointId: String) {
                    Log.w("NEARBY_SERVER", "Endpoint lost: $endpointId")
                    connectedEndpoints.remove(endpointId)
                    onStatusChange(
                        if (connectedEndpoints.isEmpty()) "NODE_LOST"
                        else "Nodes: ${connectedEndpoints.size}"
                    )
                }
            },
            discoveryOptions
        ).addOnSuccessListener {
            Log.d("NEARBY_SERVER", "Discovery started ✅")
            onStatusChange("SCANNING...")
        }.addOnFailureListener { e ->
            Log.e("NEARBY_SERVER", "Discovery FAILED: ${e.message}")
            onStatusChange("DISCOVERY_FAILED")

            // ✅ Retry after 3 seconds
            managerScope.launch {
                delay(3000)
                Log.d("NEARBY_SERVER", "Retrying discovery...")
                startDiscovery()
            }
        }
    }

    fun stopDiscovery() = connectionsClient.stopDiscovery()
    fun stopAdvertising() = connectionsClient.stopAdvertising()

    fun stopAll() {
        connectionsClient.stopAllEndpoints()
        connectionsClient.stopDiscovery()
        connectionsClient.stopAdvertising()
        managerScope.cancel()
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            Log.d("NEARBY_SERVER", "Connection initiated: ${info.endpointName}")
            connectedEndpoints[endpointId] = info.endpointName ?: "Unknown"
            connectionsClient.acceptConnection(endpointId, payloadCallback)
            onStatusChange("HANDSHAKING...")
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                val name = connectedEndpoints[endpointId] ?: "Drone Node"
                Log.d("NEARBY_SERVER", "Connected: $name ($endpointId)")
                onStatusChange("CONNECTED: $name")
                onDeviceConnected(endpointId, name)
            } else {
                Log.e("NEARBY_SERVER", "Connection failed for $endpointId: ${result.status}")
                connectedEndpoints.remove(endpointId)
                onStatusChange("CONNECTION_FAILED")
            }
        }

        override fun onDisconnected(endpointId: String) {
            val name = connectedEndpoints[endpointId] ?: endpointId
            Log.w("NEARBY_SERVER", "Disconnected: $name")
            connectedEndpoints.remove(endpointId)
            onStatusChange(
                if (connectedEndpoints.isEmpty()) "DISCONNECTED"
                else "Nodes: ${connectedEndpoints.size}"
            )
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            managerScope.launch {
                val bytes = payload.asBytes() ?: return@launch
                val data = try {
                    String(bytes, StandardCharsets.UTF_8)
                } catch (e: Exception) {
                    Log.e("NEARBY_SERVER", "Payload decode error: ${e.message}")
                    ""
                }
                if (data.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        onMessageReceived(endpointId, data)
                    }
                }
            }
        }

        override fun onPayloadTransferUpdate(id: String, update: PayloadTransferUpdate) {}
    }

    // ✅ Single endpoint ko message
    fun sendMessage(msg: String, targetEndpointId: String? = null) {
        managerScope.launch {
            if (connectedEndpoints.isEmpty()) {
                Log.w("NEARBY_SERVER", "No endpoints connected, message dropped: $msg")
                return@launch
            }

            val payload = Payload.fromBytes(msg.toByteArray(StandardCharsets.UTF_8))

            if (targetEndpointId != null && connectedEndpoints.containsKey(targetEndpointId)) {
                // Specific drone ko bhejo (e.g. Drone1 ko relay command)
                connectionsClient.sendPayload(listOf(targetEndpointId), payload)
                    .addOnFailureListener { e ->
                        Log.e("NEARBY_SERVER", "Send to $targetEndpointId failed: ${e.message}")
                    }
            } else {
                // Sabko bhejo (broadcast)
                connectionsClient.sendPayload(connectedEndpoints.keys.toList(), payload)
                    .addOnFailureListener { e ->
                        Log.e("NEARBY_SERVER", "Broadcast failed: ${e.message}")
                    }
            }
        }
    }

    // ✅ Connected endpoints ki list — MainActivity use kar sakti hai
    fun getConnectedEndpoints(): Map<String, String> = connectedEndpoints.toMap()
}