package com.example.droneswarm

import android.content.Context
import android.graphics.ImageFormat
import androidx.camera.core.ImageProxy
import org.webrtc.CapturerObserver
import org.webrtc.JavaI420Buffer
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoFrame
import java.nio.ByteBuffer

class CameraXVideoCapturer : VideoCapturer {

    private var capturerObserver: CapturerObserver? = null
    @Volatile private var isCapturing = false

    override fun initialize(
        surfaceTextureHelper: SurfaceTextureHelper?,
        context: Context?,
        observer: CapturerObserver?
    ) {
        this.capturerObserver = observer
        isCapturing = true
        capturerObserver?.onCapturerStarted(true)
    }

    override fun startCapture(width: Int, height: Int, framerate: Int) {
        // CameraManager already camera chala raha hai — yahan kuch nahi karna
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
        if (capturerObserver == null) return
        if (imageProxy.format != ImageFormat.YUV_420_888) return
        if (imageProxy.planes.size < 3) return

        try {
            val i420Buffer = imageProxyToI420(imageProxy)
            val timestampNs = System.nanoTime()
            val videoFrame = VideoFrame(i420Buffer, rotationDegrees, timestampNs)
            capturerObserver?.onFrameCaptured(videoFrame)
            videoFrame.release()
        } catch (e: Exception) {
            // drop frame
        } finally {
            imageProxy.close()
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

        copyPlane(yPlane.buffer, yPlane.rowStride, yPlane.pixelStride, i420.dataY, i420.strideY, width, height)
        copyPlane(uPlane.buffer, uPlane.rowStride, uPlane.pixelStride, i420.dataU, i420.strideU, chromaWidth, chromaHeight)
        copyPlane(vPlane.buffer, vPlane.rowStride, vPlane.pixelStride, i420.dataV, i420.strideV, chromaWidth, chromaHeight)

        return i420
    }

    private fun copyPlane(
        src: ByteBuffer, srcRowStride: Int, srcPixelStride: Int,
        dst: ByteBuffer, dstRowStride: Int, width: Int, height: Int
    ) {
        src.rewind(); dst.rewind()
        if (srcPixelStride == 1 && srcRowStride == width && dstRowStride == width) {
            val bytes = ByteArray(width * height)
            src.get(bytes); dst.put(bytes)
            return
        }
        val rowBuffer = ByteArray(width)
        for (row in 0 until height) {
            val srcRowStart = row * srcRowStride
            if (srcPixelStride == 1) {
                src.position(srcRowStart); src.get(rowBuffer, 0, width)
            } else {
                for (col in 0 until width) rowBuffer[col] = src.get(srcRowStart + col * srcPixelStride)
            }
            dst.position(row * dstRowStride); dst.put(rowBuffer, 0, width)
        }
    }
}