package com.example.vision

import android.graphics.RectF
import android.util.Log
import androidx.compose.ui.graphics.Color
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult

sealed class OverlayElement {
    data class Box(
        val rect: RectF, 
        val label: String, 
        val color: Color, 
        val isCritical: Boolean = false,
        val pulse: Boolean = false
    ) : OverlayElement()

    data class PathLine(
        val x1: Float, val y1: Float, 
        val x2: Float, val y2: Float, 
        val color: Color, 
        val thickness: Float = 4f
    ) : OverlayElement()

    data class ARHint(
        val x: Float, val y: Float,
        val message: String,
        val color: Color
    ) : OverlayElement()
}

data class AssistantOpinion(
    val message: String,
    val isCritical: Boolean,
    val overlays: List<OverlayElement>
)

class VisualAssistantEngine {

    enum class Mode {
        GENERAL,
        SAFETY_WATCH,
        PARKING_ASSISTANT,
        ENVIRONMENT_QUERY
    }

    fun analyze(
        results: ObjectDetectorResult?, 
        command: String, 
        frameWidth: Int, 
        frameHeight: Int,
        rotation: Int
    ): AssistantOpinion {
        val mode = determineMode(command)
        val detections = results?.detections() ?: emptyList()
        
        val overlays = mutableListOf<OverlayElement>()
        val alerts = mutableListOf<String>()
        var isCriticalSession = false

        when (mode) {
            Mode.SAFETY_WATCH -> {
                detections.forEach { detection ->
                    val label = detection.categories().firstOrNull()?.categoryName()?.lowercase() ?: ""
                    val box = detection.boundingBox()
                    val occupancy = (box.width() * box.height()) / (frameWidth * frameHeight).toFloat()

                    // Hazard Logic
                    val isHazard = (label in listOf("person", "bicycle", "dog")) && occupancy > 0.02f ||
                                   (label in listOf("car", "truck")) && occupancy > 0.3f
                    
                    if (isHazard) {
                        isCriticalSession = true
                        alerts.add("DANGER: $label detected close by!")
                        overlays.add(OverlayElement.Box(box, label.uppercase(), Color.Red, isCritical = true, pulse = true))
                    } else if (occupancy > 0.05f) {
                        overlays.add(OverlayElement.Box(box, label, Color.Yellow))
                    }
                }
            }

            Mode.PARKING_ASSISTANT -> {
                // Logic: We want lines to appear "Bottom to Top" on the SCREEN.
                // In Portrait (90 rot), "Down" on screen is often "Left" in sensor coords.
                
                // We'll define points in Screen Normalized [0,1] and convert to sensor coords.
                fun screenToSensor(sx: Float, sy: Float): Pair<Float, Float> {
                    return when (rotation) {
                        // Portrait (90): 
                        // Screen Y (Top to Bottom) maps to Sensor X (0 to 640)
                        // Screen X (Left to Right) maps to Sensor Y (480 to 0)
                        90 -> (sy * frameWidth).toFloat() to ((1f - sx) * frameHeight).toFloat()
                        270 -> ((1f - sy) * frameWidth).toFloat() to (sx * frameHeight).toFloat()
                        180 -> ((1f - sx) * frameWidth).toFloat() to ((1f - sy) * frameHeight).toFloat()
                        else -> (sx * frameWidth).toFloat() to (sy * frameHeight).toFloat()
                    }
                }

                val bottomY = 0.90f
                val horizonY = 0.40f
                val bottomWidth = 0.20f
                val topWidth = 0.08f
                val centerX = 0.5f

                // Left Guide (Cyan)
                val lStart = screenToSensor(centerX - bottomWidth, bottomY)
                val lEnd = screenToSensor(centerX - topWidth, horizonY)
                overlays.add(OverlayElement.PathLine(lStart.first, lStart.second, lEnd.first, lEnd.second, Color.Cyan, 8f))

                // Right Guide (Cyan)
                val rStart = screenToSensor(centerX + bottomWidth, bottomY)
                val rEnd = screenToSensor(centerX + topWidth, horizonY)
                overlays.add(OverlayElement.PathLine(rStart.first, rStart.second, rEnd.first, rEnd.second, Color.Cyan, 8f))

                // Markers (Horizontal-looking guides on the floor)
                val steps = listOf(0.1f, 0.3f, 0.5f)
                steps.forEachIndexed { index, ratio ->
                    val y = bottomY - (bottomY - horizonY) * ratio
                    val currentWidth = bottomWidth - (bottomWidth - topWidth) * ratio
                    val color = when(index) {
                        0 -> Color.Green
                        1 -> Color.Yellow
                        else -> Color.Red
                    }.copy(alpha = 0.6f)
                    
                    val p1 = screenToSensor(centerX - currentWidth, y)
                    val p2 = screenToSensor(centerX + currentWidth, y)
                    overlays.add(OverlayElement.PathLine(p1.first, p1.second, p2.first, p2.second, color, 4f))
                }

                alerts.add("Parking Guide Active")
                
                // Highlight obstacles in the way
                detections.forEach { detection ->
                    val label = detection.categories().firstOrNull()?.categoryName()?.lowercase() ?: ""
                    overlays.add(OverlayElement.Box(detection.boundingBox(), "OBSTACLE: $label", Color.Red))
                }
            }

            else -> {
                // General Detection
                detections.forEach { detection ->
                    val label = detection.categories().firstOrNull()?.categoryName() ?: "Object"
                    overlays.add(OverlayElement.Box(detection.boundingBox(), label, Color.Magenta))
                    
                    // Only alert for high-confidence / large objects in general mode
                    val box = detection.boundingBox()
                    val occupancy = (box.width() * box.height()) / (frameWidth * frameHeight).toFloat()
                    if (occupancy > 0.4f) {
                        alerts.add("Large $label in view.")
                    }
                }
            }
        }

        val finalMessage = if (alerts.isEmpty()) {
            if (isCriticalSession) "DANGER DETECTED!" else "" 
        } else {
            alerts.distinct().take(2).joinToString(" | ")
        }

        return AssistantOpinion(finalMessage, isCriticalSession, overlays)
    }

    private fun determineMode(command: String): Mode {
        val cmd = command.lowercase()
        return when {
            cmd.contains("park") -> Mode.PARKING_ASSISTANT
            cmd.contains("warn") || cmd.contains("risk") || cmd.contains("danger") || cmd.contains("safe") -> Mode.SAFETY_WATCH
            cmd.contains("describe") || cmd.contains("what") -> Mode.ENVIRONMENT_QUERY
            else -> Mode.GENERAL
        }
    }
}
