package com.hoker.anonymizer.domain.repository

import com.hoker.anonymizer.domain.model.MediaUri
import com.hoker.anonymizer.domain.model.PixelBuffer
import com.hoker.anonymizer.domain.model.VideoExportState
import com.hoker.anonymizer.domain.model.VideoInfo
import com.hoker.anonymizer.domain.model.VoiceStyle
import com.hoker.anonymizer.domain.video.FrameTransform
import kotlinx.coroutines.flow.Flow

interface VideoRepository {
    suspend fun getInfo(uri: MediaUri): VideoInfo

    /** Decodes one upright frame near [timeMs], scaled so its longest side is at most [maxDimension]. */
    suspend fun loadFrame(uri: MediaUri, timeMs: Long, maxDimension: Int): PixelBuffer

    /**
     * Re-encodes the video with every frame passed through a transform, and saves the result to
     * the gallery. With [scrubMetadata] the output keeps only what playback needs (rotation);
     * location, capture time and device details are dropped. [createTransform] is called on the
     * processing thread before the first frame. Cancelling collection cancels the export.
     */
    fun export(
        uri: MediaUri,
        removeAudio: Boolean,
        scrubMetadata: Boolean,
        /** Disguise the voice in the audio with this style; null keeps it as recorded. */
        voice: VoiceStyle?,
        createTransform: () -> FrameTransform,
    ): Flow<VideoExportState>
}
