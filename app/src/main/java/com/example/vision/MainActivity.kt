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
        val frameWidth: Int,
        val frameHeight: Int
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
        // MediaPipe results for Object Detection contain frame information in the first detection's image processing options or just use the bitmap dimensions from the helper.
        // However, we'll pass the dimensions from the analyzer to be sure.
    }
    
    fun updateResults(results: ObjectDetectorResult, width: Int, height: Int) {
        resultsState = DetectionState(results, width, height)
        
        // Intelligence Layer: Only process periodically to avoid spamming the chat
        val currentTime = SystemClock.uptimeMillis()
        if (currentTime - lastDetectionTime > 2000) { // Every 2 seconds
            processIntelligence(results)
            lastDetectionTime = currentTime
        }
    }

    private fun processIntelligence(results: ObjectDetectorResult) {
        val detections = results.detections()
        if (detections.isEmpty()) {
            if (lastAnalysisMessage.isNotEmpty() && !lastAnalysisMessage.contains("clear")) {
                 // Don't silence immediately, but acknowledge clear environment
            }
            return
        }

        // 1. Analyze Command Intent (The "Policy")
        val cmd = activeCommand.lowercase()
        val isWarningMode = cmd.contains("warn") || cmd.contains("risk") || cmd.contains("danger") || cmd.contains("safe")
        val isDescriptionMode = cmd.contains("describe") || cmd.contains("what") || cmd.contains("see")
        val isSpecificSearch = !isWarningMode && !isDescriptionMode && cmd.split(" ").any { it.length > 3 }

        // 2. Evaluate each detection based on the Policy
        class ReasonedDetection(val category: String, val reason: String)
        
        val frameArea = (resultsState?.frameWidth ?: 640) * (resultsState?.frameHeight ?: 480)
        
        val reasonedDetections = detections.mapNotNull { detection ->
            val category = detection.categories().firstOrNull()?.categoryName() ?: "object"
            val box = detection.boundingBox()
            val boxArea = (box.right - box.left) * (box.bottom - box.top)
            val occupancy = boxArea / frameArea.toFloat()
            
            val isSpecificallyRequested = cmd.contains(category.lowercase())
            val isAbnormallyClose = occupancy > 0.25f
            
            when {
                // Priority 1: User asked for this specific thing
                isSpecificallyRequested -> {
                    ReasonedDetection(category, "you asked to monitor this")
                }
                // Priority 2: Danger/Safe reasoning
                isWarningMode -> {
                    when {
                        isAbnormallyClose -> ReasonedDetection(category, "it is very close to your path")
                        category.lowercase() in listOf("person", "bicycle", "dog", "cat") -> 
                            ReasonedDetection(category, "it is a mobile subject in your vicinity")
                        else -> null // Ignore distant static objects in warning mode
                    }
                }
                // Priority 3: General description
                isDescriptionMode -> {
                    ReasonedDetection(category, "it is part of the scene")
                }
                // Priority 4: Specific intent but object doesn't match
                isSpecificSearch -> null
                else -> null
            }
        }.distinctBy { it.category }

        if (reasonedDetections.isEmpty()) return

        // 3. Synthesize the reasoning into a natural response
        val description = if (isWarningMode) {
            val alerts = reasonedDetections.joinToString("; ") { "${it.category} (${it.reason})" }
            "Safety Assessment: I detected $alerts. Please be cautious."
        } else if (isDescriptionMode) {
            val items = reasonedDetections.joinToString(", ") { it.category }
            "Scene Analysis: I see $items. Everything appears to be $cmd."
        } else {
            val items = reasonedDetections.joinToString(", ") { it.category }
            "Found: $items. Matches your request for '$activeCommand'."
        }

        // 4. Update Chat (with basic debouncing)
        if (description != lastAnalysisMessage && !description.contains("Command received")) {
            Log.d("VisionAI", "Reasoning Update: $description")
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
                                        updateResults(results, width, height)
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

        Column(modifier = Modifier.fillMaxSize()) {
            // Camera Section (Top)
            Box(
                modifier = Modifier
                    .weight(0.65f)
                    .fillMaxWidth()
                    .background(Color.Black)
            ) {
                AndroidView(
                    factory = { previewView },
                    modifier = Modifier.fillMaxSize()
                )

                // Overlay for detections
                resultsState?.let { state ->
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        state.results.detections().forEach { detection ->
                            val boundingBox = detection.boundingBox()
                            val scaleX = size.width / state.frameWidth
                            val scaleY = size.height / state.frameHeight

                            val left = boundingBox.left * scaleX
                            val top = boundingBox.top * scaleY
                            val right = boundingBox.right * scaleX
                            val bottom = boundingBox.bottom * scaleY

                            drawRect(
                                color = Color.Magenta,
                                topLeft = androidx.compose.ui.geometry.Offset(left, top),
                                size = androidx.compose.ui.geometry.Size(right - left, bottom - top),
                                style = Stroke(width = 2.dp.toPx())
                            )
                        }
                    }
                }

                // Status Overlay (Floating at the bottom of camera section)
                Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                    StatusOverlay(state)
                }
            }

            // Chat Section (Bottom)
            Column(
                modifier = Modifier
                    .weight(0.35f)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
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
                .background(Color.Black.copy(alpha = 0.4f))
                .padding(8.dp)
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
                color = if (msg.isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(
                    topStart = 12.dp,
                    topEnd = 12.dp,
                    bottomStart = if (msg.isUser) 12.dp else 0.dp,
                    bottomEnd = if (msg.isUser) 0.dp else 12.dp
                ),
                tonalElevation = 2.dp
            ) {
                Text(
                    text = msg.message,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (msg.isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer
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
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                ),
                singleLine = true
            )
            Spacer(modifier = Modifier.width(8.dp))
            FloatingActionButton(
                onClick = onSendClick,
                containerColor = MaterialTheme.colorScheme.primary,
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
