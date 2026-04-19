// composeApp/src/androidMain/kotlin/com/inspiredandroid/kai/tools/SelfAddMcpTool.kt
package com.inspiredandroid.kai.tools

import com.inspiredandroid.kai.mcp.McpServerManager
import com.inspiredandroid.kai.network.tools.ParameterSchema
import com.inspiredandroid.kai.network.tools.Tool
import com.inspiredandroid.kai.network.tools.ToolInfo
import com.inspiredandroid.kai.network.tools.ToolSchema
import org.koin.java.KoinJavaComponent.inject

/**
 * Lets the AI permanently add a new MCP server to its own configuration.
 * Persisted and enabled immediately, survives app restarts.
 */
object SelfAddMcpTool : Tool {

    private val mcpManager: McpServerManager by inject(McpServerManager::class.java)

    override val schema = ToolSchema(
        name = "add_mcp_server",
        description = """
Permanently add a new MCP server to your own tool configuration.
Use when you discover a useful MCP server and want it available in future sessions.
The server appears in Settings > Tools > MCP Servers and is enabled immediately.
Prefer hosted SSE/HTTP MCP servers (URLs ending in /mcp or /sse).
Example: add_mcp_server(name="Context7", url="https://mcp.context7.com/mcp")""",
        parameters = mapOf(
            "name" to ParameterSchema("string", "Display name for the server", true),
            "url" to ParameterSchema("string", "Full MCP endpoint URL (http:// or https://)", true),
            "description" to ParameterSchema("string", "What this MCP server does", false),
        ),
    )

    override suspend fun execute(args: Map<String, Any>): Any {
        val name = args["name"]?.toString()?.trim()
            ?: return mapOf("success" to false, "error" to "name is required")
        val url = args["url"]?.toString()?.trim()
            ?: return mapOf("success" to false, "error" to "url is required")

        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return mapOf("success" to false, "error" to "url must start with http:// or https://")
        }

        return try {
            val existing = mcpManager.getServers().firstOrNull {
                it.url.equals(url, ignoreCase = true)
            }
            if (existing != null) {
                mcpManager.setServerEnabled(existing.id, true)
                return mapOf(
                    "success" to true,
                    "message" to "MCP server already existed — re-enabled.",
                    "id" to existing.id,
                )
            }
            val config = mcpManager.addServer(name, url, emptyMap())
            mcpManager.setServerEnabled(config.id, true)
            mapOf(
                "success" to true,
                "message" to "MCP server '$name' added and enabled.",
                "id" to config.id,
                "url" to url,
            )
        } catch (e: Exception) {
            mapOf("success" to false, "error" to "Failed: ${e.message}")
        }
    }

    val toolInfo = ToolInfo(
        id = "add_mcp_server",
        name = "Add MCP Server",
        description = "Permanently add a new MCP server to your own configuration",
    )
}
