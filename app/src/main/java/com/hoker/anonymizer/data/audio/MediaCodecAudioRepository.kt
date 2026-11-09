package com.hoker.anonymizer.data.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.core.net.toUri
import com.hoker.anonymizer.core.dispatchers.DefaultDispatcher
import com.hoker.anonymizer.domain.audio.SpeechEnvelope
import com.hoker.anonymizer.domain.audio.SpeechEnvelopeBuilder
import com.hoker.anonymizer.domain.audio.voice.VoiceModulator
import com.hoker.anonymizer.domain.model.MediaUri
import com.hoker.anonymizer.domain.model.VoiceStyle
import com.hoker.anonymizer.domain.repository.AudioRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/** Decodes the audio track to PCM with MediaCodec, downmixed to mono, for analysis and voice previews. */
@Singleton
class MediaCodecAudioRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:DefaultDispatcher private val dispatcher: CoroutineDispatcher,
) : AudioRepository {

    override suspend fun analyzeSpeech(uri: MediaUri): SpeechEnvelope? = withContext(dispatcher) {
        var builder: SpeechEnvelopeBuilder? = null
        val hasAudio = decodeMono(
            uri = uri,
            // Aligned to the first decoded sample so the pulse lines up with the video.
            onStart = { sampleRate, firstSampleUs -> builder = SpeechEnvelopeBuilder(sampleRate, firstSampleUs) },
            onSamples = { samples, count -> builder?.add(samples, count) },
        )
        if (!hasAudio) null else builder?.build() ?: SpeechEnvelope.SILENT
    }

    override suspend fun playVoicePreview(uri: MediaUri, startMs: Long, durationMs: Long, style: VoiceStyle): Boolean {
        val clip = withContext(dispatcher) { decodeClip(uri, startMs * 1000, durationMs * 1000) } ?: return false
        val (sampleRate, samples) = clip
        if (samples.isEmpty()) return true
        val voiced = FloatArray(samples.size)
        withContext(dispatcher) { VoiceModulator(style, sampleRate).process(samples, voiced) }

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(voiced.size * Float.SIZE_BYTES)
            .build()
        try {
            track.write(voiced, 0, voiced.size, AudioTrack.WRITE_BLOCKING)
            track.play()
            // Cancelling this coroutine (the Stop button) ends playback through the finally block.
            delay(voiced.size * 1000L / sampleRate + PLAYBACK_TAIL_MS)
        } finally {
            runCatching { track.stop() }
            track.release()
        }
        return true
    }

    /** Mono samples from [startUs] for up to [durationUs], or null if there's no audio track. */
    private suspend fun decodeClip(uri: MediaUri, startUs: Long, durationUs: Long): Pair<Int, FloatArray>? {
        var sampleRate = 0
        var clip = FloatArray(0)
        var size = 0
        val hasAudio = decodeMono(
            uri = uri,
            startUs = startUs,
            endUs = startUs + durationUs,
            onStart = { rate, _ ->
                sampleRate = rate
                clip = FloatArray((rate * durationUs / 1_000_000L).toInt() + rate)
            },
            onSamples = { samples, count ->
                val n = minOf(count, clip.size - size)
                samples.copyInto(clip, size, 0, n)
                size += n
            },
        )
        return if (hasAudio) sampleRate to clip.copyOf(size) else null
    }

    /**
     * Decodes the first audio track between [startUs] and [endUs], downmixed to mono, in chunks.
     * [onStart] gets the real output sample rate and the time of the first sample delivered.
     * Returns false if the file has no audio track.
     */
    private suspend fun decodeMono(
        uri: MediaUri,
        startUs: Long = 0L,
        endUs: Long = Long.MAX_VALUE,
        onStart: (sampleRate: Int, firstSampleUs: Long) -> Unit,
        onSamples: (samples: FloatArray, count: Int) -> Unit,
    ): Boolean = withContext(dispatcher) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri.value.toUri(), null)
            val track = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return@withContext false
            extractor.selectTrack(track)
            if (startUs > 0) extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val inputFormat = extractor.getTrackFormat(track)
            val decoder = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME)!!)
            codec = decoder
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()
            pump(decoder, extractor, startUs, endUs, onStart, onSamples)
            true
        } finally {
            codec?.run {
                runCatching { stop() }
                release()
            }
            extractor.release()
        }
    }

    private fun CoroutineScope.pump(
        decoder: MediaCodec,
        extractor: MediaExtractor,
        startUs: Long,
        endUs: Long,
        onStart: (Int, Long) -> Unit,
        onSamples: (FloatArray, Int) -> Unit,
    ) {
        var started = false
        var sampleRate = 0
        var channels = 1
        var floatPcm = false
        var mono = FloatArray(0)
        val info = MediaCodec.BufferInfo()
        var inputDone = false

        while (true) {
            ensureActive()
            if (!inputDone) {
                val inputIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                if (inputIndex >= 0) {
                    val buffer = decoder.getInputBuffer(inputIndex)!!
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0 || extractor.sampleTime > endUs) {
                        decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        decoder.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            val outputIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
            if (outputIndex < 0) continue
            if (info.size > 0 && info.presentationTimeUs < endUs) {
                if (sampleRate == 0) {
                    // Read the real output format: e.g. HE-AAC decodes at twice its declared rate.
                    val format = decoder.outputFormat
                    sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                    floatPcm = format.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        format.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                }
                val buffer = decoder.getOutputBuffer(outputIndex)!!.order(ByteOrder.nativeOrder())
                buffer.position(info.offset).limit(info.offset + info.size)
                val frames = info.size / ((if (floatPcm) 4 else 2) * channels)
                // Seeking lands on the sync point before startUs; drop the samples before it.
                val skip = if (info.presentationTimeUs >= startUs) 0 else
                    ((startUs - info.presentationTimeUs) * sampleRate / 1_000_000L).toInt().coerceAtMost(frames)
                if (mono.size < frames) mono = FloatArray(frames)
                if (floatPcm) {
                    val samples = buffer.asFloatBuffer()
                    for (f in 0 until frames) {
                        var sum = 0f
                        for (c in 0 until channels) sum += samples.get()
                        mono[f] = sum / channels
                    }
                } else {
                    val samples = buffer.asShortBuffer()
                    for (f in 0 until frames) {
                        var sum = 0
                        for (c in 0 until channels) sum += samples.get()
                        mono[f] = sum / (channels * 32768f)
                    }
                }
                if (frames > skip) {
                    if (!started) {
                        started = true
                        onStart(sampleRate, info.presentationTimeUs + skip * 1_000_000L / sampleRate)
                    }
                    if (skip > 0) mono.copyInto(mono, 0, skip, frames)
                    onSamples(mono, frames - skip)
                }
            }
            decoder.releaseOutputBuffer(outputIndex, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
        }
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
        const val PLAYBACK_TAIL_MS = 150L
    }
}
