package com.hoker.anonymizer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.hoker.anonymizer.presentation.editor.EditorRoute
import com.hoker.anonymizer.ui.theme.AnonymizerTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AnonymizerTheme {
                EditorRoute()
            }
        }
    }
}
