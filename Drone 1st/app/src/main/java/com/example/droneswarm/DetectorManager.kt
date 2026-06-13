package com.example.droneswarm.drone1

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.common.ops.NormalizeOp


class YOLODetector(context: Context) {
    // Assets se model load ho raha hai
    private val modelFile = "yolov8n.tflite"
    private var interpreter: Interpreter? = null

    // YOLOv8 Nano settings
    private val inputSize = 320 // Jo humne export ke waqt rakha tha
    private val confidenceThreshold = 0.45f // Isse niche ke detections reject honge

    init {
        val options = Interpreter.Options().apply {
            setNumThreads(4) // Fast processing ke liye
            // Agar phone achha hai toh GPU delegate bhi add kar sakte hain
        }
        interpreter = Interpreter(FileUtil.loadMappedFile(context, modelFile), options)
    }

    fun detect(bitmap: Bitmap, onDetection: (Boolean, String) -> Unit) {
        // 1. Pre-processing: Normalization ZAROORI hai (0.0f to 1.0f)
        val imageProcessor = ImageProcessor.Builder()
            .add(ResizeOp(inputSize, inputSize, ResizeOp.ResizeMethod.BILINEAR))
            .add(NormalizeOp(0f, 255f)) // Pixel values ko YOLO format mein lane ke liye
            .build()

        var tensorImage = TensorImage(org.tensorflow.lite.DataType.FLOAT32)
        tensorImage.load(bitmap)
        tensorImage = imageProcessor.process(tensorImage)

        // 2. Output Buffer
        // YOLOv8n [1, 84, 2100] -> 2100 boxes, 84 features (4 box + 80 classes)
        val outputBuffer = Array(1) { Array(84) { FloatArray(2100) } }

        interpreter?.run(tensorImage.buffer, outputBuffer)

        // 3. Parsing
        var humanFound = false
        var bestConfidence = 0f

        for (i in 0 until 2100) {
            // YOLOv8 COCO model mein index 4 "Person" (Human) hota hai
            // Hum check kar rahe hain ki kya person ka score threshold se upar hai
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
    }}