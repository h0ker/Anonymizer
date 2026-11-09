package com.hoker.anonymizer.presentation.editor

import androidx.compose.ui.graphics.ImageBitmap
import com.hoker.anonymizer.domain.model.ExportStage
import com.hoker.anonymizer.domain.model.GlitchParams
import com.hoker.anonymizer.domain.model.MediaKind
import com.hoker.anonymizer.domain.model.VoiceStyle

data class EditorUiState(
    val kind: MediaKind? = null,
    /** For a video, the preview frame. */
    val original: ImageBitmap? = null,
    val rendered: ImageBitmap? = null,
    val faceCount: Int = 0,
    val params: GlitchParams = GlitchParams(),
    val isLoading: Boolean = false,
    val isRendering: Boolean = false,
    val isSaving: Boolean = false,
    val video: VideoUiState? = null,
    /** Strip EXIF and other identifying metadata from the saved file. */
    val scrubMetadata: Boolean = true,
    /**
     * Non-null while a video export runs in the background. Kept at the top level because the
     * export outlives this screen: reopening the app mid-export still shows it.
     */
    val export: ExportProgress? = null,
) {
    val hasMedia: Boolean get() = original != null
    val isExporting: Boolean get() = export != null
    val canSave: Boolean get() = rendered != null && faceCount > 0 && !isRendering && !isSaving
    val canExport: Boolean get() = video != null && !isLoading && !isExporting
}

data class VideoUiState(
    val durationMs: Long,
    val previewPositionMs: Long = 0L,
    val removeAudio: Boolean = false,
    /** Disguise the voice in the exported audio. Only applies when the audio is kept. */
    val voiceEnabled: Boolean = false,
    val voiceStyle: VoiceStyle = VoiceStyle.ROGUE_AI,
    val isPreviewingVoice: Boolean = false,
)

/** [fraction] is null while the length is still unknown. */
data class ExportProgress(val fraction: Float?, val stage: ExportStage)

sealed interface EditorEvent {
    data object PhotoSaved : EditorEvent
    data object VideoSaved : EditorEvent
    data object LoadFailed : EditorEvent
    data object SaveFailed : EditorEvent
    data object ExportFailed : EditorEvent
    data object Unsupported : EditorEvent
    data object NoAudio : EditorEvent
}
