package com.hoker.anonymizer.domain.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Second-order IIR filter (RBJ audio EQ cookbook), Butterworth Q. Keeps state between calls. */
internal class Biquad private constructor(
    private val b0: Float, private val b1: Float, private val b2: Float,
    private val a1: Float, private val a2: Float,
) {
    private var x1 = 0f
    private var x2 = 0f
    private var y1 = 0f
    private var y2 = 0f

    fun process(x: Float): Float {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x
        y2 = y1; y1 = y
        return y
    }

    companion object {
        private const val Q = 0.7071

        fun highPass(sampleRate: Int, cutoffHz: Float) = create(sampleRate, cutoffHz, highPass = true)

        /** Returns null when the cutoff is at or above Nyquist, where a low-pass would do nothing. */
        fun lowPass(sampleRate: Int, cutoffHz: Float) =
            if (cutoffHz >= sampleRate / 2f) null else create(sampleRate, cutoffHz, highPass = false)

        private fun create(sampleRate: Int, cutoffHz: Float, highPass: Boolean): Biquad {
            val w0 = 2 * PI * cutoffHz / sampleRate
            val alpha = sin(w0) / (2 * Q)
            val cosW0 = cos(w0)
            val a0 = 1 + alpha
            val b0 = if (highPass) (1 + cosW0) / 2 else (1 - cosW0) / 2
            val b1 = if (highPass) -(1 + cosW0) else 1 - cosW0
            return Biquad(
                b0 = (b0 / a0).toFloat(),
                b1 = (b1 / a0).toFloat(),
                b2 = (b0 / a0).toFloat(),
                a1 = (-2 * cosW0 / a0).toFloat(),
                a2 = ((1 - alpha) / a0).toFloat(),
            )
        }
    }
}
