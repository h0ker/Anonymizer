package com.hoker.anonymizer.ui.theme

import androidx.compose.runtime.Composable
import com.hoker.supra.presentation.theme.SupraTheme
import com.hoker.supra.presentation.theme.UiTheme

/** The app's look comes from the Supra design system: its dark Ink palette with a single accent. */
@Composable
fun AnonymizerTheme(content: @Composable () -> Unit) {
    SupraTheme(
        darkTheme = true,
        customColorScheme = UiTheme.LIVE.colorScheme,
        content = content,
    )
}
