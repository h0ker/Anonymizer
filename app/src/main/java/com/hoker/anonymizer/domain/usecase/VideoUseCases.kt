package com.hoker.anonymizer.domain.usecase

import com.hoker.anonymizer.domain.effect.FaceGlitchEffect
import com.hoker.anonymizer.domain.model.ExportOutcome
import com.hoker.anonymizer.domain.model.ExportStage
import com.hoker.anonymizer.domain.model.ExportStatus
import com.hoker.anonymizer.domain.model.MediaKind
import com.hoker.anonymizer.domain.model.MediaUri
import com.hoker.anonymizer.domain.model.PixelBuffer
import com.hoker.anonymizer.domain.model.VideoExportRequest
import com.hoker.anonymizer.domain.model.VideoExportState
import com.hoker.anonymizer.domain.model.VideoInfo
import com.hoker.anonymizer.domain.model.VoiceStyle
import com.hoker.anonymizer.domain.repository.AudioRepository
import com.hoker.anonymizer.domain.repository.FaceDetector
import com.hoker.anonymizer.domain.repository.MediaRepository
import com.hoker.anonymizer.domain.repository.VideoExportScheduler
import com.hoker.anonymizer.domain.repository.VideoRepository
import com.hoker.anonymizer.domain.video.FaceGlitchFrameTransform
import com.hoker.anonymizer.domain.video.FaceTracker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import javax.inject.Inject

class GetMediaKindUseCase @Inject constructor(
    private val mediaRepository: MediaRepository,
) {
    suspend operator fun invoke(uri: MediaUri): MediaKind? = mediaRepository.getKind(uri)
}

class GetVideoInfoUseCase @Inject constructor(
    private val videoRepository: VideoRepository,
) {
    suspend operator fun invoke(uri: MediaUri): VideoInfo = videoRepository.getInfo(uri)
}

/** Loads a single frame to preview and tune the effect on before exporting. */
class LoadVideoFrameUseCase @Inject constructor(
    private val videoRepository: VideoRepository,
) {
    suspend operator fun invoke(uri: MediaUri, timeMs: Long): PixelBuffer =
        videoRepository.loadFrame(uri, timeMs, PREVIEW_MAX_DIMENSION)

    companion object {
        /** Effect settings are relative to face size, so a smaller preview frame looks the same as the export. */
        const val PREVIEW_MAX_DIMENSION = 1280
    }
}

/**
 * Runs one export end to end. With the speech pulse on, the audio is analysed first (it's fast
 * compared to video), then every frame looks up how loudly someone is speaking at that moment.
 * Used by the background export service; screens go through [StartVideoExportUseCase].
 */
class AnonymizeVideoUseCase @Inject constructor(
    private val videoRepository: VideoRepository,
    private val audioRepository: AudioRepository,
    private val faceDetector: FaceDetector,
    private val effect: FaceGlitchEffect,
) {
    operator fun invoke(request: VideoExportRequest): Flow<VideoExportState> = flow {
        val speech = if (request.params.speechPulse > 0f) {
            emit(VideoExportState.InProgress(null, ExportStage.ANALYZING_AUDIO))
            audioRepository.analyzeSpeech(request.uri)
        } else {
            null
        }
        emitAll(
            videoRepository.export(request.uri, request.removeAudio, request.scrubMetadata, request.voice) {
                FaceGlitchFrameTransform(faceDetector.openVideoSession(), FaceTracker(), effect, request.params, speech)
            },
        )
    }
}

class PreviewVoiceUseCase @Inject constructor(
    private val audioRepository: AudioRepository,
) {
    /** Plays a few seconds of disguised voice from [startMs]. Returns false if the video has no audio. */
    suspend operator fun invoke(uri: MediaUri, startMs: Long, style: VoiceStyle): Boolean =
        audioRepository.playVoicePreview(uri, startMs, PREVIEW_DURATION_MS, style)

    companion object {
        const val PREVIEW_DURATION_MS = 6_000L
    }
}

class StartVideoExportUseCase @Inject constructor(
    private val scheduler: VideoExportScheduler,
) {
    /** Returns false if another export is already running. */
    operator fun invoke(request: VideoExportRequest): Boolean = scheduler.start(request)
}

class CancelVideoExportUseCase @Inject constructor(
    private val scheduler: VideoExportScheduler,
) {
    operator fun invoke() = scheduler.cancel()
}

class ObserveVideoExportUseCase @Inject constructor(
    private val scheduler: VideoExportScheduler,
) {
    val status: StateFlow<ExportStatus> get() = scheduler.status
    val outcomes: Flow<ExportOutcome> get() = scheduler.outcomes
}
