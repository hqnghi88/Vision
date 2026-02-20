package com.example.vision

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult

class ObjectDetectorHelper(
    val context: Context,
    var threshold: Float = 0.5f,
    var maxResults: Int = 3,
    val listener: DetectorListener
) {

    private var objectDetector: ObjectDetector? = null

    init {
        setupObjectDetector()
    }

    fun setupObjectDetector() {
        val baseOptionsBuilder = BaseOptions.builder()
            .setModelAssetPath("efficientdet_lite0.tflite")
        
        // Try GPU first, will fall back if it fails
        try {
            baseOptionsBuilder.setDelegate(Delegate.GPU)
        } catch (e: Exception) {
            baseOptionsBuilder.setDelegate(Delegate.CPU)
        }

        val optionsBuilder = ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(baseOptionsBuilder.build())
            .setScoreThreshold(threshold)
            .setMaxResults(maxResults)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setResultListener(this::returnLivestreamResult)
            .setErrorListener(this::returnLivestreamError)

        try {
            objectDetector = ObjectDetector.createFromOptions(context, optionsBuilder.build())
        } catch (e: Exception) {
            // Final fallback to CPU if GPU failed during creation
            try {
                baseOptionsBuilder.setDelegate(Delegate.CPU)
                objectDetector = ObjectDetector.createFromOptions(context, optionsBuilder.build())
            } catch (e2: Exception) {
                listener.onError("Detector failed to initialize: ${e2.message}")
            }
        }
    }

    fun detectLiveStream(bitmap: Bitmap, frameTime: Long) {
        val mpImage = BitmapImageBuilder(bitmap).build()
        objectDetector?.detectAsync(mpImage, frameTime)
    }

    private fun returnLivestreamResult(
        result: ObjectDetectorResult,
        input: com.google.mediapipe.framework.image.MPImage
    ) {
        val finishTimeMs = SystemClock.uptimeMillis()
        val inferenceTime = finishTimeMs - result.timestampMs()
        listener.onResults(result, inferenceTime)
    }

    private fun returnLivestreamError(error: RuntimeException) {
        listener.onError(error.message ?: "An unknown error occurred")
    }

    interface DetectorListener {
        fun onError(error: String)
        fun onResults(results: ObjectDetectorResult, inferenceTime: Long)
    }
}
