package com.hermesmobile.runtime

import android.content.Context
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class HermesProcessSupervisor(private val context: Context) {
    private val installer = HermesRuntimeInstaller(context)
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private var server: HermesLocalServer? = null
    private var restartTask: ScheduledFuture<*>? = null

    fun start() {
        executor.execute {
            val prefix = installer.install()
            server = HermesLocalServer(context, prefix, installer.home, installer.tmp).also { it.start() }
        }
    }

    fun stop() {
        restartTask?.cancel(true)
        server?.stop()
        executor.shutdownNow()
    }

    fun runHermes(arguments: List<String>): Process? {
        val prefix = installer.prefix
        val hermesBin = File(prefix, "bin/hermes")
        if (!hermesBin.exists()) return null
        val command = mutableListOf(hermesBin.absolutePath).apply { addAll(arguments) }
        return ProcessBuilder(command)
            .directory(installer.home)
            .redirectErrorStream(true)
            .apply {
                environment()["PREFIX"] = prefix.absolutePath
                environment()["HOME"] = installer.home.absolutePath
                environment()["TMPDIR"] = installer.tmp.absolutePath
                environment()["PATH"] = File(prefix, "bin").absolutePath
                environment()["HERMES_HOME"] = installer.home.absolutePath
            }
            .start()
    }
}
