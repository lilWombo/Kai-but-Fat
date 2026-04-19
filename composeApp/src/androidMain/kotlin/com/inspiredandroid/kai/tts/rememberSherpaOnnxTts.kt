// composeApp/src/androidMain/kotlin/com/inspiredandroid/kai/tts/rememberSherpaOnnxTts.kt
package com.inspiredandroid.kai.tts

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * Creates and remembers a [SherpaOnnxTtsInstance], triggering model download and
 * engine initialisation asynchronously. Returns null until [SherpaOnnxTtsInstance.prepare]
 * completes successfully.
 */
@Composable
fun rememberSherpaOnnxTts(): SherpaOnnxTtsInstance? {
    val context = LocalContext.current
    val instance = remember { SherpaOnnxTtsInstance(context) }
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        runCatching { instance.prepare() }
        ready = true
    }
    return if (ready) instance else null
}
