package com.hoker.anonymizer.presentation.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hoker.anonymizer.core.dispatchers.DefaultDispatcher
import com.hoker.anonymizer.domain.model.ExportOutcome
import com.hoker.anonymizer.domain.model.ExportStatus
import com.hoker.anonymizer.domain.model.FaceRegion
import com.hoker.anonymizer.domain.model.GlitchParams
import com.hoker.anonymizer.domain.model.MediaKind
import com.hoker.anonymizer.domain.model.MediaUri
import com.hoker.anonymizer.domain.model.PixelBuffer
import com.hoker.anonymizer.domain.model.VideoExportRequest
import com.hoker.anonymizer.domain.model.VoiceStyle
import com.hoker.anonymizer.domain.usecase.CancelVideoExportUseCase
import com.hoker.anonymizer.domain.usecase.ApplyFaceGlitchUseCase
import com.hoker.anonymizer.domain.usecase.DetectFacesUseCase
import com.hoker.anonymizer.domain.usecase.GetMediaKindUseCase
import com.hoker.anonymizer.domain.usecase.GetVideoInfoUseCase
import com.hoker.anonymizer.domain.usecase.LoadImageUseCase
import com.hoker.anonymizer.domain.usecase.LoadVideoFrameUseCase
import com.hoker.anonymizer.domain.usecase.ObserveVideoExportUseCase
import com.hoker.anonymizer.domain.usecase.PreviewVoiceUseCase
import com.hoker.anonymizer.domain.usecase.SaveImageUseCase
import com.hoker.anonymizer.domain.usecase.StartVideoExportUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.random.Random

@HiltViewModel
class EditorViewModel @Inject constructor(
    private val getMediaKind: GetMediaKindUseCase,
    private val loadImage: LoadImageUseCase,
    private val getVideoInfo: GetVideoInfoUseCase,
    private val loadVideoFrame: LoadVideoFrameUseCase,
    private val detectFaces: DetectFacesUseCase,
    private val applyFaceGlitch: ApplyFaceGlitchUseCase,
    private val saveImage: SaveImageUseCase,
    private val startVideoExport: StartVideoExportUseCase,
    private val cancelVideoExport: CancelVideoExportUseCase,
    private val observeVideoExport: ObserveVideoExportUseCase,
    private val previewVoice: PreviewVoiceUseCase,
    @param:DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
) : ViewModel() {

    /** The still being edited: the photo itself, or the current preview frame of a video. */
    private class LoadedImage(val image: PixelBuffer, val faces: List<FaceRegion>, val isVideoFrame: Boolean)

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private val _events = Channel<EditorEvent>(Channel.BUFFERED)
    val events: Flow<EditorEvent> = _events.receiveAsFlow()

    private val loaded = MutableStateFlow<LoadedImage?>(null)
    private val params = MutableStateFlow(GlitchParams())
    private var renderedImage: PixelBuffer? = null
    private var mediaUri: MediaUri? = null
    private var loadJob: Job? = null
    private var voicePreviewJob: Job? = null

    init {
        observeRenders()
        observeExports()
    }

    fun onMediaPicked(uri: String) {
        if (_uiState.value.isExporting) return
        stopVoicePreview()
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val media = MediaUri(uri)
                when (getMediaKind(media)) {
                    MediaKind.PHOTO -> openPhoto(media)
                    MediaKind.VIDEO -> openVideo(media)
                    null -> {
                        _uiState.update { it.copy(isLoading = false) }
                        _events.send(EditorEvent.Unsupported)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false) }
                _events.send(EditorEvent.LoadFailed)
            }
        }
    }

    private suspend fun openPhoto(uri: MediaUri) {
        val image = loadImage(uri)
        mediaUri = uri
        showStill(image, MediaKind.PHOTO, video = null)
    }

    private suspend fun openVideo(uri: MediaUri) {
        val info = getVideoInfo(uri)
        val frame = loadVideoFrame(uri, 0L)
        mediaUri = uri
        showStill(frame, MediaKind.VIDEO, video = VideoUiState(durationMs = info.durationMs))
    }

    private suspend fun showStill(image: PixelBuffer, kind: MediaKind, video: VideoUiState?) {
        val faces = detectFaces(image)
        val preview = withContext(defaultDispatcher) { image.toImageBitmap() }
        renderedImage = null
        _uiState.update {
            it.copy(
                kind = kind,
                original = preview,
                rendered = null,
                faceCount = faces.size,
                isLoading = false,
                video = video ?: it.video.takeIf { kind == MediaKind.VIDEO },
            )
        }
        loaded.value = LoadedImage(image, faces, isVideoFrame = kind == MediaKind.VIDEO)
    }

    /** Moves the scrubber; the frame itself loads when the drag ends ([onPreviewPositionCommitted]). */
    fun onPreviewPositionChange(positionMs: Long) {
        _uiState.update { state -> state.copy(video = state.video?.copy(previewPositionMs = positionMs)) }
    }

    fun onPreviewPositionCommitted() {
        val uri = mediaUri ?: return
        val video = _uiState.value.video ?: return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val frame = loadVideoFrame(uri, video.previewPositionMs)
                showStill(frame, MediaKind.VIDEO, video = null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false) }
                _events.send(EditorEvent.LoadFailed)
            }
        }
    }

    fun onVoiceEnabledChange(enabled: Boolean) {
        if (!enabled) stopVoicePreview()
        _uiState.update { state -> state.copy(video = state.video?.copy(voiceEnabled = enabled)) }
    }

    fun onVoiceStyleChange(style: VoiceStyle) {
        stopVoicePreview()
        _uiState.update { state -> state.copy(video = state.video?.copy(voiceStyle = style)) }
    }

    /** Plays a few seconds of disguised audio from the preview frame, or stops a preview that's playing. */
    fun onToggleVoicePreview() {
        if (voicePreviewJob?.isActive == true) {
            stopVoicePreview()
            return
        }
        val uri = mediaUri ?: return
        val video = _uiState.value.video ?: return
        // Near the end there's little left to hear, so start early enough for a full clip.
        val start = video.previewPositionMs.coerceAtMost((video.durationMs - PreviewVoiceUseCase.PREVIEW_DURATION_MS).coerceAtLeast(0L))
        voicePreviewJob = viewModelScope.launch {
            setPreviewingVoice(true)
            try {
                if (!previewVoice(uri, start, video.voiceStyle)) _events.send(EditorEvent.NoAudio)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.send(EditorEvent.LoadFailed)
            } finally {
                setPreviewingVoice(false)
            }
        }
    }

    private fun stopVoicePreview() {
        voicePreviewJob?.cancel()
        voicePreviewJob = null
    }

    private fun setPreviewingVoice(playing: Boolean) {
        _uiState.update { state -> state.copy(video = state.video?.copy(isPreviewingVoice = playing)) }
    }

    fun onScrubMetadataChange(scrub: Boolean) {
        _uiState.update { it.copy(scrubMetadata = scrub) }
    }

    fun onRemoveAudioChange(remove: Boolean) {
        if (remove) stopVoicePreview()
        _uiState.update { state -> state.copy(video = state.video?.copy(removeAudio = remove)) }
    }

    fun onParamsChange(transform: (GlitchParams) -> GlitchParams) {
        if (_uiState.value.isExporting) return
        val updated = transform(params.value)
        params.value = updated
        _uiState.update { it.copy(params = updated) }
    }

    fun onReseed() = onParamsChange { it.copy(seed = Random.nextInt()) }

    fun onSavePhoto() {
        val image = renderedImage ?: return
        if (!_uiState.value.canSave) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            try {
                saveImage(image, mediaUri ?: return@launch, _uiState.value.scrubMetadata)
                _events.send(EditorEvent.PhotoSaved)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.send(EditorEvent.SaveFailed)
            } finally {
                _uiState.update { it.copy(isSaving = false) }
            }
        }
    }

    /** Hands the export to the background service; progress comes back through [observeExports]. */
    fun onExportVideo() {
        stopVoicePreview()
        val uri = mediaUri ?: return
        val state = _uiState.value
        val video = state.video ?: return
        if (!state.canExport) return
        startVideoExport(
            VideoExportRequest(
                uri = uri,
                params = params.value,
                removeAudio = video.removeAudio,
                scrubMetadata = state.scrubMetadata,
                voice = video.voiceStyle.takeIf { video.voiceEnabled && !video.removeAudio },
            ),
        )
    }

    fun onCancelExport() = cancelVideoExport()

    private fun observeExports() {
        viewModelScope.launch {
            observeVideoExport.status.collect { status ->
                val progress = (status as? ExportStatus.Running)?.let { ExportProgress(it.fraction, it.stage) }
                _uiState.update { it.copy(export = progress) }
            }
        }
        viewModelScope.launch {
            observeVideoExport.outcomes.collect { outcome ->
                when (outcome) {
                    is ExportOutcome.Completed -> _events.send(EditorEvent.VideoSaved)
                    ExportOutcome.Failed -> _events.send(EditorEvent.ExportFailed)
                    ExportOutcome.Cancelled -> Unit
                }
            }
        }
    }

    /**
     * Re-renders whenever the still or settings change. Slider drags are debounced, and
     * collectLatest cancels a render that's still running when newer settings arrive.
     */
    @OptIn(FlowPreview::class)
    private fun observeRenders() {
        viewModelScope.launch {
            combine(loaded.filterNotNull(), params) { image, settings -> image to settings }
                .debounce(RENDER_DEBOUNCE_MS)
                .collectLatest { (source, settings) ->
                    _uiState.update { it.copy(isRendering = true) }
                    val output = if (source.faces.isEmpty()) source.image else {
                        // Video previews show the speech pulse at its peak so its strength can be judged.
                        applyFaceGlitch(source.image, source.faces, settings, speechLevel = if (source.isVideoFrame) 1f else 0f)
                    }
                    val bitmap = withContext(defaultDispatcher) { output.toImageBitmap() }
                    // Ignore a result that finished after a different still was loaded.
                    if (loaded.value !== source) return@collectLatest
                    renderedImage = output
                    _uiState.update { it.copy(rendered = bitmap, isRendering = false) }
                }
        }
    }

    private companion object {
        const val RENDER_DEBOUNCE_MS = 120L
    }
}
