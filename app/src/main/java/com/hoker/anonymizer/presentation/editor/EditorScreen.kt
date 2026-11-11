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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hoker.anonymizer.R
import com.hoker.anonymizer.domain.model.ExportStage
import com.hoker.anonymizer.domain.model.GlitchParams
import com.hoker.anonymizer.domain.model.MediaKind
import com.hoker.anonymizer.domain.model.VoiceStyle
import com.hoker.supra.presentation.buttons.SupraButtonTone
import com.hoker.supra.presentation.buttons.SupraHardwareButton
import com.hoker.supra.presentation.fx.glitchEffect
import com.hoker.supra.presentation.indicators.IndeterminateLoadingIndicator
import com.hoker.supra.presentation.pickers.SupraDropdownPicker
import com.hoker.supra.presentation.scaffolds.SupraScaffold
import com.hoker.supra.presentation.sizes.Sizes
import com.hoker.supra.presentation.surfaces.SupraCollapsibleArea
import com.hoker.supra.presentation.text.SupraBodyTextMedium
import com.hoker.supra.presentation.text.SupraBodyTextSmall
import com.hoker.supra.presentation.text.SupraDisplayBlock
import com.hoker.supra.presentation.theme.Ink4
import com.hoker.supra.presentation.theme.Ink5
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
    val resources = LocalResources.current
    val supraFX = viewModel.supraFX

    // Messages go through SupraFX: the scaffold shows them as Supra snackbars, and saves and
    // failures also get Supra's success/error sound, haptic and colour pulse.
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
            when (event) {
                EditorEvent.PhotoSaved, EditorEvent.VideoSaved -> supraFX.success()
                EditorEvent.SaveFailed, EditorEvent.ExportFailed -> supraFX.error()
                else -> Unit
            }
            supraFX.showSnackbar(resources.getString(message))
        }
    }

    // While an export runs and the app is on screen, don't let the display time out. Exports keep
    // going with the screen off too (the service holds a wake lock); this is so progress stays visible.
    val view = LocalView.current
    DisposableEffect(state.isExporting) {
        view.keepScreenOn = state.isExporting
        onDispose { view.keepScreenOn = false }
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

@Composable
fun EditorScreen(state: EditorUiState, actions: EditorActions) {
    val context = LocalContext.current
    val versionName = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
    val glitchColors = remember { listOf(Color.Cyan, Color.Magenta, Color.Yellow) }

    SupraScaffold(
        // The whole screen glitches each time a new photo or video comes in.
        modifier = Modifier.glitchEffect(key = state.mediaGeneration, glitchColors = glitchColors, slices = 40),
        title = stringResource(R.string.app_name),
        readout = if (state.hasMedia) null to stringResource(R.string.readout_faces, state.faceCount) else null,
        rail = true,
        // Live magnetometer reading along the bottom-left rail.
        railMeter = true,
        railStamp = stringResource(R.string.rail_stamp, versionName),
        topBar = if (state.hasMedia) {
            {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Sizes.small),
                ) {
                    StatusLine(state.faceCount, state.kind, modifier = Modifier.weight(1f))
                    SupraHardwareButton(
                        text = stringResource(R.string.editor_open),
                        tone = SupraButtonTone.QUIET,
                        enabled = !state.isExporting,
                        onClick = actions.onPickMedia,
                    )
                }
            }
        } else {
            null
        },
        bottomBar = {
            val export = state.export
            when {
                export != null -> ExportProgressBar(export, actions.onCancelExport)
                state.hasMedia -> BottomButtons(state, actions)
            }
        },
    ) { modifier ->
        Box(modifier = modifier.fillMaxSize()) {
            val original = state.original
            when {
                original != null -> EditorContent(state, original, actions)
                else -> EmptyState(actions.onPickMedia, modifier = Modifier.align(Alignment.Center))
            }
            // Opening the first file blocks the screen; slider re-renders don't (see ImagePreview).
            IndeterminateLoadingIndicator(show = state.isLoading && original == null)
        }
    }
}

@Composable
private fun EditorContent(state: EditorUiState, original: ImageBitmap, actions: EditorActions) {
    val video = state.video
    val editable = !state.isExporting
    Column(modifier = Modifier.fillMaxSize()) {
        ImagePreview(
            original = original,
            rendered = state.rendered,
            loading = state.isLoading,
            modifier = Modifier.weight(0.5f).fillMaxWidth(),
        )
        Column(
            modifier = Modifier
                .weight(0.5f)
                .verticalScroll(rememberScrollState())
                .padding(top = Sizes.small),
            // Gaps between the groups so each section reads as its own plate.
            verticalArrangement = Arrangement.spacedBy(Sizes.small),
        ) {
            if (video != null) FrameScrubber(video, editable, actions)
            GlitchSection(state.params, actions.onParamsChange, enabled = editable, showShuffle = video != null)
            SmearSection(state.params, actions.onParamsChange, enabled = editable, showSpeechPulse = video != null)
            if (video != null) AudioSection(video, editable, actions)
            PrivacySection(state.scrubMetadata, editable, actions)
        }
    }
}

/** Picks which frame of the video to preview and tune the effect on. */
@Composable
private fun FrameScrubber(video: VideoUiState, enabled: Boolean, actions: EditorActions) {
    Column(modifier = Modifier.padding(horizontal = Sizes.rowInset, vertical = Sizes.small)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            SupraBodyTextMedium(text = stringResource(R.string.video_preview_frame))
            Text(
                "${formatTime(video.previewPositionMs)} / ${formatTime(video.durationMs)}",
                style = MaterialTheme.typography.labelLarge,
                color = Ink5,
            )
        }
        Slider(
            value = video.previewPositionMs.toFloat(),
            onValueChange = { actions.onPreviewPositionChange(it.toLong()) },
            onValueChangeFinished = actions.onPreviewPositionCommitted,
            valueRange = 0f..video.durationMs.coerceAtLeast(1L).toFloat(),
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.secondary,
                activeTrackColor = MaterialTheme.colorScheme.secondary,
                inactiveTrackColor = Ink4,
            ),
        )
    }
}

/** Remove the soundtrack, or keep it and disguise the voice. */
@Composable
private fun AudioSection(video: VideoUiState, enabled: Boolean, actions: EditorActions) {
    val voiceOn = video.voiceEnabled && !video.removeAudio
    SupraCollapsibleArea(
        title = stringResource(R.string.section_audio),
        status = when {
            video.removeAudio -> stringResource(R.string.status_muted)
            voiceOn -> stringResource(video.voiceStyle.label)
            else -> stringResource(R.string.status_original)
        },
    ) {
        Column(modifier = Modifier.padding(horizontal = Sizes.rowInset)) {
            SettingToggleRow(
                label = stringResource(R.string.video_remove_audio),
                checked = video.removeAudio,
                onCheckedChange = actions.onRemoveAudioChange,
                enabled = enabled,
            )
            Hint(stringResource(R.string.video_remove_audio_body))
            SettingToggleRow(
                label = stringResource(R.string.voice_title),
                checked = voiceOn,
                onCheckedChange = actions.onVoiceEnabledChange,
                enabled = enabled && !video.removeAudio,
            )
            Hint(stringResource(if (video.removeAudio) R.string.voice_body_needs_audio else R.string.voice_body))
            if (voiceOn) {
                val labels = VoiceStyle.entries.map { stringResource(it.label) }
                Spacer(Modifier.height(Sizes.medium))
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    SupraDropdownPicker(
                        items = labels,
                        selectedValue = stringResource(video.voiceStyle.label),
                        width = maxWidth,
                        onItemSelected = { label -> actions.onVoiceStyleChange(VoiceStyle.entries[labels.indexOf(label)]) },
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = Sizes.small),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Sizes.small),
                ) {
                    Hint(stringResource(video.voiceStyle.description), modifier = Modifier.weight(1f))
                    SupraHardwareButton(
                        text = stringResource(if (video.isPreviewingVoice) R.string.voice_stop_preview else R.string.voice_preview),
                        tone = SupraButtonTone.NEUTRAL,
                        enabled = enabled,
                        onClick = actions.onToggleVoicePreview,
                    )
                }
            }
        }
    }
}

@Composable
private fun PrivacySection(scrubMetadata: Boolean, enabled: Boolean, actions: EditorActions) {
    SupraCollapsibleArea(
        title = stringResource(R.string.section_privacy),
        status = stringResource(if (scrubMetadata) R.string.status_scrubbed else R.string.status_kept),
    ) {
        Column(modifier = Modifier.padding(horizontal = Sizes.rowInset, vertical = Sizes.small)) {
            SettingToggleRow(
                label = stringResource(R.string.option_scrub_metadata),
                checked = scrubMetadata,
                onCheckedChange = actions.onScrubMetadataChange,
                enabled = enabled,
            )
            Hint(stringResource(R.string.option_scrub_metadata_body))
        }
    }
}

/** One accent per screen: the committing action (Save or Export). Reshuffle is neutral. */
@Composable
private fun BottomButtons(state: EditorUiState, actions: EditorActions) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = Sizes.small),
        horizontalArrangement = Arrangement.spacedBy(Sizes.small),
    ) {
        SupraHardwareButton(
            modifier = Modifier.weight(1f),
            text = stringResource(R.string.editor_reseed),
            tone = SupraButtonTone.NEUTRAL,
            fullWidth = true,
            onClick = actions.onReseed,
        )
        if (state.kind == MediaKind.VIDEO) {
            SupraHardwareButton(
                modifier = Modifier.weight(1f),
                text = stringResource(R.string.editor_export),
                fullWidth = true,
                enabled = state.canExport,
                onClick = actions.onExportVideo,
            )
        } else {
            SupraHardwareButton(
                modifier = Modifier.weight(1f),
                text = stringResource(R.string.editor_save),
                fullWidth = true,
                enabled = state.canSave,
                onClick = actions.onSavePhoto,
            )
        }
    }
}

@Composable
private fun ExportProgressBar(progress: ExportProgress, onCancel: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Sizes.small, vertical = Sizes.medium)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                val fraction = progress.fraction
                SupraBodyTextMedium(
                    text = when {
                        progress.stage == ExportStage.ANALYZING_AUDIO -> stringResource(R.string.video_analyzing_audio)
                        fraction == null -> stringResource(R.string.video_exporting)
                        else -> stringResource(R.string.video_exporting_percent, (fraction * 100).roundToInt())
                    },
                )
                Hint(stringResource(R.string.video_export_hint))
            }
            SupraHardwareButton(
                text = stringResource(R.string.editor_cancel),
                tone = SupraButtonTone.DANGER,
                onClick = onCancel,
            )
        }
        Spacer(Modifier.height(Sizes.small))
        val fraction = progress.fraction
        val barColor = MaterialTheme.colorScheme.secondary
        if (fraction == null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = barColor, trackColor = Ink4)
        } else {
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth(), color = barColor, trackColor = Ink4)
        }
    }
}

/** Shows the glitched image; pressing and holding swaps in the original for comparison. */
@Composable
private fun ImagePreview(
    original: ImageBitmap,
    rendered: ImageBitmap?,
    loading: Boolean,
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
        // Loading a new frame or photo covers the preview so stale content isn't mistaken for it.
        IndeterminateLoadingIndicator(show = loading)
    }
}

@Composable
private fun StatusLine(faceCount: Int, kind: MediaKind?, modifier: Modifier = Modifier) {
    val isVideo = kind == MediaKind.VIDEO
    val text = when {
        faceCount > 0 -> pluralStringResource(R.plurals.editor_face_count, faceCount, faceCount) +
            " · " + stringResource(R.string.editor_hold_hint)
        isVideo -> stringResource(R.string.editor_no_faces_in_frame)
        else -> stringResource(R.string.editor_no_faces)
    }
    SupraBodyTextSmall(
        text = text,
        color = if (faceCount == 0 && !isVideo) MaterialTheme.colorScheme.error else Ink5,
        modifier = modifier,
    )
}

@Composable
private fun EmptyState(onPickMedia: () -> Unit, modifier: Modifier = Modifier) {
    // Left-aligned display block, like Supra's own landing screens.
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = Sizes.medium),
        horizontalAlignment = Alignment.Start,
    ) {
        SupraDisplayBlock(text = stringResource(R.string.editor_empty_title))
        Spacer(Modifier.height(Sizes.medium))
        SupraBodyTextMedium(
            text = stringResource(R.string.editor_empty_body),
            color = Ink5,
        )
        Spacer(Modifier.height(Sizes.large))
        // The text reads left-aligned, but the call to action sits centred.
        SupraHardwareButton(
            modifier = Modifier.align(Alignment.CenterHorizontally),
            text = stringResource(R.string.editor_choose_media),
            onClick = onPickMedia,
        )
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

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
