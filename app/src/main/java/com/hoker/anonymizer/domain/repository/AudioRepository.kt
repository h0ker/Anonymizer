package com.hoker.anonymizer.domain.repository

import com.hoker.anonymizer.domain.audio.SpeechEnvelope
import com.hoker.anonymizer.domain.model.MediaUri
import com.hoker.anonymizer.domain.model.VoiceStyle

interface AudioRepository {
    /** Analyses the video's audio track for speech. Returns null if the video has no audio. */
    suspend fun analyzeSpeech(uri: MediaUri): SpeechEnvelope?

    /**
     * Plays [durationMs] of the video's audio from [startMs] with the voice disguised, returning
     * when playback ends. Cancel to stop early. Returns false if the video has no audio.
     */
    suspend fun playVoicePreview(uri: MediaUri, startMs: Long, durationMs: Long, style: VoiceStyle): Boolean
}
