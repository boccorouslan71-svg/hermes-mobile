package com.hermesmobile.runtime

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.zip.ZipFile

class HermesRuntimeInstaller(private val context: Context) {
    val prefix: File = File(context.filesDir, "usr")
    val home: File = File(context.filesDir, "home")
    val tmp: File = File(context.filesDir, "tmp")
    private val hermesCheckout: File get() = File(home, "hermes-agent")

    fun install(): File {
        listOf(prefix, home, tmp).forEach { it.mkdirs() }
        val marker = File(prefix, ".hermes-installed")
        if (marker.exists() && File(prefix, "bin/hermes").exists()) return prefix

        val staging = File(context.filesDir, "usr.staging")
        if (staging.exists()) staging.deleteRecursively()
        staging.mkdirs()
        extractBootstrap(staging)
        if (prefix.exists()) prefix.deleteRecursively()
        check(staging.renameTo(prefix)) { "Unable to install Hermes prefix" }

        installHermesAgent()
        marker.writeText("version=0.2.0\nrepo=https://github.com/NousResearch/hermes-agent.git\n")
        return prefix
    }

    private fun extractBootstrap(staging: File) {
        val assetName = "bootstrap-aarch64.zip"
        val asset = runCatching { context.assets.open(assetName) }.getOrNull()
        if (asset == null) {
            // The server remains usable without the optional bootstrap, but the
            // installer reports the missing runtime when it tries to provision.
            File(staging, "bin/sh").apply {
                parentFile?.mkdirs()
                writeText("#!/system/bin/sh\nexec /system/bin/sh \"$@\"\n")
                setExecutable(true)
            }
            return
        }
        asset.use { input ->
            val zipFile = File(context.cacheDir, assetName)
            zipFile.outputStream().use { output -> input.copyTo(output) }
            ZipFile(zipFile).use { zip ->
                zip.entries().asSequence().forEach { entry ->
                    val out = File(staging, entry.name).canonicalFile
                    require(out.path.startsWith(staging.canonicalPath)) { "Invalid bootstrap entry" }
                    if (entry.isDirectory) out.mkdirs()
                    else {
                        out.parentFile?.mkdirs()
                        zip.getInputStream(entry).use { source -> out.outputStream().use { target -> source.copyTo(target) } }
                        if (entry.name.startsWith("bin/") || entry.name.startsWith("libexec/")) out.setExecutable(true)
                    }
                }
            }
            zipFile.delete()
        }
    }

    private fun installHermesAgent() {
        val shell = File(prefix, "bin/sh").takeIf { it.exists() } ?: File("/system/bin/sh")
        val script = """
            set -e
            export PREFIX='${prefix.absolutePath}'
            export HOME='${home.absolutePath}'
            export TMPDIR='${tmp.absolutePath}'
            export HERMES_HOME='${File(home, ".hermes").absolutePath}'
            export PATH='${File(prefix, "bin").absolutePath}':${'$'}PATH
            mkdir -p \"${'$'}HOME\" \"${'$'}HERMES_HOME\"
            if [ -x \"${'$'}PREFIX/bin/pkg\" ]; then
              \"${'$'}PREFIX/bin/pkg\" update -y || true
              \"${'$'}PREFIX/bin/pkg\" install -y python git clang rust make pkg-config libffi openssl nodejs ripgrep ffmpeg
            fi
            command -v git >/dev/null 2>&1
            command -v python >/dev/null 2>&1
            if [ ! -d \"${'$'}HOME/hermes-agent/.git\" ]; then
              git clone --depth 1 https://github.com/NousResearch/hermes-agent.git \"${'$'}HOME/hermes-agent\"
            else
              git -C \"${'$'}HOME/hermes-agent\" fetch --depth 1 origin main
              git -C \"${'$'}HOME/hermes-agent\" reset --hard origin/main
            fi
            cd \"${'$'}HOME/hermes-agent\"
            python -m venv venv
            export ANDROID_API_LEVEL=${'$'}(getprop ro.build.version.sdk 2>/dev/null || echo 35)
            venv/bin/python -m pip install --upgrade pip setuptools wheel
            venv/bin/python -m pip install -e '.[termux]' -c constraints-termux.txt || venv/bin/python -m pip install -e '.' -c constraints-termux.txt
            mkdir -p \"${'$'}PREFIX/bin\"
            ln -sf \"${'$'}PWD/venv/bin/hermes\" \"${'$'}PREFIX/bin/hermes\"
            \"${'$'}PREFIX/bin/hermes\" version
        """.trimIndent()
        runCommand(shell, listOf("-c", script), timeoutMs = 30 * 60 * 1000L)
    }

    private fun runCommand(executable: File, args: List<String>, timeoutMs: Long): String {
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
        if (process.isAlive) {
            process.destroyForcibly()
            throw IllegalStateException("Hermes installation timed out: $output")
        }
        while (reader.ready()) output.appendLine(reader.readLine())
        if (process.exitValue() != 0) throw IllegalStateException("Hermes installation failed (${process.exitValue()}): $output")
        return output.toString()
    }
}
