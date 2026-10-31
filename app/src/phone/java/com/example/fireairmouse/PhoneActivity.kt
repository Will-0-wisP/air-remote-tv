package com.example.fireairmouse

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.widget.*
import kotlin.math.abs

/** Looks like the Fire TV remote app (D-pad ring, swipe pad, Back/Home/Menu, media, volume) + an Air mouse tab. */
@SuppressLint("ClickableViewAccessibility")
class PhoneActivity : Activity(), SensorEventListener {

    private val BG = Color.parseColor("#0E1116"); private val CARD = Color.parseColor("#181C23")
    private val CARD2 = Color.parseColor("#222733"); private val ACCENT = Color.parseColor("#4C8DFF")
    private val TEXT = Color.parseColor("#E6E9EF"); private val SUB = Color.parseColor("#8A93A3")
    private val GREEN = Color.parseColor("#34C759"); private val AMBER = Color.parseColor("#FFB020"); private val RED = Color.parseColor("#FF5C5C")
    private val DIALOG = AlertDialog.THEME_DEVICE_DEFAULT_DARK

    private lateinit var net: Net
    private lateinit var prefs: SharedPreferences
    private lateinit var status: TextView
    private lateinit var dot: View
    private lateinit var airPad: TextView
    private lateinit var lockTile: TextView
    private lateinit var sensors: SensorManager
    private lateinit var tabs: List<TextView>
    private lateinit var boxes: List<View>
    private val ui = Handler(Looper.getMainLooper())

    @Volatile private var aiming = false
    private var locked = false
    private var sensProgress = 600
    private var upright = false
    private var remX = 0f; private var remY = 0f
    private var lastTs = 0L
    private var vt: VelocityTracker? = null
    private var flingRun: Runnable? = null
    private var dX = 0f; private var dY = 0f; private var accX = 0f; private var accY = 0f
    private val aimBeat = object : Runnable { override fun run() { if (aiming) { net.send("AIM|1"); ui.postDelayed(this, 1000) } } }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun shape(color: Int, r: Int, oval: Boolean = false, stroke: Int = 0) = GradientDrawable().apply {
        if (oval) this.shape = GradientDrawable.OVAL else cornerRadius = dp(r).toFloat()
        setColor(color); if (stroke != 0) setStroke(dp(2), stroke)
    }
    private fun bgOf(fill: Int) = RippleDrawable(ColorStateList.valueOf(0x44FFFFFF), shape(fill, 16), shape(Color.WHITE, 16))

    private fun tile(label: String, fill: Int = CARD, color: Int = TEXT, size: Float = 16f, h: Int = 52, full: Boolean = false, onClick: () -> Unit) = TextView(this).apply {
        text = label; gravity = Gravity.CENTER; textSize = size; setTextColor(color); isClickable = true
        background = bgOf(fill)
        layoutParams = (if (full) LinearLayout.LayoutParams(-1, dp(h)) else LinearLayout.LayoutParams(0, dp(h), 1f)).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }
        setOnClickListener { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); onClick() }
    }
    private fun row(vararg v: View) = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; v.forEach { addView(it) } }
    private var hintShown = false
    private fun key(k: String) {
        val code = RemoteKeys.CODES[k]
        val wanted = prefs.getBoolean("adb_wanted", false)
        if (code != null && (AdbRemote.ready || (wanted && SystemClock.elapsedRealtime() - AdbRemote.lastReadyAt < 60_000))) {
            AdbRemote.key(code); return                                           // direct, like "adb" from a computer; reconnects by itself if the link went stale
        }
        net.send("K|$k")
        if (!hintShown && net.keyMode != "ADB" && SystemClock.elapsedRealtime() - net.lastPong < 4000) {
            hintShown = true
            Toast.makeText(this, "Real remote keys are not set up yet – tap Connect → Enable real remote keys", Toast.LENGTH_LONG).show()
        }
    }

    private var connectDlg: AlertDialog? = null

    // ---- one-tap Fire TV setup: install TV app, switch the pointer service on, pair PIN – all over ADB, no computer ----
    private fun newPin() = (1000 + java.security.SecureRandom().nextInt(9000)).toString()

    private fun pickApk() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }, 42)
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req != 42 || res != RESULT_OK) return
        val uri = data?.data ?: return
        Thread {
            val bytes = try { contentResolver.openInputStream(uri)?.use { it.readBytes() } } catch (_: Exception) { null }
            runOnUiThread {
                if (bytes != null && TvSetup.looksLikeApk(bytes)) autoSetup(bytes)
                else Toast.makeText(this, "That does not look like an APK file", Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun autoSetup(apkFromFile: ByteArray?) {
        val ip = prefs.getString("tv_ip", "")?.trim().orEmpty()
        if (ip.isBlank()) { Toast.makeText(this, "Enter the TV's IP address first (TV: Settings > My Fire TV > About > Network)", Toast.LENGTH_LONG).show(); return }
        AdbRemote.init(this); AdbRemote.setHosts(listOf(ip)); AdbRemote.reset()
        prefs.edit().putBoolean("adb_wanted", true).apply()
        val log = StringBuilder()
        val dlg = AlertDialog.Builder(this, DIALOG).setTitle("Setting up your Fire TV").setMessage("Starting…")
            .setCancelable(false).setPositiveButton("Close", null).create()
        dlg.show()
        fun say(t: String) { log.append(t).append('\n'); runOnUiThread { dlg.setMessage(log.toString()) } }
        AdbRemote.task {
            say("1. Connecting to $ip …\n    On the TV choose “Always allow” and OK if “Allow USB debugging?” appears.")
            if (AdbRemote.sh("true") == null) {
                say("✗ ${AdbRemote.status}\n\nCheck: Settings > My Fire TV > Developer options > ADB debugging = ON, same Wi-Fi, then try again.")
                return@task
            }
            say("✔ Connected")
            val installed = (AdbRemote.sh("pm list packages ${TvSetup.TV_PKG}") ?: "").contains("package:${TvSetup.TV_PKG}")
            var apk = apkFromFile
            if (apk == null && !installed) {
                if (BuildConfig.TV_APK_URL.isNotBlank()) {
                    say("2. Downloading the TV app…")
                    apk = try { TvSetup.download(BuildConfig.TV_APK_URL) } catch (e: Exception) { say("    (download failed: ${e.message})"); null }
                }
                if (apk == null) {
                    say("2. The TV app is not on the stick yet – choose its APK file (fire-air-mouse-tv.apk).")
                    runOnUiThread { dlg.dismiss(); pickApk() }
                    return@task
                }
            }
            if (apk != null) {
                say("2. Installing the TV app (${apk.size / 1024} KB)…")
                val r = AdbRemote.installApk(apk)
                if (r == null || !r.contains("Success")) { say("✗ Install failed: ${r?.trim() ?: AdbRemote.status}"); return@task }
                say("✔ TV app installed")
            } else say("2. ✔ TV app already installed")
            say("3. Switching on the pointer service…")
            AdbRemote.sh("pm grant ${TvSetup.TV_PKG} android.permission.WRITE_SECURE_SETTINGS")
            val cur = (AdbRemote.sh("settings get secure enabled_accessibility_services") ?: "").trim()
            val list = cur.split(":").filter { it.isNotBlank() && it != "null" }.toMutableList()
            if (!list.contains(TvSetup.SERVICE)) list.add(TvSetup.SERVICE)
            AdbRemote.sh("settings put secure enabled_accessibility_services '${list.joinToString(":")}'")
            AdbRemote.sh("settings put secure accessibility_enabled 1")
            say("4. Pairing: new PIN and opening the TV app…")
            val pin = newPin()
            prefs.edit().putString("tv_pin", pin).apply(); net.pin = pin; net.setTarget(ip)
            AdbRemote.sh("am start -n ${TvSetup.TV_ACTIVITY} --es pin $pin")
            var ok = false
            for (i in 0 until 24) { if (SystemClock.elapsedRealtime() - net.lastPong < 3000) { ok = true; break }; Thread.sleep(500) }
            say(if (ok) "✔ All set – the TV is connected." else "⚠ The TV app has not answered yet. Wait a few seconds; if the status line stays red, open “Fire Air Mouse” once on the TV.")
        }
    }

    /** The three PC commands in one tap: adb connect → pm grant WRITE_SECURE_SETTINGS → pm list packages (verify). */
    private fun grantPointer() {
        val ip = prefs.getString("tv_ip", "")?.trim().orEmpty()
        if (ip.isBlank()) { Toast.makeText(this, "Enter the TV's IP address first (TV: Settings > My Fire TV > About > Network)", Toast.LENGTH_LONG).show(); return }
        AdbRemote.init(this); AdbRemote.setHosts(listOf(ip)); AdbRemote.reset()
        prefs.edit().putBoolean("adb_wanted", true).apply()
        val log = StringBuilder()
        val dlg = AlertDialog.Builder(this, DIALOG).setTitle("Allow pointer on TV").setMessage("Starting…")
            .setCancelable(false).setPositiveButton("Close", null).create()
        dlg.show()
        fun say(t: String) { log.append(t).append('\n'); runOnUiThread { dlg.setMessage(log.toString()) } }
        AdbRemote.task {
            say("1. Connecting to $ip:5555 …\n    On the TV choose “Always allow” and OK if “Allow USB debugging?” appears.")
            if (AdbRemote.sh("true") == null) {
                say("✗ ${AdbRemote.status}\n\nCheck: Settings > My Fire TV > Developer options > ADB debugging = ON, same Wi-Fi, then try again.")
                return@task
            }
            say("✔ Connected")
            val pk = AdbRemote.sh("pm list packages ${TvSetup.TV_PKG}") ?: ""
            if (!pk.contains("package:${TvSetup.TV_PKG}")) {
                say("✗ The TV app is not installed on the stick yet.\n    Use “One-tap setup” or “Install / update TV app from a file…” first.")
                return@task
            }
            say("✔ Found on the stick: ${pk.trim()}")
            say("2. Granting the pointer permission…")
            val g = AdbRemote.sh("pm grant ${TvSetup.TV_PKG} android.permission.WRITE_SECURE_SETTINGS")?.trim().orEmpty()
            if (g.isNotEmpty()) say("    (stick said: $g)")
            val check = AdbRemote.sh("dumpsys package ${TvSetup.TV_PKG} | grep WRITE_SECURE_SETTINGS") ?: ""
            if (check.contains("granted=true")) say("✔ Permission granted")
            else say("⚠ Could not confirm the permission automatically${if (g.isEmpty()) " – it may still have worked" else ""}.")
            say("\n3. On the TV open “Fire Air Mouse” and select “Enable pointer”.\n    (It also switches itself on when you open it.)")
        }
    }

    private var askedKeys = false

    private fun showDiag() {
        val msg = "Phone → TV link:\n${AdbRemote.status}\n\nTV app's own link:\n${net.tvAdb.ifBlank { "(no data yet – connect first)" }}\n\nKeys mode reported by the TV: ${net.keyMode.ifBlank { "?" }}"
        AlertDialog.Builder(this, DIALOG).setTitle("Real remote keys – details").setMessage(msg)
            .setPositiveButton("Close", null).setNeutralButton("Retry") { _, _ -> enableKeys() }.show()
    }

    private fun askSetupKeys() {
        AlertDialog.Builder(this, DIALOG).setTitle("Set up real remote keys?")
            .setMessage("To behave exactly like the Fire remote, the TV must allow this phone once.\n\nMake sure ADB debugging is ON (Settings > My Fire TV > Developer options). Then tap Set up and, on the TV, pick “Always allow” and OK with your ORIGINAL remote.")
            .setPositiveButton("Set up") { _, _ -> enableKeys() }.setNegativeButton("Later", null).show()
    }

    private fun enableKeys() {
        prefs.edit().putBoolean("adb_wanted", true).apply()
        val ip = prefs.getString("tv_ip", "") ?: ""
        if (ip.isNotBlank()) { AdbRemote.setHosts(listOf(ip)); AdbRemote.forceReconnect(this) }
        net.send("ADB")
        Toast.makeText(this, "Look at the TV: approve “Allow USB debugging?” – choose Always allow (you may see it twice: allow both)", Toast.LENGTH_LONG).show()
    }

    private fun setState(s: String) {
        status.text = s
        (dot.background as GradientDrawable).setColor(when { s.startsWith("Connected") -> GREEN; s.startsWith("Looking") || s.startsWith("Limited") -> AMBER; else -> RED })
    }

    private fun setMode(m: Int) {
        if (m != 2 && locked) { locked = false; net.send("LK|0"); refreshLockUi() }
        boxes.forEachIndexed { i, b -> b.visibility = if (i == m) View.VISIBLE else View.GONE }
        tabs.forEachIndexed { i, t -> t.background = bgOf(if (i == m) ACCENT else CARD); t.setTextColor(if (i == m) Color.WHITE else TEXT) }
    }

    private fun airText() = if (locked) "🔒 Pointer locked\n1 swipe = 1 arrow press · press OK to open" else "Hold & move phone to aim\n(press OK to open)"
    private fun refreshLockUi() {
        airPad.text = airText(); airPad.setTextColor(SUB); airPad.background = shape(CARD, 28, stroke = if (locked) AMBER else 0)
        lockTile.text = if (locked) "🔒 Unlock pointer" else "🔓 Lock pointer"
        lockTile.background = bgOf(if (locked) Color.parseColor("#8A5A00") else CARD)
    }

    private fun padView(text: String, touch: (View, MotionEvent) -> Unit) = TextView(this).apply {
        this.text = text; gravity = Gravity.CENTER; textSize = 16f; setTextColor(SUB); background = shape(CARD, 28)
        layoutParams = LinearLayout.LayoutParams(-1, 0, 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }
        setOnTouchListener { v, e -> touch(v, e); true }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.decorView.setBackgroundColor(BG); window.statusBarColor = BG; window.navigationBarColor = BG
        sensors = getSystemService(SensorManager::class.java)
        prefs = getSharedPreferences("fam", MODE_PRIVATE)
        AdbRemote.init(this); net = Net(); applyConfig()
        net.onRelayKey = { code -> if (AdbRemote.ready) AdbRemote.key(code) }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(14)) }

        // header
        dot = View(this).apply { background = shape(RED, 0, true); layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply { rightMargin = dp(10) } }
        status = TextView(this).apply { setTextColor(TEXT); textSize = 14f; maxLines = 2; ellipsize = TextUtils.TruncateAt.END; layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
        status.setOnClickListener { showDiag() }
        val connectBtn = tile("Connect", ACCENT, Color.WHITE, 14f, h = 40) { connectDialog() }
        val kbBtn = tile("⌨", CARD, TEXT, 18f, h = 40) { keyboardDialog() }
        val setBtn = tile("⚙", CARD, TEXT, 18f, h = 40) { settingsDialog() }
        listOf(connectBtn, kbBtn, setBtn).forEach { (it.layoutParams as LinearLayout.LayoutParams).weight = 0f; it.layoutParams.width = dp(if (it === connectBtn) 92 else 44) }
        root.addView(LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; addView(dot); addView(status); addView(connectBtn); addView(kbBtn); addView(setBtn) })

        // mode tabs
        tabs = listOf(tile("D-pad", h = 40, size = 14f) { setMode(0) }, tile("Swipe", h = 40, size = 14f) { setMode(1) }, tile("Air mouse", h = 40, size = 14f) { setMode(2) })
        root.addView(row(*tabs.toTypedArray()).apply { setPadding(0, dp(8), 0, 0) })

        // control area
        val dpadBox = FrameLayout(this).apply {
            addView(DpadView(this@PhoneActivity) { k -> key(k) }, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        }
        val swipePad = padView("Swipe to navigate\n1 swipe = 1 arrow press\n(press OK to open)") { v, e -> swipeTouch(v, e) }
        val swipeBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(swipePad); addView(row(tile("OK", ACCENT, Color.WHITE, 18f) { key("OK") })) }
        airPad = padView(airText()) { v, e -> if (locked) swipeTouch(v, e) else aimTouch(v, e) }
        lockTile = tile("🔓 Lock pointer", h = 52) { locked = !locked; aiming = false; net.send("LK|${if (locked) 1 else 0}"); refreshLockUi() }
        val airBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(airPad); addView(row(lockTile, tile("OK", ACCENT, Color.WHITE, 18f) { key("OK") })) }
        boxes = listOf(dpadBox, swipeBox, airBox)
        root.addView(FrameLayout(this).apply {
            minimumHeight = dp(260)
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f).apply { setMargins(0, dp(8), 0, dp(8)) }
            boxes.forEach { addView(it, FrameLayout.LayoutParams(-1, -1)) }
        })

        // fixed remote rows (same as the Fire TV remote)
        root.addView(row(tile("‹  Back") { key("BACK") }, tile("⌂  Home") { key("HOME") }, tile("☰  Menu") { key("MENU") }))
        root.addView(row(tile("⏪") { key("REW") }, tile("⏯") { key("PLAY") }, tile("⏩") { key("FF") }))
        root.addView(row(tile("Vol −") { key("VDN") }, tile("Mute") { key("MUTE") }, tile("Vol +") { key("VUP") }))

        setContentView(ScrollView(this).apply { isFillViewport = true; addView(root) })
        refreshLockUi(); setMode(0)
    }

    // ---- air mouse: hold the pad and move the phone ----
    private fun aimTouch(v: View, e: MotionEvent) {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                v.parent.requestDisallowInterceptTouchEvent(true)
                aiming = true; remX = 0f; remY = 0f; lastTs = 0L
                v.background = shape(CARD, 28, stroke = ACCENT); airPad.setTextColor(TEXT); airPad.text = "Aiming…"
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                net.send("AIM|1"); ui.removeCallbacks(aimBeat); ui.postDelayed(aimBeat, 1000)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                aiming = false; ui.removeCallbacks(aimBeat); net.send("AIM|0"); net.send("AIM|0"); refreshLockUi()
            }
        }
    }

    // ---- swipe pad: ONE swipe = ONE arrow press in the swipe direction (fires as soon as the swipe is clear) ----
    private var swSX = 0f; private var swSY = 0f; private var swSent = false
    private fun swipeKey(dx: Float, dy: Float) = if (abs(dx) >= abs(dy)) (if (dx > 0) "RIGHT" else "LEFT") else (if (dy > 0) "DOWN" else "UP")

    private fun swipeTouch(v: View, e: MotionEvent) {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> { v.parent.requestDisallowInterceptTouchEvent(true); swSX = e.x; swSY = e.y; swSent = false }
            MotionEvent.ACTION_MOVE -> if (!swSent) {
                val dx = e.x - swSX; val dy = e.y - swSY
                if (maxOf(abs(dx), abs(dy)) > dp(20)) { swSent = true; key(swipeKey(dx, dy)); v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
            }
            MotionEvent.ACTION_UP -> if (!swSent) {              // short flick that never crossed the threshold
                val dx = e.x - swSX; val dy = e.y - swSY
                if (maxOf(abs(dx), abs(dy)) > dp(8)) { key(swipeKey(dx, dy)); v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
            }
        }
    }

    // ---- connection ----
    private fun applyConfig() {
        net.pin = prefs.getString("tv_pin", "") ?: ""
        val ip = prefs.getString("tv_ip", "") ?: ""
        if (ip.isNotBlank()) { net.setTarget(ip); AdbRemote.setHosts(listOf(ip)) }
    }

    private val tick = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            val hasIp = !prefs.getString("tv_ip", "").isNullOrBlank()
            if (hasIp) net.send("PING")
            if (locked) net.send("LK|1")
            if (hasIp && prefs.getBoolean("adb_wanted", false)) { AdbRemote.keepAlive(); if (!AdbRemote.ready) AdbRemote.retrySoon(this@PhoneActivity) }
            setState(when {
                !hasIp -> "Not set up – tap Connect"
                now - net.lastPong < 4000 -> when {
                    AdbRemote.ready -> "Connected: ${net.tvName} · real keys ✔ (direct)"
                    net.keyMode == "ADB" -> "Connected: ${net.tvName} · real keys ✔"
                    prefs.getBoolean("adb_wanted", false) -> "Limited · ${AdbRemote.status.lineSequence().first()}  (tap for details)"
                    else -> "Limited: ${net.tvName} · tap Connect → Enable real keys"
                }
                now - net.lastNoPin < 4000 -> "Wrong PIN – check the TV screen"
                else -> "Looking for TV…"
            })
            val live = hasIp && now - net.lastPong < 4000
            if (live && net.keyMode != "ADB" && !AdbRemote.ready && !prefs.getBoolean("adb_wanted", false) && !askedKeys) { askedKeys = true; askSetupKeys() }
            ui.postDelayed(this, 1500)
        }
    }

    private fun versionName(): String = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "?" } catch (_: Exception) { "?" }

    private fun connectDialog() {
        val ipEt = EditText(this).apply { hint = "e.g. 192.168.1.9"; setText(prefs.getString("tv_ip", "")); setTextColor(TEXT); setHintTextColor(SUB) }
        val pinEt = EditText(this).apply { hint = "PIN"; inputType = InputType.TYPE_CLASS_NUMBER; setText(prefs.getString("tv_pin", "")); setTextColor(TEXT); setHintTextColor(SUB) }
        net.onFound = { ip, name -> runOnUiThread { ipEt.setText(ip); Toast.makeText(this, "Found $name", Toast.LENGTH_SHORT).show() } }
        fun label(t: String) = TextView(this).apply { text = t; setTextColor(SUB); textSize = 12f; setPadding(dp(4), dp(12), 0, dp(2)) }
        fun act(t: String, fill: Int = CARD2, color: Int = TEXT, h: Int = 46, f: () -> Unit) = tile(t, fill, color, 15f, h, full = true) {
            prefs.edit().putString("tv_ip", ipEt.text.toString().trim()).apply(); applyConfig(); f()
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(4), dp(18), dp(8))
            addView(label("TV IP address  (TV: Settings > My Fire TV > About > Network)"))
            addView(ipEt)
            addView(tile("Find TV automatically", CARD2, TEXT, 15f, 46, full = true) { net.discover() })
            addView(label("Set up the TV from this phone (no computer)"))
            addView(act("⚡ One-tap setup: install + pointer + pair", ACCENT, Color.WHITE, 50) { connectDlg?.dismiss(); autoSetup(null) })
            addView(act("🔓 Allow pointer on TV (grant permission)") { connectDlg?.dismiss(); grantPointer() })
            addView(act("Install / update TV app from a file…") { connectDlg?.dismiss(); pickApk() })
            addView(act("Enable real remote keys (ADB)") { enableKeys() })
            addView(label("PIN  (One-tap setup sets it for you)"))
            addView(pinEt)
        }
        connectDlg = AlertDialog.Builder(this, DIALOG).setTitle("Connect to Fire TV · v${versionName()}")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Save") { _, _ ->
                prefs.edit().putString("tv_ip", ipEt.text.toString().trim()).putString("tv_pin", pinEt.text.toString().trim()).apply(); applyConfig()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun keyboardDialog() {
        val et = EditText(this).apply { hint = "Type to send to TV"; setTextColor(TEXT); setHintTextColor(SUB) }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)); addView(et)
            addView(row(tile("⌫", CARD2, h = 46) { net.send("B") }, tile("⏎", CARD2, h = 46) { key("ENTER") },
                tile("Send", ACCENT, Color.WHITE, h = 46) { if (et.text.isNotEmpty()) net.send("T|${et.text}"); et.setText("") }))
        }
        AlertDialog.Builder(this, DIALOG).setTitle("Keyboard (select a text field on the TV first)").setView(box).show()
    }

    private fun settingsDialog() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        box.addView(TextView(this).apply { text = "Air mouse speed"; setTextColor(SUB) })
        box.addView(SeekBar(this).apply {
            max = 1800; progress = sensProgress
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, u: Boolean) { sensProgress = p }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        })
        box.addView(Switch(this).apply { text = "Upright mode (screen facing you)"; setTextColor(TEXT); isChecked = upright; setPadding(0, dp(16), 0, 0); setOnCheckedChangeListener { _, c -> upright = c } })
        AlertDialog.Builder(this, DIALOG).setTitle("Settings").setView(box).setPositiveButton("Done", null).show()
    }

    // ---- gyro ----
    override fun onResume() { super.onResume(); ui.post(tick)
        if (prefs.getBoolean("adb_wanted", false) && !prefs.getString("tv_ip", "").isNullOrBlank()) AdbRemote.connect(this)
       
        sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) } }
    override fun onPause() {
        super.onPause(); ui.removeCallbacks(tick); ui.removeCallbacks(aimBeat); sensors.unregisterListener(this)
        if (aiming) net.send("AIM|0"); aiming = false
    }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    override fun onSensorChanged(e: SensorEvent) {
        if (!aiming || locked) { lastTs = 0L; return }
        if (lastTs == 0L) { lastTs = e.timestamp; return }
        val dt = (e.timestamp - lastTs) / 1e9f; lastTs = e.timestamp
        var yaw = if (upright) e.values[1] else e.values[2]
        var pitch = e.values[0]
        if (abs(yaw) < 0.02f) yaw = 0f
        if (abs(pitch) < 0.02f) pitch = 0f
        val k = 200f + sensProgress
        remX += -yaw * dt * k; remY += -pitch * dt * k
        val ix = remX.toInt(); val iy = remY.toInt()
        if (ix != 0 || iy != 0) { remX -= ix; remY -= iy; net.send("M|$ix,$iy") }
    }
}
