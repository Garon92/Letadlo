package app.letadlo.game

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.tan

/**
 * Promítne vlastní letadlo a palubní desku z místa očí pilota (stejně jako
 * renderer v pohledu z kabiny: trup se zadními stěnami ořezanými, deska bez ořezu)
 * a změří, jak velká část výhledu dopředu je zakrytá.
 */
class CockpitViewTest {

    private val tanV = tan(Math.toRadians(31.0)).toFloat()   // svislé FOV 62°
    private val tanH = tanV * 2f                                // obrazovka na šířku ~2:1

    /** Trojúhelníky v souřadnicích obrazovky (u, v) po ořezu blízkou rovinou. */
    private fun projected(type: AircraftType): List<FloatArray> {
        val eye = AircraftMeshes.eye(type)
        val out = ArrayList<FloatArray>()
        fun add(m: MeshData, cull: Boolean) {
            for (t in 0 until m.indexCount / 3) {
                val p = Array(3) { k ->
                    val i = m.indices[t * 3 + k] * MeshBuilder.STRIDE
                    floatArrayOf(m.vertices[i] - eye[0], m.vertices[i + 1] - eye[1], m.vertices[i + 2] - eye[2])
                }
                if (cull) {
                    val ux = p[1][0] - p[0][0]; val uy = p[1][1] - p[0][1]; val uz = p[1][2] - p[0][2]
                    val wx = p[2][0] - p[0][0]; val wy = p[2][1] - p[0][1]; val wz = p[2][2] - p[0][2]
                    val nx = uy * wz - uz * wy
                    val ny = uz * wx - ux * wz
                    val nz = ux * wy - uy * wx
                    // zadní stěna (normála od oka) se nekreslí
                    if (nx * p[0][0] + ny * p[0][1] + nz * p[0][2] >= 0f) continue
                }
                val poly = clipNear(p.toList())
                if (poly.size < 3) continue
                val uv = poly.map { floatArrayOf(it[0] / -it[2] / tanH, it[1] / -it[2] / tanV) }
                for (k in 1 until uv.size - 1) {
                    out.add(floatArrayOf(uv[0][0], uv[0][1], uv[k][0], uv[k][1], uv[k + 1][0], uv[k + 1][1]))
                }
            }
        }
        add(AircraftMeshes.body(type), true)
        add(AircraftMeshes.cockpit(type), false)
        return out
    }

    private fun clipNear(poly: List<FloatArray>): List<FloatArray> {
        val near = -0.05f
        val res = ArrayList<FloatArray>()
        for (i in poly.indices) {
            val a = poly[i]
            val b = poly[(i + 1) % poly.size]
            val aIn = a[2] <= near
            val bIn = b[2] <= near
            if (aIn) res.add(a)
            if (aIn != bIn) {
                val t = (near - a[2]) / (b[2] - a[2])
                res.add(floatArrayOf(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, near))
            }
        }
        return res
    }

    private fun inside(t: FloatArray, u: Float, v: Float): Boolean {
        fun s(ax: Float, ay: Float, bx: Float, by: Float) = (bx - ax) * (v - ay) - (by - ay) * (u - ax)
        val d1 = s(t[0], t[1], t[2], t[3])
        val d2 = s(t[2], t[3], t[4], t[5])
        val d3 = s(t[4], t[5], t[0], t[1])
        val neg = d1 < 0 || d2 < 0 || d3 < 0
        val pos = d1 > 0 || d2 > 0 || d3 > 0
        return !(neg && pos)
    }

    /** Podíl zakrytých vzorků v oblasti obrazovky u∈[u0,u1], v∈[v0,v1]. */
    private fun covered(tris: List<FloatArray>, u0: Float, u1: Float, v0: Float, v1: Float): Float {
        var hit = 0
        var all = 0
        val n = 40
        for (j in 0..n) for (i in 0..n) {
            val u = u0 + (u1 - u0) * i / n
            val v = v0 + (v1 - v0) * j / n
            all++
            if (tris.any { inside(it, u, v) }) hit++
        }
        return hit.toFloat() / all
    }

    @Test
    fun forwardViewFromEveryCockpitIsClear() {
        val problems = ArrayList<String>()
        for (type in AircraftType.entries) {
            val tris = projected(type)
            // pruh kolem horizontu: ±22° do stran, od ~8° pod do ~12° nad osu pohledu
            val horizon = covered(tris, -0.35f, 0.35f, -0.24f, 0.35f)
            // celá horní polovina obrazovky
            val upper = covered(tris, -1f, 1f, 0f, 1f)
            val whole = covered(tris, -1f, 1f, -1f, 1f)
            println("$type: horizont ${(horizon * 100).toInt()} %, horní půlka ${(upper * 100).toInt()} %, celkem ${(whole * 100).toInt()} %")
            if (horizon >= 0.1f) problems += "$type: výhled dopředu zakrytý z ${(horizon * 100).toInt()} %"
            if (upper >= 0.45f) problems += "$type: horní polovina zakrytá z ${(upper * 100).toInt()} %"
            if (whole >= 0.6f) problems += "$type: obrazovka zakrytá z ${(whole * 100).toInt()} %"
        }
        assertTrue(problems.joinToString("; "), problems.isEmpty())
    }
}
