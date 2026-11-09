package com.hoker.anonymizer.presentation.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.hoker.anonymizer.R
import com.hoker.anonymizer.domain.model.GlitchParams
import com.hoker.anonymizer.ui.theme.AnonymizerTheme
import com.hoker.supra.presentation.controls.SupraMultiSelector
import com.hoker.supra.presentation.sizes.Sizes
import com.hoker.supra.presentation.surfaces.SupraCollapsibleArea
import com.hoker.supra.presentation.text.SupraBodyTextMedium
import com.hoker.supra.presentation.text.SupraBodyTextSmall
import com.hoker.supra.presentation.theme.Ink4
import com.hoker.supra.presentation.theme.Ink5
import kotlin.math.roundToInt

/** Shape of the glitch: direction, which pixels sort, and how far it reaches past the face. */
@Composable
fun GlitchSection(
    params: GlitchParams,
    onParamsChange: ((GlitchParams) -> GlitchParams) -> Unit,
    enabled: Boolean,
) {
    SupraCollapsibleArea(
        title = stringResource(R.string.section_glitch),
        status = "${params.angleDegrees.roundToInt()}°",
        initiallyOpen = true,
    ) {
        Column(modifier = Modifier.padding(horizontal = Sizes.rowInset), verticalArrangement = Arrangement.spacedBy(Sizes.tiny)) {
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
                colors = supraSliderColors(),
                onValueChange = { range ->
                    onParamsChange { it.copy(lowThreshold = range.start, highThreshold = range.endInclusive) }
                },
                valueRange = 0f..1f,
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
        }
    }
}

/** Texture and colour on top of the sort: smearing, tint and (videos only) the speech pulse. */
@Composable
fun SmearSection(
    params: GlitchParams,
    onParamsChange: ((GlitchParams) -> GlitchParams) -> Unit,
    enabled: Boolean,
    /** The speech pulse needs an audio track, so it's only offered for videos. */
    showSpeechPulse: Boolean,
) {
    SupraCollapsibleArea(
        title = stringResource(R.string.section_smear),
        status = params.smear.percent(),
    ) {
        Column(modifier = Modifier.padding(horizontal = Sizes.rowInset), verticalArrangement = Arrangement.spacedBy(Sizes.tiny)) {
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
                Hint(stringResource(R.string.control_speech_pulse_hint))
            }
        }
    }
}

/**
 * Supra's on/off control: a label with a two-way segmented selector, as in Supra's own settings.
 * Disabled rows dim and ignore taps (the selector itself has no disabled state).
 */
@Composable
fun SettingToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    val off = stringResource(R.string.setting_off)
    val on = stringResource(R.string.setting_on)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Sizes.small)
            .alpha(if (enabled) 1f else 0.4f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            modifier = Modifier.weight(1f).padding(end = Sizes.small),
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        SupraMultiSelector(
            modifier = Modifier.height(36.dp).width(150.dp),
            options = listOf(off, on),
            selectedOption = if (checked) on else off,
            onOptionSelect = { option -> if (enabled) onCheckedChange(option == on) },
        )
    }
}

/** Small dim explanatory text under a control. */
@Composable
fun Hint(text: String, modifier: Modifier = Modifier) {
    SupraBodyTextSmall(modifier = modifier, text = text, color = Ink5)
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
        Slider(value = value, onValueChange = onValueChange, valueRange = valueRange, enabled = enabled, colors = supraSliderColors())
    }
}

@Composable
private fun ControlLabel(label: String, valueText: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        SupraBodyTextMedium(text = label)
        Text(valueText, style = MaterialTheme.typography.labelLarge, color = Ink5)
    }
}

/** Supra has no slider, so Material's is dressed in Supra colours: accent for state, Ink for the rest. */
@Composable
private fun supraSliderColors(): SliderColors = SliderDefaults.colors(
    thumbColor = MaterialTheme.colorScheme.secondary,
    activeTrackColor = MaterialTheme.colorScheme.secondary,
    inactiveTrackColor = Ink4,
    activeTickColor = MaterialTheme.colorScheme.secondary,
    inactiveTickColor = Ink4,
)

private fun Float.percent(): String = "${(this * 100).roundToInt()}%"

@Preview(showBackground = true, backgroundColor = 0xFF0B0D0E)
@Composable
private fun GlitchSectionsPreview() {
    AnonymizerTheme {
        Column {
            GlitchSection(params = GlitchParams(), onParamsChange = {}, enabled = true)
            SmearSection(params = GlitchParams(), onParamsChange = {}, enabled = true, showSpeechPulse = true)
        }
    }
}
