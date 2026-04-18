// composeApp/src/androidMain/kotlin/com/inspiredandroid/kai/tools/SequentialThinkingTool.kt
package com.inspiredandroid.kai.tools

import com.inspiredandroid.kai.network.tools.ParameterSchema
import com.inspiredandroid.kai.network.tools.Tool
import com.inspiredandroid.kai.network.tools.ToolInfo
import com.inspiredandroid.kai.network.tools.ToolSchema

/**
 * Structured multi-step reasoning tool.
 * The AI records numbered thought steps with optional revisions and branching,
 * then emits a final conclusion. All processing is local — no network needed.
 */
object SequentialThinkingTool : Tool {

    private val thoughts = java.util.concurrent.CopyOnWriteArrayList<Map<String, Any>>()

    override val schema = ToolSchema(
        name = "sequential_thinking",
        description = """Break a complex problem into explicit numbered thought steps.
Use this when a task requires planning, multi-step reasoning, or self-correction.
Call with action='think' repeatedly to build up a reasoning chain, then action='conclude' to emit the final answer.
Revise earlier steps with action='revise' and step_number=N. Branch with action='branch' and branch_from=N.""",
        parameters = mapOf(
            "action" to ParameterSchema("string", "think | revise | branch | conclude | reset", true),
            "thought" to ParameterSchema("string", "The thought content for this step", false),
            "step_number" to ParameterSchema("integer", "Step number to revise (for action=revise)", false),
            "branch_from" to ParameterSchema("integer", "Step number to branch from (for action=branch)", false),
        ),
    )

    override suspend fun execute(args: Map<String, Any>): Any {
        return when (val action = args["action"]?.toString()?.lowercase()) {
            "think" -> {
                val thought = args["thought"]?.toString()
                    ?: return mapOf("success" to false, "error" to "thought is required")
                thoughts.add(mapOf("step" to (thoughts.size + 1), "thought" to thought, "type" to "think"))
                mapOf("success" to true, "step" to thoughts.size, "thought" to thought)
            }

            "revise" -> {
                val idx = ((args["step_number"] as? Number)?.toInt() ?: 0) - 1
                val thought = args["thought"]?.toString()
                    ?: return mapOf("success" to false, "error" to "thought is required")
                if (idx < 0 || idx >= thoughts.size) return mapOf("success" to false, "error" to "step_number out of range")
                thoughts[idx] = thoughts[idx].toMutableMap().also {
                    it["thought"] = thought
                    it["type"] = "revised"
                }
                mapOf("success" to true, "revised_step" to idx + 1)
            }

            "branch" -> {
                val from = ((args["branch_from"] as? Number)?.toInt() ?: 0) - 1
                val thought = args["thought"]?.toString()
                    ?: return mapOf("success" to false, "error" to "thought is required")
                if (from < 0 || from >= thoughts.size) return mapOf("success" to false, "error" to "branch_from out of range")
                thoughts.add(mapOf("step" to (thoughts.size + 1), "thought" to thought, "type" to "branch", "branched_from" to from + 1))
                mapOf("success" to true, "step" to thoughts.size, "branched_from" to from + 1)
            }

            "conclude" -> {
                val conclusion = args["thought"]?.toString() ?: "No conclusion provided."
                val chain = thoughts.toList()
                thoughts.clear()
                mapOf("success" to true, "conclusion" to conclusion, "reasoning_chain" to chain, "total_steps" to chain.size)
            }

            "reset" -> {
                val n = thoughts.size
                thoughts.clear()
                mapOf("success" to true, "cleared_steps" to n)
            }

            else -> mapOf("success" to false, "error" to "Unknown action: $action")
        }
    }

    val toolInfo = ToolInfo(
        id = "sequential_thinking",
        name = "Sequential Thinking",
        description = "Structured multi-step reasoning with revision and branching — no network needed",
    )
}
