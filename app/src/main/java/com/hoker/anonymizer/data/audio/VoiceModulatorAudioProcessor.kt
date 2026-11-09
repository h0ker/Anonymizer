package com.hoker.anonymizer.data.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.hoker.anonymizer.domain.audio.voice.VoiceModulator
import com.hoker.anonymizer.domain.model.VoiceStyle
import java.nio.ByteBuffer

/**
 * Runs the export's audio through [VoiceModulator], one modulator per channel. Every channel's
 * modulator shares a seed, so stutters land at the same moment in left and right.
 */
@OptIn(UnstableApi::class)
internal class VoiceModulatorAudioProcessor(private val style: VoiceStyle) : BaseAudioProcessor() {

    private var modulators: Array<VoiceModulator> = emptyArray()
    private var channelIn = FloatArray(0)
    private var channelOut = Array(0) { FloatArray(0) }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    /** Start (or seek): fresh filters and delay lines, so no sound carries over. */
    override fun onFlush() {
        val format = inputAudioFormat
        modulators = Array(format.channelCount) { VoiceModulator(style, format.sampleRate, seed = SEED) }
    }

    override fun onReset() {
        modulators = emptyArray()
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val format = inputAudioFormat
        val channels = format.channelCount
        val isFloat = format.encoding == C.ENCODING_PCM_FLOAT
        val frames = inputBuffer.remaining() / format.bytesPerFrame
        if (frames == 0) return
        if (channelIn.size < frames) {
            channelIn = FloatArray(frames)
            channelOut = Array(channels) { FloatArray(frames) }
        }

        // De-interleave, process each channel, then interleave back in the same format.
        val start = inputBuffer.position()
        for (c in 0 until channels) {
            for (f in 0 until frames) {
                val index = start + (f * channels + c) * if (isFloat) 4 else 2
                channelIn[f] = if (isFloat) inputBuffer.getFloat(index) else inputBuffer.getShort(index) / 32768f
            }
            modulators[c].process(channelIn, channelOut[c], frames)
        }
        inputBuffer.position(inputBuffer.limit())

        val output = replaceOutputBuffer(frames * format.bytesPerFrame)
        for (f in 0 until frames) {
            for (c in 0 until channels) {
                val sample = channelOut[c][f]
                if (isFloat) output.putFloat(sample) else output.putShort((sample * 32767f).toInt().toShort())
            }
        }
        output.flip()
    }

    private companion object {
        const val SEED = 7
    }
}
