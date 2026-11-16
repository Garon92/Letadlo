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
        AircraftType.AIRSHIP -> airship(false)
    }

    /** Pirátská vzducholoď (cíl) – stejný tvar, temně rudý balon, bez svítících oken. */
    fun pirateAirship(): MeshData = airship(true)

    fun rotors(type: AircraftType): List<RotorSpec> = when (type) {
        AircraftType.SPORT -> listOf(RotorSpec(propeller(1.05f, 2, 0.18f, DARK), 0f, 0.05f, -3.72f, 'z', 1f))
        AircraftType.FIGHTER, AircraftType.AIRLINER -> emptyList()
        AircraftType.HELICOPTER -> listOf(
            RotorSpec(propeller(5.6f, 4, 0.36f, floatArrayOf(0.15f, 0.15f, 0.16f)), 0f, 2.0f, 0.1f, 'y', 1f),
            RotorSpec(propeller(0.95f, 2, 0.16f, floatArrayOf(0.15f, 0.15f, 0.16f)), 0.28f, 1.15f, 8.75f, 'x', 4.5f),
        )
        AircraftType.AIRSHIP -> {
            val wood = floatArrayOf(0.5f, 0.32f, 0.17f)
            val side = propeller(2.3f, 4, 0.55f, wood)
            listOf(
                RotorSpec(side, -6.8f, -9f, 6.6f, 'z', 0.7f),
                RotorSpec(side, 6.8f, -9f, 6.6f, 'z', 0.7f),
                RotorSpec(propeller(3.2f, 4, 0.7f, wood), 0f, 0f, 30.2f, 'z', 0.5f),
            )
        }
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
        AircraftType.SPORT -> floatArrayOf(0f, 1.0f, -0.55f)
        AircraftType.FIGHTER -> floatArrayOf(0f, 1.18f, -3.55f)
        AircraftType.AIRLINER -> floatArrayOf(-0.55f, 0.95f, -16.6f)
        AircraftType.HELICOPTER -> floatArrayOf(-0.45f, 0.8f, -1.6f)
        AircraftType.BOMBER -> floatArrayOf(-0.5f, 1.75f, -6.7f)
        // vzducholoď: na přídi gondoly, co nejdál pod balonem
        AircraftType.AIRSHIP -> floatArrayOf(0f, -9.0f, -10.6f)
    }

    /**
     * Vnitřek kabiny: jen nízká palubní deska se svítícími budíky.
     * Horní hrana desky je ~24° pod osou pohledu, takže výhled dopředu zůstává volný.
     */
    fun cockpit(type: AircraftType): MeshData {
        val e = eye(type)
        val mb = MeshBuilder(512)
        val ey = e[1]; val ez = e[2]
        val (w, dist) = when (type) {
            AircraftType.SPORT -> Pair(0.55f, 0.75f)
            AircraftType.FIGHTER -> Pair(0.45f, 0.7f)
            AircraftType.AIRLINER -> Pair(1.15f, 0.95f)
            AircraftType.HELICOPTER -> Pair(0.9f, 0.85f)
            AircraftType.BOMBER -> Pair(1.0f, 0.9f)
            AircraftType.AIRSHIP -> Pair(1.4f, 1.0f)
        }
        val airship = type == AircraftType.AIRSHIP
        if (airship) return helm(e)
        val pz = ez - dist
        val top = ey - dist * if (type == AircraftType.HELICOPTER) 0.62f else 0.47f
        // deska je nakloněná k pilotovi, aby budíky nebyly vidět z ostrého úhlu
        mb.hexa8(
            -w, top - 0.42f, pz - 0.2f, w, top - 0.42f, pz - 0.2f, w, top - 0.42f, pz + 0.05f, -w, top - 0.42f, pz + 0.05f,
            -w, top, pz - 0.2f, w, top, pz - 0.2f, w, top, pz, -w, top, pz,
            if (airship) 0.36f else 0.15f, if (airship) 0.22f else 0.15f, if (airship) 0.12f else 0.16f,
        )
        // tenká stříška nad budíky
        mb.box(-w - 0.02f, top, pz - 0.24f, w + 0.02f, top + 0.025f, pz + 0.03f, 0.06f, 0.06f, 0.07f)
        // budíky – svítí, s odstupem od desky (žádné problikávání)
        val cols = if (w > 0.8f) 8 else 5
        for (row in 0 until 2) for (c in 0 until cols) {
            val dx = -w * 0.8f + (2f * w * 0.8f) * c / (cols - 1)
            val dy = top - 0.1f - row * 0.16f
            val green = (c + row) % 4 == 2
            val zf = pz + 0.012f + (top - dy) * (0.05f / 0.42f)
            if (airship) {
                // mosazné budíky parního stroje
                disc(mb, dx, dy, zf - 0.004f, 0.075f, 0.85f, 0.62f, 0.22f, 1f)
                disc(mb, dx, dy, zf, 0.058f, 0.96f, 0.92f, 0.8f, 0.75f)
            } else {
                disc(mb, dx, dy, zf, 0.055f,
                    if (green) 0.25f else 0.92f, if (green) 0.85f else 0.93f, if (green) 0.45f else 0.88f, 0.75f)
            }
        }
        return mb.build()
    }

    /**
     * Můstek vzducholodi: dřevěné kormidelní kolo, mosazné zábradlí a dva budíky.
     * Všechno dole u okraje obrazovky – výhled dopředu zůstává volný.
     */
    private fun helm(e: FloatArray): MeshData {
        val mb = MeshBuilder(512)
        val ey = e[1]; val ez = e[2]
        val wood = floatArrayOf(0.45f, 0.27f, 0.13f)
        val brass = floatArrayOf(0.85f, 0.66f, 0.26f)
        val wy = ey - 0.52f
        val wz = ez - 0.62f
        val r = 0.3f
        val seg = 20
        for (k in 0 until seg) {
            val a0 = (2 * PI * k / seg).toFloat()
            val a1 = (2 * PI * (k + 1) / seg).toFloat()
            val c0 = kotlin.math.cos(a0); val s0 = sin(a0)
            val c1 = kotlin.math.cos(a1); val s1 = sin(a1)
            mb.quad(c0 * (r - 0.035f), wy + s0 * (r - 0.035f), wz, c0 * r, wy + s0 * r, wz,
                c1 * r, wy + s1 * r, wz, c1 * (r - 0.035f), wy + s1 * (r - 0.035f), wz, wood[0], wood[1], wood[2])
        }
        for (k in 0 until 8) {
            val a = (PI * k / 4 + PI / 8).toFloat()
            mb.bladeZ(a, 0.03f, r + 0.11f, 0.035f, 0.035f, wood[0], wood[1], wood[2], wz, 0f, wy)
        }
        mb.box(-0.05f, wy - 0.05f, wz - 0.06f, 0.05f, wy + 0.05f, wz + 0.06f, brass[0], brass[1], brass[2])
        mb.box(-0.04f, wy - 0.8f, wz + 0.02f, 0.04f, wy, wz + 0.1f, brass[0], brass[1], brass[2])
        // zábradlí přes příď a budíky na něm
        mb.box(-1.7f, ey - 0.64f, ez - 1.3f, 1.7f, ey - 0.58f, ez - 1.22f, brass[0], brass[1], brass[2])
        for (sx in floatArrayOf(-0.8f, 0.8f)) {
            mb.box(sx - 0.03f, ey - 0.95f, ez - 1.28f, sx + 0.03f, ey - 0.58f, ez - 1.24f, brass[0], brass[1], brass[2])
            disc(mb, sx, ey - 0.5f, ez - 1.2f, 0.1f, brass[0], brass[1], brass[2], 1f)
            disc(mb, sx, ey - 0.5f, ez - 1.195f, 0.08f, 0.96f, 0.92f, 0.8f, 0.75f)
        }
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
            AircraftType.AIRSHIP -> {
                rect(-5f, -24f, 5f, 26f); rect(-7.5f, -16f, 7.5f, 16f); rect(-8.3f, -6f, 8.3f, 8f)
                rect(-13f, 19f, 13f, 27f)
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
            -0.36f, 1.2f, -0.85f, 0.36f, 1.2f, -0.85f, 0.36f, 1.2f, 0.25f, -0.36f, 1.2f, 0.25f,
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

    // ------------------------------------------------------------------ vzducholoď

    private fun airship(pirate: Boolean): MeshData {
        val mb = MeshBuilder(4096)
        val canvasA = if (pirate) floatArrayOf(0.38f, 0.08f, 0.07f) else floatArrayOf(0.84f, 0.74f, 0.54f)
        val canvasB = if (pirate) floatArrayOf(0.24f, 0.05f, 0.05f) else floatArrayOf(0.72f, 0.6f, 0.42f)
        val copper = floatArrayOf(0.74f, 0.44f, 0.2f)
        val brass = floatArrayOf(0.85f, 0.66f, 0.26f)
        val wood = if (pirate) floatArrayOf(0.2f, 0.13f, 0.08f) else floatArrayOf(0.47f, 0.29f, 0.15f)
        val leather = if (pirate) floatArrayOf(0.12f, 0.1f, 0.1f) else floatArrayOf(0.55f, 0.22f, 0.15f)

        // balon: záplatované plátno v pruzích
        val env = arrayOf(
            floatArrayOf(-28f, 0.4f, 0.4f, 0f),
            floatArrayOf(-26f, 3.6f, 3.6f, 0f),
            floatArrayOf(-22f, 6.2f, 6.2f, 0f),
            floatArrayOf(-15f, 7.8f, 7.8f, 0f),
            floatArrayOf(-5f, 8.3f, 8.3f, 0f),
            floatArrayOf(6f, 8.2f, 8.2f, 0f),
            floatArrayOf(16f, 7f, 7f, 0f),
            floatArrayOf(24f, 4.4f, 4.4f, 0f),
            floatArrayOf(29.5f, 1f, 1f, 0f),
        )
        mb.loftZ(env, 16) { seg, ang ->
            val panel = (((ang / (2 * PI)) * 16).toInt() + seg) % 2 == 0
            if (seg < 0 || seg >= env.size) copper else if (panel) canvasA else canvasB
        }
        // měděné obruče
        for (zb in floatArrayOf(-22f, -15f, -5f, 6f, 16f, 24f)) {
            val r = interpRadius(env, zb) * 1.025f
            mb.loftZ(arrayOf(floatArrayOf(zb - 0.35f, r, r, 0f), floatArrayOf(zb + 0.35f, r, r, 0f)), 16,
                capFront = false, capBack = false) { _, _ -> copper }
        }
        // ocasní plochy do kříže
        fin(mb, 0f, 0.18f, 6f, 13.5f, 17f, 28f, 23f, 28.5f, leather)
        mb.hexa8(
            -0.18f, -13.5f, 23f, 0.18f, -13.5f, 23f, 0.18f, -13.5f, 28.5f, -0.18f, -13.5f, 28.5f,
            -0.12f, -6f, 17f, 0.12f, -6f, 17f, 0.12f, -6f, 28f, -0.12f, -6f, 28f,
            leather[0], leather[1], leather[2],
        )
        panel(mb, 5.5f, 13.5f, 0f, 0f, 17f, 28f, 23f, 28.5f, 0.18f, 0.12f, leather)
        panel(mb, -5.5f, -13.5f, 0f, 0f, 17f, 28f, 23f, 28.5f, 0.18f, 0.12f, leather)
        // gondola: dřevěný trup lodi s mosazným lemem
        mb.hexa8(
            -1.6f, -13.1f, -9f, 1.6f, -13.1f, -9f, 1.6f, -13.1f, 9f, -1.6f, -13.1f, 9f,
            -2.6f, -9.5f, -11.5f, 2.6f, -9.5f, -11.5f, 2.6f, -9.5f, 11f, -2.6f, -9.5f, 11f,
            wood[0], wood[1], wood[2],
        )
        mb.box(-2.7f, -9.7f, -11.6f, 2.7f, -9.35f, 11.1f, brass[0], brass[1], brass[2])
        // kajuta s okny (v noci svítí)
        mb.box(-2.2f, -9.35f, -8.5f, 2.2f, -7.4f, 6f, wood[0] * 0.8f, wood[1] * 0.8f, wood[2] * 0.8f, 1f, brass[0], brass[1], brass[2])
        if (!pirate) {
            for (z in floatArrayOf(-6.5f, -3.5f, -0.5f, 2.5f)) {
                // okna směřují ven – zevnitř kabiny jsou průhledná
                mb.quad(-2.21f, -8.9f, z, -2.21f, -8.9f, z + 1.6f, -2.21f, -7.9f, z + 1.6f, -2.21f, -7.9f, z, 1f, 0.78f, 0.4f, 0.7f)
                mb.quad(2.21f, -8.9f, z + 1.6f, 2.21f, -8.9f, z, 2.21f, -7.9f, z, 2.21f, -7.9f, z + 1.6f, 1f, 0.78f, 0.4f, 0.7f)
            }
        }
        // přední okno můstku
        mb.quad(1.8f, -9.2f, -8.51f, -1.8f, -9.2f, -8.51f, -1.8f, -7.7f, -8.51f, 1.8f, -7.7f, -8.51f, 0.2f, 0.28f, 0.34f)
        // vzpěry k balonu
        for (zs in floatArrayOf(-7f, 0f, 7f)) for (xs in floatArrayOf(-1.7f, 1.7f)) {
            mb.box(xs - 0.12f, -7.4f, zs - 0.12f, xs + 0.12f, -7f, zs + 0.12f, brass[0], brass[1], brass[2])
        }
        // ramena s gondolami vrtulí a komínky
        for (side in -1..1 step 2) {
            val s = side.toFloat()
            mb.box(if (side > 0) 2.6f else -6.9f, -9.3f, 3.6f, if (side > 0) 6.9f else -2.6f, -8.8f, 4.2f, brass[0], brass[1], brass[2])
            val nac = arrayOf(floatArrayOf(1.8f, 0.7f, 0.7f, -9f), floatArrayOf(6.4f, 0.55f, 0.55f, -9f))
            mb.loftZ(nac, 8, cx = s * 6.8f) { _, _ -> copper }
            mb.cylinderY(s * 5.4f, -9.4f, 3.9f, 0.55f, -6.6f, 0.45f, 8, brass[0], brass[1], brass[2])
            mb.cylinderY(s * 5.4f, -6.6f, 3.9f, 0.62f, -6.3f, 0.62f, 8, 0.15f, 0.12f, 0.1f)
            // mosazné dělo na přídi
            val gun = arrayOf(floatArrayOf(-15.5f, 0.28f, 0.28f, -10.5f), floatArrayOf(-11.3f, 0.36f, 0.36f, -10.5f))
            mb.loftZ(gun, 8, cx = s * 2.4f) { seg, _ -> if (seg == -1) DARK else brass }
            if (!pirate) light(mb, s * 13.6f, 0f, 22f, if (side > 0) NAV_GREEN else NAV_RED)
        }
        // kotevní nohy
        for (z in floatArrayOf(-6f, 6f)) mb.box(-0.15f, -13.2f, z - 0.15f, 0.15f, -12.9f, z + 0.15f, DARK[0], DARK[1], DARK[2])
        return mb.build()
    }

    private fun interpRadius(st: Array<FloatArray>, z: Float): Float {
        for (i in 0 until st.size - 1) {
            if (z >= st[i][0] && z <= st[i + 1][0]) {
                val t = (z - st[i][0]) / (st[i + 1][0] - st[i][0])
                return st[i][1] + (st[i + 1][1] - st[i][1]) * t
            }
        }
        return st.last()[1]
    }

    // ------------------------------------------------------------------ bombardér

    private fun bomber(): MeshData {
        val mb = MeshBuilder(4096)
        val olive = floatArrayOf(0.34f, 0.38f, 0.25f)
        val belly = floatArrayOf(0.55f, 0.57f, 0.55f)
        val glass = floatArrayOf(0.35f, 0.48f, 0.55f)
        val st = arrayOf(
            floatArrayOf(-11.6f, 0.3f, 0.4f, -0.25f),
            floatArrayOf(-10.5f, 0.9f, 1.1f, -0.1f),
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
