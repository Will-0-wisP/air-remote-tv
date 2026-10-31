package com.example.fireairmouse

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.net.Inet4Address
import java.net.NetworkInterface

/** Fire TV setup screen: buttons on top, then a scrollable status panel (click it with the remote and press down to scroll). */
class TvActivity : Activity() {
    private lateinit var info: TextView
    private var notice = ""
    private fun hasSecurePermission() = checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED
    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable { override fun run() { refresh(); ui.postDelayed(this, 1000) } }

    private fun localIp(): String {
        try {
            for (ni in NetworkInterface.getNetworkInterfaces())
                for (a in ni.inetAddresses) if (!a.isLoopbackAddress && a is Inet4Address) return a.hostAddress ?: "unknown"
        } catch (_: Exception) { }
        return "unknown"
    }

    private fun serviceOn(): Boolean {
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        return s.contains(ComponentName(this, FamService::class.java).flattenToString())
    }

    private fun enableService() {
        val comp = ComponentName(this, FamService::class.java).flattenToString()
        if (checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED) {
            try {
                val cur = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
                if (!cur.contains(comp)) Settings.Secure.putString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, if (cur.isEmpty()) comp else "$cur:$comp")
                Settings.Secure.putInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                notice = ""; refresh(); return
            } catch (_: Exception) { }
        }
        notice = "Pointer permission is missing. In the phone app tap Connect → “Allow pointer on TV”, then select Enable pointer again."
        refresh()
        try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (_: Exception) { }
    }

    private fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; textSize = 16f; setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(8, 0, 8, 0) }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        AdbRemote.init(this); AdbRemote.useTvHosts()
        intent?.getStringExtra("pin")?.let { if (it.length in 4..8 && it.all { c -> c.isDigit() }) Pin.set(this, it) }   // set by the phone's one-tap setup
        val pad = (resources.displayMetrics.density * 32).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#0E1116")); setPadding(pad, pad, pad, pad) }
        root.addView(TextView(this).apply { text = "Fire Air Mouse – receiver"; textSize = 28f; setTextColor(Color.WHITE); setPadding(0, 0, 0, pad / 2) })
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(btn("Enable pointer") { enableService() })
            addView(btn("Connect real keys") { AdbRemote.forceReconnect(this@TvActivity); refresh() })
            addView(btn("New PIN") { Pin.regenerate(this@TvActivity); refresh() })
        })
        info = TextView(this).apply {
            textSize = 17f; setTextColor(Color.parseColor("#C8CED9")); setPadding(pad / 2, pad / 2, pad / 2, pad / 2)
            isFocusable = true
            background = GradientDrawable().apply { setColor(Color.parseColor("#181C23")); cornerRadius = 16f }
            onFocusChangeListener = android.view.View.OnFocusChangeListener { v, f -> (v.background as GradientDrawable).setStroke(if (f) 4 else 0, Color.parseColor("#4C8DFF")) }
        }
        root.addView(ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = pad / 2 }
            addView(info)
        })
        setContentView(root)
    }

    override fun onResume() { super.onResume(); ui.post(tick); if (hasSecurePermission() && !serviceOn()) enableService() }
    override fun onPause() { super.onPause(); ui.removeCallbacks(tick) }

    private fun refresh() {
        info.text = (if (notice.isNotEmpty()) "⚠ $notice\n\n" else "") + "Pointer service:  ${if (serviceOn()) "ON ✔" else "OFF – select “Enable pointer”"}\n" +
            "IP address:  ${localIp()}     PIN:  ${Pin.get(this)}\n\n" +
            "REAL REMOTE KEYS (this TV app):\n${AdbRemote.status}\n\n" +
            "Needs: Developer options > ADB debugging ON. Select “Connect real keys”, then approve “Allow USB debugging?” with your original remote (Always allow).\n" +
            "Easiest: phone app > Connect > One-tap setup (no computer needed). If the line above says the stick refuses connections from itself, that is fine as long as the phone shows “real keys ✔ (direct)”."
    }
}
