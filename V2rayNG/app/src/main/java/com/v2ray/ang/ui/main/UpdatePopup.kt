package com.v2ray.ang.ui.main

import android.content.Intent
import android.net.Uri
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.v2ray.ang.handler.AppUpdateNotifier

/** Popup shown when the distribution server has a newer version. */
@Composable
fun UpdatePopup(info: AppUpdateNotifier.Info, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("نسخه جدید ${info.version} آماده است") },
        text = { Text(info.notes.ifBlank { "یک نسخه جدید برای دانلود موجود است." }) },
        confirmButton = {
            TextButton(onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.url)))
                onDismiss()
            }) { Text("دانلود") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("بعداً") }
        },
    )
}
