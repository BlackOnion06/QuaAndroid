package com.example.myapplication.exercise

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.sqrt

/** Minimal 3D point so the analysis logic stays independent of MediaPipe types. */
data class Point3(val x: Float, val y: Float, val z: Float = 0f)

object JointAngles {

    /**
     * Angle at vertex [b] formed by the segments b→a and b→c, in degrees [0, 180].
     * Uses the dot product: θ = acos((BA · BC) / (|BA| |BC|)).
     * Returns `NaN` if either segment has (near) zero length.
     */
    fun angle3D(a: Point3, b: Point3, c: Point3): Float {
        val bax = a.x - b.x
        val bay = a.y - b.y
        val baz = a.z - b.z
        val bcx = c.x - b.x
        val bcy = c.y - b.y
        val bcz = c.z - b.z

        val dot = bax * bcx + bay * bcy + baz * bcz
        val magnitude = sqrt(bax * bax + bay * bay + baz * baz) * sqrt(bcx * bcx + bcy * bcy + bcz * bcz)
        if (magnitude < EPSILON) return Float.NaN

        val cosTheta = (dot / magnitude).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(cosTheta).toDouble()).toFloat()
    }

    /**
     * 2D angle at vertex [b] in the image plane (z ignored), in degrees [0, 180].
     * Uses the difference of the two segment headings: θ = |atan2(BC) − atan2(BA)|.
     */
    fun angle2D(a: Point3, b: Point3, c: Point3): Float {
        val radians = atan2(c.y - b.y, c.x - b.x) - atan2(a.y - b.y, a.x - b.x)
        var degrees = abs(Math.toDegrees(radians.toDouble())).toFloat()
        if (degrees > 180f) degrees = 360f - degrees
        return degrees
    }

    private const val EPSILON = 1e-6f
}
