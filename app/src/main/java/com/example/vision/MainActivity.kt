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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.text.font.FontWeight

class MainActivity : ComponentActivity(), ObjectDetectorHelper.DetectorListener {

    private lateinit var cameraExecutor: ExecutorService
    private var objectDetectorHelper: ObjectDetectorHelper? = null
    private var resultsState by mutableStateOf<DetectionState?>(null)
    private var isDetectionActive by mutableStateOf(false)
    private var activeCommand by mutableStateOf("")
    private var lastAnalysisMessage by mutableStateOf("")
    private var lastDetectionTime = 0L
    private val chatMessages = mutableStateListOf(ChatMessage("How can I help you analyze your environment?", false))
    
    data class DetectionState(
        val results: ObjectDetectorResult,
        val originalWidth: Int,
        val originalHeight: Int,
        val rotation: Int
    )

    data class ChatMessage(
        val message: String,
        val isUser: Boolean
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
        // No-op, managed via detectLiveStream callback for better reactivity
    }

    private fun processIntelligence(results: ObjectDetectorResult) {
        val detections = results.detections()
        if (detections.isEmpty()) return

        // 1. Dynamic Intent & Sensitivity Analysis
        val rawCommand = activeCommand.lowercase()
        val commandWords = rawCommand.split(" ", ",", ".", "?").filter { it.length > 2 }.toSet()
        
        // Dynamic "Sensitivity" Factors
        val isSafetyCritical = rawCommand.contains("warn") || rawCommand.contains("danger") || 
                               rawCommand.contains("risk") || rawCommand.contains("safe") || 
                               rawCommand.contains("careful")
        
        val isObservational = rawCommand.contains("describe") || rawCommand.contains("what") || 
                              rawCommand.contains("tell") || rawCommand.contains("happen")

        // 2. The Universal Salience Engine (Evaluates ANYTHING detected)
        class EvaluatedDetections(val label: String, val score: Float, val highlights: List<String>)
        
        val frameArea = (resultsState?.originalWidth ?: 640) * (resultsState?.originalHeight ?: 480)
        
        val salientDetections = detections.mapNotNull { detection ->
            val label = detection.categories().firstOrNull()?.categoryName() ?: return@mapNotNull null
            val box = detection.boundingBox()
            val occupancy = ((box.right - box.left) * (box.bottom - box.top)) / frameArea.toFloat()
            
            // Is this label semantically mentioned in the command?
            val isDirectlyRelevant = commandWords.any { label.lowercase().contains(it) || it.contains(label.lowercase()) }
            
            // Universal reasoning factors (not tied to specific cases)
            val reasons = mutableListOf<String>()
            var salienceScore = 0f

            if (isDirectlyRelevant) {
                reasons.add("directly related to your request")
                salienceScore += 0.8f
            }
            if (occupancy > 0.2f) {
                reasons.add("it is physically prominent in your view")
                salienceScore += 0.5f
            }
            if (isSafetyCritical && occupancy > 0.15f) {
                reasons.add("potential immediate hazard due to proximity")
                salienceScore += 0.6f
            }
            if (isObservational && occupancy > 0.05f) {
                reasons.add("notable element in the current scene")
                salienceScore += 0.3f
            }

            if (salienceScore > 0.4f) {
                EvaluatedDetections(label, salienceScore, reasons)
            } else null
        }.sortedByDescending { it.score }.distinctBy { it.label }

        if (salientDetections.isEmpty()) return

        // 3. Dynamic Response Synthesis
        val topAlerts = salientDetections.take(3)
        val description = when {
            isSafetyCritical -> {
                val detail = topAlerts.joinToString(", ") { "${it.label} (${it.highlights.first()})" }
                "Safety Alert: I'm flagging $detail. Please be alert."
            }
            isObservational -> {
                val detail = topAlerts.joinToString(", ") { it.label }
                "Observation: In response to '$activeCommand', I can see $detail."
            }
            else -> {
                val detail = topAlerts.joinToString(", ") { "${it.label} (${it.highlights.first()})" }
                "Follow-up: I found $detail."
            }
        }

        // 4. Update Interaction State
        if (description != lastAnalysisMessage && !description.contains("Command received")) {
            Log.d("VisionAI", "Flexible Reasoning: $description")
            lastAnalysisMessage = description
            chatMessages.add(ChatMessage(description, false))
        }
    }

    @Composable
    fun CameraScreen(state: DetectionState?) {
        val context = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current
        val previewView = remember { PreviewView(context) }
        var chatInput by remember { mutableStateOf("") }

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

                            if (isDetectionActive) {
                                objectDetectorHelper?.detectLiveStream(
                                    bitmap,
                                    rotation,
                                    SystemClock.uptimeMillis()
                                ) { results ->
                                    runOnUiThread {
                                        resultsState = DetectionState(results, imageProxy.width, imageProxy.height, rotation)
                                        processIntelligence(results)
                                    }
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

        Box(modifier = Modifier.fillMaxSize()) {
            // Fullscreen Camera Preview
            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize()
            )

            // Fullscreen Overlay for detections
            resultsState?.let { state ->
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val rotation = state.rotation
                    val isRotated = rotation == 90 || rotation == 270
                    
                    val rotatedWidth = if (isRotated) state.originalHeight else state.originalWidth
                    val rotatedHeight = if (isRotated) state.originalWidth else state.originalHeight

                    // Calculate scale and offset for FILL_CENTER
                    val scale = maxOf(size.width / rotatedWidth, size.height / rotatedHeight)
                    val offsetX = (size.width - rotatedWidth * scale) / 2f
                    val offsetY = (size.height - rotatedHeight * scale) / 2f

                    state.results.detections().forEach { detection ->
                        val box = detection.boundingBox()
                        
                        // Map coordinates from unrotated image to rotated UI space
                        val (mapLeft, mapTop, mapRight, mapBottom) = when (rotation) {
                            90 -> listOf(
                                rotatedWidth - (box.bottom), // left
                                box.left, // top
                                rotatedWidth - (box.top), // right
                                box.right // bottom
                            )
                            270 -> listOf(
                                box.top,
                                rotatedHeight - (box.right),
                                box.bottom,
                                rotatedHeight - (box.left)
                            )
                            180 -> listOf(
                                state.originalWidth - box.right,
                                state.originalHeight - box.bottom,
                                state.originalWidth - box.left,
                                state.originalHeight - box.top
                            )
                            else -> listOf(box.left, box.top, box.right, box.bottom)
                        }

                        val left = mapLeft * scale + offsetX
                        val top = mapTop * scale + offsetY
                        val right = mapRight * scale + offsetX
                        val bottom = mapBottom * scale + offsetY

                        drawRect(
                            color = Color.Magenta,
                            topLeft = androidx.compose.ui.geometry.Offset(left, top),
                            size = androidx.compose.ui.geometry.Size(right - left, bottom - top),
                            style = Stroke(width = 2.dp.toPx())
                        )
                    }
                }
            }

            // Top Status Bar (Transparent)
            Box(modifier = Modifier.align(Alignment.TopCenter)) {
                StatusOverlay(state)
            }

            // Bottom Chat Section (Transparent Overlay)
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(0.4f) // Chat covers bottom 40%
                    .background(Color.Black.copy(alpha = 0.2f))
                    .padding(8.dp)
            ) {
                // Chat History
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    reverseLayout = true
                ) {
                    items(chatMessages.toList().reversed()) { msg ->
                        ChatBubble(msg)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Input Box
                ChatInputRow(
                    input = chatInput,
                    onInputChange = { chatInput = it },
                    onSendClick = {
                        if (chatInput.isNotBlank()) {
                            val input = chatInput
                            chatMessages.add(ChatMessage(input, true))
                            activeCommand = input
                            isDetectionActive = true
                            lastAnalysisMessage = "Command received: \"$input\". Starting analysis..."
                            chatMessages.add(ChatMessage(lastAnalysisMessage, false))
                            lastDetectionTime = 0L 
                            chatInput = ""
                        }
                    }
                )
            }
        }
    }

    @Composable
    fun StatusOverlay(state: DetectionState?) {
        val detections = resultsState?.results?.detections() ?: emptyList()
        val objects = detections.mapNotNull { it.categories().firstOrNull()?.categoryName() }.distinct()
        
        val statusText = when {
            !isDetectionActive -> "Vision AI: Waiting for command..."
            state == null -> "Analyzing environment..."
            detections.isEmpty() -> "Scanning for: \"$activeCommand\"..."
            activeCommand.contains("warn", ignoreCase = true) && objects.isNotEmpty() -> "WARNING: Abnormal detection!"
            activeCommand.contains("describe", ignoreCase = true) -> "Describing scene..."
            else -> "Continuous Analysis Active"
        }

        val intelligentSummary = if (objects.isNotEmpty()) {
            if (activeCommand.contains("warn", ignoreCase = true)) {
                "Abnormal: ${objects.joinToString(", ")}"
            } else {
                "Found: ${objects.joinToString(", ")}"
            }
        } else {
            ""
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.25f)) // More subtle
                .padding(12.dp)
        ) {
            Text(
                text = statusText,
                color = if (statusText.contains("WARNING")) Color.Red else Color.Yellow,
                style = MaterialTheme.typography.titleMedium
            )
            if (intelligentSummary.isNotEmpty()) {
                Text(
                    text = intelligentSummary,
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }

    @Composable
    fun ChatBubble(msg: ChatMessage) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalAlignment = if (msg.isUser) Alignment.End else Alignment.Start
        ) {
            Surface(
                color = if (msg.isUser) MaterialTheme.colorScheme.primary.copy(alpha = 0.7f) 
                        else Color.White.copy(alpha = 0.15f),
                shape = RoundedCornerShape(
                    topStart = 16.dp,
                    topEnd = 16.dp,
                    bottomStart = if (msg.isUser) 16.dp else 4.dp,
                    bottomEnd = if (msg.isUser) 4.dp else 16.dp
                ),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
            ) {
                Text(
                    text = msg.message,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White
                )
            }
        }
    }

    @Composable
    fun ChatInputRow(
        input: String,
        onInputChange: (String) -> Unit,
        onSendClick: () -> Unit
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(24.dp)),
                placeholder = { Text("Ask about environment...") },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Black.copy(alpha = 0.4f),
                    unfocusedContainerColor = Color.Black.copy(alpha = 0.4f),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedPlaceholderColor = Color.Gray,
                    unfocusedPlaceholderColor = Color.Gray
                ),
                singleLine = true
            )
            Spacer(modifier = Modifier.width(8.dp))
            FloatingActionButton(
                onClick = onSendClick,
                containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.size(48.dp)
            ) {
                Icon(Icons.Default.Send, contentDescription = "Send", tint = Color.White)
            }
        }
    }


    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
