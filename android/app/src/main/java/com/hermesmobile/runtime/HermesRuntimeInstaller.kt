package com.hermesmobile.runtime

import android.content.Context
import java.io.File
import java.util.zip.ZipFile

class HermesRuntimeInstaller(private val context: Context) {
    val prefix: File = File(context.filesDir, "usr")
    val home: File = File(context.filesDir, "home")
    val tmp: File = File(context.filesDir, "tmp")

    fun install(): File {
        listOf(prefix, home, tmp).forEach { it.mkdirs() }
        val marker = File(prefix, ".hermes-installed")
        if (marker.exists()) return prefix

        val staging = File(context.filesDir, "usr.staging")
        if (staging.exists()) staging.deleteRecursively()
        staging.mkdirs()

        val assetName = "bootstrap-aarch64.zip"
        val asset = runCatching { context.assets.open(assetName) }.getOrNull()
        if (asset != null) {
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
        } else {
            File(staging, "bin/sh").apply { parentFile?.mkdirs(); writeText("#!/system/bin/sh\nexec /system/bin/sh \"$@\"\n"); setExecutable(true) }
        }

        if (prefix.exists()) prefix.deleteRecursively()
        check(staging.renameTo(prefix)) { "Unable to install Hermes prefix" }
        File(prefix, ".hermes-installed").writeText("version=0.1.0\n")
        return prefix
    }
}
