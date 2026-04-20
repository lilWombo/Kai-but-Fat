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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
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
 * [TextToSpeechInstance] backed by sherpa-onnx offline VITS TTS (en_US-amy-low).
 * Call [prepare] once (from an IO coroutine) before use. The ~63 MB model is
 * downloaded to [Context.filesDir] on first launch and reused thereafter.
 */
class SherpaOnnxTtsInstance(private val context: Context) : TextToSpeechInstance {

    private val job   = SupervisorJob()
    private val scope  = CoroutineScope(job + Dispatchers.IO)
    private var tts: OfflineTts? = null
    private var audioTrack: AudioTrack? = null
    @Volatile private var stopRequested = false

    // ── TextToSpeechInstance state ──────────────────────────────────────────
    private val _isSynthesizing = MutableStateFlow(false)
    override val isSynthesizing: StateFlow<Boolean> = _isSynthesizing

    private val _isWarmingUp = MutableStateFlow(false)
    override val isWarmingUp: StateFlow<Boolean> = _isWarmingUp

    override var volume: Int = 100
    override var isMuted: Boolean = false
    override var pitch: Float = 1.0f
    override var rate: Float = 1.0f
    override val language: String = "en-US"

    @OptIn(ExperimentalVoiceApi::class)
    override val voices: Sequence<Voice> = emptySequence()

    @OptIn(ExperimentalVoiceApi::class)
    override var currentVoice: Voice? = null

    // ── Lifecycle ───────────────────────────────────────────────────────────

    /**
     * Downloads the model if absent, then initialises [OfflineTts] and [AudioTrack].
     * Safe to call multiple times — subsequent calls are no-ops if already ready.
     */
    suspend fun prepare() = withContext(Dispatchers.IO) {
        if (tts != null) return@withContext
        _isWarmingUp.value = true
        try {
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
        } finally {
            _isWarmingUp.value = false
        }
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

    // ── TextToSpeechInstance speak API ──────────────────────────────────────

    override suspend fun say(
        text: String,
        clearQueue: Boolean,
        clearQueueOnCancellation: Boolean,
    ) {
        if (isMuted) return
        val engine = tts ?: return
        if (clearQueue) stopRequested = true
        stopRequested = false
        _isSynthesizing.value = true
        try {
            withContext(Dispatchers.IO) {
                engine.generateWithCallback(
                    text  = text,
                    sid   = 0,
                    speed = rate,
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
        } finally {
            _isSynthesizing.value = false
        }
    }

    override fun say(
        text: String,
        clearQueue: Boolean,
        callback: (Result<Unit>) -> Unit,
    ) {
        scope.launch {
            runCatching { say(text, clearQueue) }
                .also { callback(it) }
        }
    }

    override fun enqueue(text: String, clearQueue: Boolean) {
        scope.launch { say(text, clearQueue) }
    }

    override fun plusAssign(text: String) {
        enqueue(text, clearQueue = false)
    }

    override fun stop() {
        stopRequested = true
        audioTrack?.pause()
        audioTrack?.flush()
        _isSynthesizing.value = false
    }

    override fun close() {
        stop()
        audioTrack?.release()
        audioTrack = null
        job.cancel()
    }

    // ── Model download ──────────────────────────────────────────────────────

    private fun downloadAndExtract(modelDir: File) {
        modelDir.mkdirs()
        val tmp = File(context.cacheDir, "sherpa-tts-model.tar.bz2")
        try {
            val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 30_000
            conn.readTimeout    = 300_000
            try {
                conn.inputStream.use { inp ->
                    tmp.outputStream().use { out -> inp.copyTo(out) }
                }
            } finally {
                conn.disconnect()
            }
            extractTarBz2(tmp, modelDir)
        } finally {
            tmp.delete()
        }
    }

    private fun extractTarBz2(archive: File, dest: File) {
        archive.inputStream().buffered().use { fis ->
            BZip2CompressorInputStream(fis).use { bz2 ->
                TarArchiveInputStream(bz2).use { tar ->
                    var entry = tar.nextEntry
                    while (entry != null) {
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
