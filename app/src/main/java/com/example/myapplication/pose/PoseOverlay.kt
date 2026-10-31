package com.example.myapplication.pose

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import kotlin.math.max

/**
 * Draws the 33 pose landmarks and their skeletal connections.
 *
 * Must be laid out over a `PreviewView` using `ScaleType.FILL_CENTER` with the same bounds,
 * so the same center-crop transform is applied here.
 */
@Composable
fun PoseOverlay(
    pose: PoseResultBundle?,
    modifier: Modifier = Modifier,
    pointColor: Color = Color(0xFFFFEB3B),
    lineColor: Color = Color(0xFF00E5FF),
) {
    Canvas(modifier) {
        val bundle = pose ?: return@Canvas
        if (bundle.imageWidth == 0 || bundle.imageHeight == 0) return@Canvas

        // Replicate PreviewView FILL_CENTER: scale to fill, then center-crop.
        val scale = max(size.width / bundle.imageWidth, size.height / bundle.imageHeight)
        val drawnWidth = bundle.imageWidth * scale
        val drawnHeight = bundle.imageHeight * scale
        val offsetX = (size.width - drawnWidth) / 2f
        val offsetY = (size.height - drawnHeight) / 2f

        fun NormalizedLandmark.toOffset() =
            Offset(x() * drawnWidth + offsetX, y() * drawnHeight + offsetY)

        val strokeWidth = 4.dp.toPx()
        val pointRadius = 5.dp.toPx()

        bundle.result.landmarks().forEach { landmarks ->
            PoseLandmarker.POSE_LANDMARKS.forEach { connection ->
                drawLine(
                    color = lineColor,
                    start = landmarks[connection.start()].toOffset(),
                    end = landmarks[connection.end()].toOffset(),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round,
                )
            }
            landmarks.forEach { landmark ->
                drawCircle(color = pointColor, radius = pointRadius, center = landmark.toOffset())
            }
        }
    }
}
