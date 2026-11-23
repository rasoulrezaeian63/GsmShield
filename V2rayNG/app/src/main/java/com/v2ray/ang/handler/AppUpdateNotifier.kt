package com.v2ray.ang.handler

import com.v2ray.ang.BuildConfig
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.JsonUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads a small JSON manifest from the distribution server and reports a newer app version.
 * Expected manifest: {"version":"2.3.15","notes":"...","url":"https://netlixy.ir/dl/PattNG_2.3.15.apk"}
 */
object AppUpdateNotifier {
    const val MANIFEST_URL = "https://netlixy.ir/dl/version.json"

    class UpdateManifest {
        var version: String? = null
        var notes: String? = null
        var url: String? = null
    }

    data class Info(val version: String, val notes: String, val url: String)

    suspend fun check(): Info? = withContext(Dispatchers.IO) {
        try {
            val body = HttpUtil.getUrlContent(UrlContentRequest(url = MANIFEST_URL, timeout = 8000))
                ?: return@withContext null
            val manifest = JsonUtil.fromJsonSafe(body, UpdateManifest::class.java)
                ?: return@withContext null
            val version = manifest.version?.trim().orEmpty()
            val url = manifest.url?.trim().orEmpty()
            if (version.isEmpty() || url.isEmpty()) return@withContext null
            if (compare(version, BuildConfig.VERSION_NAME) <= 0) return@withContext null
            Info(version, manifest.notes.orEmpty(), url)
        } catch (e: Exception) {
            null
        }
    }

    /** Compares numeric segments: "2.3.14" > "2.3.13". */
    fun compare(a: String, b: String): Int {
        val x = a.split(Regex("[^0-9]+")).filter { it.isNotEmpty() }.map { it.toLong() }
        val y = b.split(Regex("[^0-9]+")).filter { it.isNotEmpty() }.map { it.toLong() }
        for (i in 0 until maxOf(x.size, y.size)) {
            val p = x.getOrElse(i) { 0L }
            val q = y.getOrElse(i) { 0L }
            if (p != q) return p.compareTo(q)
        }
        return 0
    }
}
