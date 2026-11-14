package app.letadlo.game

import kotlin.math.cos
import kotlin.math.sin

/**
 * Nízkopolygonové sportovní letadlo. Nos míří do -Z, Y nahoru, X doprava.
 * Délka ~8 m, rozpětí ~10,5 m.
 */
object PlaneMesh {

    private val WHITE = floatArrayOf(0.94f, 0.94f, 0.96f)
    private val RED = floatArrayOf(0.86f, 0.17f, 0.14f)
    private val GLASS = floatArrayOf(0.16f, 0.25f, 0.36f)
    private val DARK = floatArrayOf(0.10f, 0.10f, 0.11f)
    private val GREY = floatArrayOf(0.55f, 0.56f, 0.58f)

    fun body(): MeshData {
        val mb = MeshBuilder(1024)
        fuselage(mb)

        // kabina
        hexa(
            mb,
            -0.50f, 0.62f, -1.6f, 0.50f, 0.62f, 0.6f,
            -0.36f, 1.02f, -0.85f, 0.36f, 1.02f, 0.25f,
            GLASS,
        )
        // křídla (dolnoplošník s mírným vzepětím)
        wing(mb, 1f)
        wing(mb, -1f)
        // vodorovná ocasní plocha
        hexa(mb, -1.95f, 0.26f, 3.15f, 1.95f, 0.26f, 3.95f, -1.95f, 0.34f, 3.15f, 1.95f, 0.34f, 3.95f, WHITE, frontInsetTop = 0f)
        // směrovka
        mb.quadFin()
        // podvozek
        gear(mb, 1.35f, -0.65f)
        gear(mb, -1.35f, -0.65f)
        gear(mb, 0f, -2.55f)
        return mb.build()
    }

    fun propeller(): MeshData {
        val mb = MeshBuilder(64)
        mb.box(-1.05f, -0.09f, -0.03f, 1.05f, 0.09f, 0.03f, DARK[0], DARK[1], DARK[2])
        mb.box(-1.05f, -0.09f, -0.031f, -0.85f, 0.09f, 0.031f, 0.95f, 0.8f, 0.1f)
        mb.box(0.85f, -0.09f, -0.031f, 1.05f, 0.09f, 0.031f, 0.95f, 0.8f, 0.1f)
        return mb.build()
    }

    /** Plochý obrys letadla pro stín (y = 0). */
    fun shadow(): MeshData {
        val mb = MeshBuilder(32)
        mb.quad(-5.2f, 0f, 0.2f, 5.2f, 0f, 0.2f, 5.2f, 0f, -0.9f, -5.2f, 0f, -0.9f, 0f, 0f, 0f)
        mb.quad(-0.6f, 0f, 3.9f, 0.6f, 0f, 3.9f, 0.6f, 0f, -3.7f, -0.6f, 0f, -3.7f, 0f, 0f, 0f)
        mb.quad(-1.9f, 0f, 3.9f, 1.9f, 0f, 3.9f, 1.9f, 0f, 3.2f, -1.9f, 0f, 3.2f, 0f, 0f, 0f)
        return mb.build()
    }

    /** Hrubá koule pro výbuch a kouř. */
    fun ball(): MeshData {
        val mb = MeshBuilder(256)
        val rings = 6
        val sides = 10
        for (rI in 0 until rings) {
            val t0 = (Math.PI * rI / rings).toFloat()
            val t1 = (Math.PI * (rI + 1) / rings).toFloat()
            for (s in 0 until sides) {
                val p0 = (2 * Math.PI * s / sides).toFloat()
                val p1 = (2 * Math.PI * (s + 1) / sides).toFloat()
                mb.quad(
                    sin(t0) * cos(p0), cos(t0), sin(t0) * sin(p0),
                    sin(t0) * cos(p1), cos(t0), sin(t0) * sin(p1),
                    sin(t1) * cos(p1), cos(t1), sin(t1) * sin(p1),
                    sin(t1) * cos(p0), cos(t1), sin(t1) * sin(p0),
                    1f, 1f, 1f,
                )
            }
        }
        return mb.build()
    }

    private fun fuselage(mb: MeshBuilder) {
        // z, poloviční šířka, poloviční výška, střed y
        val st = arrayOf(
            floatArrayOf(-3.62f, 0.30f, 0.30f, 0.05f),
            floatArrayOf(-3.25f, 0.58f, 0.58f, 0.06f),
            floatArrayOf(-2.20f, 0.62f, 0.68f, 0.08f),
            floatArrayOf(-0.50f, 0.62f, 0.72f, 0.12f),
            floatArrayOf(1.00f, 0.50f, 0.56f, 0.14f),
            floatArrayOf(2.80f, 0.25f, 0.33f, 0.24f),
            floatArrayOf(4.05f, 0.08f, 0.15f, 0.32f),
        )
        val sides = 8
        for (s in 0 until st.size - 1) {
            val a = st[s]
            val b = st[s + 1]
            val col = if (s <= 1) RED else WHITE
            for (k in 0 until sides) {
                val a0 = (2 * Math.PI * k / sides + Math.PI / sides).toFloat()
                val a1 = (2 * Math.PI * (k + 1) / sides + Math.PI / sides).toFloat()
                // proužek po boku trupu
                val stripe = s in 2..4 && (k == 0 || k == 3)
                val c = if (stripe) RED else col
                mb.quad(
                    cos(a0) * a[1], a[3] + sin(a0) * a[2], a[0],
                    cos(a1) * a[1], a[3] + sin(a1) * a[2], a[0],
                    cos(a1) * b[1], b[3] + sin(a1) * b[2], b[0],
                    cos(a0) * b[1], b[3] + sin(a0) * b[2], b[0],
                    c[0], c[1], c[2],
                )
            }
        }
        // kryt vrtule (spinner) a konec trupu
        val n = st[0]
        for (k in 0 until sides) {
            val a0 = (2 * Math.PI * k / sides + Math.PI / sides).toFloat()
            val a1 = (2 * Math.PI * (k + 1) / sides + Math.PI / sides).toFloat()
            mb.triangle(
                cos(a1) * n[1], n[3] + sin(a1) * n[2], n[0],
                cos(a0) * n[1], n[3] + sin(a0) * n[2], n[0],
                0f, n[3], n[0] - 0.45f,
                GREY[0], GREY[1], GREY[2],
            )
        }
        val t = st[st.size - 1]
        for (k in 0 until sides) {
            val a0 = (2 * Math.PI * k / sides + Math.PI / sides).toFloat()
            val a1 = (2 * Math.PI * (k + 1) / sides + Math.PI / sides).toFloat()
            mb.triangle(
                cos(a0) * t[1], t[3] + sin(a0) * t[2], t[0],
                cos(a1) * t[1], t[3] + sin(a1) * t[2], t[0],
                0f, t[3], t[0] + 0.1f,
                WHITE[0], WHITE[1], WHITE[2],
            )
        }
    }

    private fun wing(mb: MeshBuilder, side: Float) {
        val rootX = 0.55f
        val tipX = 5.25f
        val redX = 4.6f
        val y = -0.28f
        // od kořene ke konci, vzepětí 0,3 m
        val yAt = { x: Float -> y + (x - rootX) / (tipX - rootX) * 0.3f }
        val front = { x: Float -> -1.15f + (x - rootX) / (tipX - rootX) * 0.3f }
        val back = { x: Float -> 0.45f - (x - rootX) / (tipX - rootX) * 0.25f }
        val thick = { x: Float -> 0.11f - (x - rootX) / (tipX - rootX) * 0.05f }
        wingPart(mb, side, rootX, redX, yAt, front, back, thick, WHITE)
        wingPart(mb, side, redX, tipX, yAt, front, back, thick, RED)
    }

    private fun wingPart(
        mb: MeshBuilder, side: Float, x0: Float, x1: Float,
        yAt: (Float) -> Float, front: (Float) -> Float, back: (Float) -> Float, thick: (Float) -> Float,
        col: FloatArray,
    ) {
        // pro levé křídlo prohodíme strany, aby hexa měla x- < x+
        val ax = if (side > 0) x0 else -x1
        val bx = if (side > 0) x1 else -x0
        val axAbs = if (side > 0) x0 else x1
        val bxAbs = if (side > 0) x1 else x0
        hexaFull(
            mb,
            // spodní: (x-,z-), (x+,z-), (x+,z+), (x-,z+)
            ax, yAt(axAbs) - thick(axAbs), front(axAbs),
            bx, yAt(bxAbs) - thick(bxAbs), front(bxAbs),
            bx, yAt(bxAbs) - thick(bxAbs), back(bxAbs),
            ax, yAt(axAbs) - thick(axAbs), back(axAbs),
            // horní
            ax, yAt(axAbs) + thick(axAbs), front(axAbs),
            bx, yAt(bxAbs) + thick(bxAbs), front(bxAbs),
            bx, yAt(bxAbs) + thick(bxAbs), back(bxAbs),
            ax, yAt(axAbs) + thick(axAbs), back(axAbs),
            col,
        )
    }

    private fun gear(mb: MeshBuilder, x: Float, z: Float) {
        mb.box(x - 0.05f, -1.25f, z - 0.05f, x + 0.05f, -0.3f, z + 0.05f, GREY[0], GREY[1], GREY[2])
        mb.box(x - 0.11f, -1.53f, z - 0.28f, x + 0.11f, -0.97f, z + 0.28f, DARK[0], DARK[1], DARK[2])
    }

    private fun MeshBuilder.quadFin() {
        hexaFull(
            this,
            -0.06f, 0.35f, 2.85f, 0.06f, 0.35f, 2.85f, 0.06f, 0.35f, 4.05f, -0.06f, 0.35f, 4.05f,
            -0.04f, 1.75f, 3.65f, 0.04f, 1.75f, 3.65f, 0.04f, 1.75f, 4.1f, -0.04f, 1.75f, 4.1f,
            RED,
        )
    }

    /** Šestistěn s obdélníkovou spodní a horní podstavou (osově zarovnané obdélníky). */
    @Suppress("UNUSED_PARAMETER")
    private fun hexa(
        mb: MeshBuilder,
        bx0: Float, by: Float, bz0: Float, bx1: Float, by1: Float, bz1: Float,
        tx0: Float, ty: Float, tz0: Float, tx1: Float, ty1: Float, tz1: Float,
        col: FloatArray,
        frontInsetTop: Float = 0f,
    ) {
        hexaFull(
            mb,
            bx0, by, bz0, bx1, by, bz0, bx1, by, bz1, bx0, by, bz1,
            tx0, ty, tz0 + frontInsetTop, tx1, ty, tz0 + frontInsetTop, tx1, ty, tz1, tx0, ty, tz1,
            col,
        )
    }

    /**
     * Obecný šestistěn: rohy 0..3 dole a 4..7 nahoře v pořadí
     * (x-,z-), (x+,z-), (x+,z+), (x-,z+).
     */
    private fun hexaFull(
        mb: MeshBuilder,
        x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float,
        x2: Float, y2: Float, z2: Float, x3: Float, y3: Float, z3: Float,
        x4: Float, y4: Float, z4: Float, x5: Float, y5: Float, z5: Float,
        x6: Float, y6: Float, z6: Float, x7: Float, y7: Float, z7: Float,
        c: FloatArray,
    ) {
        val r = c[0]; val g = c[1]; val b = c[2]
        mb.quad(x0, y0, z0, x1, y1, z1, x2, y2, z2, x3, y3, z3, r * 0.8f, g * 0.8f, b * 0.8f) // spodek
        mb.quad(x4, y4, z4, x7, y7, z7, x6, y6, z6, x5, y5, z5, r, g, b)                      // vršek
        mb.quad(x0, y0, z0, x4, y4, z4, x5, y5, z5, x1, y1, z1, r, g, b)                      // předek
        mb.quad(x3, y3, z3, x2, y2, z2, x6, y6, z6, x7, y7, z7, r, g, b)                      // zadek
        mb.quad(x0, y0, z0, x3, y3, z3, x7, y7, z7, x4, y4, z4, r, g, b)                      // levý bok
        mb.quad(x1, y1, z1, x5, y5, z5, x6, y6, z6, x2, y2, z2, r, g, b)                      // pravý bok
    }
}
