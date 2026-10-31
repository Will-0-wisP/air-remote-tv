package com.example.fireairmouse

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/** Round Fire-remote style ring: four arrows around a centre OK button. Holding an arrow repeats it. */
class DpadView(ctx: Context, private val onKey: (String) -> Unit) : View(ctx) {
    private fun paint(c: String) = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor(c) }
    private val ring = paint("#181C23"); private val okFill = paint("#4C8DFF")
    private val arrow = paint("#E6E9EF"); private val press = paint("#44FFFFFF")
    private val txt = paint("#FFFFFF").apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    private var pressed: String? = null
    private val ui = Handler(Looper.getMainLooper())
    private val rep = object : Runnable { override fun run() { pressed?.let { if (it != "OK") { onKey(it); ui.postDelayed(this, 110) } } } }

    override fun onMeasure(w: Int, h: Int) {
        val ws = MeasureSpec.getSize(w); val hs = MeasureSpec.getSize(h)
        val s = if (hs > 0) min(ws, hs) else ws
        setMeasuredDimension(s, s)
    }

    override fun onDraw(c: Canvas) {
        val r = width / 2f - 4f; val cx = width / 2f; val cy = height / 2f; val inner = r * 0.36f
        c.drawCircle(cx, cy, r, ring)
        val box = RectF(cx - r, cy - r, cx + r, cy + r)
        when (pressed) {
            "UP" -> c.drawArc(box, 225f, 90f, true, press)
            "RIGHT" -> c.drawArc(box, 315f, 90f, true, press)
            "DOWN" -> c.drawArc(box, 45f, 90f, true, press)
            "LEFT" -> c.drawArc(box, 135f, 90f, true, press)
        }
        c.drawCircle(cx, cy, inner, okFill)
        if (pressed == "OK") c.drawCircle(cx, cy, inner, press)
        txt.textSize = inner * 0.7f; c.drawText("OK", cx, cy + inner * 0.25f, txt)
        val d = r * 0.68f; val s = r * 0.11f
        for ((angle, _) in listOf(0f to "UP", 90f to "RIGHT", 180f to "DOWN", 270f to "LEFT")) {
            c.save(); c.translate(cx, cy); c.rotate(angle)
            val p = Path().apply { moveTo(0f, -d - s); lineTo(-s * 1.3f, -d + s * 0.7f); lineTo(s * 1.3f, -d + s * 0.7f); close() }
            c.drawPath(p, arrow); c.restore()
        }
    }

    private fun zone(x: Float, y: Float): String? {
        val dx = x - width / 2f; val dy = y - height / 2f; val r = width / 2f
        val d = hypot(dx, dy)
        if (d > r) return null
        if (d < r * 0.36f) return "OK"
        val a = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble()))
        return when { a >= -135 && a < -45 -> "UP"; a >= -45 && a < 45 -> "RIGHT"; a >= 45 && a < 135 -> "DOWN"; else -> "LEFT" }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val z = zone(e.x, e.y)
                if (z != pressed) {
                    ui.removeCallbacks(rep); pressed = z; invalidate()
                    if (z != null) { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); onKey(z); if (z != "OK") ui.postDelayed(rep, 380) }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { ui.removeCallbacks(rep); pressed = null; invalidate() }
        }
        return true
    }
}
