package app.letadlo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/** Malý umělý horizont – ukazuje náklon křídel a sklon nosu. */
class AttitudeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var bankDeg = 0f
    private var pitchDeg = 0f

    private val sky = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(70, 140, 220) }
    private val ground = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(150, 105, 60) }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }
    private val plane = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 210, 60)
        strokeWidth = 6f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 20, 25, 35)
        strokeWidth = 6f
        style = Paint.Style.STROKE
    }
    private val clip = Path()

    fun set(bank: Float, pitch: Float) {
        if (kotlin.math.abs(bank - bankDeg) < 0.2f && kotlin.math.abs(pitch - pitchDeg) < 0.2f) return
        bankDeg = bank
        pitchDeg = pitch
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(cx, cy) - 4f
        clip.reset()
        clip.addCircle(cx, cy, r, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        canvas.rotate(-bankDeg, cx, cy)
        val offset = pitchDeg / 40f * r
        canvas.drawRect(cx - r * 2, cy - r * 3 + offset, cx + r * 2, cy + offset, sky)
        canvas.drawRect(cx - r * 2, cy + offset, cx + r * 2, cy + r * 3 + offset, ground)
        canvas.drawLine(cx - r * 2, cy + offset, cx + r * 2, cy + offset, line)
        for (i in listOf(-20, -10, 10, 20)) {
            val y = cy + offset - i / 40f * r
            val w = if (kotlin.math.abs(i) == 10) r * 0.25f else r * 0.4f
            canvas.drawLine(cx - w, y, cx + w, y, line)
        }
        canvas.restore()
        canvas.drawLine(cx - r * 0.6f, cy, cx - r * 0.18f, cy, plane)
        canvas.drawLine(cx + r * 0.18f, cy, cx + r * 0.6f, cy, plane)
        canvas.drawCircle(cx, cy, 4f, plane)
        canvas.drawCircle(cx, cy, r, rim)
    }
}
