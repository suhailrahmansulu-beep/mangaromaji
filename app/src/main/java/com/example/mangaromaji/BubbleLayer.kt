package com.example.mangaromaji

import android.content.Context
import android.graphics.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.View

/** Touch-through captions with a compositor marker in the same surface buffer. */
class BubbleLayer(ctx: Context) : View(ctx) {
    data class Bubble(val rect: RectF, val text: String)
    private data class Drawn(val rect: RectF, val layout: StaticLayout)
    var revision = 1; private set
    val markerX get() = (8*resources.displayMetrics.density).toInt()
    val markerY get() = (72*resources.displayMetrics.density).toInt()
    var bubbles: List<Bubble> = emptyList(); private set
    private var drawn: List<Drawn> = emptyList()
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.WHITE }
    private val marker = Paint()
    private val pad get() = 3*resources.displayMetrics.density
    fun present(value: List<Bubble>) { bubbles=value; revision=(revision+1) and 0xffff; prepare(); invalidate() }
    fun clear() = present(emptyList())
    override fun onSizeChanged(w: Int,h: Int,oldw: Int,oldh: Int) { prepare() }
    private fun prepare() {
        if(width<=0 || height<=0) return
        val sp=resources.displayMetrics.scaledDensity
        val preferred=context.getSharedPreferences("ui",Context.MODE_PRIVATE).getInt("captionSp",12).coerceIn(10,18)*sp
        val minimum=maxOf(10*sp,preferred-2*sp)
        drawn=bubbles.map { bubble ->
            val r=RectF(bubble.rect)
            r.left=r.left.coerceIn(0f,(width-1).toFloat());r.right=r.right.coerceIn(r.left+1,width.toFloat())
            r.top=r.top.coerceIn(0f,(height-1).toFloat());r.bottom=r.bottom.coerceIn(r.top+1,height.toFloat())
            val text=TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLACK;typeface=Typeface.DEFAULT }
            val w=(r.width()-2*pad).toInt().coerceAtLeast(1)
            val h=(r.height()-2*pad).coerceAtLeast(1f)
            var size=preferred
            fun layout(limit: Int = Int.MAX_VALUE): StaticLayout {
                text.textSize=size
                return StaticLayout.Builder.obtain(bubble.text,0,bubble.text.length,text,w)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false)
                    .setMaxLines(limit).setEllipsize(TextUtils.TruncateAt.END).build()
            }
            var fitted=layout()
            while(fitted.height>h && size>minimum) { size=maxOf(minimum,size-sp);fitted=layout() }
            if(fitted.height>h) fitted=layout((h/(text.fontMetrics.descent-text.fontMetrics.ascent)).toInt().coerceAtLeast(1))
            Drawn(r,fitted)
        }
    }
    override fun onDraw(canvas: Canvas) {
        for(d in drawn) {
            canvas.drawRoundRect(d.rect,5f,5f,bg)
            canvas.save();canvas.clipRect(d.rect)
            canvas.translate(d.rect.left+pad,d.rect.top+maxOf(pad,(d.rect.height()-d.layout.height)/2))
            d.layout.draw(canvas);canvas.restore()
        }
        for(bit in 0 until 16) for(second in 0..1) {
            marker.color=CaptureMarker.color(revision,bit,second==1)
            val x=markerX+(bit%8*2+second)*CaptureMarker.CELL
            val y=markerY+bit/8*CaptureMarker.CELL
            canvas.drawRect(x.toFloat(),y.toFloat(),(x+CaptureMarker.CELL).toFloat(),(y+CaptureMarker.CELL).toFloat(),marker)
        }
    }
}
