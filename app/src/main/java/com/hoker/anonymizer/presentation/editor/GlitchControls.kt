package com.hoker.anonymizer.presentation.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.hoker.anonymizer.R
import com.hoker.anonymizer.domain.model.GlitchParams
import com.hoker.anonymizer.ui.theme.AnonymizerTheme
import kotlin.math.roundToInt

@Composable
fun GlitchControls(
    params: GlitchParams,
    onParamsChange: ((GlitchParams) -> GlitchParams) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** The speech pulse needs an audio track, so it's only offered for videos. */
    showSpeechPulse: Boolean = false,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LabeledSlider(
            label = stringResource(R.string.control_angle),
            valueText = "${params.angleDegrees.roundToInt()}°",
            value = params.angleDegrees,
            valueRange = -180f..180f,
            enabled = enabled,
            onValueChange = { v -> onParamsChange { it.copy(angleDegrees = v) } },
        )
        ControlLabel(
            label = stringResource(R.string.control_threshold),
            valueText = "${params.lowThreshold.percent()} – ${params.highThreshold.percent()}",
        )
        RangeSlider(
            value = params.lowThreshold..params.highThreshold,
            enabled = enabled,
            onValueChange = { range ->
                onParamsChange { it.copy(lowThreshold = range.start, highThreshold = range.endInclusive) }
            },
            valueRange = 0f..1f,
        )
        LabeledSlider(
            label = stringResource(R.string.control_smear),
            valueText = params.smear.percent(),
            value = params.smear,
            valueRange = 0f..1f,
            enabled = enabled,
            onValueChange = { v -> onParamsChange { it.copy(smear = v) } },
        )
        LabeledSlider(
            label = stringResource(R.string.control_streak_length),
            valueText = params.streakLength.percent(),
            value = params.streakLength,
            valueRange = 0.05f..1f,
            enabled = enabled,
            onValueChange = { v -> onParamsChange { it.copy(streakLength = v) } },
        )
        LabeledSlider(
            label = stringResource(R.string.control_bleed),
            valueText = params.bleed.percent(),
            value = params.bleed,
            valueRange = 0f..0.4f,
            enabled = enabled,
            onValueChange = { v -> onParamsChange { it.copy(bleed = v) } },
        )
        LabeledSlider(
            label = stringResource(R.string.control_trail),
            valueText = params.trail.percent(),
            value = params.trail,
            valueRange = 0f..4f,
            enabled = enabled,
            onValueChange = { v -> onParamsChange { it.copy(trail = v) } },
        )
        LabeledSlider(
            label = stringResource(R.string.control_raggedness),
            valueText = params.raggedness.percent(),
            value = params.raggedness,
            valueRange = 0f..1f,
            enabled = enabled,
            onValueChange = { v -> onParamsChange { it.copy(raggedness = v) } },
        )
        LabeledSlider(
            label = stringResource(R.string.control_tint),
            valueText = params.tintStrength.percent(),
            value = params.tintStrength,
            valueRange = 0f..1f,
            enabled = enabled,
            onValueChange = { v -> onParamsChange { it.copy(tintStrength = v) } },
        )
        if (showSpeechPulse) {
            LabeledSlider(
                label = stringResource(R.string.control_speech_pulse),
                valueText = params.speechPulse.percent(),
                value = params.speechPulse,
                valueRange = 0f..1f,
                enabled = enabled,
                onValueChange = { v -> onParamsChange { it.copy(speechPulse = v) } },
            )
            Text(
                stringResource(R.string.control_speech_pulse_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    onValueChange: (Float) -> Unit,
) {
    Column {
        ControlLabel(label, valueText)
        Slider(value = value, onValueChange = onValueChange, valueRange = valueRange, enabled = enabled)
    }
}

@Composable
private fun ControlLabel(label: String, valueText: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Text(valueText, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun Float.percent(): String = "${(this * 100).roundToInt()}%"

@Preview(showBackground = true)
@Composable
private fun GlitchControlsPreview() {
    AnonymizerTheme {
        GlitchControls(params = GlitchParams(), onParamsChange = {})
    }
}
