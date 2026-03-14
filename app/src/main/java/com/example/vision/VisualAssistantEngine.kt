package com.example.vision

import android.graphics.RectF
import android.util.Log
import android.graphics.Bitmap
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

    data class PathPolygon(
        val x1: Float, val y1: Float,
        val x2: Float, val y2: Float,
        val x3: Float, val y3: Float,
        val x4: Float, val y4: Float,
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
        roll: Float = 0f,  // in radians
        bitmap: Bitmap? = null
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
                fun screenToSensor(sx: Float, sy: Float): Pair<Float, Float> {
                    return when (rotation) {
                        90 -> (sy * frameWidth).toFloat() to ((1f - sx) * frameHeight).toFloat()
                        270 -> ((1f - sy) * frameWidth).toFloat() to (sx * frameHeight).toFloat()
                        180 -> ((1f - sx) * frameWidth).toFloat() to ((1f - sy) * frameHeight).toFloat()
                        else -> (sx * frameWidth).toFloat() to (sy * frameHeight).toFloat()
                    }
                }

                // Adjust Horizon
                val pitchCorrection = (pitch / (Math.PI.toFloat() / 2.5f)) * 0.4f
                val horizonY = (0.45f + pitchCorrection).coerceIn(0.2f, 0.7f)
                
                // Roll adjusts the "lean" of the lines
                val rollCorrection = (roll / (Math.PI.toFloat() / 4f)) * 0.15f
                var centerX = (0.5f + rollCorrection).coerceIn(0.4f, 0.6f)

                val bottomY = 1.0f 
                var nearWidth = 0.24f
                val farWidth = 0.0f

                // --- DYNAMIC ROAD BOUNDS DETECTION ---
                if (bitmap != null) {
                    try {
                        val bw = bitmap.width.toFloat()
                        val bh = bitmap.height.toFloat()
                        val samples = 60
                        val luminances = FloatArray(samples)
                        
                        val scanSy = 0.85f 
                        for (i in 0 until samples) {
                            val sx = i.toFloat() / (samples - 1)
                            val (bxFloat, byFloat) = when (rotation) {
                                90 -> scanSy * bw to (1f - sx) * bh
                                270 -> (1f - scanSy) * bw to sx * bh
                                180 -> (1f - sx) * bw to (1f - scanSy) * bh
                                else -> sx * bw to scanSy * bh
                            }
                            val bx = bxFloat.toInt().coerceIn(0, bitmap.width - 1)
                            val by = byFloat.toInt().coerceIn(0, bitmap.height - 1)
                            val pixel = bitmap.getPixel(bx, by)
                            luminances[i] = 0.299f * ((pixel shr 16) and 0xff) + 0.587f * ((pixel shr 8) and 0xff) + 0.114f * (pixel and 0xff)
                        }
                        
                        val centerIdx = samples / 2
                        var leftPeakIdx = -1
                        var leftPeakVal = 0f
                        for (i in (centerIdx - 1) downTo 4) {
                            if (luminances[i] > leftPeakVal && luminances[i] > 100f) {
                                if (luminances[i] > luminances[i-1] && luminances[i] > luminances[i+1]) {
                                    leftPeakVal = luminances[i]
                                    leftPeakIdx = i
                                }
                            }
                        }
                        var rightPeakIdx = -1
                        var rightPeakVal = 0f
                        for (i in centerIdx until (samples - 4)) {
                            if (luminances[i] > rightPeakVal && luminances[i] > 100f) {
                                if (luminances[i] > luminances[i-1] && luminances[i] > luminances[i+1]) {
                                    rightPeakVal = luminances[i]
                                    rightPeakIdx = i
                                }
                            }
                        }
                        if (leftPeakIdx != -1 && rightPeakIdx != -1) {
                            val dynamicCenterX = (leftPeakIdx.toFloat() / (samples - 1) + rightPeakIdx.toFloat() / (samples - 1)) / 2f
                            val dynamicNearWidth = (rightPeakIdx.toFloat() / (samples - 1) - leftPeakIdx.toFloat() / (samples - 1)) / 2f
                            centerX = (centerX * 0.4f) + (dynamicCenterX * 0.6f)
                            nearWidth = dynamicNearWidth.coerceIn(0.15f, 0.4f)
                        }
                    } catch (e: Exception) {
                        Log.e("Vision", "Lane scan error", e)
                    }
                }
                // -----------------------------------------------------------

                val maxDistanceRatio = 0.85f 
                var isPathBlocked = false
                var closestObstacleBottomY = 0f
                var obstacleLabel = ""
                var steeringAdvice = ""
                var maxDangerLevel = 0

                data class ObstacleBox(val rect: RectF, val label: String, val level: Int)
                val obstacleBoxes = mutableListOf<ObstacleBox>()

                detections.forEach { detection ->
                    val label = detection.categories().firstOrNull()?.categoryName()?.lowercase() ?: ""
                    if (label in listOf("airplane", "bird", "kite", "clock")) return@forEach
                    
                    val box = detection.boundingBox()
                    val boxCenterScreenX = box.centerX() / frameWidth
                    val boxBottomScreenY = box.bottom / frameHeight
                    val boxArea = (box.width() * box.height()) / (frameWidth * frameHeight).toFloat()
                    
                    if (boxBottomScreenY > horizonY) { 
                         val pathWidthAtY = nearWidth - (nearWidth - farWidth) * ((bottomY - boxBottomScreenY) / (bottomY - horizonY))
                         val distFromCenter = Math.abs(boxCenterScreenX - centerX)
                         
                         var dangerLevel = 0
                         if (boxArea > 0.35f || boxBottomScreenY > 0.85f) dangerLevel = 2 else if (boxArea > 0.15f || boxBottomScreenY > 0.65f) dangerLevel = 1
                         
                         if (distFromCenter < pathWidthAtY * 1.8f) { 
                             isPathBlocked = true
                             obstacleBoxes.add(ObstacleBox(box, label, dangerLevel))
                             if (boxBottomScreenY > closestObstacleBottomY) {
                                 closestObstacleBottomY = boxBottomScreenY
                                 obstacleLabel = label
                                 maxDangerLevel = dangerLevel
                                 
                                 // Suggest steering away from obstacle center
                                 steeringAdvice = if (boxCenterScreenX < centerX) "STEER RIGHT" else "STEER LEFT"
                             }
                         } else if (dangerLevel > 0) {
                             // Dangerous but slightly out of tight path (e.g. adjacent lane)
                             obstacleBoxes.add(ObstacleBox(box, label, dangerLevel - 1))
                         }
                    }
                }

                val actualStopRatio = if (isPathBlocked) {
                    Math.min(maxDistanceRatio, ((bottomY - closestObstacleBottomY) / (bottomY - horizonY)) - 0.05f).coerceAtLeast(0.1f)
                } else maxDistanceRatio

                val actualStopY = bottomY - (bottomY - horizonY) * actualStopRatio
                val actualStopWidth = nearWidth - (nearWidth - farWidth) * actualStopRatio

                val pathColor = if (maxDangerLevel == 2) Color.Red else if (maxDangerLevel == 1) Color.Yellow else Color.Cyan

                val bl = screenToSensor(centerX - nearWidth, bottomY)
                val br = screenToSensor(centerX + nearWidth, bottomY)
                val tr = screenToSensor(centerX + actualStopWidth, actualStopY)
                val tl = screenToSensor(centerX - actualStopWidth, actualStopY)

                overlays.add(OverlayElement.PathPolygon(bl.first, bl.second, br.first, br.second, tr.first, tr.second, tl.first, tl.second, pathColor.copy(alpha = 0.25f)))
                overlays.add(OverlayElement.PathLine(bl.first, bl.second, tl.first, tl.second, pathColor, 12f))
                overlays.add(OverlayElement.PathLine(br.first, br.second, tr.first, tr.second, pathColor, 12f))

                val distanceSteps = listOf(0.15f to Color.Green, 0.45f to Color.Yellow, 0.8f to Color.Red)
                distanceSteps.forEach { (ratio, baseColor) ->
                    if (ratio <= actualStopRatio + 0.02f) {
                        val y = bottomY - (bottomY - horizonY) * ratio
                        val currentWidth = nearWidth - (nearWidth - farWidth) * ratio
                        val markerColor = if (isPathBlocked) Color.Red else baseColor
                        val p1 = screenToSensor(centerX - currentWidth, y)
                        val p2 = screenToSensor(centerX + currentWidth, y)
                        overlays.add(OverlayElement.PathLine(p1.first, p1.second, p2.first, p2.second, markerColor.copy(alpha = 0.9f), 6f))
                    }
                }

                if (isPathBlocked) {
                    // For parking, the user just wants actionable guidance, not a list of objects.
                    val primaryAction = if (maxDangerLevel == 2) "STOP!" else steeringAdvice
                    alerts.add(primaryAction)
                    isCriticalSession = maxDangerLevel == 2
                } else {
                    alerts.add("STEER STRAIGHT")
                }
                
                obstacleBoxes.forEach { ob ->
                    val color = if (ob.level >= 2) Color.Red else if (ob.level == 1) Color.Yellow else Color.Cyan
                    overlays.add(OverlayElement.Box(ob.rect, if (ob.level > 0) "CAUTION: ${ob.label}" else ob.label, color, isCritical = ob.level >= 2, pulse = ob.level > 0))
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
