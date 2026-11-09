package com.hoker.anonymizer.domain.audio.voice

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/**
 * Real-time pitch shifter using two crossfaded read heads on a delay line.
 *
 * Each head's delay sweeps steadily, which plays the audio back slightly faster or slower and so
 * shifts the pitch. When one head reaches the end of its sweep it jumps back, hidden by a
 * crossfade to the other head. Short windows give a slightly metallic, robotic texture, which
 * suits a machine voice.
 */
internal class PitchShifter(semitones: Float, sampleRate: Int, windowMs: Float = 40f) {

    private val ratio = 2.0.pow(semitones / 12.0).toFloat()
    private val window = sampleRate * windowMs / 1000f
    private val buffer = FloatArray(Integer.highestOneBit((window * 2).toInt() + 4) shl 1)
    private val mask = buffer.size - 1
    private var writeIndex = 0
    private var phase = 0f
    private val phaseStep = (1f - ratio) / window

    fun process(x: Float): Float {
        buffer[writeIndex] = x
        phase = wrap(phase + phaseStep)
        val other = wrap(phase + 0.5f)
        // Hann crossfade: the two weights always add up to 1.
        val g1 = sin(PI.toFloat() * phase).let { it * it }
        val g2 = 1f - g1
        val y = read(phase * window) * g1 + read(other * window) * g2
        writeIndex = (writeIndex + 1) and mask
        return y
    }

    /** Reads [delay] samples back from the newest sample, with linear interpolation. */
    private fun read(delay: Float): Float {
        val position = writeIndex - 1 - delay
        val base = kotlin.math.floor(position).toInt()
        val t = position - base
        val a = buffer[base and mask]
        val b = buffer[(base + 1) and mask]
        return a + (b - a) * t
    }

    private fun wrap(p: Float): Float {
        var v = p % 1f
        if (v < 0f) v += 1f
        return v
    }
}
