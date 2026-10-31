package com.example.myapplication.exercise

/** Simple moving average over the last [windowSize] samples. Not thread-safe. */
class MovingAverage(private val windowSize: Int) {

    init {
        require(windowSize >= 1) { "windowSize must be >= 1" }
    }

    private val samples = ArrayDeque<Float>(windowSize)
    private var sum = 0f

    /** Adds [value] and returns the average of the samples currently in the window. */
    fun add(value: Float): Float {
        samples.addLast(value)
        sum += value
        if (samples.size > windowSize) sum -= samples.removeFirst()
        return sum / samples.size
    }

    fun clear() {
        samples.clear()
        sum = 0f
    }
}
