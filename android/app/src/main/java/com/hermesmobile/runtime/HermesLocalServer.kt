package com.hermesmobile.runtime

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class HermesLocalServer(
    private val context: Context,
    private val prefix: java.io.File,
    private val home: java.io.File,
    private val tmp: java.io.File,
    private val installer: HermesRuntimeInstaller? = null
) {
    private val running = AtomicBoolean(false)
    private val executor = Executors.newCachedThreadPool()
    private val configFile = java.io.File(home, "hermes-config.json")
    @Volatile private var installError: String? = null
    private var socket: ServerSocket? = null

    fun setInstallError(error: String?) { installError = error }

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
                // Closing the listening socket is the normal service shutdown path.
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
            val path = parts[1].substringBefore('?')
            var contentLength = 0
            while (true) {
                val header = reader.readLine() ?: break
                if (header.isEmpty()) break
                if (header.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = header.substringAfter(":").trim().toIntOrNull() ?: 0
                }
            }
            val body = if (contentLength > 0) CharArray(contentLength).also { reader.read(it) }.concatToString() else ""
            val response = route(method, path, body)
            val payload = response.second.second.toByteArray(StandardCharsets.UTF_8)
            val writer = OutputStreamWriter(connection.getOutputStream(), StandardCharsets.UTF_8)
            writer.write("HTTP/1.1 ${response.first}\r\n")
            writer.write("Content-Type: ${response.second.first}\r\n")
            writer.write("Content-Length: ${payload.size}\r\n")
            writer.write("Access-Control-Allow-Origin: *\r\n")
            writer.write("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
            writer.write("Access-Control-Allow-Headers: Content-Type\r\n")
            writer.write("Connection: close\r\n\r\n")
            writer.flush()
            connection.getOutputStream().write(payload)
            connection.getOutputStream().flush()
        }
    }

    private fun route(method: String, path: String, body: String): Pair<String, Pair<String, String>> {
        if (method == "OPTIONS") return ok("text/plain", "")
        if (method == "GET" && path == "/") return ok("text/html; charset=utf-8", loadAsset("web/index.html"))
        if (method == "GET" && path == "/api/status") {
            val hermes = java.io.File(prefix, "bin/hermes").exists()
            val error = installError?.let(::json) ?: "null"
            val log = readInstallLog()
            return ok("application/json", "{\"serverReady\":true,\"hermesInstalled\":$hermes,\"installing\":${!hermes && installError == null},\"installError\":$error,\"installLog\":${json(log)},\"configPath\":${json(configFile.absolutePath)}}")
        }
        if (method == "GET" && path == "/api/config") return ok("application/json", readConfig())
        if (method == "POST" && path == "/api/config") {
            val saved = saveConfig(body)
            return ok("application/json", "{\"ok\":true,\"config\":$saved}")
        }
        if (method == "GET" && path == "/api/events") return ok("text/event-stream", "event: ready\ndata: {\"serverReady\":true}\n\n")
        if (method == "POST" && path == "/api/message") {
            val message = extractJson(body, "message")
            val reply = if (java.io.File(prefix, "bin/hermes").exists()) "Hermes runtime détecté. La requête locale est prête à être transmise au processus Hermes." else "Le serveur local est prêt, mais l’installation Hermes est encore en cours ou a échoué."
            return ok("application/json", "{\"message\":${json(message)},\"reply\":${json(reply)}}")
        }
        return Pair("404 Not Found", Pair("application/json", "{\"error\":\"not_found\"}"))
    }

    private fun readConfig(): String {
        if (!configFile.exists()) return "{\"apiKey\":\"\",\"baseUrl\":\"https://api.openai.com/v1\",\"model\":\"gpt-4o-mini\"}"
        return runCatching { configFile.readText(StandardCharsets.UTF_8) }.getOrDefault("{\"apiKey\":\"\",\"baseUrl\":\"https://api.openai.com/v1\",\"model\":\"gpt-4o-mini\"}")
    }

    private fun saveConfig(body: String): String {
        val config = "{\"apiKey\":${json(extractJson(body, "apiKey"))},\"baseUrl\":${json(extractJson(body, "baseUrl"))},\"model\":${json(extractJson(body, "model"))}}"
        home.mkdirs()
        configFile.writeText(config, StandardCharsets.UTF_8)
        return config
    }

    private fun readInstallLog(): String {
        val log = installer?.installLogFile ?: java.io.File(home, "hermes_install.log")
        if (!log.exists()) return ""
        return runCatching {
            val text = log.readText(StandardCharsets.UTF_8)
            if (text.length > 16000) text.takeLast(16000) else text
        }.getOrDefault("")
    }

    private fun loadAsset(name: String): String = runCatching { context.assets.open(name).bufferedReader().use { it.readText() } }.getOrDefault("<h1>Hermes Mobile</h1>")

    private fun extractJson(body: String, key: String): String {
        val marker = "\"$key\""
        val start = body.indexOf(marker)
        if (start < 0) return ""
        var index = body.indexOf(':', start + marker.length)
        if (index < 0) return ""
        while (++index < body.length && body[index].isWhitespace()) {}
        if (index >= body.length || body[index] != '"') return ""
        val result = StringBuilder()
        var escaped = false
        for (i in index + 1 until body.length) {
            val ch = body[i]
            if (escaped) { result.append(if (ch == 'n') '\n' else ch); escaped = false }
            else if (ch == '\\') escaped = true
            else if (ch == '"') break
            else result.append(ch)
        }
        return result.toString()
    }

    private fun json(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""
    private fun ok(type: String, value: String) = Pair("200 OK", Pair(type, value))

    companion object { private const val PORT = 18923 }
}
