package com.example.fireairmouse

import android.content.Context
import android.graphics.*
import android.view.View

/** Arrow pointer; tip = top-left corner. Turns amber while the pointer is locked. */
class CursorView(ctx: Context) : View(ctx) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 3f; strokeJoin = Paint.Join.ROUND }
    var locked = false
        set(v) { field = v; fill.color = if (v) Color.parseColor("#FFC107") else Color.WHITE; invalidate() }
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val p = Path().apply {
            moveTo(2f, 2f); lineTo(2f, h * 0.80f); lineTo(w * 0.22f, h * 0.62f); lineTo(w * 0.40f, h * 0.97f)
            lineTo(w * 0.54f, h * 0.90f); lineTo(w * 0.36f, h * 0.56f); lineTo(w * 0.62f, h * 0.56f); close()
        }
        c.drawPath(p, fill); c.drawPath(p, line)
    }
}
