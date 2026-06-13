package com.example.droneswarm

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
            payload.asBytes()?.let {
                val receivedMsg = String(it, StandardCharsets.UTF_8)
                val parts = receivedMsg.split("|")
                val senderTag = if (parts.isNotEmpty()) parts[0] else "Remote"
                onMessageReceived(senderTag, receivedMsg)
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
            } else {
                connectedEndpoints.remove(endpointId)
                onStatusChange("Failed")
            }
        }

        override fun onDisconnected(endpointId: String) {
            connectedEndpoints.remove(endpointId)
            onStatusChange(if (connectedEndpoints.isEmpty()) "Disconnected" else "Connected")
        }
    }

    // Drone 2 sirf RELAY_CHANNEL pe advertise karta hai — Drone 1 se connect hone ke liye
    fun startAdvertising() {
        val options = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startAdvertising(
            "Drone 2",
            "com.example.droneswarm.RELAY_CHANNEL",
            connectionLifecycleCallback,
            options
        )
            .addOnSuccessListener { onStatusChange("Broadcasting for Drone 1...") }
            .addOnFailureListener { onStatusChange("Adv Error") }
    }

    fun sendMessage(msg: String) {
        for ((id, _) in connectedEndpoints) {
            connectionsClient.sendPayload(
                id,
                Payload.fromBytes(msg.toByteArray(StandardCharsets.UTF_8))
            )
        }
    }
    fun sendBytes(bytes: ByteArray) {
        for ((id, _) in connectedEndpoints) {
            connectionsClient.sendPayload(
                id,
                Payload.fromBytes(bytes)
            )
        }
    }
    fun stopAll() {
        try {
            connectionsClient.stopAdvertising()
            connectionsClient.stopDiscovery()
            connectionsClient.stopAllEndpoints()
            connectedEndpoints.clear()
            onStatusChange("Offline")
            Log.d("NearbyManager_D2", "All Nearby services stopped.")
        } catch (e: Exception) {
            Log.e("NearbyManager_D2", "Error in stopAll: ${e.message}")
        }
    }
}