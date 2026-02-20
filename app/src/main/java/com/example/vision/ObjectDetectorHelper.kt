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
    var threshold: Float = 0.3f,
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
            .setDelegate(Delegate.CPU)

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

    private fun returnLivestreamResult(
        result: ObjectDetectorResult,
        input: com.google.mediapipe.framework.image.MPImage
    ) {
        val finishTimeMs = SystemClock.uptimeMillis()
        val inferenceTime = finishTimeMs - result.timestampMs()
        // Always notify the listener to update the UI state
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
