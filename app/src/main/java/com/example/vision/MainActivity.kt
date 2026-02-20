package com.example.vision

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity(), ObjectDetectorHelper.DetectorListener {

    private lateinit var cameraExecutor: ExecutorService
    private var objectDetectorHelper: ObjectDetectorHelper? = null
    private var resultsState by mutableStateOf<ObjectDetectorResult?>(null)

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        cameraExecutor = Executors.newSingleThreadExecutor()
        
        objectDetectorHelper = ObjectDetectorHelper(
            context = this,
            listener = this
        )

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    CameraScreen(resultsState)
                }
            }
        }

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(
        baseContext, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        // Implementation below in CameraScreen via AndroidView
    }

    override fun onError(error: String) {
        runOnUiThread {
            Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResults(results: ObjectDetectorResult, inferenceTime: Long) {
        resultsState = results
    }

    @Composable
    fun CameraScreen(results: ObjectDetectorResult?) {
        val context = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current
        val previewView = remember { PreviewView(context) }

        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize()
            ) { view ->
                val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(view.surfaceProvider)
                    }

                    val imageAnalyzer = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setTargetRotation(view.display.rotation)
                        .build()
                        .also {
                            it.setAnalyzer(cameraExecutor) { imageProxy ->
                                // Using the built-in toBitmap()
                                val bitmap = imageProxy.toBitmap()
                                
                                // Rotate bitmap to match device orientation
                                val matrix = Matrix().apply {
                                    postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
                                }
                                val rotatedBitmap = Bitmap.createBitmap(
                                    bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
                                )

                                // Scaling down for the model's preferred input size (around 320x320)
                                val scaledBitmap = Bitmap.createScaledBitmap(rotatedBitmap, 320, 320, true)

                                objectDetectorHelper?.detectLiveStream(
                                    scaledBitmap,
                                    SystemClock.uptimeMillis()
                                )
                                imageProxy.close()
                            }
                        }

                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            imageAnalyzer
                        )
                    } catch (exc: Exception) {
                        Log.e("Vision", "Use case binding failed", exc)
                    }
                }, ContextCompat.getMainExecutor(context))
            }

            // Overlay for detections
            results?.let {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    it.detections().forEach { detection ->
                        val boundingBox = detection.boundingBox()
                        val categories = detection.categories()
                        val text = categories.firstOrNull()?.categoryName() ?: "Unknown"
                        val confidence = categories.firstOrNull()?.score() ?: 0f

                        // Scale coordinates
                        val left = boundingBox.left * size.width / 440f // Based on model input size
                        val top = boundingBox.top * size.height / 440f
                        val right = boundingBox.right * size.width / 440f
                        val bottom = boundingBox.bottom * size.height / 440f

                        drawRect(
                            color = Color.Green,
                            topLeft = androidx.compose.ui.geometry.Offset(left, top),
                            size = androidx.compose.ui.geometry.Size(right - left, bottom - top),
                            style = Stroke(width = 4.dp.toPx())
                        )
                        
                        // We can't easily draw text in Compose Canvas without a native paint, 
                        // but we can use this for the box.
                    }
                }
            }
            
            // Text descriptions as a list overlay
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.3f))
                    .padding(top = 40.dp, start = 16.dp, bottom = 16.dp)
            ) {
                Text(
                    text = if (results == null || results.detections().isEmpty()) "Scanning..." else "Detected:",
                    color = Color.Yellow,
                    style = MaterialTheme.typography.titleLarge
                )
                results?.detections()?.forEach { detection ->
                    val category = detection.categories().firstOrNull()
                    if (category != null) {
                        Text(
                            text = "• ${category.categoryName()} (${(category.score() * 100).toInt()}%)",
                            color = Color.White,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
