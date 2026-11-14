package app.letadlo

import android.app.Activity
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.view.Surface
import app.letadlo.game.Controls
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * Čte gravitaci z telefonu držného na šířku.
 *  - natočení jako volantem (nebo náklon do strany) = zatáčení
 *  - horní hrana k sobě = stoupání, od sebe = klesání (vůči kalibrované poloze)
 */
class TiltInput(private val activity: Activity) : SensorEventListener, Controls {

    private val sensorManager = activity.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val rawAccelerometer = sensor?.type == Sensor.TYPE_ACCELEROMETER

    val available: Boolean get() = sensor != null

    @Volatile override var roll = 0f
        private set

    @Volatile override var pitch = 0f
        private set

    @Volatile override var calibrating = true
        private set

    @Volatile var invertPitch = false

    /** Citlivost 0..10 – kolik stupňů náklonu znamená plnou výchylku. */
    @Volatile var sensitivity = DEFAULT_SENSITIVITY

    companion object {
        const val DEFAULT_SENSITIVITY = 7

        /** Náklon telefonu (°) pro plnou zatáčku při dané citlivosti. */
        fun fullRollDeg(sens: Int): Float = 30f - sens.coerceIn(0, 10) * 2.2f

        fun fullPitchDeg(sens: Int): Float = fullRollDeg(sens) * 0.85f
    }

    private var gx = 0f
    private var gy = 9.81f
    private var gz = 0f
    private var hasSample = false
    private var neutralPitch = 0f
    private var calibSum = 0f
    private var calibCount = 0
    private var calibStart = 0L
    private var rotation = Surface.ROTATION_90
    private var events = 0

    fun start() {
        sensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        calibrate()
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    /** Aktuální poloha telefonu se bere jako „rovný let". */
    fun calibrate() {
        calibrating = true
        calibSum = 0f
        calibCount = 0
        calibStart = 0L
    }

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) activity.display?.rotation ?: Surface.ROTATION_90
        else activity.windowManager.defaultDisplay.rotation

    override fun onSensorChanged(event: SensorEvent) {
        if (events++ % 30 == 0) rotation = displayRotation()
        val vx = event.values[0]
        val vy = event.values[1]
        val vz = event.values[2]
        // přepočet do souřadnic obrazovky (x doprava, y nahoru, z ven z displeje)
        val sx: Float
        val sy: Float
        when (rotation) {
            Surface.ROTATION_90 -> { sx = -vy; sy = vx }
            Surface.ROTATION_180 -> { sx = -vx; sy = -vy }
            Surface.ROTATION_270 -> { sx = vy; sy = -vx }
            else -> { sx = vx; sy = vy }
        }
        val sz = vz
        if (!hasSample) {
            gx = sx; gy = sy; gz = sz
            hasSample = true
        } else {
            val a = if (rawAccelerometer) 0.12f else 0.4f
            gx += (sx - gx) * a
            gy += (sy - gy) * a
            gz += (sz - gz) * a
        }
        val g = sqrt(gx * gx + gy * gy + gz * gz).coerceAtLeast(0.1f)
        val rollDeg = Math.toDegrees(asin((-gx / g).coerceIn(-1f, 1f)).toDouble()).toFloat()
        val pitchDeg = Math.toDegrees(atan2(gz, sqrt(gx * gx + gy * gy)).toDouble()).toFloat()

        val s = sensitivity
        roll = shape(rollDeg, fullRollDeg(s))
        if (calibrating) {
            if (calibStart == 0L) calibStart = event.timestamp
            calibSum += pitchDeg
            calibCount++
            pitch = 0f
            if (event.timestamp - calibStart > 600_000_000L && calibCount >= 5) {
                neutralPitch = calibSum / calibCount
                calibrating = false
            }
            return
        }
        // telefon víc „naležato" než při kalibraci = nos dolů
        val p = -shape(pitchDeg - neutralPitch, fullPitchDeg(s))
        pitch = if (invertPitch) -p else p
    }

    /** Malá mrtvá zóna a téměř lineární odezva – už malý náklon něco udělá. */
    private fun shape(deg: Float, full: Float): Float {
        val dead = 1.2f
        val a = abs(deg)
        if (a < dead) return 0f
        val v = ((a - dead) / (full - dead)).coerceIn(0f, 1f)
        return sign(deg) * v * (0.8f + 0.2f * v)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
