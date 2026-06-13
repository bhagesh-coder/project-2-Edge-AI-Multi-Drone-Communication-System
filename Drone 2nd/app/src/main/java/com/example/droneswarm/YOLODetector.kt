package com.example.droneswarm

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.common.ops.NormalizeOp

class YOLODetector(context: Context) {
    private val modelFile = "yolov8n.tflite"
    private var interpreter: Interpreter? = null
    private val inputSize = 320
    private val confidenceThreshold = 0.45f

    init {
        val options = Interpreter.Options().apply {
            setNumThreads(4)
        }
        interpreter = Interpreter(FileUtil.loadMappedFile(context, modelFile), options)
    }

    fun detect(bitmap: Bitmap, onDetection: (Boolean, String) -> Unit) {
        val imageProcessor = ImageProcessor.Builder()
            .add(ResizeOp(inputSize, inputSize, ResizeOp.ResizeMethod.BILINEAR))
            .add(NormalizeOp(0f, 255f))
            .build()

        var tensorImage = TensorImage(org.tensorflow.lite.DataType.FLOAT32)
        tensorImage.load(bitmap)
        tensorImage = imageProcessor.process(tensorImage)

        val outputBuffer = Array(1) { Array(84) { FloatArray(2100) } }
        interpreter?.run(tensorImage.buffer, outputBuffer)

        // DEBUG — max person score dekho
        var maxPersonScore = 0f
        for (i in 0 until 2100) {
            if (outputBuffer[0][4][i] > maxPersonScore) {
                maxPersonScore = outputBuffer[0][4][i]
            }
        }
        Log.d("YOLO_DEBUG", "MAX person score (index 4): $maxPersonScore")

        var humanFound = false
        var bestConfidence = 0f

        for (i in 0 until 2100) {
            val personScore = outputBuffer[0][4][i]
            if (personScore > confidenceThreshold) {
                humanFound = true
                if (personScore > bestConfidence) bestConfidence = personScore
            }
        }

        if (humanFound) {
            onDetection(true, "Human Detected (${(bestConfidence * 100).toInt()}%)")
        } else {
            onDetection(false, "Scanning...")
        }
    }
}