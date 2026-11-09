package com.hoker.anonymizer.domain.audio.voice

import com.hoker.anonymizer.domain.audio.Biquad
import com.hoker.anonymizer.domain.model.VoiceStyle
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.random.Random

/**
 * Disguises a voice in real time, one channel of float samples (-1..1) at a time. Keeps state
 * between calls, so audio can be fed in chunks of any size.
 *
 * Signal chain:
 *  1. **Pitch layers:** several pitch-shifted copies mixed together. The speaker's own pitch is
 *     never one of them, which is what disguises the voice.
 *  2. **Comb filter:** a very short feedback delay that rings at one pitch, adding a metallic edge.
 *  3. **Ring modulation:** multiplying by a low tone, for the classic machine buzz.
 *  4. **Drive:** tanh saturation for grit, which also evens out loud and quiet words.
 *  5. **Bit crush:** coarser samples and sample-and-hold for a degraded digital sound (some styles).
 *  6. **Band limit:** cuts rumble and harsh highs that distortion creates, keeping speech clear.
 *  7. **Stutter:** now and then, while someone is talking, repeats the last syllable fragment a
 *     few times. It's mixed under the live voice rather than replacing it, so no words are lost.
 *
 * Feed separate instances with the same [seed] for each channel to keep stutters in sync.
 */
class VoiceModulator(style: VoiceStyle, private val sampleRate: Int, seed: Int = 1) {

    private val preset = VoicePreset.of(style)
    private val random = Random(seed)

    private val layers = preset.layers.map { (semitones, gain) -> PitchShifter(semitones, sampleRate) to gain }

    private val combLength = (preset.combDelayMs * sampleRate / 1000f).roundToInt().coerceAtLeast(1)
    private val comb = FloatArray(combLength)
    private var combIndex = 0

    private var ringPhase = 0f
    private val ringStep = (2 * PI * preset.ringHz / sampleRate).toFloat()
    private val driveNorm = tanh(preset.drive)

    private var heldSample = 0f
    private var holdCounter = 0
    private val crushSteps = if (preset.bits > 0) (1 shl (preset.bits - 1)).toFloat() else 0f

    private val highPass = Biquad.highPass(sampleRate, preset.highPassHz)
    private val lowPass = Biquad.lowPass(sampleRate, preset.lowPassHz)

    // Stutter state.
    private val history = FloatArray(Integer.highestOneBit(sampleRate / 2) shl 1)
    private val historyMask = history.size - 1
    private var historyIndex = 0
    private var envelope = 0f
    private val envelopeRelease = 1f - 1f / (0.05f * sampleRate)
    private var samplesUntilStutter = nextStutterGap()
    private var stutterStart = 0
    private var stutterLength = 0
    private var stutterPosition = 0
    private var stutterRemaining = 0
    private val stutterFade = (0.005f * sampleRate).toInt().coerceAtLeast(1)

    fun process(input: FloatArray, output: FloatArray, count: Int = input.size) {
        for (i in 0 until count) output[i] = processSample(input[i])
    }

    private fun processSample(x: Float): Float {
        // 1. Pitch layers.
        var y = 0f
        for ((shifter, gain) in layers) y += shifter.process(x) * gain

        // 2. Comb: resonance scaled by (1 - feedback) so it doesn't get louder than the input.
        val delayed = comb[combIndex]
        val combed = y + preset.combFeedback * delayed
        comb[combIndex] = combed
        combIndex = (combIndex + 1) % combLength
        y += (combed * (1f - preset.combFeedback) - y) * preset.combMix

        // 3. Ring modulation, mixed so the voice underneath stays readable.
        if (preset.ringMix > 0f) {
            ringPhase += ringStep
            if (ringPhase > 2 * PI) ringPhase -= (2 * PI).toFloat()
            y += (y * sin(ringPhase) - y) * preset.ringMix
        }

        // 4. Drive.
        y = tanh(y * preset.drive) / driveNorm

        // 5. Bit crush.
        if (preset.decimate > 1) {
            if (holdCounter == 0) heldSample = y
            holdCounter = (holdCounter + 1) % preset.decimate
            y = heldSample
        }
        if (crushSteps > 0f) y = (y * crushSteps).roundToInt() / crushSteps

        // 6. Band limit.
        y = highPass.process(y)
        if (lowPass != null) y = lowPass.process(y)

        // 7. Stutter.
        if (preset.stutterIntervalSeconds > 0f) y = stutter(x, y)

        return (y * preset.outputGain).coerceIn(-1f, 1f)
    }

    private fun stutter(input: Float, y: Float): Float {
        history[historyIndex] = y
        historyIndex = (historyIndex + 1) and historyMask
        envelope = max(abs(input), envelope * envelopeRelease)

        if (stutterRemaining > 0) {
            val position = stutterPosition % stutterLength
            val fade = min(1f, min(position, stutterLength - position) / stutterFade.toFloat())
            val repeated = history[(stutterStart + position) and historyMask] * fade
            stutterPosition++
            stutterRemaining--
            return y * STUTTER_DUCK + repeated * preset.stutterMix
        }

        if (samplesUntilStutter > 0) {
            samplesUntilStutter--
        } else if (envelope > SPEECH_THRESHOLD) {
            // Repeat the fragment that just played, 2–3 times.
            stutterLength = (sampleRate * (0.06f + 0.08f * random.nextFloat())).toInt()
            stutterStart = (historyIndex - stutterLength) and historyMask
            stutterPosition = 0
            stutterRemaining = stutterLength * (2 + random.nextInt(2))
            samplesUntilStutter = nextStutterGap()
        }
        return y
    }

    private fun nextStutterGap(): Int =
        (preset.stutterIntervalSeconds * (0.5f + random.nextFloat()) * sampleRate).toInt()

    private companion object {
        /** Live voice level while a stutter plays over it. */
        const val STUTTER_DUCK = 0.7f

        /** Only stutter while someone is audibly speaking, never on silence. */
        const val SPEECH_THRESHOLD = 0.04f
    }
}

/** The settings behind each [VoiceStyle]. */
internal class VoicePreset(
    /** (semitones, gain) for each pitch-shifted copy. */
    val layers: List<Pair<Float, Float>>,
    val combDelayMs: Float,
    val combFeedback: Float,
    val combMix: Float,
    val ringHz: Float,
    val ringMix: Float,
    val drive: Float,
    /** 0 = off. */
    val bits: Int,
    /** Hold each sample for this many samples; 1 = off. */
    val decimate: Int,
    /** Average gap between stutters; 0 = never. */
    val stutterIntervalSeconds: Float,
    val stutterMix: Float,
    val highPassHz: Float,
    val lowPassHz: Float,
    val outputGain: Float,
) {
    companion object {
        fun of(style: VoiceStyle): VoicePreset = when (style) {
            VoiceStyle.ROGUE_AI -> VoicePreset(
                layers = listOf(3f to 0.55f, -5f to 0.5f, 12f to 0.12f),
                combDelayMs = 4.2f, combFeedback = 0.55f, combMix = 0.35f,
                ringHz = 110f, ringMix = 0.2f,
                drive = 2.2f, bits = 0, decimate = 1,
                stutterIntervalSeconds = 0.85f, stutterMix = 0.7f,
                highPassHz = 140f, lowPassHz = 7000f, outputGain = 0.9f,
            )
            VoiceStyle.SYNTHETIC -> VoicePreset(
                layers = listOf(-2f to 1f),
                combDelayMs = 2.5f, combFeedback = 0.4f, combMix = 0.25f,
                ringHz = 55f, ringMix = 0.75f,
                drive = 1.8f, bits = 8, decimate = 2,
                stutterIntervalSeconds = 0f, stutterMix = 0f,
                highPassHz = 120f, lowPassHz = 6000f, outputGain = 0.9f,
            )
            VoiceStyle.LOW_MACHINE -> VoicePreset(
                layers = listOf(-6f to 0.8f, -12f to 0.35f),
                combDelayMs = 7f, combFeedback = 0.6f, combMix = 0.35f,
                ringHz = 35f, ringMix = 0.25f,
                drive = 3f, bits = 0, decimate = 1,
                stutterIntervalSeconds = 0f, stutterMix = 0f,
                highPassHz = 70f, lowPassHz = 5000f, outputGain = 0.9f,
            )
            VoiceStyle.CORRUPTED -> VoicePreset(
                layers = listOf(2f to 0.8f, -7f to 0.35f),
                combDelayMs = 3f, combFeedback = 0.5f, combMix = 0.2f,
                ringHz = 0f, ringMix = 0f,
                drive = 2.5f, bits = 6, decimate = 3,
                stutterIntervalSeconds = 0.9f, stutterMix = 0.8f,
                highPassHz = 150f, lowPassHz = 6500f, outputGain = 0.9f,
            )
        }
    }
}
