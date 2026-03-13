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
    private var resultsState by mutableStateOf<DetectionState?>(null)
    
    data class DetectionState(
        val results: ObjectDetectorResult,
        val frameWidth: Int,
        val frameHeight: Int
    )

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
        // MediaPipe results for Object Detection contain frame information in the first detection's image processing options or just use the bitmap dimensions from the helper.
        // However, we'll pass the dimensions from the analyzer to be sure.
    }
    
    fun updateResults(results: ObjectDetectorResult, width: Int, height: Int) {
        resultsState = DetectionState(results, width, height)
    }

    @Composable
    fun CameraScreen(state: DetectionState?) {
        val context = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current
        val previewView = remember { PreviewView(context) }

        Box(modifier = Modifier.fillMaxSize()) {
            LaunchedEffect(Unit) {
                val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    val imageAnalyzer = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setTargetResolution(android.util.Size(640, 480))
                        .setTargetRotation(previewView.display.rotation)
                        .build()
                        .also {
                            it.setAnalyzer(cameraExecutor) { imageProxy ->
                                val bitmap = imageProxy.toBitmap()
                                val rotation = imageProxy.imageInfo.rotationDegrees
                                val width = if (rotation == 90 || rotation == 270) imageProxy.height else imageProxy.width
                                val height = if (rotation == 90 || rotation == 270) imageProxy.width else imageProxy.height

                                objectDetectorHelper?.detectLiveStream(
                                    bitmap,
                                    rotation,
                                    SystemClock.uptimeMillis()
                                ) { results ->
                                    runOnUiThread {
                                        updateResults(results, width, height)
                                    }
                                }
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

            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize()
            )

            // Overlay for detections
            resultsState?.let { state ->
                Canvas(modifier = Modifier.fillMaxSize()) {
                    state.results.detections().forEach { detection ->
                        val boundingBox = detection.boundingBox()
                        
                        // Scale coordinates based on the ratio between the frame and the current view size
                        val scaleX = size.width / state.frameWidth
                        val scaleY = size.height / state.frameHeight

                        val left = boundingBox.left * scaleX
                        val top = boundingBox.top * scaleY
                        val right = boundingBox.right * scaleX
                        val bottom = boundingBox.bottom * scaleY

                        drawRect(
                            color = Color.Green,
                            topLeft = androidx.compose.ui.geometry.Offset(left, top),
                            size = androidx.compose.ui.geometry.Size(right - left, bottom - top),
                            style = Stroke(width = 2.dp.toPx())
                        )
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
                val detections = resultsState?.results?.detections() ?: emptyList()
                val statusText = when {
                    state == null -> "Initializing AI..."
                    detections.isEmpty() -> "AI Active: No objects found"
                    else -> "Detected:"
                }
                Text(
                    text = statusText,
                    color = Color.Yellow,
                    style = MaterialTheme.typography.titleLarge
                )
                detections.forEach { detection ->
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
