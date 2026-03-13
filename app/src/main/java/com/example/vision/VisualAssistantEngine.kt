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
        rotation: Int,
        pitch: Float = 0f, // in radians
        roll: Float = 0f  // in radians
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
                // Realistic Road Perspective:
                // 1. Lines应该从屏幕底部边缘开始（代表车辆宽度）
                // 2. 消失点（Vanishing Point）应该随着手机倾斜（Pitch）上下移动
                
                fun screenToSensor(sx: Float, sy: Float): Pair<Float, Float> {
                    return when (rotation) {
                        90 -> (sy * frameWidth).toFloat() to ((1f - sx) * frameHeight).toFloat()
                        270 -> ((1f - sy) * frameWidth).toFloat() to (sx * frameHeight).toFloat()
                        180 -> ((1f - sx) * frameWidth).toFloat() to ((1f - sy) * frameHeight).toFloat()
                        else -> (sx * frameWidth).toFloat() to (sy * frameHeight).toFloat()
                    }
                }

                // Adjust Horizon: 0.0 is top, 1.0 is bottom.
                // When looking down (negative pitch), horizon moves UP (toward 0).
                // Default horizon at 0.45 (slightly below center for dash mount).
                val pitchCorrection = (pitch / (Math.PI.toFloat() / 2.5f)) * 0.4f
                val horizonY = (0.45f + pitchCorrection).coerceIn(0.2f, 0.7f)
                
                // Roll adjusts the "lean" of the lines
                val rollCorrection = (roll / (Math.PI.toFloat() / 4f)) * 0.15f
                val centerX = (0.5f + rollCorrection).coerceIn(0.4f, 0.6f)

                // Wide base at the bottom, narrow at horizon
                val bottomY = 1.0f 
                val nearWidth = 0.45f // Much wider at the bottom to match car width
                val farWidth = 0.05f  // Very narrow at horizon for perspective

                // Left Guide (Cyan)
                val lStart = screenToSensor(centerX - nearWidth, bottomY)
                val lEnd = screenToSensor(centerX - farWidth, horizonY)
                overlays.add(OverlayElement.PathLine(lStart.first, lStart.second, lEnd.first, lEnd.second, Color.Cyan, 12f))

                // Right Guide (Cyan)
                val rStart = screenToSensor(centerX + nearWidth, bottomY)
                val rEnd = screenToSensor(centerX + farWidth, horizonY)
                overlays.add(OverlayElement.PathLine(rStart.first, rStart.second, rEnd.first, rEnd.second, Color.Cyan, 12f))

                // Distance Markers (Ground-relative steps)
                // Use a non-linear scale for markers to simulate depth (1m, 3m, 5m etc)
                val distanceSteps = listOf(0.15f, 0.45f, 0.75f)
                distanceSteps.forEachIndexed { index, ratio ->
                    val y = bottomY - (bottomY - horizonY) * ratio
                    val currentWidth = nearWidth - (nearWidth - farWidth) * ratio
                    
                    val color = when(index) {
                        0 -> Color.Green
                        1 -> Color.Yellow
                        else -> Color.Red
                    }.copy(alpha = 0.8f)
                    
                    val p1 = screenToSensor(centerX - currentWidth, y)
                    val p2 = screenToSensor(centerX + currentWidth, y)
                    overlays.add(OverlayElement.PathLine(p1.first, p1.second, p2.first, p2.second, color, 8f))
                }

                alerts.add("Safe Path Projected")
                
                // Intelligent Obstacle Filter for Parking
                detections.forEach { detection ->
                    val label = detection.categories().firstOrNull()?.categoryName()?.lowercase() ?: ""
                    val box = detection.boundingBox()
                    
                    // Ignore confidence-challenged or irrelevant hits (like "airplane" in a parking lot)
                    if (label == "airplane") return@forEach 
                    
                    // Map box center to screen coords
                    val boxCenterScreenX = (box.centerX() / frameWidth) 
                    val boxBottomScreenY = (box.bottom / frameHeight)
                    
                    // Only flag obstacles in the lower half of the view (close to road)
                    if (boxBottomScreenY > horizonY) {
                         // Check if obstacle is roughly within the horizontal range of the path
                         val pathWidthAtY = nearWidth - (nearWidth - farWidth) * ((bottomY - boxBottomScreenY) / (bottomY - horizonY))
                         val distFromCenter = Math.abs(boxCenterScreenX - centerX)
                         
                         if (distFromCenter < pathWidthAtY * 1.5f) {
                             overlays.add(OverlayElement.Box(box, "CAUTION: $label", Color.Red, isCritical = true, pulse = true))
                             alerts.add("OBSTACLE: $label")
                         }
                    }
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
