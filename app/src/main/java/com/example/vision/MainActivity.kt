package com.example.vision

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.os.SystemClock
import android.graphics.RectF
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
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.foundation.shape.CircleShape
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.animation.core.*
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import android.graphics.Paint
import android.graphics.Typeface
import android.content.Intent
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

class MainActivity : ComponentActivity(), ObjectDetectorHelper.DetectorListener {

    private lateinit var cameraExecutor: ExecutorService
    private var objectDetectorHelper: ObjectDetectorHelper? = null
    private val assistantEngine = VisualAssistantEngine()
    private var resultsState by mutableStateOf<DetectionState?>(null)
    private var isDetectionActive by mutableStateOf(false)
    private var activeCommand by mutableStateOf("")
    private var lastAnalysisMessage by mutableStateOf("")
    private var lastDetectionTime = 0L
    private val chatMessages = mutableStateListOf(ChatMessage("How can I help you analyze your environment?", false))
    
    // Frame metadata for AR alignment (Warm-start with defaults)
    private var lastFrameWidth = 640
    private var lastFrameHeight = 480
    private var lastFrameRotation = 90
    
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening by mutableStateOf(false)
    
    data class DetectionState(
        val results: ObjectDetectorResult?,
        val originalWidth: Int,
        val originalHeight: Int,
        val rotation: Int,
        val opinion: AssistantOpinion? = null,
        val smoothedOverlays: List<OverlayElement> = emptyList()
    )

    private var smoothedOverlays = mutableMapOf<Int, SmoothedOverlay>()
    private var nextOverlayId = 0

    data class SmoothedOverlay(
        val element: OverlayElement,
        var life: Int = 3,
        var smoothedRect: RectF? = null
    )

    data class ChatMessage(
        val message: String,
        val isUser: Boolean
    )

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.CAMERA] == true) {
            startCamera()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        cameraExecutor = Executors.newSingleThreadExecutor()
        
        objectDetectorHelper = ObjectDetectorHelper(
            context = this,
            listener = this
        )

        setupSpeechRecognizer()

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    CameraScreen(
                        state = resultsState,
                        isActive = isDetectionActive,
                        command = activeCommand
                    )
                }
            }
        }

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissionsLauncher.launch(
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            )
        }
    }

    private fun startCamera() {
        // Actual implementation is inside CameraScreen's LaunchedEffect
    }

    private fun allPermissionsGranted() = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    ).all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun setupSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Speech recognition not available on this device", Toast.LENGTH_LONG).show()
            return
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { 
                isListening = true 
                Toast.makeText(this@MainActivity, "Listening...", Toast.LENGTH_SHORT).show()
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { isListening = false }
            override fun onError(error: Int) {
                isListening = false
                val msg = when(error) {
                    SpeechRecognizer.ERROR_NETWORK -> "Network error"
                    SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Permissions required"
                    else -> "Speech error: $error"
                }
                Log.e("Vision", msg)
                runOnUiThread { Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show() }
            }
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val text = matches[0]
                    runOnUiThread {
                        chatMessages.add(ChatMessage(text, true))
                        submitCommand(text)
                    }
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    // Could show partial text in the text field if we wanted
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun startListening() {
        runOnUiThread { isListening = true }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        try {
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            isListening = false
            Toast.makeText(this, "Speech error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopListening() {
        speechRecognizer?.stopListening()
        isListening = false
    }

    private fun submitCommand(input: String) {
        runOnUiThread {
            val cmd = input.trim()
            activeCommand = cmd
            isDetectionActive = true
            lastAnalysisMessage = "" 
            val intro = "Activating AI for: \"$cmd\"..."
            chatMessages.add(ChatMessage(intro, false))
            lastDetectionTime = SystemClock.uptimeMillis()
            
            // Force immediate render even if results are null
            processIntelligence(null, lastFrameWidth, lastFrameHeight, lastFrameRotation)
            
            Toast.makeText(this@MainActivity, "Commmand: $cmd", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onError(error: String) {
        runOnUiThread {
            Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResults(results: ObjectDetectorResult, inferenceTime: Long) {
        val w = lastFrameWidth
        val h = lastFrameHeight
        val r = lastFrameRotation
        
        // Log frame results only in verbose
        Log.v("Vision", "Frame Results: ${results.detections().size} objects")
        
        if (w <= 0 || h <= 0) return

        runOnUiThread {
            processIntelligence(results, w, h, r)
        }
    }

    private fun processIntelligence(results: ObjectDetectorResult?, width: Int, height: Int, rotation: Int) {
        Log.v("Vision", "processIntelligence: results=${results != null} size=${width}x${height}")
        // Allow width/height 0 only if we have a state to pull from, otherwise wait for camera
        if (width <= 0 || height <= 0) return
        
        lastDetectionTime = SystemClock.uptimeMillis()
        
        val opinion = assistantEngine.analyze(
            results,
            activeCommand,
            width,
            height,
            rotation
        )

        // Perform Smoothing on Overlays
        val newOverlays = opinion.overlays
        val finalOverlays = mutableListOf<OverlayElement>()

        // Update life of existing smoothed overlays
        smoothedOverlays.values.forEach { it.life-- }

        newOverlays.forEach { newElement ->
            if (newElement is OverlayElement.Box) {
                // Find matching smoothed overlay
                val match = smoothedOverlays.values.find { matchItem ->
                    val element = matchItem.element
                    element is OverlayElement.Box && 
                    element.label == newElement.label &&
                    iou(matchItem.smoothedRect ?: element.rect, newElement.rect) > 0.4f
                }

                if (match != null) {
                    val box = match.element as OverlayElement.Box
                    val prevRect = match.smoothedRect ?: box.rect
                    match.smoothedRect = RectF(
                        prevRect.left * 0.7f + newElement.rect.left * 0.3f,
                        prevRect.top * 0.7f + newElement.rect.top * 0.3f,
                        prevRect.right * 0.7f + newElement.rect.right * 0.3f,
                        prevRect.bottom * 0.7f + newElement.rect.bottom * 0.3f
                    )
                    match.life = 5 // Reset life
                } else {
                    smoothedOverlays[nextOverlayId++] = SmoothedOverlay(newElement, life = 5, smoothedRect = newElement.rect)
                }
            } else {
                // Non-box elements (lines, hints) are usually logic-based and stable
                finalOverlays.add(newElement)
            }
        }

        // Clean up dead overlays and build final list
        smoothedOverlays.entries.removeIf { it.value.life <= 0 }
        smoothedOverlays.values.forEach { smoothed ->
            val element = smoothed.element
            if (element is OverlayElement.Box && smoothed.smoothedRect != null) {
                finalOverlays.add(element.copy(rect = smoothed.smoothedRect!!))
            } else {
                finalOverlays.add(element)
            }
        }

        resultsState = DetectionState(
            results = results,
            originalWidth = width,
            originalHeight = height,
            rotation = rotation,
            opinion = opinion,
            smoothedOverlays = finalOverlays
        )

        if (opinion.message != lastAnalysisMessage && opinion.message.isNotBlank()) {
            lastAnalysisMessage = opinion.message
            chatMessages.add(ChatMessage(opinion.message, false))
        }
    }

    private fun iou(a: RectF, b: RectF): Float {
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)

        if (left >= right || top >= bottom) return 0f

        val intersectionArea = (right - left) * (bottom - top)
        val areaA = kotlin.math.abs((a.right - a.left) * (a.bottom - a.top))
        val areaB = kotlin.math.abs((b.right - b.left) * (b.bottom - b.top))
        val unionArea = areaA + areaB - intersectionArea
        
        return if (unionArea <= 0f) 0f else intersectionArea / unionArea
    }

    @Composable
    fun CameraScreen(
        state: DetectionState?,
        isActive: Boolean,
        command: String
    ) {
        Log.e("Vision", "RECOMPOSE: active=$isActive cmd=$command")
        val context = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current
        val previewView = remember { 
            PreviewView(context).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            }
        }
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
                                lastFrameWidth = imageProxy.width
                                lastFrameHeight = imageProxy.height
                                lastFrameRotation = rotation
                                
                                objectDetectorHelper?.detectLiveStream(
                                    bitmap,
                                    rotation,
                                    SystemClock.uptimeMillis()
                                )
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

        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (isActive) Modifier.border(4.dp, Color.Cyan) else Modifier
                )
        ) {
            // Fullscreen Camera Preview
            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize()
            )

            val infiniteTransition = rememberInfiniteTransition(label = "pulse")
            val pulseAlpha by infiniteTransition.animateFloat(
                initialValue = 0.4f,
                targetValue = 1.0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(600, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "pulseAlpha"
            )

            // DEBUG INDICATOR: Always visible if active
            if (isActive) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(Color.Cyan.copy(alpha = pulseAlpha))
                        .align(Alignment.TopCenter)
                )
            }
            
            // AI Activity Heartbeat
            Canvas(modifier = Modifier.fillMaxSize()) {
                if (isActive) {
                    drawLine(
                        color = Color.Cyan.copy(alpha = 0.4f),
                        start = androidx.compose.ui.geometry.Offset(0f, 0f),
                        end = androidx.compose.ui.geometry.Offset(size.width * pulseAlpha, 0f),
                        strokeWidth = 4.dp.toPx()
                    )
                }
            }

            // Fullscreen Overlay for detections
            resultsState?.let { state ->
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val rotation = state.rotation
                    val isRotated = rotation == 90 || rotation == 270
                    
                    val rotatedWidth = if (isRotated) state.originalHeight else state.originalWidth
                    val rotatedHeight = if (isRotated) state.originalWidth else state.originalHeight

                    // Changed to minOf to ensure the entire AR sensor area is visible on tall screens
                    val scale = minOf(size.width / rotatedWidth, size.height / rotatedHeight)
                    val offsetX = (size.width - rotatedWidth * scale) / 2f
                    val offsetY = (size.height - rotatedHeight * scale) / 2f

                    fun map(x: Float, y: Float): androidx.compose.ui.geometry.Offset {
                        val (mx, my) = when (rotation) {
                            90 -> (rotatedWidth - y) to x
                            270 -> y to (rotatedHeight - x)
                            180 -> (state.originalWidth - x) to (state.originalHeight - y)
                            else -> x to y
                        }
                        return androidx.compose.ui.geometry.Offset(mx * scale + offsetX, my * scale + offsetY)
                    }

                    state.smoothedOverlays.forEach { element ->
                        when (element) {
                            is OverlayElement.Box -> {
                                val topLeft = map(element.rect.left, element.rect.top)
                                val bottomRight = map(element.rect.right, element.rect.bottom)
                                val rectColor = if (element.pulse) element.color.copy(alpha = pulseAlpha) else element.color
                                
                                drawRect(
                                    color = rectColor,
                                    topLeft = androidx.compose.ui.geometry.Offset(
                                        minOf(topLeft.x, bottomRight.x),
                                        minOf(topLeft.y, bottomRight.y)
                                    ),
                                    size = androidx.compose.ui.geometry.Size(
                                        kotlin.math.abs(bottomRight.x - topLeft.x),
                                        kotlin.math.abs(bottomRight.y - topLeft.y)
                                    ),
                                    style = Stroke(width = (if (element.isCritical) 4.dp else 2.dp).toPx())
                                )
                                
                                // Draw simple label background + text
                                val labelX = minOf(topLeft.x, bottomRight.x)
                                val labelY = minOf(topLeft.y, bottomRight.y)
                                
                                if (element.label.isNotBlank()) {
                                    drawRect(
                                        color = rectColor.copy(alpha = 0.6f),
                                        topLeft = androidx.compose.ui.geometry.Offset(labelX, labelY - 20.dp.toPx()),
                                        size = androidx.compose.ui.geometry.Size(80.dp.toPx(), 20.dp.toPx())
                                    )
                                    drawIntoCanvas { canvas ->
                                        val paint = Paint().apply {
                                            color = android.graphics.Color.WHITE
                                            textSize = 12.dp.toPx()
                                            typeface = Typeface.DEFAULT_BOLD
                                        }
                                        canvas.nativeCanvas.drawText(
                                            element.label,
                                            labelX + 4.dp.toPx(),
                                            labelY - 5.dp.toPx(),
                                            paint
                                        )
                                    }
                                }
                            }
                            is OverlayElement.PathLine -> {
                                val start = map(element.x1, element.y1)
                                val end = map(element.x2, element.y2)
                                drawLine(
                                    color = element.color,
                                    start = start,
                                    end = end,
                                    strokeWidth = element.thickness.dp.toPx()
                                )
                            }
                            is OverlayElement.ARHint -> {
                                val pos = map(element.x, element.y)
                                drawCircle(
                                    color = element.color,
                                    center = pos,
                                    radius = 10.dp.toPx()
                                )
                            }
                        }
                    }
                }
            }

            // Top Status Bar (Transparent)
            Box(modifier = Modifier.align(Alignment.TopCenter)) {
                StatusOverlay(state, isActive, command)
            }

            // Bottom Chat Section (Transparent Overlay)
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(if (size.width > size.height) 0.3f else 0.4f) // Less space in landscape
                    .background(Color.Black.copy(alpha = 0.15f))
                    .padding(4.dp)
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
                    isListening = isListening,
                    onMicClick = {
                        if (isListening) stopListening() else startListening()
                    },
                    onSendClick = {
                        if (chatInput.isNotBlank()) {
                            val input = chatInput
                            chatMessages.add(ChatMessage(input, true))
                            submitCommand(input)
                            chatInput = ""
                        }
                    }
                )
            }
        }
    }

    @Composable
    fun StatusOverlay(
        state: DetectionState?,
        isActive: Boolean,
        command: String
    ) {
        val detections = state?.results?.detections() ?: emptyList()
        val objects = detections.mapNotNull { it.categories().firstOrNull()?.categoryName() }.distinct()
        
        val statusText = when {
            !isActive -> ""
            state == null -> "Initializing..."
            detections.isEmpty() -> "" 
            command.contains("warn", ignoreCase = true) && objects.isNotEmpty() -> "WARNING: Hazards Present"
            else -> state.opinion.message
        }

        if (statusText.isBlank()) return

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
        isListening: Boolean,
        onMicClick: () -> Unit,
        onSendClick: () -> Unit
    ) {
        val infiniteTransition = rememberInfiniteTransition(label = "micTransition")
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
                placeholder = { Text("Ask or tap mic...") },
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
            
            val micPulseAlpha by infiniteTransition.animateFloat(
                initialValue = 0.6f,
                targetValue = 1.0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(400, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "micPulse"
            )

            // Mic Button
            IconButton(
                onClick = onMicClick,
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        if (isListening) Color.Red.copy(alpha = micPulseAlpha) 
                        else Color.White.copy(alpha = 0.1f),
                        CircleShape
                    )
            ) {
                Icon(
                    if (isListening) Icons.Default.Stop else Icons.Default.Mic,
                    contentDescription = "Voice",
                    tint = Color.White
                )
            }
            
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
        speechRecognizer?.destroy()
        cameraExecutor.shutdown()
    }
}
