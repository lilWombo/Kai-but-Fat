// composeApp/src/androidMain/kotlin/com/inspiredandroid/kai/tools/SelfEnableToolTool.kt
package com.inspiredandroid.kai.tools

import com.inspiredandroid.kai.data.AppSettings
import com.inspiredandroid.kai.network.tools.ParameterSchema
import com.inspiredandroid.kai.network.tools.Tool
import com.inspiredandroid.kai.network.tools.ToolInfo
import com.inspiredandroid.kai.network.tools.ToolSchema
import org.koin.java.KoinJavaComponent.inject

/**
 * Lets the AI enable or disable its own tools at runtime.
 * Changes persist across sessions via AppSettings.
 */
object SelfEnableToolTool : Tool {

    private val appSettings: AppSettings by inject(AppSettings::class.java)

    override val schema = ToolSchema(
        name = "set_tool_enabled",
        description = """
Enable or disable one of your own tools by its ID.
Use to activate tools you need for a task before using them. Changes persist across sessions.
Common tool IDs: brave_search, web_search, exa_search, fetch_url, github,
                 firecrawl, tavily_search, execute_shell_command, parse_code,
                 send_notification, create_calendar_event, set_alarm,
                 vector_memory, run_agent, ollama, add_mcp_server""",
        parameters = mapOf(
            "tool_id" to ParameterSchema("string", "The tool schema name / ID", true),
            "enabled" to ParameterSchema("boolean", "true to enable, false to disable", true),
        ),
    )

    override suspend fun execute(args: Map<String, Any>): Any {
        val toolId = args["tool_id"]?.toString()?.trim()
            ?: return mapOf("success" to false, "error" to "tool_id is required")
        val enabled = when (val v = args["enabled"]) {
            is Boolean -> v
            is String -> v.lowercase() == "true"
            else -> return mapOf("success" to false, "error" to "enabled must be true or false")
        }
        appSettings.setToolEnabled(toolId, enabled)
        val state = if (enabled) "enabled" else "disabled"
        return mapOf(
            "success" to true,
            "tool_id" to toolId,
            "enabled" to enabled,
            "message" to "Tool '$toolId' is now $state.",
        )
    }

    val toolInfo = ToolInfo(
        id = "set_tool_enabled",
        name = "Enable/Disable Tool",
        description = "Enable or disable any of your own tools at runtime",
    )
}
