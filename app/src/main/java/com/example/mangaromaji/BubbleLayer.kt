package com.example.mangaromaji

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View

/** Full-screen, touch-through layer that paints romaji over the original bubbles. */
class BubbleLayer(ctx: Context) : View(ctx) {

    class Bubble(val rect: RectF, val text: String)
    private class Drawn(val rect: RectF, val layout: StaticLayout)

    private var drawn: List<Drawn> = emptyList()
    private val pad = 8f
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(242, 255, 255, 255) }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.argb(70, 0, 0, 0); strokeWidth = 2f
    }

    fun setBubbles(list: List<Bubble>) { drawn = list.map { fit(it) }; invalidate() }
    fun clear() { drawn = emptyList(); invalidate() }

    private fun fit(b: Bubble): Drawn {
        val w = (b.rect.width() - 2 * pad).toInt().coerceAtLeast(24)
        val maxH = b.rect.height() - 2 * pad
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; typeface = Typeface.DEFAULT_BOLD }
        var size = minOf(b.rect.height() * 0.5f, 64f).coerceAtLeast(14f)
        var sl: StaticLayout
        while (true) {
            tp.textSize = size
            sl = StaticLayout.Builder.obtain(b.text, 0, b.text.length, tp, w)
                .setAlignment(Layout.Alignment.ALIGN_CENTER).build()
            if (sl.height <= maxH || size <= 14f) break
            size -= 2f
        }
        return Drawn(b.rect, sl)
    }

    override fun onDraw(c: Canvas) {
        for (d in drawn) {
            c.drawRoundRect(d.rect, 16f, 16f, bg)
            c.drawRoundRect(d.rect, 16f, 16f, edge)
            c.save()
            val ty = maxOf(d.rect.top, d.rect.top + (d.rect.height() - d.layout.height) / 2f)
            c.translate(d.rect.left + pad, ty)
            c.clipRect(0f, 0f, d.rect.width() - 2 * pad, d.rect.bottom - ty)
            d.layout.draw(c)
            c.restore()
        }
    }
}
