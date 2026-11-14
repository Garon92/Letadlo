package app.letadlo

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.Random
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Procedurální zvuk pístového motoru a větru. Žádné zvukové soubory –
 * vše se syntetizuje v reálném čase na vlastním vlákně.
 */
class EngineSound {

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
        try {
            while (alive) {
                if (boomRequested) {
                    boomRequested = false
                    boom = 1.0
                }
                val v = speed.toDouble()
                val on = running
                val targetF = if (on) 34.0 + v * 0.8 else 8.0
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
                    s *= 0.72 + 0.28 * sin(twoPi * chug)
                    wind = wind * 0.985 + (rnd.nextDouble() - 0.5) * 0.15
                    var out = s * vol + wind * windVol * 3.0
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
