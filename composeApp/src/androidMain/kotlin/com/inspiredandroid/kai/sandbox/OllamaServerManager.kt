package com.inspiredandroid.kai.sandbox

import android.content.Context
import android.os.Build
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.get
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

sealed class OllamaState {
    object NotInstalled : OllamaState()
    data class Downloading(val progress: Float) : OllamaState()
    object Installing : OllamaState()
    object Starting : OllamaState()
    object Running : OllamaState()
    object Stopped : OllamaState()
    data class Error(val message: String) : OllamaState()
}

@Serializable
data class OllamaModelInfo(
    val name: String,
    val size: Long = 0,
    val digest: String = "",
)

@Serializable
data class OllamaListResponse(val models: List<OllamaModelInfo> = emptyList())

/**
 * Manages the Ollama LLM server running inside the proot Alpine Linux sandbox.
 * Downloads the static Ollama binary, installs it to the sandbox rootfs, and
 * manages the server lifecycle. Exposes an OpenAI-compatible API at localhost:11434.
 */
class OllamaServerManager(
    private val context: Context,
    private val sandboxManager: LinuxSandboxManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<OllamaState>(OllamaState.Stopped)
    val state: StateFlow<OllamaState> = _state

    private var serverHandle: ProotHandle? = null

    val port = 11434
    val baseUrl = "http://localhost:$port"

    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient(Android) {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) { requestTimeoutMillis = 30_000 }
    }

    init {
        scope.launch {
            _state.value = if (isInstalled()) OllamaState.Stopped else OllamaState.NotInstalled
        }
    }

    private fun getArch(): String = when {
        Build.SUPPORTED_ABIS.firstOrNull()?.startsWith("arm64") == true -> "arm64"
        Build.SUPPORTED_ABIS.firstOrNull()?.startsWith("x86_64") == true -> "amd64"
        else -> "arm64"
    }

    fun isInstalled(): Boolean =
        File(sandboxManager.rootfsPath, "usr/local/bin/ollama").exists()

    fun install() {
        scope.launch {
            try {
                _state.value = OllamaState.Downloading(0f)
                val arch = getArch()
                val downloadUrl =
                    "https://github.com/ollama/ollama/releases/latest/download/ollama-linux-$arch"
                val targetFile = File(sandboxManager.rootfsPath, "usr/local/bin/ollama")
                targetFile.parentFile?.mkdirs()

                withContext(Dispatchers.IO) {
                    val conn = URL(downloadUrl).openConnection() as HttpURLConnection
                    conn.instanceFollowRedirects = true
                    conn.connect()
                    val total = conn.contentLengthLong.coerceAtLeast(1)
                    var downloaded = 0L
                    conn.inputStream.use { input ->
                        targetFile.outputStream().use { out ->
                            val buf = ByteArray(16384)
                            var n: Int
                            while (input.read(buf).also { n = it } != -1) {
                                out.write(buf, 0, n)
                                downloaded += n
                                _state.value =
                                    OllamaState.Downloading(downloaded.toFloat() / total)
                            }
                        }
                    }
                    conn.disconnect()
                    targetFile.setExecutable(true, false)
                }

                _state.value = OllamaState.Installing
                val executor = sandboxManager.createProotExecutor()
                executor.execute("chmod +x /usr/local/bin/ollama", timeoutSeconds = 10)
                executor.execute("mkdir -p /root/.ollama/models", timeoutSeconds = 5)

                _state.value = OllamaState.Stopped
                android.util.Log.i("OllamaServerManager", "Ollama installed at ${targetFile.absolutePath}")
            } catch (e: Exception) {
                android.util.Log.e("OllamaServerManager", "Install failed", e)
                _state.value = OllamaState.Error("Install failed: ${e.message}")
            }
        }
    }

    fun start() {
        if (_state.value is OllamaState.Running || _state.value is OllamaState.Starting) return
        scope.launch {
            try {
                _state.value = OllamaState.Starting
                val executor = sandboxManager.createProotExecutor()
                serverHandle?.cancel()
                serverHandle = executor.executeStreaming(
                    command = "OLLAMA_HOST=0.0.0.0:$port OLLAMA_MODELS=/root/.ollama/models ollama serve",
                    onStdout = { android.util.Log.d("Ollama", it) },
                    onStderr = { android.util.Log.d("Ollama", it) },
                )
                repeat(30) {
                    delay(1000)
                    if (isServerReady()) {
                        _state.value = OllamaState.Running
                        return@launch
                    }
                }
                _state.value = OllamaState.Error("Server failed to start within 30s")
            } catch (e: Exception) {
                _state.value = OllamaState.Error("Start failed: ${e.message}")
            }
        }
    }

    fun stop() {
        serverHandle?.cancel()
        serverHandle = null
        _state.value = OllamaState.Stopped
    }

    private suspend fun isServerReady(): Boolean = withContext(Dispatchers.IO) {
        try {
            val conn = URL("$baseUrl/api/tags").openConnection() as HttpURLConnection
            conn.connectTimeout = 500
            conn.readTimeout = 500
            conn.connect()
            val ok = conn.responseCode == 200
            conn.disconnect()
            ok
        } catch (_: Exception) {
            false
        }
    }

    suspend fun listModels(): List<OllamaModelInfo> = withContext(Dispatchers.IO) {
        try {
            val response = client.get("$baseUrl/api/tags")
            json.decodeFromString(OllamaListResponse.serializer(), response.bodyAsText()).models
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun pullModel(modelName: String, onProgress: (String) -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val executor = sandboxManager.createProotExecutor()
                val handle = executor.executeStreaming(
                    command = "ollama pull $modelName",
                    onStdout = { onProgress(it) },
                    onStderr = { onProgress(it) },
                )
                handle.awaitExit() == 0
            } catch (_: Exception) {
                false
            }
        }

    suspend fun generate(model: String, prompt: String): String = withContext(Dispatchers.IO) {
        try {
            val body = """{"model":${json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(model))},"prompt":${json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(prompt))},"stream":false}"""
            val response = client.post("$baseUrl/api/generate") {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            val resp = json.parseToJsonElement(response.bodyAsText())
            (resp as? JsonObject)?.get("response")
                ?.let { (it as? JsonPrimitive)?.content } ?: response.bodyAsText()
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
}
