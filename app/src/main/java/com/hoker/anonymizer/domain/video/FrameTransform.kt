package com.hoker.anonymizer.domain.video

import com.hoker.anonymizer.domain.audio.SpeechEnvelope
import com.hoker.anonymizer.domain.effect.FaceGlitchEffect
import com.hoker.anonymizer.domain.model.GlitchParams
import com.hoker.anonymizer.domain.model.PixelBuffer
import com.hoker.anonymizer.domain.repository.VideoFaceDetectionSession

/**
 * Per-frame processing for a video export. The video pipeline calls [apply] once per frame,
 * in presentation order, on a single thread, then calls [close] on that same thread.
 */
interface FrameTransform : AutoCloseable {
    /** Returns the processed frame, or [frame] itself (same instance) if nothing changed. */
    fun apply(frame: PixelBuffer, presentationTimeUs: Long): PixelBuffer
}

/** Detects and tracks faces in each frame, then glitches them, pulsing with [speech] if given. */
class FaceGlitchFrameTransform(
    private val session: VideoFaceDetectionSession,
    private val tracker: FaceTracker,
    private val effect: FaceGlitchEffect,
    private val params: GlitchParams,
    private val speech: SpeechEnvelope? = null,
) : FrameTransform {

    override fun apply(frame: PixelBuffer, presentationTimeUs: Long): PixelBuffer {
        val detections = session.detect(frame, presentationTimeUs / 1000)
        val faces = tracker.update(detections, presentationTimeUs)
        if (faces.isEmpty()) return frame
        val speechLevel = speech?.at(presentationTimeUs) ?: 0f
        return effect.apply(frame, faces, params, speechLevel, shuffleStep(presentationTimeUs, params.shufflesPerSecond))
    }

    companion object {
        /**
         * Which shuffle step a frame falls in: it changes [shufflesPerSecond] times a second, so
         * frames within one step share a pattern. Based on time rather than frame count, so the
         * rate is the same whatever the video's frame rate.
         */
        fun shuffleStep(presentationTimeUs: Long, shufflesPerSecond: Float): Int =
            // Rounded, not floored: frame times sit a hair under exact multiples (33 333 µs at 30 fps).
            if (shufflesPerSecond <= 0f) 0 else Math.round(presentationTimeUs * shufflesPerSecond.toDouble() / 1_000_000).toInt()
    }

    override fun close() = session.close()
}
