package app.letadlo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** Vzhled FPV brýlí: ztmavené rohy, tenký záměrný kříž a blikající ● REC. */
class FpvOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val vignette = Paint()
    private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(170, 255, 255, 255)
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
    }
    private val rec = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        isFakeBoldText = true
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 45, 45) }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val r = max(w, h) * 0.62f
        vignette.shader = RadialGradient(
            w / 2f, h / 2f, r,
            intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(150, 0, 0, 0)),
            floatArrayOf(0f, 0.62f, 1f), Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, vignette)
        val cx = w / 2f
        val cy = h / 2f
        val s = min(w, h) * 0.035f
        canvas.drawLine(cx - s * 2f, cy, cx - s * 0.7f, cy, cross)
        canvas.drawLine(cx + s * 0.7f, cy, cx + s * 2f, cy, cross)
        canvas.drawLine(cx, cy - s * 1.4f, cx, cy - s * 0.7f, cross)
        // ● REC bliká jednou za sekundu
        val blink = (System.currentTimeMillis() / 600) % 2 == 0L
        rec.textSize = min(w, h) * 0.045f
        val ry = h - rec.textSize * 1.4f
        val rx = cx - rec.textSize * 1.6f
        if (blink) canvas.drawCircle(rx, ry - rec.textSize * 0.35f, rec.textSize * 0.32f, dot)
        canvas.drawText("REC", rx + rec.textSize * 0.6f, ry, rec)
        postInvalidateDelayed(300)
    }
}
