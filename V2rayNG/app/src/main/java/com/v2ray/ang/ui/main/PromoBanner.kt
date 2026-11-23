package com.v2ray.ang.ui.main

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Remote-controlled promo banner.
 *
 * The app downloads BANNER_URL (JSON) and shows the banner when "enabled" is true.
 * Change the banner at any time by editing the file on the server, no app update needed.
 *
 * JSON format (all fields optional except enabled):
 * {
 *   "enabled": true,
 *   "id": "promo-1",            // change this to show the banner again to users who closed it
 *   "title": "GsmShield Premium",
 *   "text": "اتصال پایدار و سریع‌تر",
 *   "url": "https://netlixy.ir",// opened on tap
 *   "image": "https://netlixy.ir/dl/banner.png" // optional; replaces the text card
 * }
 */
object PromoConfig {
    const val BANNER_URL = "https://netlixy.ir/dl/banner.json"
    const val REFRESH_MS = 30 * 60 * 1000L
}

data class PromoData(
    val enabled: Boolean,
    val id: String,
    val title: String,
    val text: String,
    val url: String,
    val image: String,
)

private fun httpGet(url: String): HttpURLConnection? {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.connectTimeout = 8000
    conn.readTimeout = 8000
    conn.useCaches = false
    return if (conn.responseCode == 200) conn else {
        conn.disconnect()
        null
    }
}

private suspend fun fetchPromo(): PromoData? = withContext(Dispatchers.IO) {
    try {
        val conn = httpGet(PromoConfig.BANNER_URL) ?: return@withContext null
        try {
            val o = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            PromoData(
                enabled = o.optBoolean("enabled", false),
                id = o.optString("id", ""),
                title = o.optString("title", ""),
                text = o.optString("text", ""),
                url = o.optString("url", ""),
                image = o.optString("image", ""),
            )
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        null
    }
}

private suspend fun fetchImage(url: String): ImageBitmap? = withContext(Dispatchers.IO) {
    try {
        val conn = httpGet(url) ?: return@withContext null
        try {
            BitmapFactory.decodeStream(conn.inputStream)?.asImageBitmap()
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        null
    }
}

@Composable
fun PromoBanner(modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    var promo by remember { mutableStateOf<PromoData?>(null) }
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    var dismissedId by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            // Keep the last good banner if a refresh fails.
            fetchPromo()?.let { p ->
                promo = p
                image = if (p.enabled && p.image.isNotBlank()) fetchImage(p.image) else null
            }
            delay(PromoConfig.REFRESH_MS)
        }
    }

    val p = promo ?: return
    if (!p.enabled || p.id == dismissedId) return

    val tapModifier = if (p.url.isNotBlank()) {
        Modifier.clickable { uriHandler.openUri(p.url) }
    } else {
        Modifier
    }

    val bmp = image
    if (bmp != null) {
        Box(
            modifier
                .fillMaxWidth()
                .height(64.dp)
                .clip(RoundedCornerShape(16.dp))
                .then(tapModifier)
        ) {
            Image(
                bitmap = bmp,
                contentDescription = p.title.ifBlank { null },
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(64.dp),
            )
            CloseButton(Modifier.align(Alignment.TopEnd)) { dismissedId = p.id }
        }
        return
    }

    if (p.title.isBlank() && p.text.isBlank()) return

    Row(
        modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.horizontalGradient(listOf(Color(0xFF0E2A47), Color(0xFF1F5C4A))))
            .then(tapModifier)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            if (p.title.isNotBlank()) {
                Text(
                    text = p.title,
                    color = Color(0xFFF5F1E6),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (p.text.isNotBlank()) {
                Text(text = p.text, color = Color(0xFFE0B354), fontSize = 12.sp)
            }
        }
        CloseButton(Modifier) { dismissedId = p.id }
    }
}

@Composable
private fun CloseButton(modifier: Modifier, onClose: () -> Unit) {
    Text(
        text = "✕",
        color = Color(0xFFF5F1E6),
        fontSize = 14.sp,
        modifier = modifier
            .padding(6.dp)
            .clickable { onClose() },
    )
}
