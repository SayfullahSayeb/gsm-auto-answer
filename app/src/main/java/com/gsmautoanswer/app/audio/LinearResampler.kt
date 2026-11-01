package com.gsmautoanswer.app.audio

/**
 * Streaming mono linear-interpolation resampler.
 *
 * Keeps only one sample of state, so decoder output can be pushed in arbitrary chunk sizes.
 */
class LinearResampler(
    private val sourceRate: Int,
    private val targetRate: Int = 16_000,
) {
    init {
        require(sourceRate > 0) { "sourceRate must be positive" }
        require(targetRate > 0) { "targetRate must be positive" }
    }

    private val step = sourceRate.toDouble() / targetRate.toDouble()
    private var previous = 0f
    private var previousIndex = 0L
    private var currentIndex = 0L
    private var nextOutputPosition = 0.0
    private var hasSample = false

    fun reset() {
        previous = 0f
        previousIndex = 0L
        currentIndex = 0L
        nextOutputPosition = 0.0
        hasSample = false
    }

    fun push(sample: Float, emit: (Float) -> Unit) {
        if (!hasSample) {
            hasSample = true
            previous = sample
            previousIndex = 0L
            currentIndex = 0L
            emit(sample)
            nextOutputPosition = 1.0
            return
        }

        currentIndex++
        while (nextOutputPosition * step <= currentIndex.toDouble()) {
            val position = nextOutputPosition * step
            val fraction = (position - previousIndex.toDouble()).toFloat().coerceIn(0f, 1f)
            emit(previous + (sample - previous) * fraction)
            nextOutputPosition += 1.0
        }
        previous = sample
        previousIndex = currentIndex
    }
}
