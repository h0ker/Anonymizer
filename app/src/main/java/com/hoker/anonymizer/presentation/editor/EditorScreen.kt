package com.hoker.anonymizer.presentation.editor

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hoker.anonymizer.R
import com.hoker.anonymizer.domain.model.ExportStage
import com.hoker.anonymizer.domain.model.GlitchParams
import com.hoker.anonymizer.domain.model.MediaKind
import com.hoker.anonymizer.domain.model.VoiceStyle
import com.hoker.anonymizer.ui.theme.AnonymizerTheme
import kotlin.math.roundToInt

/** Everything the editor screen can ask the ViewModel to do. */
data class EditorActions(
    val onPickMedia: () -> Unit = {},
    val onParamsChange: ((GlitchParams) -> GlitchParams) -> Unit = {},
    val onReseed: () -> Unit = {},
    val onSavePhoto: () -> Unit = {},
    val onPreviewPositionChange: (Long) -> Unit = {},
    val onPreviewPositionCommitted: () -> Unit = {},
    val onRemoveAudioChange: (Boolean) -> Unit = {},
    val onScrubMetadataChange: (Boolean) -> Unit = {},
    val onVoiceEnabledChange: (Boolean) -> Unit = {},
    val onVoiceStyleChange: (VoiceStyle) -> Unit = {},
    val onToggleVoicePreview: () -> Unit = {},
    val onExportVideo: () -> Unit = {},
    val onCancelExport: () -> Unit = {},
)

@Composable
fun EditorRoute(viewModel: EditorViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val resources = LocalResources.current

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            val message = when (event) {
                EditorEvent.PhotoSaved -> R.string.message_photo_saved
                EditorEvent.VideoSaved -> R.string.message_video_saved
                EditorEvent.LoadFailed -> R.string.message_load_failed
                EditorEvent.SaveFailed -> R.string.message_save_failed
                EditorEvent.ExportFailed -> R.string.message_export_failed
                EditorEvent.Unsupported -> R.string.message_unsupported
                EditorEvent.NoAudio -> R.string.message_no_audio
            }
            snackbarHostState.showSnackbar(resources.getString(message))
        }
    }

    // Ask once for notification permission so export progress can show after leaving the app.
    // The export starts either way; without permission it just runs without a visible notification.
    val context = LocalContext.current
    val notificationPermission = rememberLauncherForActivityResult(RequestPermission()) { viewModel.onExportVideo() }
    val exportWithNotifications = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.onExportVideo()
        }
    }

    val picker = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        uri?.let { viewModel.onMediaPicked(it.toString()) }
    }

    EditorScreen(
        state = state,
        snackbarHostState = snackbarHostState,
        actions = EditorActions(
            onPickMedia = { picker.launch(PickVisualMediaRequest(PickVisualMedia.ImageAndVideo)) },
            onParamsChange = viewModel::onParamsChange,
            onReseed = viewModel::onReseed,
            onSavePhoto = viewModel::onSavePhoto,
            onPreviewPositionChange = viewModel::onPreviewPositionChange,
            onPreviewPositionCommitted = viewModel::onPreviewPositionCommitted,
            onRemoveAudioChange = viewModel::onRemoveAudioChange,
            onScrubMetadataChange = viewModel::onScrubMetadataChange,
            onVoiceEnabledChange = viewModel::onVoiceEnabledChange,
            onVoiceStyleChange = viewModel::onVoiceStyleChange,
            onToggleVoicePreview = viewModel::onToggleVoicePreview,
            onExportVideo = exportWithNotifications,
            onCancelExport = viewModel::onCancelExport,
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    state: EditorUiState,
    snackbarHostState: SnackbarHostState,
    actions: EditorActions,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    if (state.hasMedia) {
                        TextButton(onClick = actions.onPickMedia, enabled = !state.isExporting) {
                            Text(stringResource(R.string.editor_open))
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            val original = state.original
            when {
                original != null -> EditorContent(state, original, actions)
                state.isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                else -> EmptyState(actions.onPickMedia, modifier = Modifier.align(Alignment.Center))
            }
            // The app was reopened while an export from earlier is still running.
            val export = state.export
            if (original == null && export != null) {
                ExportProgressBar(export, actions.onCancelExport, modifier = Modifier.align(Alignment.BottomCenter))
            }
        }
    }
}

@Composable
private fun EditorContent(state: EditorUiState, original: ImageBitmap, actions: EditorActions) {
    val video = state.video
    Column(modifier = Modifier.fillMaxSize()) {
        ImagePreview(
            original = original,
            rendered = state.rendered,
            busy = state.isRendering || state.isLoading,
            modifier = Modifier.weight(0.5f).fillMaxWidth(),
        )
        StatusLine(state.faceCount, state.kind)
        Column(
            modifier = Modifier
                .weight(0.5f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            if (video != null) {
                VideoOptions(video, enabled = !state.isExporting, actions)
            }
            SwitchRow(
                title = stringResource(R.string.option_scrub_metadata),
                body = stringResource(R.string.option_scrub_metadata_body),
                checked = state.scrubMetadata,
                onCheckedChange = actions.onScrubMetadataChange,
                enabled = !state.isExporting,
            )
            GlitchControls(
                params = state.params,
                onParamsChange = actions.onParamsChange,
                showSpeechPulse = state.kind == MediaKind.VIDEO,
                enabled = !state.isExporting,
            )
        }
        val progress = state.export
        if (progress != null) {
            ExportProgressBar(progress, actions.onCancelExport)
        } else {
            BottomButtons(state, actions)
        }
    }
}

@Composable
private fun VideoOptions(video: VideoUiState, enabled: Boolean, actions: EditorActions) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.video_preview_frame), style = MaterialTheme.typography.labelLarge)
            Text(
                "${formatTime(video.previewPositionMs)} / ${formatTime(video.durationMs)}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = video.previewPositionMs.toFloat(),
            onValueChange = { actions.onPreviewPositionChange(it.toLong()) },
            onValueChangeFinished = actions.onPreviewPositionCommitted,
            valueRange = 0f..video.durationMs.coerceAtLeast(1L).toFloat(),
            enabled = enabled,
        )
        SwitchRow(
            title = stringResource(R.string.video_remove_audio),
            body = stringResource(R.string.video_remove_audio_body),
            checked = video.removeAudio,
            onCheckedChange = actions.onRemoveAudioChange,
            enabled = enabled,
        )
        VoiceOptions(video, enabled, actions)
    }
}

/** Voice disguise: on/off, a style, and a short preview. Needs the audio to be kept. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VoiceOptions(video: VideoUiState, enabled: Boolean, actions: EditorActions) {
    val available = enabled && !video.removeAudio
    SwitchRow(
        title = stringResource(R.string.voice_title),
        body = stringResource(if (video.removeAudio) R.string.voice_body_needs_audio else R.string.voice_body),
        checked = video.voiceEnabled && !video.removeAudio,
        onCheckedChange = actions.onVoiceEnabledChange,
        enabled = available,
    )
    if (!video.voiceEnabled || video.removeAudio) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        VoiceStyle.entries.forEach { style ->
            FilterChip(
                selected = style == video.voiceStyle,
                onClick = { actions.onVoiceStyleChange(style) },
                label = { Text(stringResource(style.label)) },
                enabled = enabled,
            )
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(video.voiceStyle.description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = actions.onToggleVoicePreview, enabled = enabled) {
            Text(stringResource(if (video.isPreviewingVoice) R.string.voice_stop_preview else R.string.voice_preview))
        }
    }
}

private val VoiceStyle.label: Int
    get() = when (this) {
        VoiceStyle.ROGUE_AI -> R.string.voice_rogue_ai
        VoiceStyle.SYNTHETIC -> R.string.voice_synthetic
        VoiceStyle.LOW_MACHINE -> R.string.voice_low_machine
        VoiceStyle.CORRUPTED -> R.string.voice_corrupted
    }

private val VoiceStyle.description: Int
    get() = when (this) {
        VoiceStyle.ROGUE_AI -> R.string.voice_rogue_ai_body
        VoiceStyle.SYNTHETIC -> R.string.voice_synthetic_body
        VoiceStyle.LOW_MACHINE -> R.string.voice_low_machine_body
        VoiceStyle.CORRUPTED -> R.string.voice_corrupted_body
    }

@Composable
private fun SwitchRow(
    title: String,
    body: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun BottomButtons(state: EditorUiState, actions: EditorActions) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedButton(onClick = actions.onReseed, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.editor_reseed))
        }
        if (state.kind == MediaKind.VIDEO) {
            Button(onClick = actions.onExportVideo, enabled = state.canExport, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.editor_export))
            }
        } else {
            Button(onClick = actions.onSavePhoto, enabled = state.canSave, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.editor_save))
            }
        }
    }
}

@Composable
private fun ExportProgressBar(progress: ExportProgress, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                val fraction = progress.fraction
                Text(
                    text = if (progress.stage == ExportStage.ANALYZING_AUDIO) {
                        stringResource(R.string.video_analyzing_audio)
                    } else if (fraction == null) {
                        stringResource(R.string.video_exporting)
                    } else {
                        stringResource(R.string.video_exporting_percent, (fraction * 100).roundToInt())
                    },
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    stringResource(R.string.video_export_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onCancel) { Text(stringResource(R.string.editor_cancel)) }
        }
        Spacer(Modifier.height(8.dp))
        val fraction = progress.fraction
        if (fraction == null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Shows the glitched image; pressing and holding swaps in the original for comparison. */
@Composable
private fun ImagePreview(
    original: ImageBitmap,
    rendered: ImageBitmap?,
    busy: Boolean,
    modifier: Modifier = Modifier,
) {
    var showOriginal by remember { mutableStateOf(false) }
    Box(
        modifier = modifier.pointerInput(Unit) {
            detectTapGestures(onPress = {
                showOriginal = true
                tryAwaitRelease()
                showOriginal = false
            })
        },
    ) {
        Image(
            bitmap = if (showOriginal || rendered == null) original else rendered,
            contentDescription = stringResource(R.string.editor_preview_description),
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).size(24.dp),
                strokeWidth = 3.dp,
            )
        }
    }
}

@Composable
private fun StatusLine(faceCount: Int, kind: MediaKind?) {
    val isVideo = kind == MediaKind.VIDEO
    val text = when {
        faceCount > 0 -> pluralStringResource(R.plurals.editor_face_count, faceCount, faceCount) +
            " · " + stringResource(R.string.editor_hold_hint)
        isVideo -> stringResource(R.string.editor_no_faces_in_frame)
        else -> stringResource(R.string.editor_no_faces)
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (faceCount == 0 && !isVideo) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun EmptyState(onPickMedia: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.editor_empty_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.editor_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onPickMedia) { Text(stringResource(R.string.editor_choose_media)) }
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

@Preview(showBackground = true)
@Composable
private fun EmptyEditorPreview() {
    AnonymizerTheme {
        Surface {
            EditorScreen(
                state = EditorUiState(),
                snackbarHostState = remember { SnackbarHostState() },
                actions = EditorActions(),
            )
        }
    }
}
