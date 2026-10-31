package com.example.myapplication.exercise

import com.example.myapplication.pose.PoseResultBundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

enum class SquatPhase { WAITING_FOR_START, UP, DESCENDING, DOWN, ASCENDING }

enum class FormIssue(val label: String) {
    INCOMPLETE_DEPTH("Incomplete Depth"),
    KNEES_CAVING("Knees Caving In"),
}

data class SquatMetrics(
    val repCount: Int = 0,
    val goodFormReps: Int = 0,
    /** Smoothed knee angle driving the state machine (average of visible knees). */
    val liveKneeAngle: Float? = null,
    val leftKneeAngle: Float? = null,
    val rightKneeAngle: Float? = null,
    /** Knee width / hip width; only computed when the user faces the camera. */
    val kneeToHipWidthRatio: Float? = null,
    val phase: SquatPhase = SquatPhase.WAITING_FOR_START,
    val feedback: String = "Step into the frame",
    val feedbackLevel: FeedbackLevel = FeedbackLevel.INFO,
    val activeIssues: Set<FormIssue> = emptySet(),
    val lastRepIssues: Set<FormIssue> = emptySet(),
)

data class SquatConfig(
    /** Knee angle at or above which the user is considered standing (no full lockout needed). */
    val upAngle: Float = 145f,
    /** Knee angle below which the rep has definitively reached depth. */
    val downAngle: Float = 100f,
    /** Drop below [upAngle] needed before a descent counts as a rep attempt (hysteresis). */
    val repStartMargin: Float = 10f,
    /** Rise above the lowest angle that marks the user turning back up. */
    val turnBackMargin: Float = 10f,
    /** Knee caving is only evaluated once the knee angle is below this. */
    val kneeCheckMaxAngle: Float = 145f,
    /** Knees narrower than this fraction of hip width are flagged as caving. */
    val kneeCaveRatio: Float = 0.8f,
    /** Consecutive frames required before caving is flagged (debounces jitter). */
    val kneeCaveFrames: Int = 3,
    /** Hip width / thigh length below this means a side view; knee check is skipped. */
    val minFrontalHipToThighRatio: Float = 0.2f,
    val minVisibility: Float = 0.5f,
    /** Number of frames in the knee-angle moving average (1 = no smoothing). */
    val smoothingWindow: Int = 5,
    /** Consecutive frames without visible legs tolerated before smoothing history is dropped. */
    val maxMissingFrames: Int = 5,
    /** Use MediaPipe world landmarks (metric 3D) for angles; falls back to 2D image coords. */
    val useWorldLandmarks: Boolean = true,
)

/**
 * Counts bodyweight squats and checks form from pose landmarks.
 *
 * Rep state machine: WAITING_FOR_START → UP → DESCENDING → DOWN → ASCENDING → UP.
 * Dropping below [SquatConfig.downAngle] latches depth for the current rep; the rep is counted
 * as soon as the smoothed knee angle returns to [SquatConfig.upAngle], regardless of any jitter
 * between the intermediate phases. Returning up without depth flags [FormIssue.INCOMPLETE_DEPTH].
 *
 * Thread-safe: [analyze] is called from the MediaPipe result thread, [reset] from the UI.
 */
class SquatAnalyzer(private val config: SquatConfig = SquatConfig()) : ExerciseAnalyzer {

    private val _metrics = MutableStateFlow(SquatMetrics())
    val metrics: StateFlow<SquatMetrics> = _metrics.asStateFlow()

    private var phase = SquatPhase.WAITING_FOR_START
    private var repCount = 0
    private var goodFormReps = 0
    private val angleFilter = MovingAverage(config.smoothingWindow)
    private var missingFrames = 0

    // Per-rep tracking.
    private var minAngleThisRep = 180f
    private var reachedDepth = false
    private var kneesCavedThisRep = false
    private var cavingFrameStreak = 0
    private var lastRepIssues: Set<FormIssue> = emptySet()
    private var lastRepMessage: Pair<String, FeedbackLevel>? = null

    override fun process(bundle: PoseResultBundle) {
        analyze(bundle.toPoseFrame())
    }

    @Synchronized
    fun analyze(frame: PoseFrame?): SquatMetrics {
        val left = frame?.kneeAngle(LEFT_HIP, LEFT_KNEE, LEFT_ANKLE)
        val right = frame?.kneeAngle(RIGHT_HIP, RIGHT_KNEE, RIGHT_ANKLE)
        val raw = listOfNotNull(left, right).takeIf { it.isNotEmpty() }?.average()?.toFloat()

        if (frame == null || raw == null) {
            // Ride out brief dropouts (common at the bottom of a squat) without touching any state.
            if (++missingFrames <= config.maxMissingFrames) return _metrics.value
            // Sustained loss: keep counts and phase, drop the smoothing history.
            angleFilter.clear()
            return publish(
                SquatMetrics(
                    repCount = repCount,
                    goodFormReps = goodFormReps,
                    phase = phase,
                    feedback = "Step back so your hips, knees and ankles are visible",
                    feedbackLevel = FeedbackLevel.WARNING,
                    lastRepIssues = lastRepIssues,
                )
            )
        }

        missingFrames = 0
        val angle = angleFilter.add(raw)

        val kneeRatio = frame.kneeToHipWidthRatio()
        val inRep = phase == SquatPhase.DESCENDING || phase == SquatPhase.DOWN || phase == SquatPhase.ASCENDING
        val cavingNow = inRep && angle < config.kneeCheckMaxAngle &&
            kneeRatio != null && kneeRatio < config.kneeCaveRatio
        cavingFrameStreak = if (cavingNow) cavingFrameStreak + 1 else 0
        val cavingConfirmed = cavingFrameStreak >= config.kneeCaveFrames
        if (cavingConfirmed) kneesCavedThisRep = true

        updatePhase(angle)

        val activeIssues = buildSet {
            if (cavingConfirmed) add(FormIssue.KNEES_CAVING)
            if (phase == SquatPhase.ASCENDING && !reachedDepth) add(FormIssue.INCOMPLETE_DEPTH)
        }
        val (feedback, level) = liveFeedback(cavingConfirmed)

        return publish(
            SquatMetrics(
                repCount = repCount,
                goodFormReps = goodFormReps,
                liveKneeAngle = angle,
                leftKneeAngle = left,
                rightKneeAngle = right,
                kneeToHipWidthRatio = kneeRatio,
                phase = phase,
                feedback = feedback,
                feedbackLevel = level,
                activeIssues = activeIssues,
                lastRepIssues = lastRepIssues,
            )
        )
    }

    @Synchronized
    override fun reset() {
        phase = SquatPhase.WAITING_FOR_START
        repCount = 0
        goodFormReps = 0
        angleFilter.clear()
        missingFrames = 0
        lastRepIssues = emptySet()
        lastRepMessage = null
        resetRepTracking()
        _metrics.value = SquatMetrics()
    }

    private fun updatePhase(angle: Float) {
        when (phase) {
            SquatPhase.WAITING_FOR_START -> {
                if (angle >= config.upAngle) phase = SquatPhase.UP
                return
            }
            SquatPhase.UP -> {
                if (angle >= config.upAngle - config.repStartMargin) return
                resetRepTracking()
                // Fall through so a fast drop straight to depth registers on this same frame.
            }
            else -> Unit
        }

        minAngleThisRep = min(minAngleThisRep, angle)
        // Depth latch: stays set until the rep finishes, so jitter after the bottom can't lose it.
        if (angle < config.downAngle) reachedDepth = true

        phase = when {
            angle >= config.upAngle -> {
                finishRep()
                SquatPhase.UP
            }
            angle < config.downAngle -> SquatPhase.DOWN
            // Depth already latched: anything between DOWN and UP is the way back up.
            reachedDepth -> SquatPhase.ASCENDING
            angle > minAngleThisRep + config.turnBackMargin -> SquatPhase.ASCENDING
            else -> SquatPhase.DESCENDING
        }
    }

    private fun finishRep() {
        val issues = buildSet {
            if (!reachedDepth) add(FormIssue.INCOMPLETE_DEPTH)
            if (kneesCavedThisRep) add(FormIssue.KNEES_CAVING)
        }
        if (reachedDepth) {
            repCount++
            if (issues.isEmpty()) goodFormReps++
        }
        lastRepIssues = issues
        lastRepMessage = when {
            !reachedDepth -> "Incomplete Depth – rep not counted" to FeedbackLevel.WARNING
            kneesCavedThisRep -> "Rep counted – keep your knees out" to FeedbackLevel.WARNING
            else -> "Good Form!" to FeedbackLevel.GOOD
        }
        resetRepTracking()
    }

    private fun resetRepTracking() {
        minAngleThisRep = 180f
        reachedDepth = false
        kneesCavedThisRep = false
        cavingFrameStreak = 0
    }

    private fun liveFeedback(caving: Boolean): Pair<String, FeedbackLevel> = when (phase) {
        SquatPhase.WAITING_FOR_START -> "Stand up straight to begin" to FeedbackLevel.INFO
        SquatPhase.UP -> lastRepMessage ?: ("Ready – start squatting" to FeedbackLevel.INFO)
        SquatPhase.DESCENDING ->
            if (caving) "Push Knees Out" to FeedbackLevel.WARNING else "Go Lower" to FeedbackLevel.INFO
        SquatPhase.DOWN ->
            if (caving) "Push Knees Out" to FeedbackLevel.WARNING else "Good depth – drive up" to FeedbackLevel.GOOD
        SquatPhase.ASCENDING -> when {
            !reachedDepth -> "Incomplete Depth – go lower" to FeedbackLevel.WARNING
            caving -> "Push Knees Out" to FeedbackLevel.WARNING
            else -> "Good Form" to FeedbackLevel.GOOD
        }
    }

    private fun publish(metrics: SquatMetrics): SquatMetrics {
        _metrics.value = metrics
        return metrics
    }

    private fun PoseFrame.isVisible(vararg indices: Int) =
        indices.all { visibility.getOrElse(it) { 0f } >= config.minVisibility }

    private fun PoseFrame.kneeAngle(hip: Int, knee: Int, ankle: Int): Float? {
        if (image.size <= ankle || !isVisible(hip, knee, ankle)) return null
        val points = world?.takeIf { config.useWorldLandmarks && it.size > ankle }
        val angle = if (points != null) {
            JointAngles.angle3D(points[hip], points[knee], points[ankle])
        } else {
            JointAngles.angle2D(image[hip], image[knee], image[ankle])
        }
        return angle.takeUnless { it.isNaN() }
    }

    /**
     * Horizontal knee separation relative to hip separation in the image. Returns null when the
     * user is side-on to the camera (hips overlap), where the ratio is meaningless.
     */
    private fun PoseFrame.kneeToHipWidthRatio(): Float? {
        if (image.size <= RIGHT_KNEE || !isVisible(LEFT_HIP, RIGHT_HIP, LEFT_KNEE, RIGHT_KNEE)) return null
        val hipWidth = abs(image[LEFT_HIP].x - image[RIGHT_HIP].x)
        val thighLength = (image[LEFT_HIP].distance2D(image[LEFT_KNEE]) +
            image[RIGHT_HIP].distance2D(image[RIGHT_KNEE])) / 2f
        if (thighLength <= 0f || hipWidth / thighLength < config.minFrontalHipToThighRatio) return null
        return abs(image[LEFT_KNEE].x - image[RIGHT_KNEE].x) / hipWidth
    }

    private fun Point3.distance2D(other: Point3) = hypot(x - other.x, y - other.y)

    companion object {
        const val LEFT_HIP = PoseLandmarks.LEFT_HIP
        const val RIGHT_HIP = PoseLandmarks.RIGHT_HIP
        const val LEFT_KNEE = PoseLandmarks.LEFT_KNEE
        const val RIGHT_KNEE = PoseLandmarks.RIGHT_KNEE
        const val LEFT_ANKLE = PoseLandmarks.LEFT_ANKLE
        const val RIGHT_ANKLE = PoseLandmarks.RIGHT_ANKLE
    }
}
