package com.hermesmobile.runtime

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class HermesLocalServer(
    private val context: Context,
    private val prefix: java.io.File,
    private val home: java.io.File,
    private val tmp: java.io.File
) {
    private val running = AtomicBoolean(false)
    private val executor = Executors.newCachedThreadPool()
    private var socket: ServerSocket? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        executor.execute {
            try {
                socket = ServerSocket(PORT, 16, java.net.InetAddress.getByName("127.0.0.1"))
                while (running.get()) {
                    val client = socket?.accept() ?: break
                    executor.execute { handle(client) }
                }
            } catch (_: Exception) {
                // Service shutdown closes the socket; no action is required here.
            }
        }
    }

    fun stop() {
        running.set(false)
        socket?.close()
        executor.shutdownNow()
    }

    private fun handle(client: Socket) {
        client.use { connection ->
            val reader = BufferedReader(InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val path = parts[1]
            var contentLength = 0
            while (true) {
                val header = reader.readLine() ?: break
                if (header.isEmpty()) break
                if (header.startsWith("Content-Length:", ignoreCase = true)) contentLength = header.substringAfter(":").trim().toIntOrNull() ?: 0
            }
            val body = CharArray(contentLength).let { chars -> if (contentLength > 0) reader.read(chars).let { String(chars) } else "" }
            val response = route(method, path, body)
            val writer = OutputStreamWriter(connection.getOutputStream(), StandardCharsets.UTF_8)
            writer.write("HTTP/1.1 ${response.first}\r\nContent-Type: ${response.second.first}\r\nContent-Length: ${response.second.second.toByteArray().size}\r\nConnection: close\r\nAccess-Control-Allow-Origin: http://127.0.0.1:$PORT\r\n\r\n${response.second.second}")
            writer.flush()
        }
    }

    private fun route(method: String, rawPath: String, body: String): Pair<String, Pair<String, String>> {
        val path = rawPath.substringBefore('?')
        if (method == "GET" && path == "/") return ok("text/html; charset=utf-8", loadAsset("web/index.html"))
        if (method == "GET" && path == "/api/status") {
            val hermes = java.io.File(prefix, "bin/hermes").exists()
            return ok("application/json", "{\"ready\":true,\"hermesInstalled\":$hermes,\"prefix\":${json(home.absolutePath)}}")
        }
        if (method == "GET" && path == "/api/events") {
            return ok("text/event-stream", "event: ready\ndata: {\"ready\":true}\n\n")
        }
        if (method == "POST" && path == "/api/message") {
            val message = extractJson(body, "message")
            val reply = if (java.io.File(prefix, "bin/hermes").exists()) {
                "Hermes runtime détecté. La requête sera transmise au processus local dans la prochaine version du bridge."
            } else {
                "Le runtime Linux est installé, mais le binaire Hermes n’est pas encore présent dans le bootstrap ARM64. Ajoutez-le via scripts/build-bootstrap.sh."
            }
            return ok("application/json", "{\"message\":${json(message)},\"reply\":${json(reply)}}")
        }
        if (method == "GET" && path.startsWith("/assets/")) return ok("text/plain", "Asset unavailable")
        return Pair("404 Not Found", Pair("application/json", "{\"error\":\"not_found\"}"))
    }

    private fun loadAsset(name: String): String = runCatching {
        context.assets.open(name).bufferedReader().use { it.readText() }
    }.getOrDefault("<h1>Hermes Mobile</h1>")

    private fun extractJson(body: String, key: String): String {
        val marker = "\"$key\""
        val start = body.indexOf(marker)
        if (start < 0) return ""
        val colon = body.indexOf(':', start)
        val first = body.indexOf('"', colon + 1)
        val second = body.indexOf('"', first + 1)
        return if (first >= 0 && second > first) URLDecoder.decode(body.substring(first + 1, second), "UTF-8") else ""
    }

    private fun json(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
    private fun ok(type: String, value: String) = Pair("200 OK", Pair(type, value))

    companion object { private const val PORT = 18923 }
}
