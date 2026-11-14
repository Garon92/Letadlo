package app.letadlo

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import app.letadlo.game.AircraftType
import java.util.Random
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Procedurální zvuk pístového motoru a větru. Žádné zvukové soubory –
 * vše se syntetizuje v reálném čase na vlastním vlákně.
 */
class EngineSound(private val type: AircraftType = AircraftType.SPORT) {

    private val jet = type == AircraftType.FIGHTER || type == AircraftType.AIRLINER
    private val heli = type == AircraftType.HELICOPTER

    /** Rychlost letadla v m/s – určuje otáčky a sílu větru. */
    @Volatile var speed = 0f

    /** Motor běží (po havárii zhasne). */
    @Volatile var running = true

    @Volatile private var boomRequested = false
    @Volatile private var alive = false
    private var thread: Thread? = null

    fun start() {
        if (alive) return
        alive = true
        thread = Thread({ loop() }, "letadlo-zvuk").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        alive = false
        thread?.join(400)
        thread = null
    }

    fun explode() {
        boomRequested = true
        running = false
    }

    @Volatile private var launchRequested = false
    @Volatile private var smallBoomRequested = false

    /** Syknutí odpálené rakety. */
    fun launch() {
        launchRequested = true
    }

    @Volatile private var whistleRequested = false

    /** Hvízdání padající bomby (klesající tón). */
    fun whistle() {
        whistleRequested = true
    }

    /** Výbuch rakety (motor běží dál). */
    fun boom() {
        smallBoomRequested = true
    }

    private fun loop() {
        val rate = 22050
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(min, 4096))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            return
        }
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            return
        }
        track.play()

        val buf = ShortArray(512)
        val rnd = Random()
        val twoPi = 2.0 * PI
        var phase = 0.0
        var chug = 0.0
        var freq = 30.0
        var vol = 0.0
        var wind = 0.0
        var windVol = 0.0
        var boom = 0.0
        var boomLp = 0.0
        var roar = 0.0
        var hiss = 0.0
        var whistle = 0.0
        var whistlePhase = 0.0
        try {
            while (alive) {
                if (boomRequested) {
                    boomRequested = false
                    boom = 1.0
                }
                if (smallBoomRequested) {
                    smallBoomRequested = false
                    boom = maxOf(boom, 0.55)
                }
                if (launchRequested) {
                    launchRequested = false
                    hiss = 1.0
                }
                if (whistleRequested) {
                    whistleRequested = false
                    whistle = 1.0
                }
                val v = speed.toDouble()
                val on = running
                val tone = type.engineTone.toDouble()
                val targetF = if (!on) 8.0 else when {
                    jet -> 70.0 * tone + v * 0.45
                    heli -> 28.0
                    else -> (34.0 + v * 0.8) * tone
                }
                val targetV = if (on) 0.20 + min(v, 80.0) / 80.0 * 0.16 else 0.0
                val targetWind = min(v, 110.0) / 110.0 * 0.22
                for (i in buf.indices) {
                    freq += (targetF - freq) * 0.0003
                    vol += (targetV - vol) * 0.0005
                    windVol += (targetWind - windVol) * 0.0004
                    phase += freq / rate
                    if (phase >= 1.0) phase -= 1.0
                    chug += freq * 0.5 / rate
                    if (chug >= 1.0) chug -= 1.0
                    val w = twoPi * phase
                    var s = 0.55 * sin(w) + 0.30 * sin(2 * w + 0.4) + 0.18 * sin(3 * w + 1.1) + 0.10 * sin(5 * w + 0.2)
                    wind = wind * 0.985 + (rnd.nextDouble() - 0.5) * 0.15
                    var out: Double
                    if (jet) {
                        // hvízdání turbíny + hukot výtoku
                        roar = roar * 0.9 + (rnd.nextDouble() - 0.5) * 0.25
                        s = 0.25 * sin(w * 9.0) + 0.15 * sin(w * 13.7) + 0.4 * sin(w)
                        out = s * vol * 0.5 + roar * vol * 2.6 + wind * windVol * 3.0
                    } else if (heli) {
                        // údery listů rotoru
                        roar = roar * 0.8 + (rnd.nextDouble() - 0.5) * 0.4
                        val slap = 0.5 + 0.5 * sin(twoPi * chug * 0.9)
                        val beat = slap * slap * slap * slap
                        out = (roar * 2.2 + 0.5 * sin(w)) * vol * (0.25 + 1.4 * beat) + wind * windVol * 2.0
                    } else {
                        s *= 0.72 + 0.28 * sin(twoPi * chug)
                        out = s * vol + wind * windVol * 3.0
                    }
                    if (whistle > 0.002) {
                        whistlePhase += (450.0 + 1000.0 * whistle) / rate
                        if (whistlePhase >= 1.0) whistlePhase -= 1.0
                        out += sin(twoPi * whistlePhase) * 0.14 * minOf(1.0, whistle * 4.0)
                        whistle *= 0.99994
                    }
                    if (hiss > 0.001) {
                        out += (rnd.nextDouble() * 2.0 - 1.0) * hiss * 0.45
                        hiss *= 0.99965
                    }
                    if (boom > 0.0003) {
                        boomLp = boomLp * 0.93 + (rnd.nextDouble() * 2.0 - 1.0) * 0.07
                        out += boomLp * boom * 4.0 + (rnd.nextDouble() * 2.0 - 1.0) * boom * boom * 0.35
                        boom *= 0.99988
                    }
                    if (out > 1.0) out = 1.0
                    if (out < -1.0) out = -1.0
                    buf[i] = (out * 30000.0).toInt().toShort()
                }
                track.write(buf, 0, buf.size)
            }
        } finally {
            try {
                track.pause()
                track.flush()
            } catch (e: Exception) {
            }
            track.release()
        }
    }
}
