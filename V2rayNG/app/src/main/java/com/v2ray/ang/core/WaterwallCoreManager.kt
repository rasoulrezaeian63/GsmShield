package com.v2ray.ang.core

import android.content.Context
import com.v2ray.ang.AngApplication
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
 * arm64-v8a and armeabi-v7a are bundled; Android extracts the one matching the device.
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
        p.configType == EConfigType.SOCKS && (p.remarks == NAME || p.remarks == LEGACY_NAME || p.remarks.startsWith("Watray ")) && p.server == AppConfig.LOOPBACK &&
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
            MmkvManager.decodeServerConfig(guid)?.takeIf { isWatray(it) && (it.remarks == NAME || it.remarks == LEGACY_NAME) }?.let { guid to it }
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
    fun start(context: Context, profile: ProfileItem? = null): Boolean {
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
            applyRemoteServer(File(dir, "config.json"), profile)
            pinServerAddress(File(dir, "config.json"))
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

    /** Writes the fields of the panel's server entry (see WatrayUpdater) over the bundled variables. */
    private fun applyRemoteServer(configFile: File, profile: ProfileItem?) {
        val s = profile?.let { com.v2ray.ang.handler.WatrayUpdater.serverFor(it.remarks) } ?: return
        try {
            // A full WaterWall config from the panel replaces the bundled one; only the listener is forced to our port.
            val full = s.opt("config")
            if (full != null) {
                val cfg = if (full is org.json.JSONObject) full else org.json.JSONObject(full.toString())
                val nodes = cfg.getJSONArray("nodes")
                for (i in 0 until nodes.length()) {
                    val n = nodes.getJSONObject(i)
                    if (n.optString("type") == "TcpUdpListener") {
                        n.getJSONObject("settings").put("address", AppConfig.LOOPBACK).put("port", PORT)
                    }
                }
                configFile.writeText(cfg.toString(2))
                return
            }
            var text = configFile.readText()
            fun set(key: String, value: String) {
                text = text.replace(Regex("\"$key\"\\s*:\\s*(\"[^\"]*\"|\\d+)")) { "\"$key\": $value" }
            }
            val address = s.getString("address")
            set("server_address", org.json.JSONObject.quote(address))
            if (s.has("rtmp_port")) set("rtmp_port", s.getInt("rtmp_port").toString())
            if (s.has("reality_port")) set("reality_port", s.getInt("reality_port").toString())
            val rtmpPort = if (s.has("rtmp_port")) s.getInt("rtmp_port") else 1935
            set("rtmp_url", org.json.JSONObject.quote(s.optString("rtmp_url", "rtmp://$address:$rtmpPort/waterwall")))
            for (k in listOf("reality_sni", "reality_password", "trojan_password")) {
                if (s.has(k)) set(k, org.json.JSONObject.quote(s.getString(k)))
            }
            if (s.has("obfuscator_key")) set("obfuscator_key", s.getInt("obfuscator_key").toString())
            configFile.writeText(text)
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "Waterwall: remote server entry ignored: ${e.message}")
        }
    }

    private val serverAddressRegex = Regex("\"server_address\"\\s*:\\s*\"([^\"]+)\"")
    private val lastKnownIp = object {
        private val mem = java.util.concurrent.ConcurrentHashMap<String, String>()

        // The UI process warms the cache, the VPN service runs in another process (:daemon): a file is shared by both.
        private fun file(host: String) = File(File(AngApplication.application.filesDir, ASSET_DIR).apply { mkdirs() }, "ip_" + host.replace(Regex("[^A-Za-z0-9.-]"), "_"))

        operator fun get(host: String): String? = mem[host] ?: try {
            file(host).takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotEmpty() }?.also { mem[host] = it }
        } catch (_: Exception) {
            null
        }

        operator fun set(host: String, ip: String) {
            mem[host] = ip
            try {
                file(host).writeText(ip)
            } catch (_: Exception) {
            }
        }
    }

    private fun lookup(host: String): String? = try {
        val all = java.net.InetAddress.getAllByName(host)
        (all.firstOrNull { it is java.net.Inet4Address } ?: all.firstOrNull())?.hostAddress
    } catch (e: Exception) {
        LogUtil.w(AppConfig.TAG, "Waterwall: cannot resolve $host: ${e.message}")
        null
    }

    /** Resolves [host] in the background so the next start finds its IP in the cache and does not wait. */
    fun prewarm(host: String) {
        if (host.isBlank() || host.all { it.isDigit() || it == '.' || it == ':' }) return
        Thread({ lookup(host)?.let { lastKnownIp[host] = it } }, "waterwall-prewarm").apply { isDaemon = true }.start()
    }

    /**
     * The core's own resolver (c-ares) finds no DNS servers on Android: there is no resolv.conf, and it never asks
     * the system ("Could not contact DNS servers"), unlike in Termux which ships one. So the server name is resolved
     * here, through Android's resolver, and the IP is written into the config. The app is a disallowed application,
     * so this lookup does not go through the VPN. Falls back to the last good IP, then to the name itself.
     */
    private fun pinServerAddress(configFile: File) {
        try {
            val text = configFile.readText()
            val host = serverAddressRegex.find(text)?.groupValues?.get(1) ?: return
            if (host.all { it.isDigit() || it == '.' || it == ':' }) return
            // This runs on the main thread of the service (same process as the UI): a long wait freezes the screen and
            // the connect ring with it. So a known IP is used at once and refreshed in the background; only a cold
            // start waits, and briefly.
            val cached = lastKnownIp[host]
            if (cached != null) {
                prewarm(host)
            }
            var ip: String? = null
            if (cached == null) {
                val worker = Thread({ ip = lookup(host) }, "waterwall-resolve").apply { isDaemon = true }
                worker.start()
                worker.join(3_000)
                if (ip != null) lastKnownIp[host] = ip!!
            }
            val resolved = ip ?: cached ?: lastKnownIp[host]
            if (resolved == null) {
                LogUtil.w(AppConfig.TAG, "Waterwall: no IP for $host, leaving the name to the core")
                return
            }
            LogUtil.i(AppConfig.TAG, "Waterwall: $host -> $resolved")
            configFile.writeText(
                text.replace(serverAddressRegex) { "\"server_address\": \"$resolved\"" }
            )
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "Waterwall: pinServerAddress failed: ${e.message}")
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
        val began = System.currentTimeMillis()
        val deadline = began + budgetMs
        while (System.currentTimeMillis() < deadline) {
            if (isListening()) {
                LogUtil.i(AppConfig.TAG, "Waterwall: listening after ${System.currentTimeMillis() - began} ms")
                return true
            }
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
