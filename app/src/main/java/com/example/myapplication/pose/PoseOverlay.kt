package com.example.myapplication.pose

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import kotlin.math.max

/** Landmarks 0–10 are the face (nose, eyes, ears, mouth); the body starts at the left shoulder. */
private const val FIRST_BODY_LANDMARK = 11

/**
 * Body-only skeleton connections, flattened as [start0, end0, start1, end1, ...].
 * Computed once so per-frame drawing does no filtering or allocation for them.
 */
private val BODY_CONNECTIONS: IntArray = PoseLandmarker.POSE_LANDMARKS
    .filter { it.start() >= FIRST_BODY_LANDMARK && it.end() >= FIRST_BODY_LANDMARK }
    .flatMap { listOf(it.start(), it.end()) }
    .toIntArray()

/**
 * Draws a minimal body skeleton (shoulders down): translucent white bones with solid white joints.
 *
 * Must be laid out over a `PreviewView` using `ScaleType.FILL_CENTER` with the same bounds,
 * so the same center-crop transform is applied here.
 */
@Composable
fun PoseOverlay(
    pose: PoseResultBundle?,
    modifier: Modifier = Modifier,
    boneColor: Color = Color.White.copy(alpha = 0.55f),
    jointColor: Color = Color.White,
    boneWidth: Dp = 3.5.dp,
    jointRadius: Dp = 3.5.dp,
) {
    // Reused every frame to avoid allocating a new Path at camera frame rate.
    val bonePath = remember { Path() }

    Canvas(modifier) {
        val bundle = pose ?: return@Canvas
        if (bundle.imageWidth == 0 || bundle.imageHeight == 0) return@Canvas

        // Replicate PreviewView FILL_CENTER: scale to fill, then center-crop.
        val scale = max(size.width / bundle.imageWidth, size.height / bundle.imageHeight)
        val drawnWidth = bundle.imageWidth * scale
        val drawnHeight = bundle.imageHeight * scale
        val offsetX = (size.width - drawnWidth) / 2f
        val offsetY = (size.height - drawnHeight) / 2f

        val boneStroke = Stroke(
            width = boneWidth.toPx(),
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )
        val jointDiameter = jointRadius.toPx() * 2

        bundle.result.landmarks().forEach { landmarks ->
            if (landmarks.size <= FIRST_BODY_LANDMARK) return@forEach

            fun screenX(index: Int) = landmarks[index].x() * drawnWidth + offsetX
            fun screenY(index: Int) = landmarks[index].y() * drawnHeight + offsetY

            // All bones as one path: a single draw call, and translucent strokes don't
            // double up into brighter spots where bones meet at a joint.
            bonePath.reset()
            for (i in BODY_CONNECTIONS.indices step 2) {
                val start = BODY_CONNECTIONS[i]
                val end = BODY_CONNECTIONS[i + 1]
                bonePath.moveTo(screenX(start), screenY(start))
                bonePath.lineTo(screenX(end), screenY(end))
            }
            drawPath(path = bonePath, color = boneColor, style = boneStroke)

            // Joints on top, batched into one call; round caps turn points into dots.
            val joints = List(landmarks.size - FIRST_BODY_LANDMARK) { i ->
                val index = FIRST_BODY_LANDMARK + i
                Offset(screenX(index), screenY(index))
            }
            drawPoints(
                points = joints,
                pointMode = PointMode.Points,
                color = jointColor,
                strokeWidth = jointDiameter,
                cap = StrokeCap.Round,
            )
        }
    }
}
