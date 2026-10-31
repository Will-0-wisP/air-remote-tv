package com.example.fireairmouse

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Constants and helpers for the one-tap Fire TV setup (done entirely over the stick's ADB, no computer). */
object TvSetup {
    const val TV_PKG = "com.example.fireairmouse.tv"
    const val SERVICE = "$TV_PKG/com.example.fireairmouse.FamService"
    const val TV_ACTIVITY = "$TV_PKG/com.example.fireairmouse.TvActivity"

    fun looksLikeApk(b: ByteArray) = b.size > 10_000 && b[0] == 0x50.toByte() && b[1] == 0x4B.toByte()   // "PK" zip header

    fun download(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 30_000; c.instanceFollowRedirects = true
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            val b = c.inputStream.use { it.readBytes() }
            if (!looksLikeApk(b) || b.size > 60_000_000) throw IOException("not an APK")
            return b
        } finally { c.disconnect() }
    }
}
