package com.hoker.anonymizer.domain.audio

import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Builds a [SpeechEnvelope] from mono audio fed in chunks, so a long video never has to be held
 * in memory as raw samples.
 *
 * 1. **Band-pass** 250–3500 Hz: where voices carry most of their energy. Rumble, hum and hiss are cut.
 * 2. **Loudness** every 10 ms (RMS, in decibels).
 * 3. **Normalise** against this file's own noise floor and loud peaks, so quiet and loud
 *    recordings both use the full 0..1 range.
 * 4. **Gate and smooth:** background noise is pushed to 0, then a fast attack and slower release
 *    make each syllable read as a pulse.
 *
 * This measures speech-band loudness, so loud music or noise in that range will pulse too.
 */
class SpeechEnvelopeBuilder(private val sampleRate: Int, private val startUs: Long = 0L) {

    private val hopSamples = (sampleRate / HOPS_PER_SECOND).coerceAtLeast(1)
    private val highPass = Biquad.highPass(sampleRate, LOW_CUT_HZ)
    private val lowPass = Biquad.lowPass(sampleRate, HIGH_CUT_HZ)

    private var levels = FloatArray(1024)
    private var levelCount = 0
    private var sumSquares = 0.0
    private var samplesInHop = 0

    /** Adds [count] mono samples in the range -1..1. */
    fun add(samples: FloatArray, count: Int = samples.size) {
        for (i in 0 until count) {
            var s = highPass.process(samples[i])
            if (lowPass != null) s = lowPass.process(s)
            sumSquares += s * s
            if (++samplesInHop == hopSamples) {
                pushLevel(sqrt(sumSquares / hopSamples).toFloat())
                sumSquares = 0.0
                samplesInHop = 0
            }
        }
    }

    fun build(): SpeechEnvelope {
        if (levelCount == 0) return SpeechEnvelope.SILENT
        val decibels = FloatArray(levelCount) { (20 * log10(levels[it] + 1e-9f)) }
        val sorted = decibels.sortedArray()
        val floor = sorted[(levelCount * FLOOR_PERCENTILE).toInt()]
        val ceiling = sorted[((levelCount - 1) * CEILING_PERCENTILE).toInt()]
        // Silence or a steady drone: nothing rises above the background, so nothing pulses.
        if (ceiling - floor < MIN_DYNAMIC_RANGE_DB || ceiling < SILENCE_DB) {
            return SpeechEnvelope(startUs, HOP_US, FloatArray(levelCount))
        }

        val hopSeconds = 1f / HOPS_PER_SECOND
        val attack = exp(-hopSeconds / ATTACK_SECONDS)
        val release = exp(-hopSeconds / RELEASE_SECONDS)
        var smoothed = 0f
        val values = FloatArray(levelCount) { i ->
            val level = ((decibels[i] - floor) / (ceiling - floor)).coerceIn(0f, 1f)
            val gated = ((level - GATE) / (1f - GATE)).coerceIn(0f, 1f)
            val coefficient = if (gated > smoothed) attack else release
            smoothed = gated + (smoothed - gated) * coefficient
            smoothed
        }
        return SpeechEnvelope(startUs, HOP_US, values)
    }

    private fun pushLevel(level: Float) {
        if (levelCount == levels.size) levels = levels.copyOf(levels.size * 2)
        levels[levelCount++] = level
    }


    private companion object {
        const val HOPS_PER_SECOND = 100
        const val HOP_US = 1_000_000L / HOPS_PER_SECOND
        const val LOW_CUT_HZ = 250f
        const val HIGH_CUT_HZ = 3500f
        const val FLOOR_PERCENTILE = 0.2f
        const val CEILING_PERCENTILE = 0.97f
        const val MIN_DYNAMIC_RANGE_DB = 9f
        const val SILENCE_DB = -60f
        const val GATE = 0.3f
        const val ATTACK_SECONDS = 0.02f
        const val RELEASE_SECONDS = 0.08f
    }
}
