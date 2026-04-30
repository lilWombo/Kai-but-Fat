package com.inspiredandroid.kai.sandbox

import android.util.Log
import com.inspiredandroid.kai.httpClient
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.request.headers
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.InetSocketAddress

private const val TAG = "SandboxProxy"
private const val DEFAULT_PORT = 18080

@Serializable
private data class ProxyRequest(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
)

/**
 * Localhost HTTP proxy that lets the proot sandbox perform outbound HTTP
 * via the host Android network stack. CLI tools inside the sandbox can
 * curl http://127.0.0.1:18080/proxy with a JSON body describing the
 * upstream request.
 */
class SandboxProxyServer(
    private val client: HttpClient,
    private val port: Int = DEFAULT_PORT,
) {

    private var server: HttpServer? = null
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    fun start() {
        if (server != null) return
        try {
            val httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
            httpServer.createContext("/proxy") { exchange ->
                scope.launch {
                    try {
                        val bodyBytes = exchange.requestBody.readBytes()
                        val json = bodyBytes.decodeToString()
                        val proxyReq = Json.decodeFromString(ProxyRequest.serializer(), json)

                        val response = client.request(proxyReq.url) {
                            method = HttpMethod.parse(proxyReq.method.uppercase())
                            headers {
                                proxyReq.headers.forEach { (k, v) -> append(k, v) }
                            }
                            if (proxyReq.body != null) {
                                setBody(proxyReq.body)
                            }
                        }

                        val bytes = response.bodyAsBytes()
                        exchange.sendResponseHeaders(response.status.value, bytes.size.toLong())
                        exchange.responseBody.use { it.write(bytes) }
                    } catch (t: Throwable) {
                        Log.w(TAG, "Proxy request failed", t)
                        val msg = (t.message ?: "proxy error").encodeToByteArray()
                        runCatching {
                            exchange.sendResponseHeaders(500, msg.size.toLong())
                            exchange.responseBody.use { it.write(msg) }
                        }
                    }
                }
            }
            httpServer.executor = null
            httpServer.start()
            server = httpServer
            Log.i(TAG, "Sandbox proxy listening on 127.0.0.1:$port")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start proxy", t)
        }
    }

    fun stop() {
        server?.stop(0)
        server = null
        Log.i(TAG, "Sandbox proxy stopped")
    }

    val isRunning: Boolean get() = server != null
}
