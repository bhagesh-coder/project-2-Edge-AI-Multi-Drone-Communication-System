package com.example.droneswarm

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.google.mlkit.vision.pose.PoseLandmark

class BoundingOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var landmarks: List<PoseLandmark> = emptyList()
    private val paint = Paint().apply {
        color = Color.CYAN
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    fun updatePose(newLandmarks: List<PoseLandmark>) {
        this.landmarks = newLandmarks
        postInvalidate() // Main thread ke bahar se bhi refresh karega
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        landmarks.forEach { landmark ->
            canvas.drawCircle(landmark.position.x, landmark.position.y, 8f, paint)
        }
    }
}