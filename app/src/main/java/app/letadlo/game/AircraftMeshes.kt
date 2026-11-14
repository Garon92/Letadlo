package app.letadlo.game

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** Otáčející se díl (vrtule, rotor) – síť je postavená s osou otáčení Z. */
class RotorSpec(
    val mesh: MeshData,
    val x: Float, val y: Float, val z: Float,
    /** 'z' = vrtule (osa podél trupu), 'y' = hlavní rotor, 'x' = ocasní rotor. */
    val axis: Char,
    val speed: Float,
)

/**
 * Nízkopolygonové modely všech letadel. Nos míří do -Z, Y nahoru, X doprava,
 * spodek kol leží ve výšce -gearHeight. Alfa < 0,9 znamená svítící plochu.
 */
object AircraftMeshes {

    private val WHITE = floatArrayOf(0.94f, 0.94f, 0.96f)
    private val RED = floatArrayOf(0.86f, 0.17f, 0.14f)
    private val GLASS = floatArrayOf(0.16f, 0.25f, 0.36f)
    private val DARK = floatArrayOf(0.10f, 0.10f, 0.11f)
    private val GREY = floatArrayOf(0.55f, 0.56f, 0.58f)
    private val NAV_RED = floatArrayOf(1f, 0.15f, 0.1f, 0.7f)
    private val NAV_GREEN = floatArrayOf(0.15f, 1f, 0.3f, 0.7f)

    // ------------------------------------------------------------------ veřejné API

    fun body(type: AircraftType): MeshData = when (type) {
        AircraftType.SPORT -> sport()
        AircraftType.FIGHTER -> fighter()
        AircraftType.AIRLINER -> airliner()
        AircraftType.HELICOPTER -> helicopter()
        AircraftType.BOMBER -> bomber()
    }

    fun rotors(type: AircraftType): List<RotorSpec> = when (type) {
        AircraftType.SPORT -> listOf(RotorSpec(propeller(1.05f, 2, 0.18f, DARK), 0f, 0.05f, -3.72f, 'z', 1f))
        AircraftType.FIGHTER, AircraftType.AIRLINER -> emptyList()
        AircraftType.HELICOPTER -> listOf(
            RotorSpec(propeller(5.6f, 4, 0.36f, floatArrayOf(0.15f, 0.15f, 0.16f)), 0f, 2.0f, 0.1f, 'y', 1f),
            RotorSpec(propeller(0.95f, 2, 0.16f, floatArrayOf(0.15f, 0.15f, 0.16f)), 0.28f, 1.15f, 8.75f, 'x', 4.5f),
        )
        AircraftType.BOMBER -> {
            val p = propeller(1.75f, 3, 0.3f, DARK)
            listOf(
                RotorSpec(p, -9f, -0.2f, -5.45f, 'z', 0.8f),
                RotorSpec(p, -4.5f, -0.2f, -5.45f, 'z', 0.8f),
                RotorSpec(p, 4.5f, -0.2f, -5.45f, 'z', 0.8f),
                RotorSpec(p, 9f, -0.2f, -5.45f, 'z', 0.8f),
            )
        }
    }

    /** Poloha očí pilota v souřadnicích letadla. */
    fun eye(type: AircraftType): FloatArray = when (type) {
        AircraftType.SPORT -> floatArrayOf(0f, 0.92f, -0.55f)
        AircraftType.FIGHTER -> floatArrayOf(0f, 1.18f, -3.55f)
        AircraftType.AIRLINER -> floatArrayOf(-0.55f, 0.95f, -16.6f)
        AircraftType.HELICOPTER -> floatArrayOf(-0.45f, 0.8f, -1.6f)
        AircraftType.BOMBER -> floatArrayOf(-0.5f, 1.55f, -6.7f)
    }

    /** Vnitřek kabiny: palubní deska, budíky, rám okna, knipl. */
    fun cockpit(type: AircraftType): MeshData {
        val e = eye(type)
        val mb = MeshBuilder(512)
        val ex = e[0]; val ey = e[1]; val ez = e[2]
        val (w, dist, panelDrop) = when (type) {
            AircraftType.SPORT -> Triple(0.55f, 0.75f, 0.28f)
            AircraftType.FIGHTER -> Triple(0.48f, 0.72f, 0.3f)
            AircraftType.AIRLINER -> Triple(1.15f, 0.95f, 0.3f)
            AircraftType.HELICOPTER -> Triple(0.9f, 0.85f, 0.55f)
            AircraftType.BOMBER -> Triple(1.0f, 0.9f, 0.3f)
        }
        // středem palubní desky je osa letadla (u dvoumístných kabin pilot sedí vlevo)
        val pz = ez - dist
        val top = ey - panelDrop
        mb.box(-w, top - 0.5f, pz - 0.18f, w, top, pz, 0.14f, 0.14f, 0.15f)
        mb.box(-w - 0.02f, top, pz - 0.28f, w + 0.02f, top + 0.06f, pz + 0.04f, 0.07f, 0.07f, 0.08f)
        // budíky – svítí
        val cols = if (w > 0.8f) 8 else 5
        for (row in 0 until 2) for (c in 0 until cols) {
            val dx = -w * 0.8f + (2f * w * 0.8f) * c / (cols - 1)
            val dy = top - 0.14f - row * 0.18f
            val green = (c + row) % 4 == 2
            disc(mb, dx, dy, pz + 0.005f, 0.065f,
                if (green) 0.25f else 0.92f, if (green) 0.85f else 0.93f, if (green) 0.45f else 0.88f, 0.75f)
        }
        // rám okna (sloupky a horní příčka)
        val pillar = 0.05f
        for (side in -1..1 step 2) {
            val x0 = side * w
            val x1 = side * w * 0.82f
            mb.hexa8(
                x0 - pillar, top, pz - 0.05f, x0 + pillar, top, pz - 0.05f, x0 + pillar, top, pz + 0.05f, x0 - pillar, top, pz + 0.05f,
                x1 - pillar, ey + 0.48f, ez - dist * 0.35f, x1 + pillar, ey + 0.48f, ez - dist * 0.35f,
                x1 + pillar, ey + 0.48f, ez - dist * 0.35f + 0.1f, x1 - pillar, ey + 0.48f, ez - dist * 0.35f + 0.1f,
                0.08f, 0.08f, 0.09f,
            )
        }
        mb.box(-w * 0.85f, ey + 0.46f, ez - dist * 0.4f, w * 0.85f, ey + 0.53f, ez - dist * 0.25f, 0.08f, 0.08f, 0.09f)
        if (type == AircraftType.FIGHTER) {
            // průhledový displej (HUD)
            mb.quad(-0.12f, top + 0.08f, pz + 0.1f, 0.12f, top + 0.08f, pz + 0.1f, 0.12f, top + 0.3f, pz + 0.16f,
                -0.12f, top + 0.3f, pz + 0.16f, 0.2f, 0.9f, 0.4f, 0.72f)
        }
        // knipl / beran před pilotem
        mb.box(ex - 0.04f, ey - 0.95f, ez - 0.42f, ex + 0.04f, ey - 0.55f, ez - 0.36f, 0.12f, 0.12f, 0.13f)
        mb.box(ex - 0.16f, ey - 0.58f, ez - 0.43f, ex + 0.16f, ey - 0.52f, ez - 0.35f, 0.12f, 0.12f, 0.13f)
        return mb.build()
    }

    /** Plochý obrys pro stín (y = 0). */
    fun shadow(type: AircraftType): MeshData {
        val mb = MeshBuilder(32)
        fun rect(x0: Float, z0: Float, x1: Float, z1: Float) =
            mb.quad(x0, 0f, z1, x1, 0f, z1, x1, 0f, z0, x0, 0f, z0, 0f, 0f, 0f)
        when (type) {
            AircraftType.SPORT -> {
                rect(-5.2f, -0.9f, 5.2f, 0.2f); rect(-0.6f, -3.7f, 0.6f, 3.9f); rect(-1.9f, 3.2f, 1.9f, 3.9f)
            }
            AircraftType.FIGHTER -> {
                mb.quad(-0.9f, 0f, 4.8f, 0.9f, 0f, 4.8f, 5f, 0f, 4.6f, -5f, 0f, 4.6f, 0f, 0f, 0f)
                mb.triangle(0.9f, 0f, -2.5f, 0.9f, 0f, 4.8f, 5f, 0f, 4.6f, 0f, 0f, 0f)
                mb.triangle(-0.9f, 0f, -2.5f, -5f, 0f, 4.6f, -0.9f, 0f, 4.8f, 0f, 0f, 0f)
                rect(-1f, -8f, 1f, 7.5f); rect(-2.9f, 6.2f, 2.9f, 7.3f)
            }
            AircraftType.AIRLINER -> {
                rect(-2f, -19.5f, 2f, 20f)
                mb.quad(-17f, 0f, 6.5f, 17f, 0f, 6.5f, 17f, 0f, 4.5f, -17f, 0f, 4.5f, 0f, 0f, 0f)
                mb.quad(-1.8f, 0f, 4f, 1.8f, 0f, 4f, 17f, 0f, 4.5f, -17f, 0f, 4.5f, 0f, 0f, 0f)
                mb.quad(-1.8f, 0f, -4f, 1.8f, 0f, -4f, 1.8f, 0f, 4f, -1.8f, 0f, 4f, 0f, 0f, 0f)
                mb.triangle(1.8f, 0f, -4f, 17f, 0f, 4.5f, 1.8f, 0f, 4f, 0f, 0f, 0f)
                mb.triangle(-1.8f, 0f, -4f, -1.8f, 0f, 4f, -17f, 0f, 4.5f, 0f, 0f, 0f)
                rect(-6.5f, 15f, 6.5f, 19.5f)
            }
            AircraftType.HELICOPTER -> {
                rect(-1.1f, -3.2f, 1.1f, 3.2f); rect(-0.3f, 3f, 0.3f, 9f)
                for (k in 0 until 8) {
                    val a0 = (k * PI / 4).toFloat()
                    val a1 = ((k + 1) * PI / 4).toFloat()
                    mb.triangle(0f, 0f, 0.1f, kotlin.math.cos(a1) * 5.6f, 0f, 0.1f + sin(a1) * 5.6f,
                        kotlin.math.cos(a0) * 5.6f, 0f, 0.1f + sin(a0) * 5.6f, 0f, 0f, 0f)
                }
            }
            AircraftType.BOMBER -> {
                rect(-16f, -3.5f, 16f, 1.5f); rect(-1.3f, -11.5f, 1.3f, 12f); rect(-6f, 9.5f, 6f, 12f)
            }
        }
        return mb.build()
    }

    /** Hrubá koule pro výbuch a kouř. */
    fun ball(): MeshData {
        val mb = MeshBuilder(256)
        val rings = 6
        val sides = 10
        for (rI in 0 until rings) {
            val t0 = (PI * rI / rings).toFloat()
            val t1 = (PI * (rI + 1) / rings).toFloat()
            for (s in 0 until sides) {
                val p0 = (2 * PI * s / sides).toFloat()
                val p1 = (2 * PI * (s + 1) / sides).toFloat()
                mb.quad(
                    sin(t0) * kotlin.math.cos(p0), kotlin.math.cos(t0), sin(t0) * sin(p0),
                    sin(t0) * kotlin.math.cos(p1), kotlin.math.cos(t0), sin(t0) * sin(p1),
                    sin(t1) * kotlin.math.cos(p1), kotlin.math.cos(t1), sin(t1) * sin(p1),
                    sin(t1) * kotlin.math.cos(p0), kotlin.math.cos(t1), sin(t1) * sin(p0),
                    1f, 1f, 1f,
                )
            }
        }
        return mb.build()
    }

    // ------------------------------------------------------------------ pomocné tvary

    private fun propeller(radius: Float, blades: Int, chord: Float, c: FloatArray): MeshData {
        val mb = MeshBuilder(64)
        for (i in 0 until blades) {
            val a = (2 * PI * i / blades).toFloat()
            mb.bladeZ(a, 0.08f, radius, chord, 0.06f, c[0], c[1], c[2])
            if (radius < 2f) mb.bladeZ(a, radius * 0.86f, radius, chord * 1.02f, 0.065f, 0.95f, 0.8f, 0.1f)
        }
        mb.box(-0.1f, -0.1f, -0.06f, 0.1f, 0.1f, 0.06f, 0.3f, 0.3f, 0.32f)
        return mb.build()
    }

    private fun disc(mb: MeshBuilder, cx: Float, cy: Float, z: Float, r: Float, cr: Float, cg: Float, cb: Float, a: Float) {
        for (k in 0 until 6) {
            val a0 = (k * PI / 3).toFloat()
            val a1 = ((k + 1) * PI / 3).toFloat()
            mb.triangle(cx, cy, z, cx + kotlin.math.cos(a0) * r, cy + sin(a0) * r, z,
                cx + kotlin.math.cos(a1) * r, cy + sin(a1) * r, z, cr, cg, cb, a)
        }
    }

    private fun light(mb: MeshBuilder, x: Float, y: Float, z: Float, c: FloatArray) {
        mb.box(x - 0.12f, y - 0.1f, z - 0.12f, x + 0.12f, y + 0.1f, z + 0.12f, c[0], c[1], c[2], c[3], c[0], c[1], c[2])
    }

    private fun box(mb: MeshBuilder, x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, c: FloatArray) =
        mb.box(x0, y0, z0, x1, y1, z1, c[0], c[1], c[2], if (c.size > 3) c[3] else 1f, c[0], c[1], c[2])

    /** Lichoběžníkové křídlo/ocasní plocha od kořene (x0) ke konci (x1); funguje i pro záporná x. */
    private fun panel(
        mb: MeshBuilder, x0: Float, x1: Float,
        y0: Float, y1: Float, front0: Float, back0: Float, front1: Float, back1: Float,
        t0: Float, t1: Float, c: FloatArray,
    ) {
        // hexa8 potřebuje x- < x+
        val flip = x1 < x0
        val ax = if (flip) x1 else x0
        val bx = if (flip) x0 else x1
        val ay = if (flip) y1 else y0
        val by = if (flip) y0 else y1
        val af = if (flip) front1 else front0
        val ab = if (flip) back1 else back0
        val bf = if (flip) front0 else front1
        val bb = if (flip) back0 else back1
        val at = if (flip) t1 else t0
        val bt = if (flip) t0 else t1
        mb.hexa8(
            ax, ay - at, af, bx, by - bt, bf, bx, by - bt, bb, ax, ay - at, ab,
            ax, ay + at, af, bx, by + bt, bf, bx, by + bt, bb, ax, ay + at, ab,
            c[0], c[1], c[2],
        )
    }

    /** Svislá ocasní plocha. */
    private fun fin(
        mb: MeshBuilder, x: Float, t: Float, yBottom: Float, yTop: Float,
        zb0: Float, zb1: Float, zt0: Float, zt1: Float, c: FloatArray,
    ) {
        mb.hexa8(
            x - t, yBottom, zb0, x + t, yBottom, zb0, x + t, yBottom, zb1, x - t, yBottom, zb1,
            x - t * 0.6f, yTop, zt0, x + t * 0.6f, yTop, zt0, x + t * 0.6f, yTop, zt1, x - t * 0.6f, yTop, zt1,
            c[0], c[1], c[2],
        )
    }

    private fun wheel(mb: MeshBuilder, x: Float, z: Float, bottom: Float, strutTop: Float, r: Float = 0.28f, w: Float = 0.11f) {
        box(mb, x - 0.05f, bottom + r, z - 0.05f, x + 0.05f, strutTop, z + 0.05f, GREY)
        box(mb, x - w, bottom, z - r, x + w, bottom + 2 * r, z + r, DARK)
    }

    private fun engine(mb: MeshBuilder, x: Float, y: Float, z0: Float, z1: Float, r0: Float, r1: Float, c: FloatArray, fan: Boolean) {
        val st = arrayOf(
            floatArrayOf(z0, r0, r0, y),
            floatArrayOf(z0 + (z1 - z0) * 0.3f, r0 * 1.05f, r0 * 1.05f, y),
            floatArrayOf(z1, r1, r1, y),
        )
        mb.loftZ(st, 10, cx = x) { seg, _ -> if (seg == -1) (if (fan) floatArrayOf(0.12f, 0.12f, 0.13f) else DARK) else c }
    }

    // ------------------------------------------------------------------ sportovní

    private fun sport(): MeshData {
        val mb = MeshBuilder(1024)
        val st = arrayOf(
            floatArrayOf(-3.62f, 0.30f, 0.30f, 0.05f),
            floatArrayOf(-3.25f, 0.58f, 0.58f, 0.06f),
            floatArrayOf(-2.20f, 0.62f, 0.68f, 0.08f),
            floatArrayOf(-0.50f, 0.62f, 0.72f, 0.12f),
            floatArrayOf(1.00f, 0.50f, 0.56f, 0.14f),
            floatArrayOf(2.80f, 0.25f, 0.33f, 0.24f),
            floatArrayOf(4.05f, 0.08f, 0.15f, 0.32f),
        )
        mb.loftZ(st, 8) { seg, ang ->
            when {
                seg == -1 -> GREY
                seg <= 1 -> RED
                seg in 2..4 && abs(sin(ang)) < 0.5f -> RED
                else -> WHITE
            }
        }
        // kabina
        mb.hexa8(
            -0.50f, 0.62f, -1.6f, 0.50f, 0.62f, -1.6f, 0.50f, 0.62f, 0.6f, -0.50f, 0.62f, 0.6f,
            -0.36f, 1.02f, -0.85f, 0.36f, 1.02f, -0.85f, 0.36f, 1.02f, 0.25f, -0.36f, 1.02f, 0.25f,
            GLASS[0], GLASS[1], GLASS[2],
        )
        for (side in -1..1 step 2) {
            val s = side.toFloat()
            panel(mb, s * 0.55f, s * 4.6f, -0.28f, 0.02f, -1.15f, 0.45f, -0.85f, 0.2f, 0.11f, 0.07f, WHITE)
            panel(mb, s * 4.6f, s * 5.25f, 0.02f, 0.06f, -0.85f, 0.2f, -0.85f, 0.2f, 0.07f, 0.06f, RED)
            light(mb, s * 5.3f, 0.06f, -0.4f, if (side > 0) NAV_GREEN else NAV_RED)
        }
        panel(mb, 0f, 1.95f, 0.3f, 0.3f, 3.15f, 3.95f, 3.35f, 3.95f, 0.04f, 0.03f, WHITE)
        panel(mb, 0f, -1.95f, 0.3f, 0.3f, 3.15f, 3.95f, 3.35f, 3.95f, 0.04f, 0.03f, WHITE)
        fin(mb, 0f, 0.06f, 0.35f, 1.75f, 2.85f, 4.05f, 3.65f, 4.1f, RED)
        wheel(mb, 1.35f, -0.65f, -1.53f, -0.3f)
        wheel(mb, -1.35f, -0.65f, -1.53f, -0.3f)
        wheel(mb, 0f, -2.55f, -1.53f, -0.3f)
        return mb.build()
    }

    // ------------------------------------------------------------------ stíhačka

    private fun fighter(): MeshData {
        val mb = MeshBuilder(2048)
        val body = floatArrayOf(0.50f, 0.54f, 0.58f)
        val dark = floatArrayOf(0.33f, 0.35f, 0.38f)
        val st = arrayOf(
            floatArrayOf(-8.0f, 0.05f, 0.05f, 0.0f),
            floatArrayOf(-6.5f, 0.42f, 0.40f, 0.05f),
            floatArrayOf(-4.5f, 0.72f, 0.66f, 0.15f),
            floatArrayOf(-2.0f, 0.95f, 0.82f, 0.15f),
            floatArrayOf(2.0f, 1.05f, 0.80f, 0.10f),
            floatArrayOf(5.5f, 0.95f, 0.70f, 0.05f),
            floatArrayOf(7.2f, 0.68f, 0.60f, 0.05f),
        )
        mb.loftZ(st, 10, capBack = false) { seg, ang -> if (seg == 0) dark else if (sin(ang) < -0.6f) dark else body }
        // kabina (bublina)
        mb.hexa8(
            -0.52f, 0.72f, -5.0f, 0.52f, 0.72f, -5.0f, 0.52f, 0.72f, -1.7f, -0.52f, 0.72f, -1.7f,
            -0.3f, 1.45f, -3.9f, 0.3f, 1.45f, -3.9f, 0.3f, 1.4f, -2.2f, -0.3f, 1.4f, -2.2f,
            0.32f, 0.4f, 0.48f,
        )
        // sání motoru
        for (side in -1..1 step 2) {
            val s = side.toFloat()
            val x0 = if (side > 0) 0.85f else -1.45f
            val x1 = if (side > 0) 1.45f else -0.85f
            mb.box(x0, -0.45f, -3.2f, x1, 0.35f, 1.2f, dark[0], dark[1], dark[2])
            mb.box(x0 + 0.05f, -0.4f, -3.22f, x1 - 0.05f, 0.3f, -3.15f, 0.05f, 0.05f, 0.06f)
            // delta křídla
            panel(mb, s * 0.95f, s * 5.0f, -0.05f, -0.15f, -2.6f, 4.9f, 3.1f, 4.6f, 0.13f, 0.05f, body)
            // ocasní plochy
            panel(mb, s * 0.8f, s * 2.9f, 0.05f, 0.0f, 5.0f, 7.2f, 6.4f, 7.3f, 0.07f, 0.04f, body)
            light(mb, s * 5.0f, -0.15f, 4.2f, if (side > 0) NAV_GREEN else NAV_RED)
        }
        fin(mb, 0f, 0.09f, 0.7f, 3.5f, 3.4f, 7.2f, 6.2f, 7.45f, dark)
        // tryska a plamen přídavného spalování
        val nozzle = arrayOf(
            floatArrayOf(7.2f, 0.62f, 0.56f, 0.05f),
            floatArrayOf(7.9f, 0.55f, 0.52f, 0.05f),
        )
        mb.loftZ(nozzle, 10, capFront = false) { seg, _ -> if (seg >= nozzle.size) floatArrayOf(0.12f, 0.1f, 0.1f) else floatArrayOf(0.22f, 0.22f, 0.24f) }
        val flame = arrayOf(
            floatArrayOf(7.95f, 0.45f, 0.42f, 0.05f),
            floatArrayOf(9.2f, 0.32f, 0.3f, 0.05f),
            floatArrayOf(10.6f, 0.04f, 0.04f, 0.05f),
        )
        mb.loftZ(flame, 8, capFront = true, capBack = false) { _, _ -> floatArrayOf(1f, 0.55f, 0.18f, 0.7f) }
        // podvozek
        wheel(mb, 0f, -5.2f, -2.1f, -0.4f, r = 0.3f)
        wheel(mb, 1.5f, 1.6f, -2.1f, -0.5f, r = 0.38f, w = 0.15f)
        wheel(mb, -1.5f, 1.6f, -2.1f, -0.5f, r = 0.38f, w = 0.15f)
        return mb.build()
    }

    // ------------------------------------------------------------------ dopravní

    private fun airliner(): MeshData {
        val mb = MeshBuilder(4096)
        val white = floatArrayOf(0.95f, 0.95f, 0.96f)
        val belly = floatArrayOf(0.72f, 0.74f, 0.77f)
        val windows = floatArrayOf(0.14f, 0.18f, 0.28f)
        val blue = floatArrayOf(0.1f, 0.3f, 0.72f)
        val st = arrayOf(
            floatArrayOf(-19.6f, 0.12f, 0.12f, 0.3f),
            floatArrayOf(-18.6f, 1.1f, 1.1f, 0.2f),
            floatArrayOf(-16.5f, 1.85f, 1.95f, 0.12f),
            floatArrayOf(-13.0f, 2.0f, 2.05f, 0.1f),
            floatArrayOf(10.0f, 2.0f, 2.05f, 0.1f),
            floatArrayOf(15.0f, 1.6f, 1.6f, 0.6f),
            floatArrayOf(19.0f, 0.6f, 0.6f, 1.4f),
            floatArrayOf(20.2f, 0.15f, 0.2f, 1.6f),
        )
        mb.loftZ(st, 16) { seg, ang ->
            val s = sin(ang)
            when {
                seg == -1 || seg >= st.size -> white
                seg == 1 && s > 0.15f && s < 0.75f -> windows
                seg in 2..4 && s > 0f && s < 0.4f -> windows
                seg in 2..5 && s < 0f && s > -0.4f -> blue
                s < -0.55f -> belly
                else -> white
            }
        }
        for (side in -1..1 step 2) {
            val s = side.toFloat()
            // šípová křídla se vzepětím
            panel(mb, s * 1.8f, s * 17f, -1.2f, 0.4f, -4f, 4f, 4.5f, 6.5f, 0.35f, 0.12f, floatArrayOf(0.82f, 0.83f, 0.86f))
            // motor pod křídlem
            box(mb, s * 7f - 0.15f, -1.6f, -3.5f, s * 7f + 0.15f, -0.8f, -0.5f, floatArrayOf(0.8f, 0.8f, 0.82f))
            engine(mb, s * 7f, -2.15f, -6.6f, -1.6f, 1.15f, 0.85f, floatArrayOf(0.88f, 0.88f, 0.9f), true)
            // výškovka
            panel(mb, s * 0.8f, s * 6.6f, 1.2f, 1.6f, 15f, 18.5f, 18.2f, 19.6f, 0.15f, 0.07f, white)
            light(mb, s * 17.1f, 0.45f, 5.6f, if (side > 0) NAV_GREEN else NAV_RED)
        }
        fin(mb, 0f, 0.16f, 1.5f, 9.6f, 13f, 19.6f, 18.4f, 20.6f, blue)
        // podvozek
        wheel(mb, 0f, -15f, -3.4f, -1.6f, r = 0.4f, w = 0.15f)
        for (side in -1..1 step 2) {
            val x = side * 3f
            box(mb, x - 0.1f, -2.9f, 0.9f, x + 0.1f, -1.6f, 1.1f, GREY)
            box(mb, x - 0.55f, -3.4f, 0.3f, x - 0.2f, -2.4f, 1.7f, DARK)
            box(mb, x + 0.2f, -3.4f, 0.3f, x + 0.55f, -2.4f, 1.7f, DARK)
        }
        return mb.build()
    }

    // ------------------------------------------------------------------ vrtulník

    private fun helicopter(): MeshData {
        val mb = MeshBuilder(2048)
        val red = floatArrayOf(0.82f, 0.14f, 0.12f)
        val white = floatArrayOf(0.95f, 0.95f, 0.95f)
        val glass = floatArrayOf(0.28f, 0.42f, 0.52f)
        val st = arrayOf(
            floatArrayOf(-3.2f, 0.25f, 0.3f, 0.15f),
            floatArrayOf(-2.6f, 0.85f, 0.95f, 0.2f),
            floatArrayOf(-1.0f, 1.05f, 1.15f, 0.25f),
            floatArrayOf(1.2f, 1.0f, 1.1f, 0.3f),
            floatArrayOf(2.5f, 0.55f, 0.65f, 0.5f),
            floatArrayOf(3.3f, 0.3f, 0.35f, 0.65f),
        )
        mb.loftZ(st, 10, capBack = false) { seg, ang ->
            val s = sin(ang)
            when {
                seg == -1 -> glass
                seg == 0 && s > -0.7f -> glass
                seg == 1 && s > 0.1f -> glass
                seg in 1..3 && abs(s) < 0.25f -> white
                else -> red
            }
        }
        val boom = arrayOf(
            floatArrayOf(3.3f, 0.3f, 0.33f, 0.65f),
            floatArrayOf(8.9f, 0.14f, 0.16f, 0.95f),
        )
        mb.loftZ(boom, 8, capFront = false) { _, _ -> red }
        fin(mb, 0f, 0.06f, 0.9f, 2.4f, 8.0f, 9.0f, 8.6f, 9.2f, red)
        panel(mb, 0f, 1.0f, 0.85f, 0.85f, 7.4f, 8.0f, 7.6f, 8.0f, 0.04f, 0.03f, white)
        panel(mb, 0f, -1.0f, 0.85f, 0.85f, 7.4f, 8.0f, 7.6f, 8.0f, 0.04f, 0.03f, white)
        // ližiny
        for (side in -1..1 step 2) {
            val x = side * 1.05f
            box(mb, x - 0.07f, -1.73f, -2.2f, x + 0.07f, -1.6f, 2.3f, DARK)
            box(mb, x - 0.05f, -1.62f, -1.2f, x + 0.05f, -0.6f, -1.1f, DARK)
            box(mb, x - 0.05f, -1.62f, 1.1f, x + 0.05f, -0.6f, 1.2f, DARK)
            light(mb, side * 1.1f, 0.4f, 0.8f, if (side > 0) NAV_GREEN else NAV_RED)
        }
        // stožár rotoru a motor
        box(mb, -0.6f, 1.25f, -0.6f, 0.6f, 1.6f, 1.4f, floatArrayOf(0.7f, 0.12f, 0.1f))
        box(mb, -0.12f, 1.6f, -0.02f, 0.12f, 1.95f, 0.22f, DARK)
        return mb.build()
    }

    // ------------------------------------------------------------------ bombardér

    private fun bomber(): MeshData {
        val mb = MeshBuilder(4096)
        val olive = floatArrayOf(0.34f, 0.38f, 0.25f)
        val belly = floatArrayOf(0.55f, 0.57f, 0.55f)
        val glass = floatArrayOf(0.35f, 0.48f, 0.55f)
        val st = arrayOf(
            floatArrayOf(-11.6f, 0.3f, 0.4f, 0.1f),
            floatArrayOf(-10.5f, 0.9f, 1.1f, 0.1f),
            floatArrayOf(-8.5f, 1.25f, 1.45f, 0.15f),
            floatArrayOf(5.0f, 1.25f, 1.45f, 0.15f),
            floatArrayOf(9.0f, 0.8f, 1.0f, 0.5f),
            floatArrayOf(12.2f, 0.3f, 0.45f, 0.9f),
        )
        mb.loftZ(st, 12) { seg, ang ->
            when {
                seg == -1 || seg == 0 -> glass
                sin(ang) < -0.5f -> belly
                else -> olive
            }
        }
        // kabina pilotů a střelecké věže
        mb.hexa8(
            -0.9f, 1.3f, -8.3f, 0.9f, 1.3f, -8.3f, 0.9f, 1.3f, -5.4f, -0.9f, 1.3f, -5.4f,
            -0.7f, 1.95f, -7.4f, 0.7f, 1.95f, -7.4f, 0.7f, 1.95f, -5.6f, -0.7f, 1.95f, -5.6f,
            glass[0], glass[1], glass[2],
        )
        box(mb, -0.55f, 1.5f, -3.6f, 0.55f, 2.1f, -2.6f, glass)
        box(mb, -0.55f, -1.9f, 1.6f, 0.55f, -1.2f, 2.6f, glass)
        for (side in -1..1 step 2) {
            val s = side.toFloat()
            panel(mb, s * 1.2f, s * 16f, -0.35f, 0.4f, -3.6f, 1.5f, -1.8f, 0.6f, 0.4f, 0.15f, olive)
            // dva motory na každé straně
            engine(mb, s * 4.5f, -0.2f, -5.3f, 0.8f, 0.8f, 0.42f, olive, false)
            engine(mb, s * 9f, -0.2f, -5.3f, 0.6f, 0.75f, 0.4f, olive, false)
            panel(mb, s * 0.6f, s * 6f, 0.8f, 0.9f, 9.5f, 12f, 10.6f, 12f, 0.12f, 0.06f, olive)
            light(mb, s * 16.1f, 0.4f, -0.6f, if (side > 0) NAV_GREEN else NAV_RED)
        }
        fin(mb, 0f, 0.14f, 1.2f, 5.2f, 6f, 12.1f, 10.4f, 12.3f, olive)
        // podvozek
        wheel(mb, 4.5f, -1.5f, -3.0f, -0.9f, r = 0.55f, w = 0.22f)
        wheel(mb, -4.5f, -1.5f, -3.0f, -0.9f, r = 0.55f, w = 0.22f)
        wheel(mb, 0f, -8.6f, -3.0f, -1.2f, r = 0.4f, w = 0.15f)
        return mb.build()
    }
}
