package com.inspiredandroid.kai.tools

import com.inspiredandroid.kai.httpClient
import com.inspiredandroid.kai.network.tools.ParameterSchema
import com.inspiredandroid.kai.network.tools.Tool
import com.inspiredandroid.kai.network.tools.ToolInfo
import com.inspiredandroid.kai.network.tools.ToolSchema
import com.inspiredandroid.kai.sandbox.LinuxSandboxManager
import com.inspiredandroid.kai.sandbox.SandboxState
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.koin.java.KoinJavaComponent.inject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.time.Duration.Companion.seconds

private const val CHROMA_PORT = 8100
private const val CHROMA_HOST = "localhost"
private const val CHROMA_BASE = "http://$CHROMA_HOST:$CHROMA_PORT"
private const val DEFAULT_COLLECTION = "kai_memory"

/**
 * Semantic vector memory backed by ChromaDB running inside the proot sandbox.
 * Stores and retrieves text by meaning, not exact keywords. Data persists at
 * /root/kai-chroma inside the sandbox across app restarts.
 */
object VectorMemoryTool : Tool {

    private val sandboxManager: LinuxSandboxManager by inject(LinuxSandboxManager::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    private val client = httpClient {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) { requestTimeoutMillis = 15_000 }
    }

    override val timeout = 120.seconds

    override val schema = ToolSchema(
        name = "vector_memory",
        description = """Semantic vector memory using ChromaDB. Store and retrieve information by meaning.
Operations:
- start_server: Install + start ChromaDB server (required before other ops, ~60s first time)
- status: Check if server is running
- add: Store text with a unique id and optional metadata
- search: Find semantically similar memories by query text
- list: List all stored memory ids
- delete: Remove a memory by id
Data persists at /root/kai-chroma across app restarts.""",
        parameters = mapOf(
            "operation" to ParameterSchema(
                "string",
                "Operation: start_server | status | add | search | list | delete",
                true,
            ),
            "text" to ParameterSchema("string", "Text to store or search query (required for add/search)", false),
            "id" to ParameterSchema("string", "Unique id for the memory (required for add/delete)", false),
            "metadata" to ParameterSchema("object", "Optional key-value metadata to attach (for add)", false),
            "n_results" to ParameterSchema("integer", "Number of results to return for search (default: 5)", false),
            "collection" to ParameterSchema("string", "Collection name (default: kai_memory)", false),
        ),
    )

    @Suppress("UNCHECKED_CAST")
    override suspend fun execute(args: Map<String, Any>): Any {
        val operation = args["operation"]?.toString()
            ?: return mapOf("success" to false, "error" to "operation is required")
        val collection = args["collection"]?.toString() ?: DEFAULT_COLLECTION

        return when (operation) {
            "status" -> checkStatus()
            "start_server" -> startServer()
            "add" -> {
                val text = args["text"]?.toString()
                    ?: return mapOf("success" to false, "error" to "text is required for add")
                val id = args["id"]?.toString()
                    ?: return mapOf("success" to false, "error" to "id is required for add")
                val metadata = args["metadata"] as? Map<String, Any> ?: emptyMap()
                addDocument(collection, id, text, metadata)
            }

            "search" -> {
                val query = args["text"]?.toString()
                    ?: return mapOf("success" to false, "error" to "text (query) is required for search")
                val n = (args["n_results"] as? Number)?.toInt() ?: 5
                searchDocuments(collection, query, n)
            }

            "list" -> listDocuments(collection)
            "delete" -> {
                val id = args["id"]?.toString()
                    ?: return mapOf("success" to false, "error" to "id is required for delete")
                deleteDocument(collection, id)
            }

            else -> mapOf("success" to false, "error" to "Unknown operation: $operation")
        }
    }

    private suspend fun checkStatus(): Map<String, Any> = withContext(Dispatchers.IO) {
        try {
            val conn = URL("$CHROMA_BASE/api/v1").openConnection() as HttpURLConnection
            conn.connectTimeout = 1000
            conn.readTimeout = 1000
            conn.connect()
            val ok = conn.responseCode == 200
            conn.disconnect()
            mapOf("success" to true, "running" to ok, "url" to CHROMA_BASE)
        } catch (_: Exception) {
            mapOf("success" to true, "running" to false, "url" to CHROMA_BASE)
        }
    }

    private suspend fun startServer(): Map<String, Any> {
        if (sandboxManager.state.value !is SandboxState.Ready) {
            return mapOf("success" to false, "error" to "Linux sandbox not ready. Set up sandbox in Settings first.")
        }
        if ((checkStatus()["running"] as? Boolean) == true) {
            return mapOf("success" to true, "message" to "ChromaDB already running at $CHROMA_BASE")
        }

        val executor = sandboxManager.createProotExecutor()

        // Install chromadb if missing
        val checkInstall = executor.execute("python3 -c 'import chromadb'", timeoutSeconds = 5)
        if ((checkInstall["exit_code"] as? Int) != 0) {
            val install = executor.execute(
                "pip3 install --quiet --no-cache-dir chromadb uvicorn",
                timeoutSeconds = 180,
            )
            if ((install["exit_code"] as? Int) != 0) {
                return mapOf("success" to false, "error" to "Failed to install ChromaDB: ${install["stderr"]}")
            }
        }

        // Start server in sandbox background
        executor.executeStreaming(
            command = "mkdir -p /root/kai-chroma && python3 -m chromadb.cli.cli run --host $CHROMA_HOST --port $CHROMA_PORT --path /root/kai-chroma &",
            onStdout = { android.util.Log.d("ChromaDB", it) },
            onStderr = { android.util.Log.d("ChromaDB", it) },
        )

        repeat(20) {
            delay(1000)
            if ((checkStatus()["running"] as? Boolean) == true) {
                return mapOf("success" to true, "message" to "ChromaDB started at $CHROMA_BASE")
            }
        }
        return mapOf("success" to false, "error" to "ChromaDB failed to start within 20s")
    }

    private suspend fun ensureCollection(collection: String) {
        try {
            client.post("$CHROMA_BASE/api/v1/collections") {
                contentType(ContentType.Application.Json)
                setBody("""{"name":"$collection"}""")
            }
        } catch (_: Exception) {
            // Collection may already exist — ignore
        }
    }

    private suspend fun addDocument(
        collection: String,
        id: String,
        text: String,
        metadata: Map<String, Any>,
    ): Map<String, Any> = try {
        ensureCollection(collection)
        val idJson = json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(id))
        val textJson = json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(text))
        val metaJson = json.encodeToString(
            JsonObject.serializer(),
            JsonObject(metadata.mapValues { JsonPrimitive(it.value.toString()) }),
        )
        val body = """{"ids":[$idJson],"documents":[$textJson],"metadatas":[$metaJson]}"""
        client.post("$CHROMA_BASE/api/v1/collections/$collection/add") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        mapOf("success" to true, "id" to id, "collection" to collection)
    } catch (e: Exception) {
        mapOf(
            "success" to false,
            "error" to "Add failed: ${e.message}. Ensure ChromaDB is running via operation='start_server'.",
        )
    }

    private suspend fun searchDocuments(
        collection: String,
        query: String,
        nResults: Int,
    ): Map<String, Any> = try {
        val queryJson = json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(query))
        val body = """{"query_texts":[$queryJson],"n_results":$nResults}"""
        val response = client.post("$CHROMA_BASE/api/v1/collections/$collection/query") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        mapOf("success" to true, "results" to response.bodyAsText())
    } catch (e: Exception) {
        mapOf("success" to false, "error" to "Search failed: ${e.message}")
    }

    private suspend fun listDocuments(collection: String): Map<String, Any> = try {
        val response = client.get("$CHROMA_BASE/api/v1/collections/$collection/get")
        mapOf("success" to true, "items" to response.bodyAsText())
    } catch (e: Exception) {
        mapOf("success" to false, "error" to "List failed: ${e.message}")
    }

    private suspend fun deleteDocument(collection: String, id: String): Map<String, Any> = try {
        val idJson = json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(id))
        client.post("$CHROMA_BASE/api/v1/collections/$collection/delete") {
            contentType(ContentType.Application.Json)
            setBody("""{"ids":[$idJson]}""")
        }
        mapOf("success" to true, "deleted_id" to id)
    } catch (e: Exception) {
        mapOf("success" to false, "error" to "Delete failed: ${e.message}")
    }

    val toolInfo = ToolInfo(
        id = "vector_memory",
        name = "Vector Memory (ChromaDB)",
        description = "Semantic memory — store and retrieve by meaning using ChromaDB",
    )
}
