package com.v2ray.ang.handler

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.v2ray.ang.AngApplication
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.WaterwallCoreManager
import com.v2ray.ang.util.LogUtil
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Keeps the Watray (WaterWall) servers up to date from a panel / link, every 6 hours and on demand.
 *
 * The link returns JSON:
 * {"servers":[{"name":"NL 1","address":"host","rtmp_port":1935,"rtmp_url":"rtmp://host:1935/waterwall",
 *   "reality_port":6443,"reality_sni":"x.ir","reality_password":"..","obfuscator_key":".."}]}
 * Only "name" and "address" are required; the other fields fall back to the bundled config.
 * Each server becomes a "Watray <name>" row in the server list.
 */
object WatrayUpdater {
    /** Panel link; empty = disabled. Can be overridden at runtime through the settings key [PREF_URL]. */
    private const val DEFAULT_URL = "https://netlixy.ir/dl/wat.txt"
    const val PREF_URL = "watray_update_url"
    private const val PREF_SERVERS = "watray_servers_json"
    private const val WORK_NAME = "watray_servers_update"
    const val INTERVAL_HOURS = 6L
    private const val PREFIX = "Watray "

    fun url(): String = MmkvManager.decodeSettingsString(PREF_URL)?.takeIf { it.isNotBlank() } ?: DEFAULT_URL

    fun schedule(context: Context = AngApplication.application) {
        if (url().isBlank()) return
        val req = PeriodicWorkRequestBuilder<Worker>(INTERVAL_HOURS, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        // Also refresh right away on every app start, so the list is current without a manual update
        val now = OneTimeWorkRequestBuilder<Worker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME + "_now", ExistingWorkPolicy.REPLACE, now)
    }

    /** Downloads the list and rewrites the Watray rows. Blocking: call off the main thread. True on success. */
    @Synchronized
    fun updateNow(): Boolean {
        val link = url()
        if (link.isBlank()) return false
        return try {
            val conn = (URL(link).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 15_000
                setRequestProperty("User-Agent", "Phonix")
            }
            val body = try {
                if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
                conn.inputStream.bufferedReader().readText()
            } finally {
                conn.disconnect()
            }
            val root = JSONObject(body)
            val arr = if (root.has("nodes")) {
                // A bare WaterWall config pasted on the host: one server, the config is used as is.
                val last = root.getJSONArray("nodes").let { it.getJSONObject(it.length() - 1) }
                org.json.JSONArray().put(JSONObject().apply {
                    put("name", root.optString("remarks").ifBlank { "VLESS" })
                    put("address", last.optJSONObject("settings")?.optString("address").orEmpty().ifBlank { "config" })
                    put("config", root)
                })
            } else root.getJSONArray("servers")
            val kept = JSONObject()
            for (i in 0 until arr.length()) {
                val s = arr.getJSONObject(i)
                val name = s.optString("name").trim()
                if (name.isEmpty() || s.optString("address").isBlank()) continue
                kept.put(PREFIX + name, s)
            }
            if (kept.length() == 0) error("no valid servers")
            MmkvManager.encodeSettings(PREF_SERVERS, kept.toString())
            syncProfiles(kept)
            LogUtil.i(AppConfig.TAG, "WatrayUpdater: ${kept.length()} servers")
            true
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "WatrayUpdater: failed: ${e.message}")
            false
        }
    }

    fun hasRemote(): Boolean = !MmkvManager.decodeSettingsString(PREF_SERVERS).isNullOrBlank()

    /** The server entry for a profile name, or null for the bundled default. */
    fun serverFor(remarks: String): JSONObject? = try {
        MmkvManager.decodeSettingsString(PREF_SERVERS)?.let { JSONObject(it).optJSONObject(remarks.trim()) }
    } catch (e: Exception) {
        null
    }

    @Synchronized
    private fun syncProfiles(kept: JSONObject) {
        val existing = MmkvManager.decodeServerList("").mapNotNull { g ->
            MmkvManager.decodeServerConfig(g)?.takeIf { WaterwallCoreManager.isWatray(it) }?.let { g to it }
        }
        // Same-name rows (left by two refreshes running at once) are collapsed to the first one.
        val seen = HashSet<String>()
        existing.filter { (_, p) -> !seen.add(p.remarks.trim()) }.forEach { (g, _) -> MmkvManager.removeServer(g) }
        // Rows of servers the panel dropped (never the bundled default) are removed.
        existing.filter { (_, p) -> p.remarks != WaterwallCoreManager.NAME && !kept.has(p.remarks.trim()) }
            .forEach { (g, _) -> MmkvManager.removeServer(g) }
        val have = existing.map { it.second.remarks.trim() }.toSet()
        kept.keys().forEach { name ->
            if (name !in have) {
                MmkvManager.encodeServerConfig("", WaterwallCoreManager.createProfile().apply { remarks = name })
            }
        }
    }

    class Worker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result = if (updateNow() || url().isBlank()) Result.success() else Result.retry()
    }
}
