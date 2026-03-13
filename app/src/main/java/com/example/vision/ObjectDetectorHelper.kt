package com.example.vision

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult

class ObjectDetectorHelper(
    val context: Context,
    var threshold: Float = 0.45f,
    var maxResults: Int = 5,
    val listener: DetectorListener
) {
    private var objectDetector: ObjectDetector? = null

    init {
        setupObjectDetector()
    }

    fun setupObjectDetector() {
        val baseOptionsBuilder = BaseOptions.builder()
            .setModelAssetPath("efficientdet_lite0.tflite")
            .setDelegate(Delegate.CPU) // Revert to CPU for compatibility

        val optionsBuilder = ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(baseOptionsBuilder.build())
            .setScoreThreshold(threshold)
            .setMaxResults(maxResults)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setResultListener { result, _ ->
                listener.onResults(result, SystemClock.uptimeMillis() - result.timestampMs())
            }
            .setErrorListener { error ->
                listener.onError(error.message ?: "Unknown error")
            }

        try {
            objectDetector = ObjectDetector.createFromOptions(context, optionsBuilder.build())
        } catch (e: Exception) {
            listener.onError("Detector failed to initialize: ${e.message}")
        }
    }

    fun detectLiveStream(bitmap: Bitmap, rotation: Int, frameTime: Long) {
        val mpImage = BitmapImageBuilder(bitmap).build()
        val imageProcessingOptions = ImageProcessingOptions.builder()
            .setRotationDegrees(rotation)
            .build()
        objectDetector?.detectAsync(mpImage, imageProcessingOptions, frameTime)
    }

    interface DetectorListener {
        fun onError(error: String)
        fun onResults(results: ObjectDetectorResult, inferenceTime: Long)
    }
}
