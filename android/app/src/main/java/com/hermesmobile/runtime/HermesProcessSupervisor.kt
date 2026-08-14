package com.hermesmobile.runtime

import android.content.Context
import java.io.File
import java.util.concurrent.Executors

class HermesProcessSupervisor(private val context: Context) {
    private val installer = HermesRuntimeInstaller(context)
    private val executor = Executors.newSingleThreadExecutor()
    private var server: HermesLocalServer? = null
    @Volatile private var installError: String? = null
    @Volatile private var hermesProcess: Process? = null

    fun start() {
        // Bind the HTTP server first. The UI polls it while Linux dependencies
        // and Hermes are being provisioned asynchronously.
        val localServer = HermesLocalServer(context, installer.prefix, installer.home, installer.tmp)
        server = localServer
        localServer.start()
        executor.execute {
            runCatching {
                installer.install()
                // Hermes documents `hermes gateway` as its long-running service
                // entry point. It is launched only after the runtime is ready;
                // a missing API key/config must not take down the bridge.
                hermesProcess = runHermes(listOf("gateway"))
                hermesProcess?.let { process ->
                    Thread {
                        process.inputStream.bufferedReader().use { it.readText() }
                    }.apply { name = "hermes-gateway-output"; isDaemon = true; start() }
                }
            }.onFailure { installError = it.message ?: it.javaClass.simpleName }
            localServer.setInstallError(installError)
        }
    }

    fun stop() {
        hermesProcess?.destroy()
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
                environment()["PATH"] = File(prefix, "bin").absolutePath + ":" + (environment()["PATH"] ?: "")
                environment()["HERMES_HOME"] = installer.home.absolutePath
            }
            .start()
    }
}
