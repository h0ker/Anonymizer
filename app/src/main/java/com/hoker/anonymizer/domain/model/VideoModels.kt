package com.hoker.anonymizer.domain.model

enum class MediaKind { PHOTO, VIDEO }

data class VideoInfo(val durationMs: Long)

enum class ExportStage { ANALYZING_AUDIO, RENDERING }

sealed interface VideoExportState {
    /** [fraction] is 0..1, or null while the exporter can't estimate progress yet. */
    data class InProgress(val fraction: Float?, val stage: ExportStage = ExportStage.RENDERING) : VideoExportState

    data class Completed(val uri: MediaUri) : VideoExportState
}

/** Everything needed to run one export, independent of any screen. */
data class VideoExportRequest(
    val uri: MediaUri,
    val params: GlitchParams,
    val removeAudio: Boolean,
    val scrubMetadata: Boolean,
    /** Voice disguise for the audio, or null to leave it as recorded. Ignored when [removeAudio]. */
    val voice: VoiceStyle? = null,
)

/** What the background exporter is doing right now. */
sealed interface ExportStatus {
    data object Idle : ExportStatus
    data class Running(val fraction: Float?, val stage: ExportStage) : ExportStatus
}

/** How an export ended. Emitted once per export. */
sealed interface ExportOutcome {
    data class Completed(val uri: MediaUri) : ExportOutcome
    data object Failed : ExportOutcome
    data object Cancelled : ExportOutcome
}
