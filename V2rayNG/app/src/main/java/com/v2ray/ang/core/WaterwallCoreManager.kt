package com.v2ray.ang.core

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.LogUtil
import java.io.File

/**
 * Runs the bundled WaterWall core (libwaterwall.so) for the "Watray Netherlands" profile. Xray owns the TUN
 * and dials a SOCKS outbound to the core's listener on [PORT]; the core's own upstream sockets are
 * excluded from the VPN because the app itself is a disallowed application.
 * Only arm64-v8a is bundled.
 */
object WaterwallCoreManager {
    const val NAME = "Watray Netherlands"
    const val PORT = 1085
    private const val ASSET_DIR = "waterwall"

    private var process: Process? = null

    fun isWatray(p: ProfileItem): Boolean =
        p.configType == EConfigType.SOCKS && p.remarks == NAME && p.server == AppConfig.LOOPBACK &&
            p.serverPort == PORT.toString()

    fun createProfile(): ProfileItem = ProfileItem.create(EConfigType.SOCKS).apply {
        remarks = NAME
        server = AppConfig.LOOPBACK
        serverPort = PORT.toString()
    }

    fun isSupported(context: Context): Boolean =
        File(context.applicationInfo.nativeLibraryDir, "libwaterwall.so").exists()

    @Synchronized
    fun start(context: Context) {
        stop()
        val binary = File(context.applicationInfo.nativeLibraryDir, "libwaterwall.so")
        if (!binary.exists()) {
            LogUtil.w(AppConfig.TAG, "Waterwall: binary missing (unsupported ABI)")
            return
        }
        val dir = File(context.filesDir, ASSET_DIR).apply { mkdirs() }
        // Rewritten on every start so an updated app always runs its own bundled config.
        for (name in listOf("core.json", "config.json")) {
            context.assets.open("$ASSET_DIR/$name").use { input ->
                File(dir, name).outputStream().use { input.copyTo(it) }
            }
        }
        File(dir, "log").mkdirs()
        try {
            process = ProcessBuilder(binary.absolutePath)
                .directory(dir)
                .redirectErrorStream(true)
                .start()
            LogUtil.i(AppConfig.TAG, "Waterwall: started")
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Waterwall: failed to start", e)
        }
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
