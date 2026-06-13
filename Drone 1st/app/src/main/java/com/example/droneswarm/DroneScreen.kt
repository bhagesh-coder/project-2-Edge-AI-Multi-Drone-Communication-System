package com.example.droneswarm.drone1

import android.Manifest
import android.content.Context
import android.graphics.*
import android.os.BatteryManager
import android.os.Looper
import android.util.Log
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

val DetectorPurple = Color(0xFF514595)
val DetectorDark = Color(0xFF1A1A1A)
val DetectorBg = Color(0xFFFAFAFA)
val AlertRed = Color(0xFFD32F2F)

@Composable
fun Drone1Screen(
    nearbyManager: NearbyManager,
    yoloDetector: YOLODetector,
    webRtcManager: WebRTCDroneManager,
    logs: List<String>,
    status: String,
    shouldStartCamera: Boolean,
    onCameraStarted: () -> Unit,
    onServerClick: () -> Unit,
    onNodesClick: () -> Unit
) {
    val context = LocalContext.current
    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }
    val batteryManager = remember { context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager }

    var currentLat by remember { mutableStateOf("Fetching...") }
    var currentLong by remember { mutableStateOf("Fetching...") }
    var humanStatus by remember { mutableStateOf("Idle") }
    var isCameraOpen by remember { mutableStateOf(false) }
    val sessionLogs = remember { mutableStateListOf<String>() }
    var lastLogTimeMillis by remember { mutableLongStateOf(0L) }

    LaunchedEffect(shouldStartCamera) {
        if (shouldStartCamera) {
            isCameraOpen = true
            onCameraStarted()
        }
    }

    LaunchedEffect(isCameraOpen) {
        if (!isCameraOpen) {
            humanStatus = "Idle"
        }
    }

    val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 4000L).build()
    val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let {
                currentLat = String.format("%.6f", it.latitude)
                currentLong = String.format("%.6f", it.longitude)
                if (status == "Connected") {
                    val battLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                    nearbyManager.sendMessage("DATA|BATT:$battLevel|GPS:$currentLat,$currentLong")
                }
            }
        }
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms.values.all { it }) {
            try {
                fusedLocationClient.requestLocationUpdates(
                    locationRequest,
                    locationCallback,
                    Looper.getMainLooper()
                )
            } catch (e: SecurityException) { Log.e("DRONE1", "GPS Fail") }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DetectorBg)
            .padding(16.dp)
    ) {
        // ── Header ──────────────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    "🚁 Drone 1: Gateway",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = DetectorDark
                )
                Text("Command & Control Unit", fontSize = 14.sp, color = Color.Gray)
            }
            Surface(
                color = if (status == "Connected") Color(0xFFE8F5E9) else Color(0xFFFFEBEE),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    status.uppercase(),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // ── Status Cards ─────────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatusCard(Modifier.weight(1f), "Drone 2 Status", status, "Network: Active", DetectorPurple)
            StatusCard(Modifier.weight(1f), "Gateway GPS", currentLat, currentLong, Color(0xFF00796B))
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ── Detection Alert Card ─────────────────────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (humanStatus.contains("DETECTED"))
                    AlertRed.copy(0.1f) else Color.White
            ),
            border = BorderStroke(
                1.dp,
                if (humanStatus.contains("DETECTED")) AlertRed else Color(0xFFEEEEEE)
            )
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Notifications, null,
                    tint = if (humanStatus.contains("DETECTED")) AlertRed else Color.Gray
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text("AI Detection State", fontSize = 12.sp, color = Color.Gray)
                    Text(
                        humanStatus,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (humanStatus.contains("DETECTED")) AlertRed else DetectorDark
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // ── Control Buttons ──────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(
                modifier = Modifier.weight(1.3f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        permLauncher.launch(
                            arrayOf(
                                Manifest.permission.CAMERA,
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.BLUETOOTH_SCAN,
                                Manifest.permission.BLUETOOTH_CONNECT,
                                Manifest.permission.BLUETOOTH_ADVERTISE,
                                Manifest.permission.NEARBY_WIFI_DEVICES
                            )
                        )
                        onServerClick()
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = DetectorPurple)
                ) { Text("Connect Server", fontSize = 13.sp, fontWeight = FontWeight.Bold) }

                Button(
                    onClick = { onNodesClick() },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = DetectorPurple)
                ) { Text("Connect Nodes", fontSize = 13.sp, fontWeight = FontWeight.Bold) }
            }

            Button(
                onClick = { isCameraOpen = !isCameraOpen },
                modifier = Modifier.weight(1f).height(104.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = DetectorDark)
            ) {
                Text(
                    if (isCameraOpen) "Stop AI" else "Start AI",
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // ── Camera Feed ──────────────────────────────────────────────────────
        if (isCameraOpen) {
            Card(
                modifier = Modifier.fillMaxWidth().height(280.dp),
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(3.dp, DetectorDark)
            ) {
                Box {
                    DetectorCamera(
                        yoloDetector = yoloDetector,
                        webRtcManager = webRtcManager,
                        onCameraReady = { webRtcManager.onCameraReady() },
                        onHumanDetected = { isHuman ->
                            if (isCameraOpen) {
                                humanStatus = if (isHuman) "⚠️ HUMAN DETECTED!" else "Scanning..."
                                val currentTime = System.currentTimeMillis()
                                if (isHuman && (currentTime - lastLogTimeMillis > 5000L)) {
                                    val time = SimpleDateFormat(
                                        "HH:mm:ss", Locale.getDefault()
                                    ).format(Date())
                                    val batt = batteryManager.getIntProperty(
                                        BatteryManager.BATTERY_PROPERTY_CAPACITY
                                    )
                                    val logMsg =
                                        "Local|$time|Human Detected|$currentLat, $currentLong"
                                    sessionLogs.add(0, logMsg)
                                    nearbyManager.sendMessage(
                                        "Drone 1 : ALERT|$time|$currentLat|$currentLong|BATT:$batt"
                                    )
                                    lastLogTimeMillis = currentTime
                                }
                            }
                        }
                    )
                    IconButton(
                        onClick = { isCameraOpen = false; humanStatus = "Idle" },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(16.dp)
                            .background(Color.Black.copy(0.6f), RoundedCornerShape(50))
                    ) { Icon(Icons.Default.Close, null, tint = Color.White) }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }

        // ── Activity Log ─────────────────────────────────────────────────────
        Text(
            "📜 Swarm Activity History",
            fontWeight = FontWeight.ExtraBold,
            fontSize = 18.sp,
            color = DetectorDark
        )
        Card(
            modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 10.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.dp, Color(0xFFE0E0E0))
        ) {
            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .verticalScroll(scrollState)
            ) {
                val combinedLogs = (sessionLogs + logs)
                if (combinedLogs.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "Ready for remote data relay...",
                            color = Color.LightGray,
                            fontSize = 12.sp
                        )
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

// ─────────────────────────────────────────────────────────────────────────────
// DetectorCamera
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun DetectorCamera(
    yoloDetector: YOLODetector,
    webRtcManager: WebRTCDroneManager,
    onCameraReady: () -> Unit,
    onHumanDetected: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }

    DisposableEffect(Unit) {
        onDispose { onHumanDetected(false) }
    }

    LaunchedEffect(Unit) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = providerFuture.get()

            val preview = Preview.Builder().build()

            val analyzer = ImageAnalysis.Builder()
                .setTargetResolution(Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()

            val analysisExecutor = Executors.newSingleThreadExecutor()
            val yoloExecutor = Executors.newSingleThreadExecutor()

            var lastAnalysisTime = 0L

            analyzer.setAnalyzer(analysisExecutor) { proxy ->
                val rotation = proxy.imageInfo.rotationDegrees

                try {
                    webRtcManager.pushFrame(proxy, rotation)
                } catch (e: Exception) {
                    Log.e("WebRTC_Push", "Frame push failed: ${e.message}")
                }

                val currentTime = System.currentTimeMillis()
                if (currentTime - lastAnalysisTime >= 500L) {
                    lastAnalysisTime = currentTime

                    val bitmap = try { proxy.toBitmap() } catch (e: Exception) { null }
                    proxy.close()

                    if (bitmap != null) {
                        yoloExecutor.execute {
                            yoloDetector.detect(bitmap) { found, _ ->
                                onHumanDetected(found)
                            }
                        }
                    }
                } else {
                    proxy.close()
                }
            }

            try {
                provider.unbindAll()
                preview.setSurfaceProvider(previewView.surfaceProvider)
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analyzer
                )
                onCameraReady()
                Log.d("DRONE_CAMERA", "✅ Camera bound — WebRTC ready to stream")
            } catch (e: Exception) {
                Log.e("YOLO", "Camera bind failed: ${e.message}")
            }

        }, ContextCompat.getMainExecutor(context))
    }

    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}

// ─────────────────────────────────────────────────────────────────────────────
// UI Components
// ─────────────────────────────────────────────────────────────────────────────
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

@Composable
fun EnhancedLogItem(logData: String) {
    val parts = logData.split("|")
    val tag = parts.getOrNull(0) ?: "Log"
    val time = parts.getOrNull(1) ?: "--:--:--"
    val msg = parts.getOrNull(2) ?: ""
    val loc = parts.getOrNull(3) ?: "No GPS"
    val accent = if (tag.contains("Local")) DetectorPurple else Color.Gray

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(40.dp)
        ) {
            Box(modifier = Modifier.size(10.dp).background(accent, RoundedCornerShape(50)))
            Box(modifier = Modifier.width(2.dp).height(40.dp).background(Color(0xFFEEEEEE)))
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "[$tag]",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = accent
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(time, fontSize = 11.sp, color = Color.Gray)
            }
            Text(
                msg,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (msg.contains("Detected")) AlertRed else DetectorDark
            )
            Row(
                modifier = Modifier.padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.LocationOn, null,
                    modifier = Modifier.size(14.dp),
                    tint = Color.Gray
                )
                Text(loc, fontSize = 12.sp, color = Color.DarkGray)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ImageProxy → Bitmap utility
// ─────────────────────────────────────────────────────────────────────────────
fun ImageProxy.toBitmap(): Bitmap? {
    return try {
        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, this.width, this.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, this.width, this.height), 85, out)
        val imageBytes = out.toByteArray()

        val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            ?: return null

        val matrix = Matrix().apply {
            postRotate(this@toBitmap.imageInfo.rotationDegrees.toFloat())
        }
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    } catch (e: Exception) {
        Log.e("toBitmap", "Conversion failed: ${e.message}")
        null
    }
}