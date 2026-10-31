package com.example.myapplication.exercise

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import kotlin.math.cos
import kotlin.math.sin

@RunWith(JUnit4::class)
class SquatAnalyzerTest {

    private val analyzer = SquatAnalyzer()

    @Test
    fun angle3D_rightAngle() {
        val angle = JointAngles.angle3D(Point3(0f, -1f), Point3(0f, 0f), Point3(1f, 0f))
        assertEquals(90f, angle, 0.01f)
    }

    @Test
    fun angle2D_straightLeg() {
        val angle = JointAngles.angle2D(Point3(0f, -1f), Point3(0f, 0f), Point3(0f, 1f))
        assertEquals(180f, angle, 0.01f)
    }

    @Test
    fun fullSquat_countsGoodRep() {
        doRep(bottomAngle = 85f)

        val metrics = analyzer.metrics.value
        assertEquals(1, metrics.repCount)
        assertEquals(1, metrics.goodFormReps)
        assertEquals(SquatPhase.UP, metrics.phase)
        assertTrue(metrics.lastRepIssues.isEmpty())
    }

    @Test
    fun shallowSquat_isNotCountedAndFlagsIncompleteDepth() {
        doRep(bottomAngle = 120f)

        val metrics = analyzer.metrics.value
        assertEquals(0, metrics.repCount)
        assertEquals(setOf(FormIssue.INCOMPLETE_DEPTH), metrics.lastRepIssues)
    }

    @Test
    fun turningBackEarly_givesLiveIncompleteDepthFeedback() {
        feed(175f)
        feed(120f)
        val metrics = feed(140f)

        assertEquals(SquatPhase.ASCENDING, metrics.phase)
        assertTrue(FormIssue.INCOMPLETE_DEPTH in metrics.activeIssues)
    }

    @Test
    fun kneesCaving_isFlaggedButRepStillCounts() {
        feed(175f)
        val atBottom = feed(85f, kneeWidthFactor = 0.5f)
        assertEquals("Push Knees Out", atBottom.feedback)
        feed(175f)

        val metrics = analyzer.metrics.value
        assertEquals(1, metrics.repCount)
        assertEquals(0, metrics.goodFormReps)
        assertEquals(setOf(FormIssue.KNEES_CAVING), metrics.lastRepIssues)
    }

    @Test
    fun squattingBeforeStandingUp_isNotCounted() {
        feed(85f)
        feed(175f)

        assertEquals(0, analyzer.metrics.value.repCount)
    }

    @Test
    fun sustainedTrackingLoss_keepsCountAndAsksToStepBack() {
        doRep(bottomAngle = 85f)
        var metrics = analyzer.metrics.value
        repeat(6) { metrics = analyzer.analyze(null) }

        assertEquals(1, metrics.repCount)
        assertEquals(null, metrics.liveKneeAngle)
        assertEquals(FeedbackLevel.WARNING, metrics.feedbackLevel)
    }

    @Test
    fun standingWithoutFullLockout_countsRep() {
        feed(150f)
        feed(90f)
        feed(150f)

        assertEquals(1, analyzer.metrics.value.repCount)
    }

    @Test
    fun briefDropoutAtBottom_stillCountsRep() {
        feed(175f)
        feed(90f)
        repeat(3) { analyzer.analyze(null) }
        feed(175f)

        assertEquals(1, analyzer.metrics.value.repCount)
    }

    @Test
    fun singleFrameGlitch_isSmoothedOut() {
        feed(175f)
        analyzer.analyze(frame(kneeAngle = 60f, kneeWidthFactor = 1f))
        feed(175f)

        val metrics = analyzer.metrics.value
        assertEquals(0, metrics.repCount)
        assertTrue(metrics.lastRepIssues.isEmpty())
    }

    @Test
    fun fastRep_withFewFramesPerPhase_countsRep() {
        feed(175f, frames = 5)
        feed(90f, frames = 5)
        feed(175f, frames = 5)

        assertEquals(1, analyzer.metrics.value.repCount)
    }

    @Test
    fun jitterAfterDepth_keepsDepthLatched() {
        feed(175f)
        feed(90f)
        feed(115f, frames = 3)
        feed(105f, frames = 3)
        feed(125f, frames = 3)
        feed(175f)

        val metrics = analyzer.metrics.value
        assertEquals(1, metrics.repCount)
        assertTrue(FormIssue.INCOMPLETE_DEPTH !in metrics.lastRepIssues)
    }

    private fun doRep(bottomAngle: Float) {
        feed(175f)
        feed(bottomAngle)
        feed(175f)
    }

    /** Feeds enough identical frames for the smoothed angle to converge. */
    private fun feed(kneeAngle: Float, kneeWidthFactor: Float = 1f, frames: Int = 10): SquatMetrics {
        var metrics = analyzer.metrics.value
        repeat(frames) { metrics = analyzer.analyze(frame(kneeAngle, kneeWidthFactor)) }
        return metrics
    }

    /**
     * Front-facing pose with both knees bent to [kneeAngle]. World landmarks drive the angle;
     * image knee x positions are pulled toward the midline by [kneeWidthFactor] to simulate caving.
     */
    private fun frame(kneeAngle: Float, kneeWidthFactor: Float): PoseFrame {
        val theta = Math.toRadians(kneeAngle.toDouble())
        val shinX = sin(theta).toFloat()
        val shinY = -cos(theta).toFloat()

        fun leg(hipX: Float, kneeX: Float) = Triple(
            Point3(hipX, 0f),
            Point3(kneeX, 1f),
            Point3(kneeX + shinX, 1f + shinY),
        )

        val (lHip, lKnee, lAnkle) = leg(-0.5f, -0.5f)
        val (rHip, rKnee, rAnkle) = leg(0.5f, 0.5f)
        val world = MutableList(33) { Point3(0f, 0f) }.apply {
            this[SquatAnalyzer.LEFT_HIP] = lHip
            this[SquatAnalyzer.LEFT_KNEE] = lKnee
            this[SquatAnalyzer.LEFT_ANKLE] = lAnkle
            this[SquatAnalyzer.RIGHT_HIP] = rHip
            this[SquatAnalyzer.RIGHT_KNEE] = rKnee
            this[SquatAnalyzer.RIGHT_ANKLE] = rAnkle
        }
        val image = world.toMutableList().apply {
            this[SquatAnalyzer.LEFT_KNEE] = lKnee.copy(x = lKnee.x * kneeWidthFactor)
            this[SquatAnalyzer.RIGHT_KNEE] = rKnee.copy(x = rKnee.x * kneeWidthFactor)
        }
        return PoseFrame(image = image, world = world, visibility = List(33) { 1f })
    }
}
