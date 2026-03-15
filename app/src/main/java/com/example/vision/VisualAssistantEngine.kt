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
        DRIVING_ASSISTANT,
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
        bitmap: Bitmap? = null,
        recognizedTexts: List<Pair<String, RectF>> = emptyList()
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

                // We draw one clean track sequence based on the reference images
                val segments = 15
                val carCenterX = 0.5f // Static center of the car camera
                
                // Use a linear path to the detected vanishing point (centerX) to represent a straight lane in perspective
                fun getPathPoint(t: Float): Triple<Float, Float, Float> {
                    val y = bottomY - (bottomY - horizonY) * t
                    val w = nearWidth - (nearWidth - farWidth) * t
                    // A linear interpolation provides a clean representation of the straight lane direction
                    val cx = carCenterX + (centerX - carCenterX) * t
                    return Triple(cx - w, cx + w, y)
                }

                var lastP = getPathPoint(0f)
                for (i in 1..segments) {
                    val t = i.toFloat() / segments
                    if (t > actualStopRatio + 0.05f) break // Stop line slightly after occlusion
                    
                    val p = getPathPoint(t)
                    
                    // Match the parking reference colors: Red for immediate, Yellow for mid, Green for far.
                    val segmentColor = if (maxDangerLevel == 2) Color.Red else {
                        if (t <= 0.25f) Color.Red else if (t <= 0.6f) Color.Yellow else Color.Green
                    }
                    
                    val sl1 = screenToSensor(lastP.first, lastP.third)
                    val sl2 = screenToSensor(p.first, p.third)
                    // Draw thick lines
                    overlays.add(OverlayElement.PathLine(sl1.first, sl1.second, sl2.first, sl2.second, segmentColor.copy(alpha = 0.9f), 12f))

                    val sr1 = screenToSensor(lastP.second, lastP.third)
                    val sr2 = screenToSensor(p.second, p.third)
                    overlays.add(OverlayElement.PathLine(sr1.first, sr1.second, sr2.first, sr2.second, segmentColor.copy(alpha = 0.9f), 12f))
                    
                    lastP = p
                }

                // Make horizontal connecting bounds to mimic the reference image boundaries
                listOf(0.25f, 0.6f).forEach { t ->
                    if (t <= actualStopRatio + 0.05f) {
                        val p = getPathPoint(t)
                        val barColor = if (maxDangerLevel == 2) Color.Red else if (t <= 0.25f) Color.Red else Color.Yellow
                        val l = screenToSensor(p.first, p.third)
                        val r = screenToSensor(p.second, p.third)
                        overlays.add(OverlayElement.PathLine(l.first, l.second, r.first, r.second, barColor.copy(alpha = 0.9f), 8f))
                    }
                }
                
                // Draw a final horizontal line where the track stops (if track is blocked)
                if (isPathBlocked) {
                    val pStop = getPathPoint(actualStopRatio)
                    val l = screenToSensor(pStop.first, pStop.third)
                    val r = screenToSensor(pStop.second, pStop.third)
                    overlays.add(OverlayElement.PathLine(l.first, l.second, r.first, r.second, Color.Red, 14f))
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
            Mode.DRIVING_ASSISTANT -> {
                val drivingAlerts = mutableSetOf<String>()
                
                // --- CUSTOM RED PROHIBITIVE SIGN DETECTION ---
                if (bitmap != null) {
                    try {
                        val bw = bitmap.width
                        val bh = bitmap.height
                        val step = 4
                        val cols = bw / step
                        val rows = bh / step
                        val isRed = BooleanArray(cols * rows)
                        
                        for (by in 0 until bh step step) {
                            for (bx in 0 until bw step step) {
                                val pixel = bitmap.getPixel(bx, by)
                                val r = (pixel shr 16) and 0xff
                                val g = (pixel shr 8) and 0xff
                                val b = pixel and 0xff
                                // Stricter Red color heuristic: avoid warm-lit trees and orange street lamps
                                // Pure traffic red has very low green/blue compared to its red value
                                if (r > 140 && g < r * 0.4f && b < r * 0.4f) {
                                    val c = bx / step
                                    val rIdx = by / step
                                    if (c < cols && rIdx < rows) {
                                        isRed[rIdx * cols + c] = true
                                    }
                                }
                            }
                        }
                        
                        val visited = BooleanArray(cols * rows)
                        val q = IntArray(cols * rows * 2) // flattened queue for speed
                        for (y in 0 until rows) {
                            for (x in 0 until cols) {
                                val idx = y * cols + x
                                if (isRed[idx] && !visited[idx]) {
                                    var minX = x; var maxX = x
                                    var minY = y; var maxY = y
                                    var count = 0
                                    
                                    var qHead = 0
                                    var qTail = 0
                                    q[qTail++] = x
                                    q[qTail++] = y
                                    visited[idx] = true
                                    
                                    while (qHead < qTail) {
                                        val cx = q[qHead++]
                                        val cy = q[qHead++]
                                        count++
                                        if (cx < minX) minX = cx
                                        if (cx > maxX) maxX = cx
                                        if (cy < minY) minY = cy
                                        if (cy > maxY) maxY = cy
                                        
                                        // 4-way neighbors to avoid huge unconnected diagonal blobs
                                        val ddx = intArrayOf(1, -1, 0, 0)
                                        val ddy = intArrayOf(0, 0, 1, -1)
                                        for (i in 0..3) {
                                            val nx = cx + ddx[i]
                                            val ny = cy + ddy[i]
                                            if (nx in 0 until cols && ny in 0 until rows) {
                                                val nIdx = ny * cols + nx
                                                if (isRed[nIdx] && !visited[nIdx]) {
                                                    visited[nIdx] = true
                                                    q[qTail++] = nx
                                                    q[qTail++] = ny
                                                }
                                            }
                                        }
                                    }
                                    
                                    // Filter out tiny artifacts (leaves) and loosely connected pixel scattering
                                    if (count > 25) {
                                        val w = maxX - minX + 1
                                        val h = maxY - minY + 1
                                        val ratio = w.toFloat() / h.toFloat()
                                        val fillRatio = count.toFloat() / (w * h)
                                        
                                        // Require larger solid blobs to avoid noise, ratio closer to square (circle bounds)
                                        if (ratio in 0.6f..1.6f && w in 8..(cols/3) && h in 8..(rows/3) && fillRatio > 0.25f) {
                                            val rectMinX = (minX * step).toFloat()
                                            val rectMinY = (minY * step).toFloat()
                                            val rectMaxX = (maxX * step + step).toFloat()
                                            val rectMaxY = (maxY * step + step).toFloat()
                                            
                                            // Ensure it's in the upper section (above headlights/taillights)
                                            if (rectMaxY < bh * 0.7f) {
                                                val pad = 10f
                                                val rect = RectF(rectMinX - pad, rectMinY - pad, rectMaxX + pad, rectMaxY + pad)
                                                drivingAlerts.add("PROHIBITIVE SIGN DETECTED")
                                                overlays.add(OverlayElement.Box(rect, "RESTRICTION SIGN", Color.Red, isCritical = true, pulse = true))
                                                isCriticalSession = true
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("Vision", "Red Blob detect error", e)
                    }
                }
                
                detections.forEach { detection ->
                    val label = detection.categories().firstOrNull()?.categoryName()?.lowercase() ?: ""
                    val box = detection.boundingBox()
                    val occupancy = (box.width() * box.height()) / (frameWidth * frameHeight).toFloat()
                    
                    if (label == "stop sign" && occupancy > 0.01f) {
                        drivingAlerts.add("STOP SIGN AHEAD")
                        overlays.add(OverlayElement.Box(box, "STOP", Color.Red, isCritical = true, pulse = true))
                        isCriticalSession = true
                    } else if (label == "traffic light") {
                        drivingAlerts.add("TRAFFIC LIGHT")
                        overlays.add(OverlayElement.Box(box, "TRAFFIC LIGHT", Color.Yellow))
                    } else if (label in listOf("person", "bicycle", "motorcycle") && occupancy > 0.05f) {
                        drivingAlerts.add("CAUTION: ${label.uppercase()}")
                        overlays.add(OverlayElement.Box(box, label.uppercase(), Color.Red, pulse = true))
                        isCriticalSession = true
                    } else if (label in listOf("car", "truck", "bus") && occupancy > 0.35f) {
                        drivingAlerts.add("VEHICLE PROXIMITY ALERT")
                        overlays.add(OverlayElement.Box(box, "TOO CLOSE", Color.Red, isCritical = true, pulse = true))
                        isCriticalSession = true
                    } else if (label in listOf("car", "truck", "bus") && occupancy > 0.05f) {
                        overlays.add(OverlayElement.Box(box, label.uppercase(), Color.Green))
                    } else if (occupancy > 0.1f && label !in listOf("airplane", "bird", "kite")) {
                         overlays.add(OverlayElement.Box(box, label.uppercase(), Color.Cyan))
                    }
                }
                
                recognizedTexts.forEach { (text, rect) ->
                    val upperText = text.uppercase().replace("\n", " ")
                    if (upperText.contains("SPEED") || upperText.matches(Regex(".*\\b(MAX|LIMIT)\\b.*"))) {
                        drivingAlerts.add("SPEED LIMIT DETECTED")
                        overlays.add(OverlayElement.Box(rect, "SPEED: $upperText", Color.Yellow, pulse = true))
                        isCriticalSession = true
                    } else if (upperText.contains("PARK") && (upperText.contains("NO") || upperText.contains("CAN'T") || upperText.contains("CANT") || upperText.contains("NOT"))) {
                        drivingAlerts.add("CANT PARKING ZONE")
                        overlays.add(OverlayElement.Box(rect, "NO PARKING", Color.Red, isCritical = true, pulse = true))
                        isCriticalSession = true
                    } else if (upperText.contains("TURN") && (upperText.contains("NO") || upperText.contains("CANT"))) {
                        drivingAlerts.add("RESTRICTED LANE OR TURN")
                        overlays.add(OverlayElement.Box(rect, "RESTRICTED", Color.Red, pulse = true))
                    } else if (upperText.contains("STOP")) {
                        drivingAlerts.add("STOP SIGN AHEAD")
                        overlays.add(OverlayElement.Box(rect, "STOP", Color.Red, isCritical = true, pulse = true))
                        isCriticalSession = true
                    }
                }
                
                if (drivingAlerts.isNotEmpty()) {
                    alerts.addAll(drivingAlerts)
                } else {
                    alerts.add("DRIVE SAFELY")
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
            cmd.contains("drive") || cmd.contains("traffic") || cmd.contains("sign") -> Mode.DRIVING_ASSISTANT
            cmd.contains("warn") || cmd.contains("risk") || cmd.contains("danger") || cmd.contains("safe") -> Mode.SAFETY_WATCH
            cmd.contains("describe") || cmd.contains("what") -> Mode.ENVIRONMENT_QUERY
            else -> Mode.GENERAL
        }
    }
}
