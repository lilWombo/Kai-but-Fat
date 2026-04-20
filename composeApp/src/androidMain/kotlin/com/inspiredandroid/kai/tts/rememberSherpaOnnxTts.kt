// composeApp/src/androidMain/kotlin/com/inspiredandroid/kai/tts/rememberSherpaOnnxTts.kt
package com.inspiredandroid.kai.tts

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

private const val TAG = "SherpaOnnxTts"

/**
 * Creates and remembers a [SherpaOnnxTtsInstance], triggering model download and
 * engine initialisation asynchronously. Returns null until [SherpaOnnxTtsInstance.prepare]
 * completes successfully. Any initialisation failure is logged via [Log.e] and the
 * composable continues to return null (TTS silently unavailable rather than crashing).
 */
@Composable
fun rememberSherpaOnnxTts(): SherpaOnnxTtsInstance? {
    val context = LocalContext.current
    val instance = remember { SherpaOnnxTtsInstance(context) }
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        runCatching { instance.prepare() }
            .onFailure { e -> Log.e(TAG, "TTS engine failed to initialise — TTS will be unavailable", e) }
        ready = true
    }
    return if (ready) instance else null
}
