package com.hermesmobile.runtime

import android.content.Context
import android.system.Os
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.zip.ZipFile

class HermesRuntimeInstaller(private val context: Context) {
    val prefix: File = File(context.filesDir, "usr")
    val home: File = File(context.filesDir, "home")
    val tmp: File = File(context.filesDir, "tmp")
    val installLogFile: File get() = File(home, "hermes_install.log")

    private val hermesCheckout: File get() = File(home, "hermes-agent")

    fun install(): File {
        listOf(prefix, home, tmp).forEach { it.mkdirs() }
        log("=== Hermes Mobile runtime installation started ===")
        log("ABI=${android.os.Build.SUPPORTED_ABIS.joinToString()} filesDir=${context.filesDir}")
        val marker = File(prefix, ".hermes-installed")
        if (marker.exists() && File(prefix, "bin/hermes").exists()) {
            log("Existing installation marker found: ${marker.absolutePath}")
            return prefix
        }

        val staging = File(context.filesDir, "usr.staging")
        if (staging.exists()) staging.deleteRecursively()
        staging.mkdirs()
        try {
            extractBootstrap(staging)
            if (prefix.exists()) prefix.deleteRecursively()
            check(staging.renameTo(prefix)) { "Unable to install Hermes prefix" }
            normalizeExecutables(prefix)
            log("Bootstrap extracted to ${prefix.absolutePath}")
            installHermesAgent()
            marker.writeText("version=0.3.0\nrepo=https://github.com/NousResearch/hermes-agent.git\n")
            log("Hermes installation completed successfully")
            return prefix
        } catch (error: Throwable) {
            log("INSTALLATION FAILED: ${error.stackTraceToString()}")
            throw IllegalStateException("Hermes installation failed (${error.message ?: error.javaClass.simpleName}). Log: ${installLogFile.absolutePath}", error)
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    private data class SymlinkRecord(val link: String, val target: String)

    private fun extractBootstrap(staging: File) {
        val assetName = "bootstrap-aarch64.zip"
        val asset = runCatching { context.assets.open(assetName) }.getOrNull()
            ?: throw IllegalStateException("Missing $assetName in APK assets")
        val symlinks = mutableListOf<SymlinkRecord>()
        val zipFile = File(context.cacheDir, assetName)
        asset.use { input -> zipFile.outputStream().use { output -> input.copyTo(output) } }
        log("Bootstrap archive copied: ${zipFile.length()} bytes")
        ZipFile(zipFile).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.name == "SYMLINKS.txt") {
                    zip.getInputStream(entry).bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                        lines.mapNotNullTo(symlinks) { parseManifestLine(it) }
                    }
                    continue
                }
                val out = safePath(staging, entry.name)
                if (entry.isDirectory) {
                    out.mkdirs()
                    continue
                }
                out.parentFile?.mkdirs()
                zip.getInputStream(entry).use { source -> out.outputStream().use { target -> source.copyTo(target) } }
                if (entry.name.startsWith("bin/") || entry.name.startsWith("libexec/") || entry.name.endsWith("/sh")) {
                    out.setExecutable(true, false)
                }
                // Some Android-safe bootstrap transports encode a link as a
                // tiny regular file: SYMLINK→relative/target.
                if (out.length() in 1..199) {
                    val text = runCatching { out.readText(StandardCharsets.UTF_8) }.getOrNull()
                    if (text != null && text.startsWith("SYMLINK→")) {
                        symlinks += SymlinkRecord(entry.name, text.removePrefix("SYMLINK→").trim())
                        out.delete()
                    }
                }
            }
        }
        zipFile.delete()
        restoreSymlinks(staging, symlinks)
        log("Restored ${symlinks.size} symlinks")
    }

    private fun parseManifestLine(line: String): SymlinkRecord? {
        val value = line.trim()
        if (value.isEmpty()) return null
        val separator = when {
            value.contains('←') -> '←'
            value.contains("SYMLINK→") -> '→'
            value.contains('→') -> '→'
            else -> return null
        }
        val parts = value.split(separator, limit = 2)
        if (parts.size != 2) return null
        // Termux SYMLINKS.txt uses target←link. The target is resolved relative
        // to the link's parent, matching the bootstrap extraction convention.
        return if (separator == '←') SymlinkRecord(parts[1].trim(), parts[0].trim())
        else SymlinkRecord(parts[0].removePrefix("SYMLINK").trim(), parts[1].trim())
    }

    private fun restoreSymlinks(root: File, records: List<SymlinkRecord>) {
        val rootPath = root.toPath().toAbsolutePath().normalize()
        records.forEach { record ->
            val linkPath = safePath(root, record.link)
            val parent = linkPath.parentFile?.toPath() ?: rootPath
            val targetPath = parent.resolve(record.target).normalize()
            require(targetPath.startsWith(rootPath)) { "Symlink target escapes prefix: ${record.target}" }
            require(linkPath.toPath().startsWith(rootPath)) { "Symlink link escapes prefix: ${record.link}" }
            linkPath.parentFile?.mkdirs()
            if (linkPath.exists() || java.nio.file.Files.isSymbolicLink(linkPath.toPath())) linkPath.delete()
            Os.symlink(targetPath.toString(), linkPath.absolutePath)
            log("symlink ${linkPath.relativeTo(root)} -> ${targetPath}")
        }
    }

    private fun safePath(root: File, name: String): File {
        val rootPath = root.toPath().toAbsolutePath().normalize()
        val candidate: Path = rootPath.resolve(name).normalize()
        require(candidate.startsWith(rootPath)) { "Archive path escapes prefix: $name" }
        return candidate.toFile()
    }

    private fun normalizeExecutables(root: File) {
        val bin = File(root, "bin")
        if (bin.exists()) bin.walkTopDown().filter { it.isFile }.forEach { file -> file.setExecutable(true, false) }
        File(root, "libexec").takeIf { it.exists() }?.walkTopDown()?.filter { it.isFile }?.forEach { it.setExecutable(true, false) }
        log("Executable permissions normalized")
    }

    private fun installHermesAgent() {
        val shell = File(prefix, "bin/sh").takeIf { it.exists() } ?: throw IllegalStateException("Bootstrap did not provide bin/sh")
        val script = """
            set -eux
            export PREFIX='${prefix.absolutePath}'
            export HOME='${home.absolutePath}'
            export TMPDIR='${tmp.absolutePath}'
            export HERMES_HOME='${File(home, ".hermes").absolutePath}'
            export PATH='${File(prefix, "bin").absolutePath}':${'$'}PATH
            mkdir -p "${'$'}HOME" "${'$'}HERMES_HOME"
            "${'$'}PREFIX/bin/pkg" update -y
            "${'$'}PREFIX/bin/pkg" install -y python git clang rust make pkg-config libffi openssl nodejs ripgrep ffmpeg
            command -v git
            command -v python
            if [ ! -d "${'$'}HOME/hermes-agent/.git" ]; then
              git clone --depth 1 https://github.com/NousResearch/hermes-agent.git "${'$'}HOME/hermes-agent"
            else
              git -C "${'$'}HOME/hermes-agent" fetch --depth 1 origin main
              git -C "${'$'}HOME/hermes-agent" reset --hard origin/main
            fi
            cd "${'$'}HOME/hermes-agent"
            python -m venv venv
            export ANDROID_API_LEVEL=${'$'}(getprop ro.build.version.sdk 2>/dev/null || echo 35)
            venv/bin/python -m pip install --upgrade pip setuptools wheel
            venv/bin/python -m pip install -e '.[termux]' -c constraints-termux.txt || venv/bin/python -m pip install -e '.' -c constraints-termux.txt
            mkdir -p "${'$'}PREFIX/bin"
            ln -sf "${'$'}PWD/venv/bin/hermes" "${'$'}PREFIX/bin/hermes"
            "${'$'}PREFIX/bin/hermes" version
        """.trimIndent()
        runCommand(shell, listOf("-c", script), timeoutMs = 45 * 60 * 1000L)
    }

    private fun runCommand(executable: File, args: List<String>, timeoutMs: Long): String {
        log("Running: ${executable.absolutePath} ${args.joinToString(" ")}")
        val process = ProcessBuilder(listOf(executable.absolutePath) + args)
            .directory(home)
            .redirectErrorStream(true)
            .apply {
                environment()["PREFIX"] = prefix.absolutePath
                environment()["HOME"] = home.absolutePath
                environment()["TMPDIR"] = tmp.absolutePath
                environment()["PATH"] = File(prefix, "bin").absolutePath + ":/system/bin:/system/xbin"
                environment()["HERMES_HOME"] = File(home, ".hermes").absolutePath
            }
            .start()
        val output = StringBuilder()
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        val deadline = System.currentTimeMillis() + timeoutMs
        while (process.isAlive && System.currentTimeMillis() < deadline) {
            while (reader.ready()) output.appendLine(reader.readLine())
            Thread.sleep(50)
        }
        while (reader.ready()) output.appendLine(reader.readLine())
        log(output.toString())
        if (process.isAlive) {
            process.destroyForcibly()
            throw IllegalStateException("Installation command timed out")
        }
        if (process.exitValue() != 0) throw IllegalStateException("Installation command exited ${process.exitValue()}")
        return output.toString()
    }

    private fun log(message: String) {
        runCatching {
            home.mkdirs()
            installLogFile.appendText("${System.currentTimeMillis()} $message\n")
        }
    }
}
