package space.k2p.core.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent

object LinkHandler {
    fun openUrl(context: Context, url: String) {
        if (url.isBlank()) return
        val uri = Uri.parse(url)

        if (url.startsWith("https://t.me/") || url.startsWith("tg://")) {
            val telegramIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage("org.telegram.messenger")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(telegramIntent)
                return
            } catch (_: Exception) {
                // Telegram app is not installed, fallback to browser
            }
        }

        try {
            val customTabsIntent = CustomTabsIntent.Builder()
                .setShowTitle(true)
                .build()
            customTabsIntent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            customTabsIntent.launchUrl(context, uri)
        } catch (_: Exception) {
            try {
                val fallbackIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallbackIntent)
            } catch (_: Exception) {
                // Unable to open URL
            }
        }
    }
}
