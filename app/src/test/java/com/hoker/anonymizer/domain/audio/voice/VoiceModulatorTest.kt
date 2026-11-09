package com.hoker.anonymizer.domain.audio.voice

import com.hoker.anonymizer.domain.model.VoiceStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

class VoiceModulatorTest {

    private val rate = 48_000

    @Test
    fun `every style stays in range and never produces NaN`() {
        val random = Random(4)
        // Loud speech-like signal: a 150 Hz voice with harmonics and noise, switched on and off.
        val input = FloatArray(rate * 3) { i ->
            val on = (i / (rate / 4)) % 2 == 0
            if (!on) 0f else (0.6f * sin(2 * PI * 150 * i / rate) + 0.3f * sin(2 * PI * 450 * i / rate)).toFloat() + random.nextFloat() * 0.1f
        }
        for (style in VoiceStyle.entries) {
            val output = run(style, input)
            assertTrue("$style has NaN", output.none { it.isNaN() })
            assertTrue("$style out of range", output.all { it in -1f..1f })
            assertTrue("$style is silent", output.maxOf { abs(it) } > 0.05f)
        }
    }

    @Test
    fun `silence stays silent`() {
        for (style in VoiceStyle.entries) {
            val output = run(style, FloatArray(rate * 2))
            assertTrue("$style", output.all { abs(it) < 1e-4f })
        }
    }

    @Test
    fun `chunked processing matches one pass`() {
        val input = FloatArray(rate) { (0.5 * sin(2 * PI * 220 * it / rate)).toFloat() }
        for (style in VoiceStyle.entries) {
            val whole = run(style, input)
            val modulator = VoiceModulator(style, rate)
            val chunked = FloatArray(input.size)
            var offset = 0
            for (chunk in generateSequence { 777 }.take(input.size / 777 + 1)) {
                val n = minOf(chunk, input.size - offset)
                if (n <= 0) break
                val out = FloatArray(n)
                modulator.process(input.copyOfRange(offset, offset + n), out, n)
                out.copyInto(chunked, offset)
                offset += n
            }
            assertTrue("$style", whole.contentEquals(chunked))
        }
    }

    @Test
    fun `pitch shifter moves a tone by the requested interval`() {
        val input = FloatArray(rate) { sin(2 * PI * 200 * it / rate).toFloat() }
        val up = PitchShifter(12f, rate).let { s -> FloatArray(input.size) { s.process(input[it]) } }
        val down = PitchShifter(-12f, rate).let { s -> FloatArray(input.size) { s.process(input[it]) } }
        // Ignore the first 0.1 s while the delay line fills.
        assertEquals(400.0, dominantFrequency(up, from = rate / 10), 25.0)
        assertEquals(100.0, dominantFrequency(down, from = rate / 10), 15.0)
    }

    private fun run(style: VoiceStyle, input: FloatArray): FloatArray {
        val output = FloatArray(input.size)
        VoiceModulator(style, rate).process(input, output)
        return output
    }

    /** Strongest frequency between 50 and 1000 Hz, by correlating with test tones. */
    private fun dominantFrequency(signal: FloatArray, from: Int): Double {
        return (50..1000 step 5).maxBy { hz ->
            var re = 0.0
            var im = 0.0
            for (i in from until signal.size) {
                val angle = 2 * PI * hz * i / rate
                re += signal[i] * kotlin.math.cos(angle)
                im += signal[i] * sin(angle)
            }
            re * re + im * im
        }.toDouble()
    }
}
