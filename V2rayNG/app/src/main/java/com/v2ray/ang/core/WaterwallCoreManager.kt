package com.v2ray.ang.core

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.LogUtil
import java.io.File

/**
 * Runs the bundled WaterWall core (libwaterwall.so) for the "Watray" profile. Xray owns the TUN
 * and dials a SOCKS outbound to the core's listener on [PORT]; the core's own upstream sockets are
 * excluded from the VPN because the app itself is a disallowed application.
 * Only arm64-v8a is bundled.
 */
object WaterwallCoreManager {
    const val NAME = "Watray \uD83C\uDDF3\uD83C\uDDF1"
    private const val LEGACY_NAME = "Watray Netherlands"
    const val PORT = 1085
    private const val ASSET_DIR = "waterwall"

    private var process: Process? = null

    /** Why the last start failed, shown to the user: the bare "could not start" message hid three different causes. */
    @Volatile
    var lastFailure: String = ""
        private set
    private val tail = java.util.concurrent.ConcurrentLinkedDeque<String>()

    private fun lastLines(): String = tail.toList().takeLast(3).joinToString(" | ").take(200)

    fun isWatray(p: ProfileItem): Boolean =
        p.configType == EConfigType.SOCKS && (p.remarks == NAME || p.remarks == LEGACY_NAME) && p.server == AppConfig.LOOPBACK &&
            p.serverPort == PORT.toString()

    fun createProfile(): ProfileItem = ProfileItem.create(EConfigType.SOCKS).apply {
        remarks = NAME
        server = AppConfig.LOOPBACK
        serverPort = PORT.toString()
    }

    /**
     * Makes sure the bundled profile is in the list (ungrouped), so the person never has to add it. Also renames the
     * profile of earlier builds ("Watray Netherlands"); the manual pick is kept by name, so it is moved along.
     */
    fun ensureProfile() {
        val found = MmkvManager.decodeServerList("").firstNotNullOfOrNull { guid ->
            MmkvManager.decodeServerConfig(guid)?.takeIf { isWatray(it) }?.let { guid to it }
        }
        if (found == null) {
            MmkvManager.encodeServerConfig("", createProfile())
            return
        }
        val (guid, profile) = found
        if (profile.remarks != NAME) {
            if (MmkvManager.decodeSettingsString("phonix_manual_server") == LEGACY_NAME) {
                MmkvManager.encodeSettings("phonix_manual_server", NAME)
            }
            profile.remarks = NAME
            MmkvManager.encodeServerConfig(guid, profile)
        }
    }

    fun isSupported(context: Context): Boolean =
        File(context.applicationInfo.nativeLibraryDir, "libwaterwall.so").exists()

    /** Starts the core; false when it could not start. The caller refuses the session then. */
    @Synchronized
    fun start(context: Context): Boolean {
        stop()
        tail.clear()
        lastFailure = ""
        val binary = File(context.applicationInfo.nativeLibraryDir, "libwaterwall.so")
        if (!binary.exists()) {
            LogUtil.w(AppConfig.TAG, "Waterwall: binary missing (unsupported ABI)")
            lastFailure = "binary missing (abi)"
            return false
        }
        try {
            val dir = File(context.filesDir, ASSET_DIR).apply { mkdirs() }
            // Rewritten on every start so an updated app always runs its own bundled config.
            for (name in listOf("core.json", "config.json")) {
                context.assets.open("$ASSET_DIR/$name").use { input ->
                    File(dir, name).outputStream().use { input.copyTo(it) }
                }
            }
            File(dir, "log").mkdirs()
            // No arguments: the core reads core.json from the working dir, exactly as verified by hand in Termux.
            // The nativeLibraryDir file is not marked executable on every ROM; harmless when it already is.
            binary.setExecutable(true, false)
            // On boot the core inspects/cleans iptables NAT chains. Without root that never succeeds and
            // netd holds the xtables lock, so it stalls for tens of seconds. This tunnel never uses
            // iptables, so shadow the tools with no-op stubs ("not installed"/empty tables) via PATH.
            val stubs = File(dir, "stubs").apply { mkdirs() }
            for (tool in listOf("iptables", "ip6tables", "iptables-save", "ip6tables-save", "iptables-restore", "ip6tables-restore")) {
                File(stubs, tool).apply {
                    writeText("#!/system/bin/sh\nexit 0\n")
                    setExecutable(true, false)
                }
            }
            val builder = ProcessBuilder(binary.absolutePath)
                .directory(dir)
                .redirectErrorStream(true)
            builder.environment()["PATH"] = stubs.absolutePath + ":" + (System.getenv("PATH") ?: "/system/bin")
            val started = builder.start()
            process = started
            // Drain the output: an unread pipe would stall the core, and its first lines say why it failed.
            Thread({
                try {
                    started.inputStream.bufferedReader().forEachLine {
                        // Always visible in logcat (tag "Waterwall"), whatever the app's log level is: this is the
                        // only place the core says why a handshake or a connect failed.
                        android.util.Log.w("Waterwall", it)
                        tail.addLast(it)
                        if (tail.size > 20) tail.pollFirst()
                    }
                } catch (_: Exception) {
                }
            }, "waterwall-log").apply { isDaemon = true }.start()
            LogUtil.i(AppConfig.TAG, "Waterwall: started")
            return true
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Waterwall: failed to start", e)
            lastFailure = "exec failed: ${e.message}".take(160)
            return false
        }
    }

    /** Whether the core accepts connections on its SOCKS port; Xray's outbound needs it. */
    fun isListening(timeoutMs: Int = 2000): Boolean = try {
        java.net.Socket().use { it.connect(java.net.InetSocketAddress(AppConfig.LOOPBACK, PORT), timeoutMs) }
        true
    } catch (e: Exception) {
        false
    }

    /** Polls [isListening] until the core answers or [budgetMs] runs out. */
    fun awaitListening(budgetMs: Long = 30_000L): Boolean {
        val deadline = System.currentTimeMillis() + budgetMs
        while (System.currentTimeMillis() < deadline) {
            if (isListening()) return true
            if (process?.isAlive == false) {
                LogUtil.w(AppConfig.TAG, "Waterwall: exited with ${process?.exitValue()} before listening")
                Thread.sleep(200) // let the log thread drain the last lines
                lastFailure = "exited ${process?.exitValue()}: ${lastLines()}"
                return false
            }
            Thread.sleep(250)
        }
        lastFailure = "not listening on $PORT after ${budgetMs / 1000}s: ${lastLines()}"
        return false
    }

    @Synchronized
    fun stop() {
        process?.let {
            try {
                it.destroy()
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Waterwall: failed to stop", e)
            }
        }
        process = null
    }
}
