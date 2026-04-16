package com.inspiredandroid.kai.setup

import android.content.Context
import com.inspiredandroid.kai.data.AppSettings
import com.inspiredandroid.kai.sandbox.LinuxSandboxManager
import com.inspiredandroid.kai.sandbox.OllamaServerManager
import com.inspiredandroid.kai.sandbox.SandboxState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

sealed class SetupPhase {
    /** Setup has not been triggered yet */
    object Idle : SetupPhase()

    /** Setup was completed on a previous launch */
    object AlreadyComplete : SetupPhase()

    /** Active step in progress */
    data class InProgress(val step: String, val stepIndex: Int, val totalSteps: Int) : SetupPhase()

    /** All steps succeeded */
    object Complete : SetupPhase()

    /** A step failed (non-fatal steps log a warning and continue) */
    data class Failed(val step: String, val error: String) : SetupPhase()
}

/**
 * Orchestrates the full autonomous first-run setup on installation:
 *
 * 1. Install Debian bookworm-slim proot sandbox (rootfs via debuerreotype CDN)
 * 2. Install base packages: python3, python3-pip, git, curl, wget, nodejs
 * 3. Install Python AI stack: chromadb, smolagents, openai, tree-sitter, uvicorn
 * 4. Download & install the Ollama binary for the device architecture
 *
 * Runs fully in the background on IO dispatcher. Progress exposed as [SetupPhase] StateFlow.
 * Guarded by a boolean flag in AppSettings — never runs twice.
 */
class FirstRunSetupManager(
    private val context: Context,
    private val appSettings: AppSettings,
    private val sandboxManager: LinuxSandboxManager,
    private val ollamaManager: OllamaServerManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _phase = MutableStateFlow<SetupPhase>(SetupPhase.Idle)
    val phase: StateFlow<SetupPhase> = _phase

    /**
     * Called from Application.onCreate(). Checks the completion flag and
     * runs setup in the background if this is the first launch.
     */
    fun checkAndRun() {
        if (appSettings.isFirstRunSetupComplete()) {
            _phase.value = SetupPhase.AlreadyComplete
            return
        }
        scope.launch { runSetupInternal() }
    }

    /** Force re-run setup regardless of completion flag (for troubleshooting). */
    fun forceRun() {
        scope.launch { runSetupInternal() }
    }

    private suspend fun runSetupInternal() {
        val steps = listOf(
            "Installing Linux sandbox",
            "Installing base packages",
            "Installing Python AI stack",
            "Installing Ollama",
            "Pulling default model (gemma3:1b)",
        )

        fun phase(i: Int) = SetupPhase.InProgress(steps[i], i + 1, steps.size)

        try {
            // ── Step 1: Sandbox ─────────────────────────────────────────────
            _phase.value = phase(0)
            if (sandboxManager.state.value !is SandboxState.Ready) {
                sandboxManager.setup()
                val result = sandboxManager.state.first {
                    it is SandboxState.Ready || it is SandboxState.Error
                }
                if (result is SandboxState.Error) {
                    _phase.value = SetupPhase.Failed(steps[0], result.message)
                    return
                }
            }

            // ── Step 2: Base packages ────────────────────────────────────────
            _phase.value = phase(1)
            if (!sandboxManager.arePackagesInstalled()) {
                sandboxManager.installPackages()
                val result = sandboxManager.state.first {
                    it is SandboxState.Ready || it is SandboxState.Error
                }
                if (result is SandboxState.Error) {
                    _phase.value = SetupPhase.Failed(steps[1], result.message)
                    return
                }
            }

            // ── Step 3: Python AI stack ──────────────────────────────────────
            _phase.value = phase(2)
            val executor = sandboxManager.createProotExecutor()
            val chromaCheck = executor.execute("python3 -c 'import chromadb'", timeoutSeconds = 10)
            if ((chromaCheck["exit_code"] as? Int) != 0) {
                val pip = executor.execute(
                    "pip3 install --quiet --no-cache-dir chromadb smolagents openai tree-sitter uvicorn requests",
                    timeoutSeconds = 300,
                )
                if ((pip["exit_code"] as? Int) != 0) {
                    // Non-fatal: log and continue — tools will report install error when invoked
                    android.util.Log.w(
                        "FirstRunSetup",
                        "Python AI stack install had issues: ${pip["stderr"]}",
                    )
                }
            }

            // ── Step 4: Ollama binary ────────────────────────────────────────
            _phase.value = phase(3)
            if (!ollamaManager.isInstalled()) {
                ollamaManager.install()
                repeat(90) {
                    delay(1000)
                    if (ollamaManager.isInstalled()) return@repeat
                }
            }

            // ── Step 5: Pre-pull gemma3:1b (0.8 GB) ─────────────────────────
            // Start the server and pull the smallest curated model so the user
            // has a working local LLM immediately without any manual action.
            _phase.value = phase(4)
            if (ollamaManager.isInstalled()) {
                runCatching {
                    ollamaManager.start()
                    delay(4_000) // wait for server to bind
                    val executor = sandboxManager.createProotExecutor()
                    val result = executor.execute(
                        "OLLAMA_HOST=127.0.0.1:11434 ollama pull gemma3:1b",
                        timeoutSeconds = 600,
                    )
                    if ((result["exit_code"] as? Int) == 0) {
                        android.util.Log.i("FirstRunSetup", "gemma3:1b pulled successfully")
                    } else {
                        android.util.Log.w("FirstRunSetup", "gemma3:1b pull: ${result["stderr"]}")
                    }
                }.onFailure {
                    android.util.Log.w("FirstRunSetup", "gemma3:1b pull skipped: ${it.message}")
                }
            }

            // ── Done ─────────────────────────────────────────────────────────
            appSettings.setFirstRunSetupComplete(true)
            _phase.value = SetupPhase.Complete
            android.util.Log.i("FirstRunSetup", "First-run setup complete")
        } catch (e: Exception) {
            android.util.Log.e("FirstRunSetup", "Setup failed", e)
            _phase.value = SetupPhase.Failed("Setup", e.message ?: "Unknown error")
        }
    }
}
