package com.inspiredandroid.kai

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigation.compose.rememberNavController
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.init
import nl.marc_apps.tts.TextToSpeechEngine
import nl.marc_apps.tts.rememberTextToSpeechOrNull
import org.koin.android.ext.android.get

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        FileKit.init(this)
        autoStartDaemon()

        setContent {
            val isDarkTheme = isSystemInDarkTheme()
            LaunchedEffect(isDarkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = if (isDarkTheme) {
                        SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(
                            android.graphics.Color.TRANSPARENT,
                            android.graphics.Color.TRANSPARENT,
                        )
                    },
                    navigationBarStyle = if (isDarkTheme) {
                        SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(
                            android.graphics.Color.TRANSPARENT,
                            android.graphics.Color.TRANSPARENT,
                        )
                    },
                )
            }
            val navController = rememberNavController()
            // Defer TTS initialization until after the first frame
            var ttsReady by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { ttsReady = true }
            val textToSpeech = if (ttsReady) {
                rememberTextToSpeechOrNull(TextToSpeechEngine.Google)
            } else {
                null
            }
            App(
                navController = navController,
                textToSpeech = textToSpeech,
                isKoinStarted = true,
                onAppOpens = { appOpens ->
                    if (appOpens % 5 == 0) {
                        requestReview(this@MainActivity)
                    }
                },
            )
        }
    }

    private fun autoStartDaemon() {
        val daemonController: DaemonController = get()
        if (daemonController is AndroidDaemonController && daemonController.shouldAutoStart()) {
            daemonController.start()
        }
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App(navController = rememberNavController())
}
