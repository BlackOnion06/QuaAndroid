package com.example.myapplication.pose

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult

/**
 * Wraps a MediaPipe Tasks [PoseLandmarker] running in [RunningMode.LIVE_STREAM].
 *
 * [setup], [detectLiveStream] and [close] are expected to be called from the same
 * single background thread (the CameraX analysis executor). Results are delivered
 * asynchronously on a MediaPipe-owned thread through [Listener].
 */
class PoseLandmarkerHelper(
    private val context: Context,
    private val listener: Listener,
    private val modelAssetPath: String = MODEL_POSE_LANDMARKER_FULL,
    private val numPoses: Int = 1,
    private val minPoseDetectionConfidence: Float = DEFAULT_CONFIDENCE,
    private val minPosePresenceConfidence: Float = DEFAULT_CONFIDENCE,
    private val minTrackingConfidence: Float = DEFAULT_CONFIDENCE,
) {

    interface Listener {
        fun onResults(bundle: PoseResultBundle)
        fun onError(message: String, throwable: Throwable? = null)
    }

    private val lock = Any()
    private var poseLandmarker: PoseLandmarker? = null
    private var lastTimestampMs = 0L

    /** Delegate actually in use after [setup]; GPU unless it failed and we fell back to CPU. */
    @Volatile
    var activeDelegate: Delegate? = null
        private set

    /** Set when the front camera is active so frames are mirrored to match the preview. */
    @Volatile
    var isFrontCamera: Boolean = false

    /** Creates the landmarker, preferring the GPU delegate and falling back to CPU. */
    fun setup() = synchronized(lock) {
        if (poseLandmarker != null) return@synchronized
        poseLandmarker = try {
            createLandmarker(Delegate.GPU).also { activeDelegate = Delegate.GPU }
        } catch (e: RuntimeException) {
            Log.w(TAG, "GPU delegate unavailable, falling back to CPU", e)
            try {
                createLandmarker(Delegate.CPU).also { activeDelegate = Delegate.CPU }
            } catch (e: RuntimeException) {
                listener.onError("Pose landmarker failed to initialize: ${e.message}", e)
                null
            }
        }
    }

    private fun createLandmarker(delegate: Delegate): PoseLandmarker {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(modelAssetPath)
            .setDelegate(delegate)
            .build()
        val options = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumPoses(numPoses)
            .setMinPoseDetectionConfidence(minPoseDetectionConfidence)
            .setMinPosePresenceConfidence(minPosePresenceConfidence)
            .setMinTrackingConfidence(minTrackingConfidence)
            .setResultListener(::onLiveStreamResult)
            .setErrorListener { error -> listener.onError(error.message ?: "Unknown MediaPipe error", error) }
            .build()
        return PoseLandmarker.createFromOptions(context, options)
    }

    /**
     * Converts a CameraX [ImageProxy] (RGBA_8888) to an upright [MPImage] and submits it
     * for asynchronous detection. Always closes [imageProxy].
     */
    fun detectLiveStream(imageProxy: ImageProxy) {
        val bitmap = imageProxy.use { it.toUprightBitmap(mirror = isFrontCamera) }
        val mpImage: MPImage = BitmapImageBuilder(bitmap).build()

        synchronized(lock) {
            val landmarker = poseLandmarker ?: return
            // LIVE_STREAM mode requires strictly increasing timestamps.
            val timestampMs = SystemClock.uptimeMillis().coerceAtLeast(lastTimestampMs + 1)
            lastTimestampMs = timestampMs
            try {
                landmarker.detectAsync(mpImage, timestampMs)
            } catch (e: RuntimeException) {
                listener.onError("Pose detection failed: ${e.message}", e)
            }
        }
    }

    private fun onLiveStreamResult(result: PoseLandmarkerResult, input: MPImage) {
        listener.onResults(
            PoseResultBundle(
                result = result,
                imageWidth = input.width,
                imageHeight = input.height,
                inferenceTimeMs = SystemClock.uptimeMillis() - result.timestampMs(),
            )
        )
    }

    fun close() = synchronized(lock) {
        poseLandmarker?.close()
        poseLandmarker = null
    }

    private fun ImageProxy.toUprightBitmap(mirror: Boolean): Bitmap {
        val source = toBitmap()
        val rotation = imageInfo.rotationDegrees
        if (rotation == 0 && !mirror) return source

        val matrix = Matrix().apply {
            postRotate(rotation.toFloat())
            // Mirror horizontally (in display space) so landmarks line up with the mirrored front preview.
            if (mirror) postScale(-1f, 1f)
        }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
            .also { if (it !== source) source.recycle() }
    }

    companion object {
        private const val TAG = "PoseLandmarkerHelper"
        const val MODEL_POSE_LANDMARKER_FULL = "pose_landmarker_full.task"
        const val DEFAULT_CONFIDENCE = 0.5f
    }
}

/** A single detection result plus the dimensions of the upright frame it was computed on. */
data class PoseResultBundle(
    val result: PoseLandmarkerResult,
    val imageWidth: Int,
    val imageHeight: Int,
    val inferenceTimeMs: Long,
)
