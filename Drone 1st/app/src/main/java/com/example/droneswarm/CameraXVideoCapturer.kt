package com.example.droneswarm.drone1

import android.content.Context
import android.graphics.ImageFormat
import androidx.camera.core.ImageProxy
import org.webrtc.CapturerObserver
import org.webrtc.JavaI420Buffer
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoFrame
import java.nio.ByteBuffer

/**
 * Custom VideoCapturer that does NOT open the camera itself.
 * Instead, frames are PUSHED to it from CameraX's ImageAnalysis.
 */
class CameraXVideoCapturer : VideoCapturer {

    private var capturerObserver: CapturerObserver? = null
    @Volatile private var isCapturing = false

    override fun initialize(
        surfaceTextureHelper: SurfaceTextureHelper?,
        context: Context?,
        observer: CapturerObserver?
    ) {
        this.capturerObserver = observer
    }

    override fun startCapture(width: Int, height: Int, framerate: Int) {
        isCapturing = true
        capturerObserver?.onCapturerStarted(true)
    }

    override fun stopCapture() {
        isCapturing = false
        capturerObserver?.onCapturerStopped()
    }

    override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) {}

    override fun dispose() {
        isCapturing = false
        capturerObserver = null
    }

    override fun isScreencast(): Boolean = false

    fun pushFrame(imageProxy: ImageProxy, rotationDegrees: Int) {
        if (!isCapturing || capturerObserver == null) return
        if (imageProxy.format != ImageFormat.YUV_420_888) return
        if (imageProxy.planes.size < 3) return

        try {
            val i420Buffer = imageProxyToI420(imageProxy)
            val timestampNs = System.nanoTime()
            val videoFrame = VideoFrame(i420Buffer, rotationDegrees, timestampNs)
            capturerObserver?.onFrameCaptured(videoFrame)

            // ✅ FIX: videoFrame.release() mat karo — WebRTC async process karta hai
            // i420Buffer ko release karo, VideoFrame ka ownership WebRTC ke paas hai
            videoFrame.release()

        } catch (e: Exception) {
            // Drop frame silently
        }
    }

    private fun imageProxyToI420(image: ImageProxy): JavaI420Buffer {
        val width = image.width
        val height = image.height
        val chromaWidth = width / 2
        val chromaHeight = height / 2

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val i420 = JavaI420Buffer.allocate(width, height)

        copyPlane(
            src = yPlane.buffer,
            srcRowStride = yPlane.rowStride,
            srcPixelStride = yPlane.pixelStride,
            dst = i420.dataY,
            dstRowStride = i420.strideY,
            width = width,
            height = height
        )

        copyPlane(
            src = uPlane.buffer,
            srcRowStride = uPlane.rowStride,
            srcPixelStride = uPlane.pixelStride,
            dst = i420.dataU,
            dstRowStride = i420.strideU,
            width = chromaWidth,
            height = chromaHeight
        )

        copyPlane(
            src = vPlane.buffer,
            srcRowStride = vPlane.rowStride,
            srcPixelStride = vPlane.pixelStride,
            dst = i420.dataV,
            dstRowStride = i420.strideV,
            width = chromaWidth,
            height = chromaHeight
        )

        return i420
    }

    private fun copyPlane(
        src: ByteBuffer,
        srcRowStride: Int,
        srcPixelStride: Int,
        dst: ByteBuffer,
        dstRowStride: Int,
        width: Int,
        height: Int
    ) {
        src.rewind()
        dst.rewind()
        if (srcPixelStride == 1 && srcRowStride == width && dstRowStride == width) {
            val bytes = ByteArray(width * height)
            src.get(bytes)
            dst.put(bytes)
            return
        }

        val rowBuffer = ByteArray(width)
        for (row in 0 until height) {
            val srcRowStart = row * srcRowStride
            if (srcPixelStride == 1) {
                src.position(srcRowStart)
                src.get(rowBuffer, 0, width)
            } else {
                for (col in 0 until width) {
                    rowBuffer[col] = src.get(srcRowStart + col * srcPixelStride)
                }
            }
            dst.position(row * dstRowStride)
            dst.put(rowBuffer, 0, width)
        }
    }
}