package com.example.fireairmouse

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Receives UDP commands ("PIN|CMD|ARG") from the phone.
 *  - Remote keys are sent as REAL key events through the stick's ADB (see AdbRemote) = identical to the Fire remote.
 *    If ADB is not available, an accessibility-based fallback is used.
 *  - Pointer: visible only while the phone pad is held or locked; hides 3 s later. While it moves, the app's own
 *    focus ring follows it (same highlight as the remote). On release the focus stays on / snaps to the nearest item.
 */
class FamService : AccessibilityService() {
    companion object {
        const val PORT = 9876; const val HIDE_MS = 3000L
        val KEYCODES = RemoteKeys.CODES
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = HandlerThread("fam-scan").also { it.start() }
    private val wh by lazy { Handler(worker.looper) }
    private lateinit var wm: WindowManager
    private lateinit var audio: AudioManager
    private lateinit var lp: WindowManager.LayoutParams
    private var cursor: CursorView? = null
    private var cx = 0f; private var cy = 0f
    private var sw = 1920; private var sh = 1080
    @Volatile private var running = false
    private var socket: DatagramSocket? = null
    private var pin = ""

    private var items: List<Rect> = emptyList()           // focusable, leaf-most nodes on screen
    private var lastFocused: Rect? = null
    private var lastNavRect: Rect? = null
    private var lastFocusAt = 0L
    private var ignoreFocusUntil = 0L
    private var scanning = false; private var dirty = true; private var refreshPending = false
    private var lastScan = 0L
    private var locked = false; private var aimHeld = false
    private val navPending = AtomicInteger(0)
    @Volatile private var phoneAddr: InetAddress? = null
    @Volatile private var phonePort = 0
    private val focusSeq = AtomicInteger(0)
    @Volatile private var verifyTarget: Rect? = null

    private val hide = Runnable { cursor?.visibility = View.GONE }
    private val aimExpire = Runnable { aimHeld = false; focusUnderPointer(true); scheduleHide() }
    private val lockExpire = Runnable { locked = false; cursor?.locked = false; scheduleHide() }

    override fun onServiceConnected() {
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val p = Point(); @Suppress("DEPRECATION") wm.defaultDisplay.getRealSize(p); sw = p.x; sh = p.y
        cx = sw / 2f; cy = sh / 2f
        pin = Pin.get(this)

        val size = (sw / 45).coerceAtLeast(24)
        lp = WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = cx.toInt(); y = cy.toInt() }
        cursor = CursorView(this).also { it.visibility = View.GONE; wm.addView(it, lp) }

        AdbRemote.useTvHosts(); AdbRemote.connect(this)
        running = true
        Thread { serve() }.apply { isDaemon = true }.start()
    }

    override fun onInterrupt() {}
    override fun onDestroy() {
        running = false; socket?.close(); worker.quitSafely()
        cursor?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        super.onDestroy()
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null || e.packageName?.toString() == packageName) return
        dirty = true
        if (e.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            focusSeq.incrementAndGet()
            lastFocused = null
            // Focus moved by the remote keys while the pointer is showing: bring the pointer along.
            if (cursor?.visibility == View.VISIBLE && !aimHeld && SystemClock.elapsedRealtime() > ignoreFocusUntil) {
                val r = Rect(); e.source?.getBoundsInScreen(r)
                if (r.width() > 0 && r.height() > 0) {
                    cx = r.exactCenterX().coerceIn(0f, (sw - 1).toFloat()); cy = r.exactCenterY().coerceIn(0f, (sh - 1).toFloat())
                    lastFocused = items.filter { it.contains(cx.toInt(), cy.toInt()) }.minByOrNull { it.width().toLong() * it.height() }
                    positionCursor()
                }
            }
        }
        if (cursor?.visibility == View.VISIBLE) scheduleRefresh()
    }

    // ---------------- network ----------------
    private fun serve() {
        try {
            val s = DatagramSocket(null); s.reuseAddress = true; s.bind(InetSocketAddress(PORT)); socket = s
            val buf = ByteArray(2048)
            while (running) {
                val pk = DatagramPacket(buf, buf.size); s.receive(pk)
                onPacket(s, pk, String(pk.data, 0, pk.length, Charsets.UTF_8))
            }
        } catch (_: Exception) { }
    }
    private fun reply(s: DatagramSocket, to: DatagramPacket, text: String) {
        val b = text.toByteArray()
        try { s.send(DatagramPacket(b, b.size, to.address, to.port)) } catch (_: Exception) { }
    }
    private fun onPacket(s: DatagramSocket, pk: DatagramPacket, msg: String) {
        if (msg == "HELLO") { reply(s, pk, "HELLO_R|${Build.MODEL}"); return }
        val parts = msg.split("|", limit = 3)
        if (parts.size < 2) return
        if (parts[0] != Pin.get(this)) { if (parts[1] == "PING") reply(s, pk, "NOPIN"); return }
        val cmd = parts[1]; val arg = parts.getOrElse(2) { "" }
        phoneAddr = pk.address; phonePort = pk.port
        if (cmd == "PING") { reply(s, pk, "PONG|${Build.MODEL}|${if (AdbRemote.ready) "ADB" else "LIMITED"}|${AdbRemote.status.replace("\n", " ; ").replace("|", "/")}"); return }
        main.post { execute(cmd, arg) }
    }

    // ---------------- screen scan (worker thread) ----------------
    private fun scheduleRefresh() {
        if (refreshPending) return
        refreshPending = true
        main.postDelayed({ refreshPending = false; refresh() }, 350)
    }
    private fun ensureFresh() { if (dirty || SystemClock.elapsedRealtime() - lastScan > 3000) refresh() }

    private fun refresh() {
        if (scanning) { dirty = true; return }
        scanning = true; dirty = false
        wh.post {
            val out = ArrayList<Rect>()
            try { rootInActiveWindow?.let { collect(it, out, 0) } } catch (_: Exception) { }
            main.post {
                items = out; lastScan = SystemClock.elapsedRealtime(); scanning = false
                if (aimHeld) focusUnderPointer()
                if (dirty && cursor?.visibility == View.VISIBLE) scheduleRefresh()
            }
        }
    }

    /** Leaf-most FOCUSABLE nodes = the things the real remote can land on. */
    private fun collect(n: AccessibilityNodeInfo, out: MutableList<Rect>, depth: Int): Boolean {
        if (depth > 45 || out.size > 1500 || !n.isVisibleToUser) return false
        var childHas = false
        for (i in 0 until n.childCount) { val c = n.getChild(i) ?: continue; if (collect(c, out, depth + 1)) childHas = true }
        if (childHas) return true
        if (n.isEnabled && (n.isFocusable || n.isClickable || n.isLongClickable || n.isCheckable)) {
            val r = Rect(); n.getBoundsInScreen(r)
            val big = r.width().toLong() * r.height() > 0.85 * sw * sh
            if (!big && r.width() > 8 && r.height() > 8 && r.right > 0 && r.bottom > 0 && r.left < sw && r.top < sh) { out.add(r); return true }
        }
        return false
    }

    // ---------------- pointer; focus follows it ----------------
    private fun scheduleHide() {
        main.removeCallbacks(hide)
        if (!locked && !aimHeld) main.postDelayed(hide, HIDE_MS)
    }

    private fun positionCursor() {
        val c = cursor ?: return
        lp.x = cx.toInt(); lp.y = cy.toInt()
        if (c.visibility != View.VISIBLE) c.visibility = View.VISIBLE
        try { wm.updateViewLayout(c, lp) } catch (_: Exception) { }
        scheduleHide()
    }

    private fun showCursor() { positionCursor(); ensureFresh(); focusUnderPointer() }

    private fun nearest(list: List<Rect>, px: Int, py: Int, maxDist: Float): Rect? {
        var best: Rect? = null; var bd = Float.MAX_VALUE
        for (r in list) {
            val dx = maxOf(r.left - px, 0, px - r.right).toFloat(); val dy = maxOf(r.top - py, 0, py - r.bottom).toFloat()
            val d = hypot(dx, dy)
            if (d < bd) { bd = d; best = r }
        }
        return if (bd <= maxDist) best else null
    }

    /** Puts the app's own focus on the item under the pointer. force = pointer released: settle on it / the nearest item. */
    private fun focusUnderPointer(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastFocusAt < 70) return
        val px = cx.toInt(); val py = cy.toInt()
        var best = items.filter { it.contains(px, py) }.minByOrNull { it.width().toLong() * it.height() }
        if (best == null && force) {
            best = nearest(items, px, py, sw * 0.3f)
            if (best != null && cursor?.visibility == View.VISIBLE) { cx = best.exactCenterX(); cy = best.exactCenterY(); positionCursor() }
        }
        if (best == null) return
        if (best == lastFocused && !force) return
        lastFocusAt = now; lastFocused = best
        focusRect(best)
        scheduleVerify(best, if (force) 60L else 220L)
    }

    /** Focuses the item, or the nearest focusable parent of it (tiles are often clickable children of a focusable card). */
    private fun focusRect(r: Rect) {
        ignoreFocusUntil = SystemClock.elapsedRealtime() + 500
        wh.post {
            try {
                val root = rootInActiveWindow ?: return@post
                var n: AccessibilityNodeInfo? = findByBounds(root, r, 0)
                var hops = 0
                while (n != null && !n.isFocusable && hops < 6) { n = n.parent; hops++ }
                if (n != null && n.isFocusable) n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            } catch (_: Exception) { }
        }
    }

    private fun focusedRect(root: AccessibilityNodeInfo): Rect? {
        val n = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
        if (!n.isVisibleToUser) return null
        val r = Rect(); n.getBoundsInScreen(r)
        return if (r.width() > 0 && r.height() > 0) r else null
    }

    // ---- keys from the TV side: own ADB if it works, otherwise ask the phone to press them (relay) ----
    private fun canSendKeys() = AdbRemote.ready || phoneAddr != null
    private fun sendKeyAny(code: Int) { if (AdbRemote.ready) AdbRemote.key(code) else relayKey(code) }
    private fun relayKey(code: Int) {
        val a = phoneAddr ?: return; val s = socket ?: return
        val b = "KEY|$code".toByteArray()
        try { s.send(DatagramPacket(b, b.size, a, phonePort)) } catch (_: Exception) { }
    }

    private val verifyRun = Runnable { verifyTarget?.let { t -> wh.post { try { settle(t) } catch (_: Exception) { } } } }
    private fun scheduleVerify(r: Rect, delayMs: Long) { verifyTarget = r; main.removeCallbacks(verifyRun); main.postDelayed(verifyRun, delayMs) }

    /** If the app's focus did not land on the item under the pointer, walk it there with REAL D-pad keys, like the remote. */
    private fun settle(target: Rect) {
        if (!canSendKeys()) return
        var lastDist = Float.MAX_VALUE; var worse = 0
        for (i in 0 until 6) {
            if (verifyTarget != target) return                       // pointer already moved on
            val root = rootInActiveWindow ?: return
            val f = focusedRect(root) ?: return
            val tx = target.exactCenterX(); val ty = target.exactCenterY(); val fx = f.exactCenterX(); val fy = f.exactCenterY()
            if (f.contains(tx.toInt(), ty.toInt()) || target.contains(fx.toInt(), fy.toInt())) return
            val d = hypot(tx - fx, ty - fy)
            if (d >= lastDist) { if (++worse >= 2) return } else worse = 0
            lastDist = d
            val dx = tx - fx; val dy = ty - fy
            val seq = focusSeq.get()
            sendKeyAny(if (abs(dx) >= abs(dy)) (if (dx > 0) 22 else 21) else (if (dy > 0) 20 else 19))
            val t0 = SystemClock.elapsedRealtime()
            while (focusSeq.get() == seq && SystemClock.elapsedRealtime() - t0 < 900) Thread.sleep(30)
        }
    }

    private fun findByBounds(n: AccessibilityNodeInfo, r: Rect, depth: Int): AccessibilityNodeInfo? {
        if (depth > 45) return null
        for (i in 0 until n.childCount) { val c = n.getChild(i) ?: continue; findByBounds(c, r, depth + 1)?.let { return it } }
        val b = Rect(); n.getBoundsInScreen(b)
        return if (b == r && n.isVisibleToUser) n else null
    }

    // ---------------- fallback D-pad (only used when ADB keys are unavailable) ----------------
    private fun pickNext(list: List<Rect>, origin: Rect, dir: Int): Rect? {
        val ox = origin.exactCenterX(); val oy = origin.exactCenterY()
        var best: Rect? = null; var bestScore = Float.MAX_VALUE
        for (r in list) {
            if (r == origin) continue
            val dx = r.exactCenterX() - ox; val dy = r.exactCenterY() - oy
            val prim: Float; val orth: Float
            when (dir) {
                View.FOCUS_UP -> { prim = -dy; orth = abs(dx) }
                View.FOCUS_DOWN -> { prim = dy; orth = abs(dx) }
                View.FOCUS_LEFT -> { prim = -dx; orth = abs(dy) }
                else -> { prim = dx; orth = abs(dy) }
            }
            if (prim < 4f) continue
            val score = prim + 2.5f * orth
            if (score < bestScore) { bestScore = score; best = r }
        }
        return best
    }

    private fun nav(dir: Int) {
        if (navPending.get() >= 2) return
        navPending.incrementAndGet()
        wh.post { try { navWork(dir) } catch (_: Exception) { } finally { navPending.decrementAndGet() } }
    }

    private fun navWork(dir: Int) {
        val root = rootInActiveWindow ?: return
        val list = ArrayList<Rect>(); collect(root, list, 0)
        val focused: Rect? = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { n ->
            if (!n.isVisibleToUser) null else Rect().also { n.getBoundsInScreen(it) }
        }?.takeIf { it.width() > 0 && it.height() > 0 }
        val origin: Rect? = focused ?: lastNavRect?.takeIf { list.contains(it) }
        val best: Rect? = if (origin == null) list.minByOrNull { hypot(it.exactCenterX() - sw / 2f, it.exactCenterY() - sh / 2f) }
                          else pickNext(list, origin, dir)
        if (best != null) {
            lastNavRect = best; ignoreFocusUntil = SystemClock.elapsedRealtime() + 500
            findByBounds(root, best, 0)?.let { if (it.isFocusable) it.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }
            main.post { items = list }
        } else if (dir == View.FOCUS_UP || dir == View.FOCUS_DOWN) {
            try {
                val n = findScrollable(root, sw / 2, sh / 2, 0)
                if (n != null && Build.VERSION.SDK_INT >= 23) {
                    val a = if (dir == View.FOCUS_DOWN) AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN else AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP
                    n.performAction(a.id)
                }
            } catch (_: Exception) { }
        }
    }
    private fun findScrollable(n: AccessibilityNodeInfo, x: Int, y: Int, depth: Int): AccessibilityNodeInfo? {
        if (depth > 45) return null
        val r = Rect(); n.getBoundsInScreen(r)
        if (!r.contains(x, y)) return null
        for (i in 0 until n.childCount) { val c = n.getChild(i) ?: continue; findScrollable(c, x, y, depth + 1)?.let { return it } }
        return if (n.isScrollable) n else null
    }

    private fun okFallback() {
        wh.post {
            try {
                val f = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (f != null && f.isVisibleToUser) f.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } catch (_: Exception) { }
        }
    }

    // ---------------- commands ----------------
    private fun execute(cmd: String, arg: String) {
        when (cmd) {
            "M" -> {
                val p = arg.split(",")
                if (p.size == 2) {
                    val k = sw / 1920f
                    cx = (cx + (p[0].toIntOrNull() ?: 0) * k).coerceIn(0f, (sw - 1).toFloat())
                    cy = (cy + (p[1].toIntOrNull() ?: 0) * k).coerceIn(0f, (sh - 1).toFloat())
                    showCursor()
                }
            }
            "AIM" -> {
                if (arg == "1") { aimHeld = true; main.removeCallbacks(aimExpire); main.postDelayed(aimExpire, 2500); showCursor() }
                else { aimHeld = false; main.removeCallbacks(aimExpire); focusUnderPointer(true); scheduleHide() }
            }
            "LK" -> {
                if (arg == "1") {
                    val was = locked; locked = true; cursor?.locked = true
                    main.removeCallbacks(lockExpire); main.postDelayed(lockExpire, 5000)
                    if (!was || cursor?.visibility != View.VISIBLE) showCursor()
                } else {
                    locked = false; cursor?.locked = false; main.removeCallbacks(lockExpire)
                    if (cursor?.visibility == View.VISIBLE) scheduleHide()
                }
            }
            "ADB" -> AdbRemote.forceReconnect(this)
            "K" -> key(arg)
            "T" -> typeText(arg)
            "B" -> if (AdbRemote.ready) AdbRemote.key(67) else editFocused { it.dropLast(1) }
        }
    }

    private fun key(name: String) {
        val code = RemoteKeys.CODES[name]
        if (code != null && AdbRemote.ready) { AdbRemote.key(code); return }
        if (code != null) AdbRemote.retrySoon(this)
        when (name) {   // accessibility fallback
            "BACK" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "HOME" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "MENU" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "PLAY" -> media(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            "REW" -> media(KeyEvent.KEYCODE_MEDIA_REWIND)
            "FF" -> media(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
            "VUP" -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            "VDN" -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            "MUTE" -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI)
            "UP" -> nav(View.FOCUS_UP)
            "DOWN" -> nav(View.FOCUS_DOWN)
            "LEFT" -> nav(View.FOCUS_LEFT)
            "RIGHT" -> nav(View.FOCUS_RIGHT)
            "OK", "ENTER" -> okFallback()
        }
    }

    private fun media(code: Int) {
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun typeText(t: String) {
        val hasInput = try { rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) != null } catch (_: Exception) { false }
        if (hasInput) editFocused { it + t } else if (AdbRemote.ready) AdbRemote.text(t)
    }

    private fun editFocused(change: (String) -> String) {
        try {
            val n = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return
            var cur = n.text?.toString() ?: ""
            if (Build.VERSION.SDK_INT >= 26 && n.isShowingHintText) cur = ""
            val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, change(cur)) }
            n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (_: Exception) { }
    }
}
