// composeApp/src/androidMain/kotlin/com/inspiredandroid/kai/tools/GitHubTool.kt
package com.inspiredandroid.kai.tools

import com.inspiredandroid.kai.data.AppSettings
import com.inspiredandroid.kai.httpClient
import com.inspiredandroid.kai.network.tools.ParameterSchema
import com.inspiredandroid.kai.network.tools.Tool
import com.inspiredandroid.kai.network.tools.ToolInfo
import com.inspiredandroid.kai.network.tools.ToolSchema
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.java.KoinJavaComponent.inject
import java.util.Base64

/**
 * Read GitHub repos, files, issues, and PRs via the GitHub REST API.
 * Works without a token for public repos (60 req/hr); token gives 5000 req/hr.
 * Token can be set in Settings > Tools.
 */
object GitHubTool : Tool {

    private val appSettings: AppSettings by inject(AppSettings::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val client = httpClient {
        install(HttpTimeout) { requestTimeoutMillis = 20_000 }
    }

    override val schema = ToolSchema(
        name = "github",
        description = """Read GitHub repository content, issues, and pull requests.
Actions:
  read_file   — Read a file from a repo (owner, repo, path, [branch])
  list_files  — List files in a directory (owner, repo, [path], [branch])
  list_issues — List open issues (owner, repo, [label], [count])
  list_prs    — List open pull requests (owner, repo, [count])
  get_issue   — Get a single issue (owner, repo, number)
  search_code — Search code across GitHub (query, [owner/repo filter])
Provide a GitHub token in Settings > Tools for higher rate limits and private repos.""",
        parameters = mapOf(
            "action" to ParameterSchema("string", "read_file | list_files | list_issues | list_prs | get_issue | search_code", true),
            "owner" to ParameterSchema("string", "Repository owner (username or org)", false),
            "repo" to ParameterSchema("string", "Repository name", false),
            "path" to ParameterSchema("string", "File or directory path", false),
            "branch" to ParameterSchema("string", "Branch name (default: main)", false),
            "number" to ParameterSchema("integer", "Issue or PR number", false),
            "query" to ParameterSchema("string", "Search query for search_code", false),
            "label" to ParameterSchema("string", "Filter issues by label", false),
            "count" to ParameterSchema("integer", "Number of results (default 10)", false),
        ),
    )

    private suspend fun apiGet(path: String): String {
        val token = appSettings.getGitHubToken()
        val response = client.get("https://api.github.com$path") {
            header("Accept", "application/vnd.github+json")
            header("X-GitHub-Api-Version", "2022-11-28")
            if (token.isNotBlank()) header("Authorization", "Bearer $token")
        }
        return response.bodyAsText()
    }

    override suspend fun execute(args: Map<String, Any>): Any {
        return try {
            when (val action = args["action"]?.toString()) {
                "read_file" -> {
                    val owner  = args["owner"]?.toString() ?: return mapOf("success" to false, "error" to "owner required")
                    val repo   = args["repo"]?.toString()  ?: return mapOf("success" to false, "error" to "repo required")
                    val path   = args["path"]?.toString()  ?: return mapOf("success" to false, "error" to "path required")
                    val branch = args["branch"]?.toString() ?: "main"
                    val raw = apiGet("/repos/$owner/$repo/contents/$path?ref=$branch")
                    val obj = json.parseToJsonElement(raw).jsonObject
                    val encoded = obj["content"]?.jsonPrimitive?.content?.replace("\n", "") ?: ""
                    val content = String(Base64.getDecoder().decode(encoded))
                    mapOf("success" to true, "path" to path, "content" to content)
                }
                "list_files" -> {
                    val owner  = args["owner"]?.toString() ?: return mapOf("success" to false, "error" to "owner required")
                    val repo   = args["repo"]?.toString()  ?: return mapOf("success" to false, "error" to "repo required")
                    val path   = args["path"]?.toString() ?: ""
                    val branch = args["branch"]?.toString() ?: "main"
                    val raw = apiGet("/repos/$owner/$repo/contents/$path?ref=$branch")
                    val arr = json.parseToJsonElement(raw).jsonArray
                    val files = arr.map { it.jsonObject }.map {
                        mapOf("name" to it["name"]?.jsonPrimitive?.content,
                              "type" to it["type"]?.jsonPrimitive?.content,
                              "path" to it["path"]?.jsonPrimitive?.content)
                    }
                    mapOf("success" to true, "files" to files)
                }
                "list_issues" -> {
                    val owner = args["owner"]?.toString() ?: return mapOf("success" to false, "error" to "owner required")
                    val repo  = args["repo"]?.toString()  ?: return mapOf("success" to false, "error" to "repo required")
                    val label = args["label"]?.toString() ?: ""
                    val count = ((args["count"] as? Number)?.toInt() ?: 10).coerceIn(1, 30)
                    val labelParam = if (label.isNotBlank()) "&labels=$label" else ""
                    val raw = apiGet("/repos/$owner/$repo/issues?state=open&per_page=$count$labelParam")
                    val arr = json.parseToJsonElement(raw).jsonArray
                    mapOf("success" to true, "issues" to arr.map { it.jsonObject }.map {
                        mapOf("number" to it["number"]?.jsonPrimitive?.content,
                              "title"  to it["title"]?.jsonPrimitive?.content,
                              "state"  to it["state"]?.jsonPrimitive?.content,
                              "url"    to it["html_url"]?.jsonPrimitive?.content)
                    })
                }
                "list_prs" -> {
                    val owner = args["owner"]?.toString() ?: return mapOf("success" to false, "error" to "owner required")
                    val repo  = args["repo"]?.toString()  ?: return mapOf("success" to false, "error" to "repo required")
                    val count = ((args["count"] as? Number)?.toInt() ?: 10).coerceIn(1, 30)
                    val raw = apiGet("/repos/$owner/$repo/pulls?state=open&per_page=$count")
                    val arr = json.parseToJsonElement(raw).jsonArray
                    mapOf("success" to true, "pull_requests" to arr.map { it.jsonObject }.map {
                        mapOf("number" to it["number"]?.jsonPrimitive?.content,
                              "title"  to it["title"]?.jsonPrimitive?.content,
                              "url"    to it["html_url"]?.jsonPrimitive?.content,
                              "branch" to it["head"]?.jsonObject?.get("ref")?.jsonPrimitive?.content)
                    })
                }
                "get_issue" -> {
                    val owner  = args["owner"]?.toString()  ?: return mapOf("success" to false, "error" to "owner required")
                    val repo   = args["repo"]?.toString()   ?: return mapOf("success" to false, "error" to "repo required")
                    val number = (args["number"] as? Number)?.toInt() ?: return mapOf("success" to false, "error" to "number required")
                    val raw = apiGet("/repos/$owner/$repo/issues/$number")
                    val obj = json.parseToJsonElement(raw).jsonObject
                    mapOf("success" to true,
                          "number" to number,
                          "title"  to obj["title"]?.jsonPrimitive?.content,
                          "body"   to obj["body"]?.jsonPrimitive?.content,
                          "state"  to obj["state"]?.jsonPrimitive?.content,
                          "url"    to obj["html_url"]?.jsonPrimitive?.content)
                }
                "search_code" -> {
                    val query = args["query"]?.toString() ?: return mapOf("success" to false, "error" to "query required")
                    val raw = apiGet("/search/code?q=${java.net.URLEncoder.encode(query, "UTF-8")}&per_page=10")
                    val obj = json.parseToJsonElement(raw).jsonObject
                    val items = obj["items"]?.jsonArray ?: JsonArray(emptyList())
                    mapOf("success" to true, "results" to items.map { it.jsonObject }.map {
                        mapOf("name" to it["name"]?.jsonPrimitive?.content,
                              "path" to it["path"]?.jsonPrimitive?.content,
                              "url"  to it["html_url"]?.jsonPrimitive?.content,
                              "repo" to it["repository"]?.jsonObject?.get("full_name")?.jsonPrimitive?.content)
                    })
                }
                else -> mapOf("success" to false, "error" to "Unknown action: $action")
            }
        } catch (e: Exception) {
            mapOf("success" to false, "error" to "GitHub API error: ${e.message}")
        }
    }

    val toolInfo = ToolInfo(
        id = "github",
        name = "GitHub",
        description = "Read GitHub repos, files, issues, and PRs via the GitHub REST API",
    )
}
