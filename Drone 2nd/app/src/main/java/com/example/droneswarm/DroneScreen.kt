package com.example.droneswarm

import android.Manifest
import android.os.Looper
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.location.*
import java.text.SimpleDateFormat
import java.util.*

val DetectorPurple = Color(0xFF514595)
val DetectorDark = Color(0xFF1A1A1A)
val DetectorBg = Color(0xFFFAFAFA)
val AlertRed = Color(0xFFD32F2F)

@Composable
fun Drone2Screen(
    nearbyManager: NearbyManager,
    yoloDetector: YOLODetector,
    cameraManager: CameraManager,
    logs: List<String>,
    status: String,
    humanDetected: Boolean,
    onCameraToggle: (Boolean) -> Unit  // ✅ naya
) {
    val context = LocalContext.current
    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }

    var currentLat by remember { mutableStateOf("Fetching...") }
    var currentLong by remember { mutableStateOf("Fetching...") }
    var isCameraOpen by remember { mutableStateOf(false) }

    val sessionLogs = remember { mutableStateListOf<String>() }
    var lastLogTimeMillis by remember { mutableLongStateOf(0L) }

    val humanStatus = when {
        !isCameraOpen -> "Idle"
        humanDetected -> "⚠️ HUMAN DETECTED!"
        else -> "Scanning..."
    }

    LaunchedEffect(humanDetected) {
        if (humanDetected && isCameraOpen) {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastLogTimeMillis > 5000L) {
                val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                val logMsg = "Local|$time|Human Detected|$currentLat, $currentLong"
                sessionLogs.add(0, logMsg)
                nearbyManager.sendMessage("Drone 2 : ALERT|$time|$currentLat|$currentLong")
                lastLogTimeMillis = currentTime
            }
        }
    }

    val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 4000L).build()
    val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let {
                currentLat = String.format("%.6f", it.latitude)
                currentLong = String.format("%.6f", it.longitude)
            }
        }
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms.values.all { it }) {
            try {
                fusedLocationClient.requestLocationUpdates(
                    locationRequest, locationCallback, Looper.getMainLooper()
                )
                nearbyManager.startAdvertising()
            } catch (e: SecurityException) { Log.e("DRONE2", "GPS Fail") }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(DetectorBg).padding(16.dp)) {

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("🚁 Drone 2: Detector", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = DetectorDark)
                Text("Target Identification Unit", fontSize = 14.sp, color = Color.Gray)
            }
            Surface(
                color = if (status == "Connected") Color(0xFFE8F5E9) else Color(0xFFFFEBEE),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    status.uppercase(),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    fontSize = 10.sp, fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatusCard(Modifier.weight(1f), "Drone 1 Status", status, "Network: Active", DetectorPurple)
            StatusCard(Modifier.weight(1f), "Detector GPS", currentLat, currentLong, Color(0xFF00796B))
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (humanDetected && isCameraOpen) AlertRed.copy(0.1f) else Color.White
            ),
            border = BorderStroke(1.dp, if (humanDetected && isCameraOpen) AlertRed else Color(0xFFEEEEEE))
        ) {
            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Notifications, null,
                    tint = if (humanDetected && isCameraOpen) AlertRed else Color.Gray
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text("AI Detection State", fontSize = 12.sp, color = Color.Gray)
                    Text(
                        humanStatus, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        color = if (humanDetected && isCameraOpen) AlertRed else DetectorDark
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = {
                    permLauncher.launch(arrayOf(
                        Manifest.permission.CAMERA,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.BLUETOOTH_ADVERTISE,
                        Manifest.permission.NEARBY_WIFI_DEVICES
                    ))
                },
                modifier = Modifier.weight(1.3f).height(60.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = DetectorPurple)
            ) {
                Text("Initialize Detector", fontWeight = FontWeight.Bold)
            }
            Button(
                onClick = {
                    val newState = !isCameraOpen
                    isCameraOpen = newState
                    onCameraToggle(newState)  // ✅ camera start/stop yahan se
                },
                modifier = Modifier.weight(1f).height(60.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = DetectorDark)
            ) {
                Text(if (isCameraOpen) "Stop AI" else "Start AI")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (isCameraOpen) {
            Card(
                modifier = Modifier.fillMaxWidth().height(280.dp),
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(3.dp, DetectorDark)
            ) {
                Box {
                    AndroidView(
                        factory = { ctx ->
                            PreviewView(ctx).also { pv ->
                                cameraManager.previewView = pv
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                    IconButton(
                        onClick = {
                            isCameraOpen = false
                            onCameraToggle(false)  // ✅ X button pe bhi camera band
                        },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(16.dp)
                            .background(Color.Black.copy(0.6f), RoundedCornerShape(50))
                    ) {
                        Icon(Icons.Default.Close, null, tint = Color.White)
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }

        Text("📜 Detection Log History", fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, color = DetectorDark)
        Card(
            modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 10.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.dp, Color(0xFFE0E0E0))
        ) {
            val scrollState = rememberScrollState()
            Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(scrollState)) {
                val combinedLogs = (sessionLogs + logs)
                if (combinedLogs.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Ready for detection relay...", color = Color.LightGray, fontSize = 12.sp)
                    }
                } else {
                    combinedLogs.forEach { log ->
                        EnhancedLogItem(log)
                        Divider(color = Color(0xFFF5F5F5), thickness = 1.dp)
                    }
                }
            }
        }
    }
}

@Composable
fun EnhancedLogItem(logData: String) {
    val parts = logData.split("|")
    val tag = parts.getOrNull(0) ?: "Log"
    val time = parts.getOrNull(1) ?: "--:--:--"
    val msg = parts.getOrNull(2) ?: ""
    val loc = parts.getOrNull(3) ?: "No GPS"
    val accent = if (tag.contains("Local")) DetectorPurple else Color.Gray
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.Top) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(40.dp)) {
            Box(modifier = Modifier.size(10.dp).background(accent, RoundedCornerShape(50)))
            Box(modifier = Modifier.width(2.dp).height(40.dp).background(Color(0xFFEEEEEE)))
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("[$tag]", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = accent)
                Spacer(modifier = Modifier.width(8.dp))
                Text(time, fontSize = 11.sp, color = Color.Gray)
            }
            Text(
                msg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                color = if (msg.contains("Detected")) AlertRed else DetectorDark
            )
            Row(modifier = Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.LocationOn, null, modifier = Modifier.size(14.dp), tint = Color.Gray)
                Text(
                    loc, fontSize = 12.sp, color = Color.DarkGray,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
fun StatusCard(modifier: Modifier, label: String, value: String, sub: String, color: Color) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(2.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(label, fontSize = 11.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
            Text(value, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = color)
            Text(sub, fontSize = 10.sp, color = Color.Gray)
        }
    }
}