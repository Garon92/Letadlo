package app.letadlo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import app.letadlo.game.World
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Průhledná kruhová mapa okolí natočená ve směru letu (nahoře je vždy
 * směr, kam letadlo letí). Obraz terénu dodává renderer, pozici HUD.
 */
class MiniMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var bitmap: Bitmap? = null
    private var capX = 0f
    private var capZ = 0f
    private var metersPerPixel = 1f
    private var mapPixels = 1
    private var planeX = 0f
    private var planeZ = 0f
    private var headingDeg = 0f

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 0, 0, 0) }
    private val img = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 175 }
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val runway = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 240, 240, 240)
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
    }
    private val plane = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 210, 60) }
    private val planeEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 0, 0, 0)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val north = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val clip = Path()
    private val arrow = Path()
    private val dst = RectF()

    fun setImage(pixels: IntArray, size: Int, centerX: Float, centerZ: Float, mPerPx: Float) {
        val b = bitmap?.takeIf { it.width == size } ?: Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        b.setPixels(pixels, 0, size, 0, 0, size, size)
        bitmap = b
        mapPixels = size
        capX = centerX
        capZ = centerZ
        metersPerPixel = mPerPx
        invalidate()
    }

    fun setPose(x: Float, z: Float, heading: Float) {
        planeX = x
        planeZ = z
        headingDeg = heading
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(cx, cy) - 2f
        clip.reset()
        clip.addCircle(cx, cy, r, Path.Direction.CW)
        canvas.drawCircle(cx, cy, r, bg)
        val b = bitmap
        if (b != null) {
            canvas.save()
            canvas.clipPath(clip)
            canvas.rotate(-headingDeg, cx, cy)
            // obraz pokrývá průměr mapy; posun o kus, který letadlo uletělo od pořízení
            val pxPerM = (2f * r) / (mapPixels * metersPerPixel)
            val dx = (planeX - capX) * pxPerM
            val dz = (planeZ - capZ) * pxPerM
            dst.set(cx - r - dx, cy - r - dz, cx + r - dx, cy + r - dz)
            canvas.drawBitmap(b, null, dst, img)
            // dráha
            val x0 = cx + (0f - planeX) * pxPerM
            canvas.drawLine(
                x0, cy + (-World.RUNWAY_HALF_LENGTH - planeZ) * pxPerM,
                x0, cy + (World.RUNWAY_HALF_LENGTH - planeZ) * pxPerM, runway,
            )
            canvas.restore()
        }
        // letadlo uprostřed míří vždy nahoru
        val s = r * 0.12f
        arrow.reset()
        arrow.moveTo(cx, cy - s * 1.4f)
        arrow.lineTo(cx + s, cy + s)
        arrow.lineTo(cx, cy + s * 0.45f)
        arrow.lineTo(cx - s, cy + s)
        arrow.close()
        canvas.drawPath(arrow, plane)
        canvas.drawPath(arrow, planeEdge)
        canvas.drawCircle(cx, cy, r, rim)
        // sever
        val h = Math.toRadians(headingDeg.toDouble())
        val nr = r - r * 0.16f
        north.textSize = r * 0.2f
        canvas.drawText("S", cx - sin(h).toFloat() * nr, cy - cos(h).toFloat() * nr + north.textSize * 0.35f, north)
    }
}
