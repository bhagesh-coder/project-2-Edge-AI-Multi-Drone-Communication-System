package com.example.droneswarm.server

import android.graphics.Bitmap
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import com.example.droneswarm.server.webrtc.WebRTCManager
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

data class DroneDevice(
    val id: String,
    val battery: Int = 100,
    val status: String = "ACTIVE",
    val hopPath: String = ""
)

@Composable
fun TacticalServerScreen(
    nearbyManager: NearbyManager,
    logs: List<String>,
    status: String,
    connectedDrones: List<DroneDevice>,
    onNavigateHome: () -> Unit,
    drone1WebRtcManager: WebRTCManager,
    drone2WebRtcManager: WebRTCManager,
    drone1VideoTrack: VideoTrack?,
    drone2VideoTrack: VideoTrack?,
    d2FrameBitmap: Bitmap?
) {
    var selectedId by remember { mutableStateOf<String?>(null) }
    var showDroneList by remember { mutableStateOf(false) }
    var isFeedVisible by remember { mutableStateOf(true) }

    LaunchedEffect(connectedDrones) {
        if (selectedId == null && connectedDrones.isNotEmpty()) {
            selectedId = connectedDrones.first().id
        }
    }

    val activeDrone = connectedDrones.find { it.id == selectedId }
    val isRelaydrone = activeDrone?.status == "RELAY"

    val activeVideoTrack = when {
        !isFeedVisible -> null
        isRelaydrone -> drone2VideoTrack
        else -> drone1VideoTrack
    }

    val activeWebRtcManager = if (isRelaydrone) drone2WebRtcManager else drone1WebRtcManager

    val primaryGreen = Color(0xFF4CAF50)
    val obsidian = Color(0xFF070707)
    val surfaceColor = Color(0xFF121212)
    val borderStroke = Color(0xFF222222)
    val relayColor = Color(0xFF2196F3)

    Row(modifier = Modifier.fillMaxSize().background(obsidian)) {

        // SIDEBAR
        Column(
            modifier = Modifier
                .width(64.dp).fillMaxHeight()
                .background(surfaceColor)
                .border(0.5.dp, borderStroke)
                .padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(28.dp)
        ) {
            IconButton(onClick = { }) {
                Icon(
                    imageVector = Icons.Default.TrackChanges,
                    contentDescription = "RADAR",
                    tint = if (connectedDrones.isNotEmpty()) primaryGreen else Color.Gray,
                    modifier = Modifier.size(24.dp)
                )
            }
            IconButton(onClick = onNavigateHome) {
                Icon(Icons.Default.Home, "HOME", tint = Color(0xFF636366))
            }
            IconButton(onClick = { }) {
                Icon(Icons.Default.Layers, "LAYERS", tint = Color(0xFF636366))
            }
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier.size(8.dp).clip(CircleShape)
                    .background(if (connectedDrones.isNotEmpty()) primaryGreen else Color.Red)
            )
            IconButton(onClick = { }) {
                Icon(Icons.Default.Settings, "SETTINGS", tint = Color(0xFF636366))
            }
        }

        // MAIN WORKSPACE
        Column(modifier = Modifier.weight(1f).padding(16.dp)) {

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box {
                    Surface(
                        onClick = { showDroneList = true },
                        color = Color.Black,
                        shape = RoundedCornerShape(4.dp),
                        border = BorderStroke(1.dp, borderStroke)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Dns, null, tint = primaryGreen, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(
                                    "NODES: ${connectedDrones.size}  ▼",
                                    color = Color.White, fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold
                                )
                                if (activeDrone?.hopPath?.isNotEmpty() == true) {
                                    Text(
                                        activeDrone.hopPath,
                                        color = if (isRelaydrone) relayColor else primaryGreen,
                                        fontSize = 9.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }

                    DropdownMenu(
                        expanded = showDroneList,
                        onDismissRequest = { showDroneList = false },
                        modifier = Modifier
                            .background(Color(0xFF101010))
                            .border(1.dp, borderStroke, RoundedCornerShape(4.dp))
                            .width(260.dp)
                    ) {
                        if (connectedDrones.isEmpty()) {
                            DropdownMenuItem(
                                text = {
                                    Text("NO ACTIVE NODES", color = Color.Gray, fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace)
                                },
                                onClick = { showDroneList = false },
                                enabled = false
                            )
                        } else {
                            connectedDrones.forEach { drone ->
                                val isDroneRelay = drone.status == "RELAY"
                                DropdownMenuItem(
                                    text = {
                                        Column(modifier = Modifier.padding(vertical = 2.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Surface(
                                                        color = if (isDroneRelay) relayColor.copy(0.2f)
                                                        else primaryGreen.copy(0.2f),
                                                        shape = RoundedCornerShape(2.dp)
                                                    ) {
                                                        Text(
                                                            if (isDroneRelay) " RELAY " else " DIRECT ",
                                                            color = if (isDroneRelay) relayColor else primaryGreen,
                                                            fontSize = 8.sp,
                                                            fontFamily = FontFamily.Monospace,
                                                            modifier = Modifier.padding(
                                                                horizontal = 4.dp, vertical = 2.dp
                                                            )
                                                        )
                                                    }
                                                    Spacer(Modifier.width(8.dp))
                                                    Text(
                                                        drone.id, color = Color.White,
                                                        fontWeight = FontWeight.Bold,
                                                        fontFamily = FontFamily.Monospace, fontSize = 11.sp
                                                    )
                                                }
                                                Text(
                                                    "${drone.battery}% PWR",
                                                    color = if (isDroneRelay) relayColor else primaryGreen,
                                                    fontSize = 10.sp, fontFamily = FontFamily.Monospace
                                                )
                                            }
                                            if (drone.hopPath.isNotEmpty()) {
                                                Spacer(Modifier.height(4.dp))
                                                Text(
                                                    "PATH: ${drone.hopPath}",
                                                    color = Color.Gray, fontSize = 9.sp,
                                                    fontFamily = FontFamily.Monospace
                                                )
                                            }
                                        }
                                    },
                                    onClick = {
                                        selectedId = drone.id
                                        showDroneList = false
                                    },
                                    modifier = Modifier.background(
                                        if (drone.id == selectedId) Color(0xFF1A1A1A)
                                        else Color.Transparent
                                    )
                                )
                            }
                        }
                    }
                }

                Button(
                    onClick = { nearbyManager.startDiscovery() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (status.contains("Scan")) Color.White else Color(0xFF1A1A1A)
                    ),
                    shape = RoundedCornerShape(2.dp),
                    border = BorderStroke(
                        1.dp,
                        if (status.contains("Scan")) Color.White else borderStroke
                    )
                ) {
                    Text(
                        text = status.uppercase(),
                        color = if (status.contains("Scan")) Color.Black else Color.White,
                        fontSize = 10.sp, fontWeight = FontWeight.ExtraBold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // VIDEO FEED VIEWPORT
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color.Black)
                    .border(1.dp, borderStroke),
                contentAlignment = Alignment.Center
            ) {
                if (activeDrone != null) {
                    if (activeVideoTrack != null) {
                        VideoViewContainer(
                            videoTrack = activeVideoTrack,
                            webRtcManager = activeWebRtcManager
                        )
                        // Overlay
                        Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                            Column(modifier = Modifier.align(Alignment.TopStart)) {
                                Surface(
                                    color = Color.Black.copy(alpha = 0.6f),
                                    shape = RoundedCornerShape(2.dp)
                                ) {
                                    Text(
                                        " NODE: ${activeDrone.id} // BATT: ${activeDrone.battery}% ",
                                        color = primaryGreen, fontSize = 10.sp,
                                        modifier = Modifier.padding(4.dp),
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                if (isRelaydrone && activeDrone.hopPath.isNotEmpty()) {
                                    Spacer(Modifier.height(4.dp))
                                    Surface(
                                        color = relayColor.copy(alpha = 0.8f),
                                        shape = RoundedCornerShape(2.dp)
                                    ) {
                                        Text(
                                            " RELAY: ${activeDrone.hopPath} ",
                                            color = Color.White, fontSize = 9.sp,
                                            modifier = Modifier.padding(4.dp),
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(
                                color = if (isRelaydrone) relayColor else primaryGreen,
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                if (isRelaydrone) "AWAITING_RELAY_STREAM"
                                else "INITIALIZING_WEBRTC_STREAM",
                                color = if (isRelaydrone) relayColor else primaryGreen,
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.LinkOff, null,
                            tint = Color(0xFF121212),
                            modifier = Modifier.size(72.dp)
                        )
                        Text(
                            "DISCONNECTED_FROM_UPLINK",
                            color = Color(0xFF1A1A1A), fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // COMMAND CENTER PANEL
            Surface(
                modifier = Modifier.fillMaxWidth().height(90.dp),
                color = surfaceColor,
                border = BorderStroke(1.dp, borderStroke),
                shape = RoundedCornerShape(4.dp)
            ) {
                if (activeDrone != null) {
                    Row(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Button(
                            onClick = {
                                if (isRelaydrone) nearbyManager.sendMessage("D2_START_STREAM")
                                else nearbyManager.sendMessage("CMD_STREAM_ON")
                                isFeedVisible = true
                            },
                            modifier = Modifier.weight(1f).height(48.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Black),
                            border = BorderStroke(1.dp, primaryGreen),
                            shape = RoundedCornerShape(4.dp)
                        ) { Text("VIEW FEED", color = primaryGreen) }

                        Button(
                            onClick = {
                                if (isRelaydrone) nearbyManager.sendMessage("D2_STOP_STREAM")
                                else nearbyManager.sendMessage("CMD_STREAM_OFF")
                                isFeedVisible = false
                            },
                            modifier = Modifier.weight(1f).height(48.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A0000)),
                            border = BorderStroke(1.dp, Color(0xFFB71C1C)),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                "STOP FEED", color = Color(0xFFEF5350),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                } else {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Text("AWAITING_NODE_SELECTION", color = Color.DarkGray, fontSize = 11.sp)
                    }
                }
            }
        }

        // LOG PANEL
        Column(modifier = Modifier.width(280.dp).fillMaxHeight().padding(16.dp)) {
            Text(
                "SYSTEM_TELEMETRY", color = Color.White,
                fontSize = 11.sp, fontWeight = FontWeight.Black
            )
            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black)
                    .border(1.dp, borderStroke).padding(8.dp)
            ) {
                LazyColumn {
                    items(logs.asReversed()) { logEntry ->
                        Text(
                            ">> $logEntry",
                            color = when {
                                logEntry.contains("RELAY") -> relayColor
                                logEntry.contains("WARN") -> Color.Yellow
                                else -> Color.White.copy(alpha = 0.6f)
                            },
                            fontSize = 9.sp,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                        HorizontalDivider(color = borderStroke, thickness = 0.5.dp)
                    }
                }
            }
        }
    }
}

@Composable
fun VideoViewContainer(videoTrack: VideoTrack, webRtcManager: WebRTCManager) {
    val rendererRef = remember { mutableStateOf<SurfaceViewRenderer?>(null) }

    DisposableEffect(videoTrack) {
        val renderer = rendererRef.value
        if (renderer != null) {
            videoTrack.addSink(renderer)
        }
        onDispose {
            rendererRef.value?.let { videoTrack.removeSink(it) }
        }
    }

    AndroidView(
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                init(webRtcManager.eglBase.eglBaseContext, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                setEnableHardwareScaler(true)
                videoTrack.addSink(this)
                rendererRef.value = this
            }
        },
        modifier = Modifier.fillMaxSize(),
        onRelease = { view ->
            try {
                videoTrack.removeSink(view)
                rendererRef.value = null
                view.release()
            } catch (e: Exception) {
                Log.e("TACTICAL_LOG", "VideoView release error: ${e.message}")
            }
        }
    )
}

@Composable
fun DroneSelectionDialog(
    drones: List<DroneDevice>,
    onSelect: (DroneDevice) -> Unit,
    onDismiss: () -> Unit
) {
    val relayColor = Color(0xFF2196F3)
    val primaryGreen = Color(0xFF4CAF50)

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .wrapContentHeight()
                .border(1.dp, Color(0xFF333333), RoundedCornerShape(8.dp)),
            color = Color(0xFF101010),
            shape = RoundedCornerShape(8.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    "NODE_DISCOVERY_LIST", color = Color.White,
                    fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(4.dp))
                Text("Tap a node to view its feed", color = Color.Gray, fontSize = 10.sp)
                Spacer(Modifier.height(16.dp))

                LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    items(drones) { drone ->
                        val isDroneRelay = drone.status == "RELAY"
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                .clickable { onSelect(drone) },
                            colors = CardDefaults.cardColors(
                                containerColor = if (isDroneRelay) Color(0xFF0D1B2A)
                                else Color(0xFF1A1A1A)
                            ),
                            border = BorderStroke(
                                0.5.dp,
                                if (isDroneRelay) relayColor else Color(0xFF333333)
                            )
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Surface(
                                            color = if (isDroneRelay) relayColor.copy(0.2f)
                                            else primaryGreen.copy(0.2f),
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                if (isDroneRelay) " RELAY " else " DIRECT ",
                                                color = if (isDroneRelay) relayColor else primaryGreen,
                                                fontSize = 8.sp, fontFamily = FontFamily.Monospace,
                                                modifier = Modifier.padding(
                                                    horizontal = 4.dp, vertical = 2.dp
                                                )
                                            )
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            drone.id, color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace, fontSize = 13.sp
                                        )
                                    }
                                    Text(
                                        "${drone.battery}% PWR",
                                        color = if (isDroneRelay) relayColor else primaryGreen,
                                        fontSize = 11.sp
                                    )
                                }
                                if (drone.hopPath.isNotEmpty()) {
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        "PATH: ${drone.hopPath}",
                                        color = Color.Gray, fontSize = 9.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text("CLOSE", color = Color.Gray)
                }
            }
        }
    }
}