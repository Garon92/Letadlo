package app.letadlo.game

import kotlin.math.abs
import kotlin.math.floor

/** Deterministický 2D gradientní šum – stejné vstupy dají vždy stejný terén. */
object Noise {

    fun hash(x: Int, y: Int, seed: Int): Int {
        var h = (x * 0x27d4eb2d) xor (y * 0x165667b1) xor (seed * 0x5bd1e995)
        h = (h xor (h ushr 15)) * 0x2c1b3c6d
        h = (h xor (h ushr 12)) * 0x297a2d39
        h = h xor (h ushr 15)
        return h
    }

    /** Náhodné číslo 0..1 pro celočíselné souřadnice. */
    fun hash01(x: Int, y: Int, seed: Int): Float = (hash(x, y, seed) ushr 8) / 16777216f

    private fun grad(h: Int, x: Float, y: Float): Float = when (h and 7) {
        0 -> x + y
        1 -> -x + y
        2 -> x - y
        3 -> -x - y
        4 -> x * 1.4f
        5 -> -x * 1.4f
        6 -> y * 1.4f
        else -> -y * 1.4f
    }

    /** Gradientní šum, výsledek zhruba v rozsahu -1..1. */
    fun noise(x: Float, y: Float, seed: Int): Float {
        val fx0 = floor(x)
        val fy0 = floor(y)
        val x0 = fx0.toInt()
        val y0 = fy0.toInt()
        val fx = x - fx0
        val fy = y - fy0
        val u = fx * fx * fx * (fx * (fx * 6f - 15f) + 10f)
        val v = fy * fy * fy * (fy * (fy * 6f - 15f) + 10f)
        val n00 = grad(hash(x0, y0, seed), fx, fy)
        val n10 = grad(hash(x0 + 1, y0, seed), fx - 1f, fy)
        val n01 = grad(hash(x0, y0 + 1, seed), fx, fy - 1f)
        val n11 = grad(hash(x0 + 1, y0 + 1, seed), fx - 1f, fy - 1f)
        val a = n00 + (n10 - n00) * u
        val b = n01 + (n11 - n01) * u
        return (a + (b - a) * v) * 0.75f
    }

    /** Fraktální šum (součet oktáv), zhruba -1..1. */
    fun fbm(x: Float, y: Float, octaves: Int, seed: Int): Float {
        var sum = 0f
        var amp = 1f
        var freq = 1f
        var norm = 0f
        for (i in 0 until octaves) {
            sum += noise(x * freq + i * 17.13f, y * freq - i * 9.71f, seed + i * 101) * amp
            norm += amp
            amp *= 0.5f
            freq *= 2.03f
        }
        return sum / norm
    }

    /** Hřebenový šum pro horské štíty, rozsah 0..1. */
    fun ridged(x: Float, y: Float, octaves: Int, seed: Int): Float {
        var sum = 0f
        var amp = 1f
        var freq = 1f
        var norm = 0f
        var weight = 1f
        for (i in 0 until octaves) {
            var n = 1f - abs(noise(x * freq + i * 31.7f, y * freq + i * 13.3f, seed + i * 131))
            n = (n * n).coerceIn(0f, 1f)
            n *= weight
            weight = (n * 1.6f).coerceIn(0f, 1f)
            sum += n * amp
            norm += amp
            amp *= 0.5f
            freq *= 2.1f
        }
        return (sum / norm).coerceIn(0f, 1f)
    }
}
