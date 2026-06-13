package com.example.droneswarm

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import android.util.Size
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

class CameraManager(
    private val context: Context,
    private val onFrameBytes: (ByteArray) -> Unit,
    private val onHumanDetected: ((Boolean) -> Unit)? = null,
    private val yoloDetector: YOLODetector? = null ,
    private val onFrameForWebRTC: ((ImageProxy, Int) -> Unit)? = null // File 2 ke according same rkha hai
) {
    private val TAG = "D2_Camera"
    private var cameraProvider: ProcessCameraProvider? = null
    var previewView: PreviewView? = null

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val yoloExecutor = Executors.newSingleThreadExecutor()

    fun startCamera(lifecycleOwner: LifecycleOwner) {
        Log.d(TAG, "✅ startCamera() called")
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build()
            if (previewView != null) {
                preview.setSurfaceProvider(previewView!!.surfaceProvider)
            } else {
                Log.e(TAG, "❌ previewView is NULL")
            }

            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            var lastYoloTime = 0L
            var frameCount = 0

            imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
                frameCount++
                if (frameCount % 30 == 0) Log.d(TAG, "📸 frames: $frameCount")

                val width = imageProxy.width
                val height = imageProxy.height
                val rotation = imageProxy.imageInfo.rotationDegrees

                // ✅ WebRTC active hai toh pehle frame pass karo bina crash ke
                if (onFrameForWebRTC != null) {
                    // YOLO ke liye NV21 pehle hi nikal lo imageProxy close hone se pehle
                    val now = System.currentTimeMillis()
                    val nv21ForYolo = if (yoloDetector != null && onHumanDetected != null && now - lastYoloTime >= 500L) {
                        lastYoloTime = now
                        yuv420ToNv21(imageProxy)
                    } else null

                    // Ab WebRTC pipeline ko handle karne do, pushFrame internally close karega
                    onFrameForWebRTC.invoke(imageProxy, rotation)

                    // Agar YOLO execute hona hai, toh async background thread pe daal do
                    nv21ForYolo?.let { nv21 ->
                        Log.d(TAG, "🤖 running YOLO (WebRTC mode)")
                        yoloExecutor.execute {
                            try {
                                val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
                                val out = ByteArrayOutputStream()
                                yuvImage.compressToJpeg(Rect(0, 0, width, height), 85, out)
                                val imageBytes = out.toByteArray()
                                val raw = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                                if (raw != null) {
                                    val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                                    val bitmap = Bitmap.createBitmap(
                                        raw, 0, 0, raw.width, raw.height, matrix, true
                                    )
                                    yoloDetector?.detect(bitmap) { found, label ->
                                        Log.d(TAG, "🎯 YOLO: found=$found, $label")
                                        onHumanDetected?.invoke(found)  // ✅ FIX 1
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "YOLO error: ${e.message}")
                            }
                        }
                    }
                    return@setAnalyzer
                }

                // ─── FALLBACK PURANA PATH (AGAR WEBRTC NA CHALE) ───
                val nv21 = yuv420ToNv21(imageProxy)

                imageProxy.close()

                // Nearby ke liye JPEG
                try {
                    val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
                    val nearbyOut = ByteArrayOutputStream()
                    yuvImage.compressToJpeg(Rect(0, 0, width, height), 40, nearbyOut)
                    val jpegBytes = nearbyOut.toByteArray()
                    if (jpegBytes.size < 28_000) onFrameBytes(jpegBytes)
                } catch (e: Exception) {
                    Log.e(TAG, "Nearby bytes error: ${e.message}")
                }

                // YOLO — 500ms throttle
                val now = System.currentTimeMillis()
                if (yoloDetector != null && onHumanDetected != null && now - lastYoloTime >= 500L) {
                    lastYoloTime = now
                    Log.d(TAG, "🤖 running YOLO")

                    yoloExecutor.execute {
                        try {
                            val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
                            val out = ByteArrayOutputStream()
                            yuvImage.compressToJpeg(Rect(0, 0, width, height), 85, out)
                            val imageBytes = out.toByteArray()
                            val raw = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                            if (raw != null) {
                                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                                val bitmap = Bitmap.createBitmap(
                                    raw, 0, 0, raw.width, raw.height, matrix, true
                                )
                                yoloDetector.detect(bitmap) { found, label ->
                                    Log.d(TAG, "🎯 YOLO: found=$found, $label")
                                    onHumanDetected?.invoke(found)  // ✅ FIX 2
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "YOLO error: ${e.message}")
                        }
                    }
                }
            }

            try {
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis
                )
                Log.d(TAG, "✅ Camera bound")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Camera bind failed: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    // ✅ Sahi YUV420 → NV21 conversion — stride handle karta hai
    private fun yuv420ToNv21(imageProxy: ImageProxy): ByteArray {
        val width = imageProxy.width
        val height = imageProxy.height
        val yPlane = imageProxy.planes[0]
        val uPlane = imageProxy.planes[1]
        val vPlane = imageProxy.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val nv21 = ByteArray(width * height * 3 / 2)

        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        var pos = 0
        for (row in 0 until height) {
            val rowStart = row * yRowStride
            for (col in 0 until width) {
                nv21[pos++] = yBuffer.get(rowStart + col * yPixelStride)
            }
        }

        val uvRowStride = vPlane.rowStride
        val uvPixelStride = vPlane.pixelStride
        for (row in 0 until height / 2) {
            val rowStart = row * uvRowStride
            for (col in 0 until width / 2) {
                val idx = rowStart + col * uvPixelStride
                nv21[pos++] = vBuffer.get(idx)  // V
                nv21[pos++] = uBuffer.get(idx)  // U
            }
        }

        return nv21
    }

    fun stopCamera() {
        cameraProvider?.unbindAll()
        Log.d(TAG, "Camera stopped")
    }
}