package com.hoker.anonymizer.domain.audio

/**
 * How strongly someone is speaking over time, 0..1, sampled every [hopUs] starting at [startUs].
 * Rises on each syllable and falls in the gaps between them.
 */
class SpeechEnvelope(
    val startUs: Long,
    val hopUs: Long,
    val values: FloatArray,
) {
    /** Level at [timeUs], interpolated between samples; 0 outside the analysed range. */
    fun at(timeUs: Long): Float {
        if (values.isEmpty()) return 0f
        val position = (timeUs - startUs).toDouble() / hopUs
        if (position < 0 || position > values.size - 1) return 0f
        val index = position.toInt()
        val next = minOf(index + 1, values.size - 1)
        val t = (position - index).toFloat()
        return values[index] + (values[next] - values[index]) * t
    }

    companion object {
        val SILENT = SpeechEnvelope(0L, 10_000L, FloatArray(0))
    }
}
