package com.inspiredandroid.kai.tools

import com.inspiredandroid.kai.network.tools.ParameterSchema
import com.inspiredandroid.kai.network.tools.Tool
import com.inspiredandroid.kai.network.tools.ToolInfo
import com.inspiredandroid.kai.network.tools.ToolSchema
import com.inspiredandroid.kai.sandbox.LinuxSandboxManager
import com.inspiredandroid.kai.sandbox.SandboxState
import org.koin.java.KoinJavaComponent.inject
import kotlin.time.Duration.Companion.minutes

/**
 * Runs a fully autonomous Python agent loop inside the proot sandbox using
 * smolagents (HuggingFace). The agent receives a task, writes and executes
 * Python/bash code steps iteratively until the task is solved.
 *
 * Connects to a local Ollama server or any OpenAI-compatible API for the
 * driving LLM. Falls back to direct bash execution if no model_url is set.
 */
object AgentOrchestratorTool : Tool {

    private val sandboxManager: LinuxSandboxManager by inject(LinuxSandboxManager::class.java)

    override val timeout = 5.minutes

    override val schema = ToolSchema(
        name = "run_agent",
        description = """Run a multi-step autonomous agent inside the Linux sandbox.
The agent receives a task, writes Python/bash code, executes it, observes results, and iterates.
Powered by smolagents (HuggingFace CodeAgent).

Operations:
- install: Install smolagents + openai library (~30s)
- status: Check installation status
- run: Execute an autonomous task

For run, set model_url to 'http://localhost:11434/v1' if Ollama is running.
The agent has access to: shell, file I/O, pip install, web requests.
Max steps is capped at 30. Output is truncated at 8000 chars.""",
        parameters = mapOf(
            "operation" to ParameterSchema("string", "Operation: install | status | run", true),
            "task" to ParameterSchema("string", "Task description for the agent to solve (required for run)", false),
            "model_url" to ParameterSchema(
                "string",
                "OpenAI-compatible API base URL (e.g. http://localhost:11434/v1). Optional.",
                false,
            ),
            "model_id" to ParameterSchema("string", "Model name (e.g. gemma3:4b, llama3.2:3b). Default: gemma3:4b", false),
            "max_steps" to ParameterSchema("integer", "Maximum agent steps (default: 10, max: 30)", false),
        ),
    )

    override suspend fun execute(args: Map<String, Any>): Any {
        if (sandboxManager.state.value !is SandboxState.Ready) {
            return mapOf("success" to false, "error" to "Linux sandbox not ready.")
        }
        val executor = sandboxManager.createProotExecutor()

        return when (args["operation"]?.toString()) {
            "install" -> {
                val r = executor.execute(
                    "pip3 install --quiet --no-cache-dir smolagents openai",
                    timeoutSeconds = 120,
                )
                mapOf(
                    "success" to (r["exit_code"] as? Int == 0),
                    "output" to ((r["stdout"] as? String ?: "") + (r["stderr"] as? String ?: "")),
                )
            }

            "status" -> {
                val r = executor.execute(
                    "python3 -c 'import smolagents; print(smolagents.__version__)'",
                    timeoutSeconds = 5,
                )
                mapOf(
                    "success" to true,
                    "installed" to (r["exit_code"] as? Int == 0),
                    "version" to (r["stdout"] as? String ?: "").trim(),
                )
            }

            "run" -> {
                val task = args["task"]?.toString()
                    ?: return mapOf("success" to false, "error" to "task is required for run")
                val modelUrl = args["model_url"]?.toString() ?: ""
                val modelId = args["model_id"]?.toString() ?: "gemma3:4b"
                val maxSteps = (args["max_steps"] as? Number)?.toInt()?.coerceIn(1, 30) ?: 10

                val scriptPath = "/tmp/kai_agent_run.py"
                val script = buildAgentScript(task, modelUrl, modelId, maxSteps)

                // Write script via python to handle special chars safely
                val encodedTask = task.replace("\\", "\\\\").replace("\"", "\\\"")
                executor.execute(
                    "python3 -c \"import base64,os; open('$scriptPath','wb').write(base64.b64decode('${script.encodeToByteArray().let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) }}'))\"",
                    timeoutSeconds = 10,
                )

                val result = executor.execute("python3 $scriptPath", timeoutSeconds = 270)
                val output = ((result["stdout"] as? String ?: "") + (result["stderr"] as? String ?: "")).take(8000)
                mapOf("success" to (result["exit_code"] as? Int == 0), "output" to output)
            }

            else -> mapOf("success" to false, "error" to "Unknown operation. Use: install | status | run")
        }
    }

    private fun buildAgentScript(task: String, modelUrl: String, modelId: String, maxSteps: Int): String {
        val taskEscaped = task.replace("\\", "\\\\").replace("\"\"\"", "\\\"\\\"\\\"")
        return """
import subprocess, sys, traceback

TASK = \"\"\"$taskEscaped\"\"\"

try:
    from smolagents import CodeAgent, tool

    @tool
    def bash(command: str) -> str:
        \"\"\"Run a bash command and return combined stdout + stderr.\"\"\"
        try:
            r = subprocess.run(command, shell=True, capture_output=True, text=True, timeout=60)
            return (r.stdout + r.stderr).strip()[:4000]
        except Exception as e:
            return str(e)

    if "$modelUrl":
        from smolagents import OpenAIServerModel
        model = OpenAIServerModel(
            model_id="$modelId",
            api_base="$modelUrl",
            api_key="ollama",
        )
        agent = CodeAgent(tools=[bash], model=model, max_steps=$maxSteps, verbosity_level=1)
        result = agent.run(TASK)
        print("=== AGENT RESULT ===")
        print(result)
    else:
        print("No model_url — executing task as direct bash command:")
        print(bash(TASK))

except ImportError:
    print("smolagents not installed. Run run_agent with operation='install' first.", file=sys.stderr)
    sys.exit(1)
except Exception:
    traceback.print_exc()
    sys.exit(1)
""".trimIndent()
    }

    val toolInfo = ToolInfo(
        id = "run_agent",
        name = "Agent Orchestrator (smolagents)",
        description = "Run multi-step autonomous Python agents for complex tasks",
    )
}
