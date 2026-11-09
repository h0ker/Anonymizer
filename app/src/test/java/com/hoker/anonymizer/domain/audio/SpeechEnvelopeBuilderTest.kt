package com.hoker.anonymizer.domain.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class SpeechEnvelopeBuilderTest {

    private val rate = 16_000

    @Test
    fun `silence gives a flat zero envelope`() {
        val envelope = build(FloatArray(rate * 2))
        assertTrue(envelope.values.all { it == 0f })
    }

    @Test
    fun `syllable-like bursts pulse on and off`() {
        val envelope = build(bursts(seconds = 4, burstHz = 4f, toneHz = 1000f))
        // Bursts are on for the first half of every 250 ms cycle, from 1 s to the end at 4 s.
        val onLevels = (1 until 11).map { envelope.at(1_000_000L + it * 250_000L + 100_000L) }
        val offLevels = (1 until 11).map { envelope.at(1_000_000L + it * 250_000L + 240_000L) }
        assertTrue("on: $onLevels", onLevels.all { it > 0.6f })
        assertTrue("off: $offLevels", offLevels.all { it < 0.35f })
        assertTrue("silent lead-in", envelope.at(500_000L) < 0.05f)
    }

    @Test
    fun `low rumble is filtered out and does not mask speech`() {
        val speech = bursts(seconds = 4, burstHz = 4f, toneHz = 1000f)
        val rumble = FloatArray(speech.size) { 0.4f * sin(2 * PI * 60 * it / rate).toFloat() }
        val envelope = build(FloatArray(speech.size) { speech[it] + rumble[it] })
        val on = envelope.at(2_000_000L + 100_000L)
        val off = envelope.at(2_000_000L + 240_000L)
        assertTrue("on $on vs off $off", on > 0.6f && off < 0.35f)
    }

    @Test
    fun `steady noise does not pulse`() {
        val random = Random(1)
        val envelope = build(FloatArray(rate * 3) { (random.nextFloat() * 2 - 1) * 0.2f })
        assertTrue(envelope.values.all { it < 0.05f })
    }

    @Test
    fun `chunked input matches a single call`() {
        val signal = bursts(seconds = 2, burstHz = 3f, toneHz = 800f)
        val whole = SpeechEnvelopeBuilder(rate).apply { add(signal) }.build()
        val chunked = SpeechEnvelopeBuilder(rate).apply {
            signal.toList().chunked(1234).forEach { add(it.toFloatArray()) }
        }.build()
        assertTrue(whole.values.contentEquals(chunked.values))
    }

    @Test
    fun `lookup interpolates and is zero outside the range`() {
        val envelope = SpeechEnvelope(startUs = 1_000L, hopUs = 10L, values = floatArrayOf(0f, 1f))
        assertEquals(0.5f, envelope.at(1_005L), 1e-4f)
        assertEquals(0f, envelope.at(0L), 0f)
        assertEquals(0f, envelope.at(2_000L), 0f)
    }

    private fun build(samples: FloatArray) = SpeechEnvelopeBuilder(rate).apply { add(samples) }.build()

    /** 1 s of silence, then a tone switched on for the first half of every cycle. */
    private fun bursts(seconds: Int, burstHz: Float, toneHz: Float): FloatArray {
        val cycle = (rate / burstHz).toInt()
        return FloatArray(rate * seconds) { i ->
            if (i < rate || (i - rate) % cycle >= cycle / 2) 0f else 0.5f * sin(2 * PI * toneHz * i / rate).toFloat()
        }
    }
}
