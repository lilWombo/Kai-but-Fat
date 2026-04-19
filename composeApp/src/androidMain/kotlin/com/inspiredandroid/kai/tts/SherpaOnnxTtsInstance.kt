// composeApp/src/androidMain/kotlin/com/inspiredandroid/kai/tts/SherpaOnnxTtsInstance.kt
package com.inspiredandroid.kai.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.marc_apps.tts.TextToSpeechInstance
import nl.marc_apps.tts.Voice
import nl.marc_apps.tts.experimental.ExperimentalVoiceApi
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private const val MODEL_DIR_NAME = "sherpa-tts-amy"
private const val MODEL_URL =
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/" +
        "vits-piper-en_US-amy-low.tar.bz2"

/**
 * A [TextToSpeechInstance] backed by sherpa-onnx offline TTS (VITS piper en_US-amy-low).
 * Call [prepare] once before using [say]. The model (~63 MB) is downloaded to [Context.filesDir]
 * on first use and reused on subsequent launches.
 */
class SherpaOnnxTtsInstance(private val context: Context) : TextToSpeechInstance {

    private var tts: OfflineTts? = null
    private var audioTrack: AudioTrack? = null
    @Volatile private var stopRequested = false

    val isReady: Boolean get() = tts != null

    /**
     * Downloads the model if absent, then initialises [OfflineTts] and [AudioTrack].
     * Must be called from an IO-safe coroutine scope (e.g. [Dispatchers.IO]).
     */
    suspend fun prepare() = withContext(Dispatchers.IO) {
        val modelDir = File(context.filesDir, MODEL_DIR_NAME)
        val modelFile = File(modelDir, "model.onnx")
        if (!modelFile.exists()) {
            downloadAndExtract(modelDir)
        }
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model   = modelFile.absolutePath,
                    tokens  = File(modelDir, "tokens.txt").absolutePath,
                    dataDir = modelDir.absolutePath,
                ),
                numThreads = 2,
                provider   = "cpu",
            ),
        )
        val engine = OfflineTts(config = config)
        tts = engine
        initAudioTrack(engine.sampleRate())
    }

    private fun initAudioTrack(sampleRate: Int) {
        val bufSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        audioTrack = AudioTrack(
            AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .build(),
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setSampleRate(sampleRate)
                .build(),
            bufSize,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE,
        )
        audioTrack?.play()
    }

    override suspend fun say(text: String, clearQueue: Boolean) {
        val engine = tts ?: return
        stopRequested = false
        withContext(Dispatchers.IO) {
            engine.generateWithCallback(
                text     = text,
                sid      = 0,
                speed    = 1.0f,
                callback = { samples ->
                    if (stopRequested) {
                        audioTrack?.pause()
                        audioTrack?.flush()
                        0
                    } else {
                        audioTrack?.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
                        1
                    }
                },
            )
        }
    }

    override fun stopSpeaking() {
        stopRequested = true
        audioTrack?.pause()
        audioTrack?.flush()
    }

    override fun close() {
        stopSpeaking()
        audioTrack?.release()
        audioTrack = null
    }

    @OptIn(ExperimentalVoiceApi::class)
    override var currentVoice: Voice? = null

    @OptIn(ExperimentalVoiceApi::class)
    override val voices: Set<Voice> = emptySet()

    // ── model download ──────────────────────────────────────────────────────────

    private fun downloadAndExtract(modelDir: File) {
        modelDir.mkdirs()
        val tmp = File(context.cacheDir, "sherpa-tts-model.tar.bz2")
        try {
            val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 30_000
            conn.readTimeout    = 300_000
            try {
                conn.inputStream.use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
            } finally {
                conn.disconnect()
            }
            extractTarBz2(tmp, modelDir)
        } finally {
            tmp.delete()
        }
    }

    /**
     * Extracts a .tar.bz2 archive into [dest], stripping the top-level directory component
     * (mirrors `tar -xjf archive.tar.bz2 --strip-components=1 -C dest`).
     */
    private fun extractTarBz2(archive: File, dest: File) {
        archive.inputStream().buffered().use { fis ->
            BZip2CompressorInputStream(fis).use { bz2 ->
                TarArchiveInputStream(bz2).use { tar ->
                    var entry = tar.nextEntry
                    while (entry != null) {
                        // strip the first path component (the top-level dir in the archive)
                        val stripped = entry.name.substringAfter('/')
                        if (stripped.isNotEmpty() && !entry.isDirectory) {
                            val outFile = File(dest, stripped)
                            outFile.parentFile?.mkdirs()
                            outFile.outputStream().use { tar.copyTo(it) }
                        }
                        entry = tar.nextEntry
                    }
                }
            }
        }
    }
}
