package app.letadlo.game

import kotlin.math.PI

/** Šablony pohyblivých věcí. Příď/obličej míří do -Z, střed spodku v počátku. */
object LifeMeshes {

    val carColors = arrayOf(
        floatArrayOf(0.78f, 0.1f, 0.1f),
        floatArrayOf(0.12f, 0.25f, 0.65f),
        floatArrayOf(0.92f, 0.92f, 0.92f),
        floatArrayOf(0.1f, 0.1f, 0.11f),
        floatArrayOf(0.95f, 0.75f, 0.1f),
        floatArrayOf(0.6f, 0.62f, 0.66f),
    )

    /** Varianty: 0..5 osobní auta, 6 autobus. */
    fun car(variant: Int): MeshData {
        val mb = MeshBuilder(160)
        if (variant >= carColors.size) {
            // autobus
            val c = floatArrayOf(0.85f, 0.15f, 0.12f)
            mb.box(-1.25f, 0.4f, -5.5f, 1.25f, 3.0f, 5.5f, c[0], c[1], c[2], 1f, 0.9f, 0.9f, 0.9f, bottom = false)
            mb.box(-1.27f, 1.6f, -5.3f, 1.27f, 2.6f, 5.3f, 0.15f, 0.2f, 0.26f, bottom = false)
            wheels(mb, 1.25f, 3.6f, 0.5f)
            lights(mb, 0.8f, 5.51f, 0.8f)
            return mb.build()
        }
        val c = carColors[variant]
        mb.box(-0.85f, 0.32f, -2.1f, 0.85f, 1.0f, 2.1f, c[0], c[1], c[2], bottom = false)
        mb.hexa8(
            -0.8f, 1.0f, -0.95f, 0.8f, 1.0f, -0.95f, 0.8f, 1.0f, 1.25f, -0.8f, 1.0f, 1.25f,
            -0.7f, 1.52f, -0.45f, 0.7f, 1.52f, -0.45f, 0.7f, 1.52f, 0.95f, -0.7f, 1.52f, 0.95f,
            0.16f, 0.2f, 0.26f,
        )
        mb.box(-0.7f, 1.52f, -0.45f, 0.7f, 1.57f, 0.95f, c[0], c[1], c[2], bottom = false)
        wheels(mb, 0.85f, 1.35f, 0.34f)
        lights(mb, 0.5f, 2.11f, 0.65f)
        return mb.build()
    }

    private fun wheels(mb: MeshBuilder, halfW: Float, axle: Float, r: Float) {
        for (z in floatArrayOf(-axle, axle)) {
            mb.box(-halfW - 0.06f, 0f, z - r, halfW + 0.06f, 2 * r, z + r, 0.08f, 0.08f, 0.08f, bottom = false)
        }
    }

    /** Přední bílá a zadní červená světla (svítí). */
    private fun lights(mb: MeshBuilder, x: Float, z: Float, y: Float) {
        for (s in -1..1 step 2) {
            val cx = s * x
            mb.quad(cx - 0.18f, y - 0.1f, -z, cx + 0.18f, y - 0.1f, -z, cx + 0.18f, y + 0.1f, -z, cx - 0.18f, y + 0.1f, -z,
                1f, 0.97f, 0.85f, 0.7f)
            mb.quad(cx + 0.18f, y - 0.1f, z, cx - 0.18f, y - 0.1f, z, cx - 0.18f, y + 0.1f, z, cx + 0.18f, y + 0.1f, z,
                0.95f, 0.06f, 0.05f, 0.7f)
        }
    }

    private val shirts = arrayOf(
        floatArrayOf(0.85f, 0.2f, 0.18f),
        floatArrayOf(0.2f, 0.45f, 0.8f),
        floatArrayOf(0.95f, 0.85f, 0.25f),
        floatArrayOf(0.25f, 0.6f, 0.3f),
        floatArrayOf(0.92f, 0.92f, 0.9f),
        floatArrayOf(0.6f, 0.3f, 0.7f),
    )
    private val skins = arrayOf(
        floatArrayOf(0.93f, 0.76f, 0.62f),
        floatArrayOf(0.78f, 0.58f, 0.42f),
        floatArrayOf(0.45f, 0.3f, 0.22f),
    )

    fun person(variant: Int): MeshData {
        val mb = MeshBuilder(64)
        val shirt = shirts[variant % shirts.size]
        val skin = skins[variant % skins.size]
        val pants = if (variant % 2 == 0) floatArrayOf(0.15f, 0.17f, 0.28f) else floatArrayOf(0.25f, 0.22f, 0.2f)
        mb.box(-0.18f, 0f, -0.11f, 0.18f, 0.88f, 0.11f, pants[0], pants[1], pants[2], bottom = false)
        mb.box(-0.27f, 0.88f, -0.14f, 0.27f, 1.5f, 0.14f, shirt[0], shirt[1], shirt[2], bottom = false)
        mb.box(-0.12f, 1.52f, -0.12f, 0.12f, 1.8f, 0.12f, skin[0], skin[1], skin[2])
        return mb.build()
    }

    fun boat(sail: Boolean): MeshData {
        val mb = MeshBuilder(128)
        val hull = if (sail) floatArrayOf(0.92f, 0.92f, 0.9f) else floatArrayOf(0.95f, 0.95f, 0.95f)
        mb.hexa8(
            -0.55f, -0.3f, -2.6f, 0.55f, -0.3f, -2.6f, 0.55f, -0.3f, 2.4f, -0.55f, -0.3f, 2.4f,
            -1.1f, 0.8f, -3.3f, 1.1f, 0.8f, -3.3f, 1.1f, 0.8f, 2.6f, -1.1f, 0.8f, 2.6f,
            hull[0], hull[1], hull[2],
        )
        mb.box(-1.12f, 0.15f, -3.2f, 1.12f, 0.35f, 2.62f, 0.1f, 0.25f, 0.55f, bottom = false)
        if (sail) {
            mb.box(-0.06f, 0.8f, -0.36f, 0.06f, 9.5f, -0.24f, 0.8f, 0.8f, 0.8f)
            mb.triangle(0f, 1.6f, -0.2f, 0f, 9.2f, -0.2f, 0f, 1.6f, 2.8f, 0.97f, 0.97f, 0.95f)
            mb.triangle(0f, 1.6f, -0.4f, 0f, 7.5f, -0.4f, 0f, 1.4f, -3.0f, 0.95f, 0.93f, 0.9f)
        } else {
            mb.box(-0.75f, 0.8f, -0.6f, 0.75f, 1.8f, 1.5f, 0.9f, 0.9f, 0.88f, 1f, 0.3f, 0.3f, 0.32f, bottom = false)
            mb.box(-0.77f, 1.25f, -0.62f, 0.77f, 1.65f, 1.0f, 0.15f, 0.2f, 0.26f, bottom = false)
        }
        return mb.build()
    }

    /** Raketa: bílé tělo, červená špička, stabilizátory a svítící plamen vzadu (příď v -Z). */
    fun rocket(): MeshData {
        val mb = MeshBuilder(160)
        val st = arrayOf(
            floatArrayOf(-1.3f, 0.02f, 0.02f, 0f),
            floatArrayOf(-0.9f, 0.13f, 0.13f, 0f),
            floatArrayOf(1.1f, 0.13f, 0.13f, 0f),
        )
        mb.loftZ(st, 8) { seg, _ -> if (seg <= 0) floatArrayOf(0.85f, 0.15f, 0.12f) else floatArrayOf(0.92f, 0.92f, 0.9f) }
        mb.box(-0.38f, -0.02f, 0.7f, 0.38f, 0.02f, 1.1f, 0.3f, 0.3f, 0.32f)
        mb.box(-0.02f, -0.38f, 0.7f, 0.02f, 0.38f, 1.1f, 0.3f, 0.3f, 0.32f)
        val flame = arrayOf(
            floatArrayOf(1.12f, 0.16f, 0.16f, 0f),
            floatArrayOf(1.9f, 0.1f, 0.1f, 0f),
            floatArrayOf(2.6f, 0.02f, 0.02f, 0f),
        )
        mb.loftZ(flame, 6, capBack = false) { _, _ -> floatArrayOf(1f, 0.7f, 0.25f, 0.7f) }
        return mb.build()
    }

    /** Letecká puma: tmavé tělo, zakulacená špička a křížové stabilizátory (příď v -Z). */
    fun bomb(): MeshData {
        val mb = MeshBuilder(160)
        val st = arrayOf(
            floatArrayOf(-1.15f, 0.08f, 0.08f, 0f),
            floatArrayOf(-0.9f, 0.3f, 0.3f, 0f),
            floatArrayOf(0.3f, 0.36f, 0.36f, 0f),
            floatArrayOf(1.0f, 0.12f, 0.12f, 0f),
        )
        mb.loftZ(st, 10) { _, _ -> floatArrayOf(0.24f, 0.27f, 0.19f) }
        mb.box(-0.55f, -0.03f, 0.75f, 0.55f, 0.03f, 1.3f, 0.2f, 0.22f, 0.16f)
        mb.box(-0.03f, -0.55f, 0.75f, 0.03f, 0.55f, 1.3f, 0.2f, 0.22f, 0.16f)
        mb.box(-0.37f, -0.05f, -0.3f, 0.37f, 0.05f, -0.2f, 0.75f, 0.65f, 0.15f)
        return mb.build()
    }

    /** Obláček kouře (jednotkový poloměr). */
    fun puff(dark: Boolean): MeshData {
        val mb = MeshBuilder(64)
        val c = if (dark) 0.2f else 0.78f
        mb.blob(0f, 0f, 0f, 1f, 1f, 6, c, c, c * 1.02f)
        return mb.build()
    }

    /** Plamen hořících trosek (svítí). */
    fun flame(): MeshData {
        val mb = MeshBuilder(64)
        for (k in 0 until 6) {
            val a0 = (k * PI / 3).toFloat()
            val a1 = ((k + 1) * PI / 3).toFloat()
            mb.triangle(
                kotlin.math.cos(a1), 0f, kotlin.math.sin(a1), kotlin.math.cos(a0), 0f, kotlin.math.sin(a0), 0f, 2.4f, 0f,
                1f, 0.55f, 0.12f, 0.7f,
            )
            mb.triangle(
                kotlin.math.cos(a1) * 0.5f, 0f, kotlin.math.sin(a1) * 0.5f, kotlin.math.cos(a0) * 0.5f, 0f, kotlin.math.sin(a0) * 0.5f,
                0f, 1.6f, 0f, 1f, 0.9f, 0.4f, 0.7f,
            )
        }
        return mb.build()
    }

    /** Zaměřovač v rovině XY: kruh, tečka uprostřed, čtyři čárky a „křidélka". Bílý – barví se v shaderu. */
    fun reticle(): MeshData {
        val mb = MeshBuilder(256)
        val seg = 40
        for (k in 0 until seg) {
            val a0 = (2 * PI * k / seg).toFloat()
            val a1 = (2 * PI * (k + 1) / seg).toFloat()
            val c0 = kotlin.math.cos(a0); val s0 = kotlin.math.sin(a0)
            val c1 = kotlin.math.cos(a1); val s1 = kotlin.math.sin(a1)
            mb.quad(c0 * 0.94f, s0 * 0.94f, 0f, c0, s0, 0f, c1, s1, 0f, c1 * 0.94f, s1 * 0.94f, 0f, 1f, 1f, 1f)
        }
        fun bar(x0: Float, y0: Float, x1: Float, y1: Float) = mb.quad(x0, y0, 0f, x1, y0, 0f, x1, y1, 0f, x0, y1, 0f, 1f, 1f, 1f)
        bar(-0.05f, -0.05f, 0.05f, 0.05f)
        bar(-0.03f, 1.12f, 0.03f, 1.5f)
        bar(-0.03f, -1.5f, 0.03f, -1.12f)
        bar(1.12f, -0.03f, 1.5f, 0.03f)
        bar(-1.5f, -0.03f, -1.12f, 0.03f)
        bar(-2.4f, -0.03f, -1.8f, 0.03f)
        bar(1.8f, -0.03f, 2.4f, 0.03f)
        bar(-1.83f, -0.3f, -1.77f, 0.03f)
        bar(1.77f, -0.3f, 1.83f, 0.03f)
        return mb.build()
    }

    /** Kruh k průletu: prstenec v rovině XY o poloměru 1 (barví se v shaderu, svítí). */
    fun ring(): MeshData {
        val mb = MeshBuilder(512)
        val seg = 48
        val t = 0.075f
        for (k in 0 until seg) {
            val a0 = (2 * PI * k / seg).toFloat()
            val a1 = (2 * PI * (k + 1) / seg).toFloat()
            val c0 = kotlin.math.cos(a0); val s0 = kotlin.math.sin(a0)
            val c1 = kotlin.math.cos(a1); val s1 = kotlin.math.sin(a1)
            // čelní, zadní, vnější a vnitřní plocha prstence
            for (z in floatArrayOf(-t, t)) {
                mb.quad(c0 * (1 - t), s0 * (1 - t), z, c0 * (1 + t), s0 * (1 + t), z, c1 * (1 + t), s1 * (1 + t), z, c1 * (1 - t), s1 * (1 - t), z, 1f, 1f, 1f)
            }
            mb.quad(c0 * (1 + t), s0 * (1 + t), -t, c0 * (1 + t), s0 * (1 + t), t, c1 * (1 + t), s1 * (1 + t), t, c1 * (1 + t), s1 * (1 + t), -t, 1f, 1f, 1f)
            mb.quad(c0 * (1 - t), s0 * (1 - t), -t, c1 * (1 - t), s1 * (1 - t), -t, c1 * (1 - t), s1 * (1 - t), t, c0 * (1 - t), s0 * (1 - t), t, 1f, 1f, 1f)
        }
        return mb.build()
    }

    /** Zlaté ozubené kolo v rovině XY (svítí), poloměr ~6 m. */
    fun gear(): MeshData {
        val mb = MeshBuilder(512)
        val teeth = 12
        val r0 = 4.6f
        val r1 = 6f
        val t = 0.7f
        val gold = floatArrayOf(1f, 0.78f, 0.25f)
        val seg = teeth * 2
        for (k in 0 until seg) {
            val a0 = (2 * PI * k / seg).toFloat()
            val a1 = (2 * PI * (k + 1) / seg).toFloat()
            val c0 = kotlin.math.cos(a0); val s0 = kotlin.math.sin(a0)
            val c1 = kotlin.math.cos(a1); val s1 = kotlin.math.sin(a1)
            // věnec kola s otvorem uprostřed
            for (z in floatArrayOf(-t, t)) {
                mb.quad(c0 * 1.6f, s0 * 1.6f, z, c0 * r0, s0 * r0, z, c1 * r0, s1 * r0, z, c1 * 1.6f, s1 * 1.6f, z, gold[0], gold[1], gold[2], 0.75f)
            }
            mb.quad(c0 * r0, s0 * r0, -t, c0 * r0, s0 * r0, t, c1 * r0, s1 * r0, t, c1 * r0, s1 * r0, -t, gold[0] * 0.8f, gold[1] * 0.8f, gold[2] * 0.8f, 0.75f)
            mb.quad(c0 * 1.6f, s0 * 1.6f, -t, c1 * 1.6f, s1 * 1.6f, -t, c1 * 1.6f, s1 * 1.6f, t, c0 * 1.6f, s0 * 1.6f, t, 0.6f, 0.45f, 0.15f, 0.75f)
            if (k % 2 == 0) {
                // zub
                val m0 = a0 + (a1 - a0) * 0.12f
                val m1 = a1 - (a1 - a0) * 0.12f
                val q0c = kotlin.math.cos(m0); val q0s = kotlin.math.sin(m0)
                val q1c = kotlin.math.cos(m1); val q1s = kotlin.math.sin(m1)
                mb.hexa8(
                    q0c * r0, q0s * r0, -t, q0c * r1, q0s * r1, -t, q0c * r1, q0s * r1, t, q0c * r0, q0s * r0, t,
                    q1c * r0, q1s * r0, -t, q1c * r1, q1s * r1, -t, q1c * r1, q1s * r1, t, q1c * r0, q1s * r0, t,
                    gold[0], gold[1], gold[2], 0.75f,
                )
            }
        }
        // paprsky
        for (k in 0 until 4) {
            val a = (PI / 4 + k * PI / 2).toFloat()
            mb.bladeZ(a, 0.8f, r0, 0.9f, 1.2f, gold[0] * 0.9f, gold[1] * 0.9f, gold[2] * 0.9f)
        }
        return mb.build()
    }

    /** Železná dělová koule. */
    fun shell(): MeshData {
        val mb = MeshBuilder(64)
        mb.puff(0f, -0.35f, 0f, 0.55f, 0.55f, 0.18f)
        mb.blob(0f, -0.35f, 0f, 0.5f, 0.42f, 6, 0.16f, 0.16f, 0.17f)
        return mb.build()
    }

    /** Kosočtverečná značka cíle (billboard). */
    fun diamond(): MeshData {
        val mb = MeshBuilder(32)
        mb.quad(0f, -1f, 0f, 0.7f, 0f, 0f, 0f, 1f, 0f, -0.7f, 0f, 0f, 1f, 1f, 1f)
        mb.quad(0f, -0.55f, 0.01f, 0.38f, 0f, 0.01f, 0f, 0.55f, 0.01f, -0.38f, 0f, 0.01f, 0.15f, 0.15f, 0.15f)
        mb.quad(0f, -0.38f, 0.02f, 0.26f, 0f, 0.02f, 0f, 0.38f, 0.02f, -0.26f, 0f, 0.02f, 1f, 1f, 1f)
        return mb.build()
    }

    /** Pozemní a vzdušné terče (viz [TargetStyle]). Střed spodku v počátku. */
    fun target(style: Int): MeshData {
        val mb = MeshBuilder(256)
        when (style) {
            TargetStyle.RADAR -> {
                mb.box(-4f, 0f, -4f, 4f, 3f, 4f, 0.35f, 0.4f, 0.3f, bottom = false)
                mb.box(-0.4f, 3f, -0.4f, 0.4f, 9f, 0.4f, 0.5f, 0.5f, 0.52f, bottom = false)
                mb.hexa8(
                    -5f, 8f, -0.5f, 5f, 8f, -0.5f, 5f, 8f, 0.4f, -5f, 8f, 0.4f,
                    -5f, 13f, -1.6f, 5f, 13f, -1.6f, 5f, 13f, -0.7f, -5f, 13f, -0.7f,
                    0.85f, 0.85f, 0.88f,
                )
                mb.box(-0.6f, 13f, -1.4f, 0.6f, 14f, -0.6f, 0.9f, 0.15f, 0.12f, 0.7f, 0.9f, 0.15f, 0.12f)
            }
            TargetStyle.FUEL -> {
                for ((i, x) in floatArrayOf(-8f, 0f, 8f).withIndex()) {
                    mb.cylinderY(x, 0f, 0f, 3.6f, 9f, 3.6f, 10, 0.88f, 0.88f, 0.86f)
                    mb.cylinderY(x, 9f, 0f, 3.6f, 10.2f, 1.5f, 10, if (i == 1) 0.85f else 0.7f, 0.18f, 0.12f)
                }
                mb.box(-11f, 0f, -0.5f, 11f, 1f, 0.5f, 0.35f, 0.35f, 0.37f, bottom = false)
            }
            TargetStyle.TANK -> {
                val o = floatArrayOf(0.33f, 0.37f, 0.22f)
                mb.box(-1.8f, 0f, -3.4f, 1.8f, 1.5f, 3.4f, o[0], o[1], o[2], bottom = false)
                mb.box(-2.1f, 0f, -3.2f, -1.6f, 1.1f, 3.2f, 0.12f, 0.12f, 0.12f, bottom = false)
                mb.box(1.6f, 0f, -3.2f, 2.1f, 1.1f, 3.2f, 0.12f, 0.12f, 0.12f, bottom = false)
                mb.box(-1.2f, 1.5f, -1.4f, 1.2f, 2.5f, 1.4f, o[0] * 0.9f, o[1] * 0.9f, o[2] * 0.9f, bottom = false)
                mb.box(-0.15f, 1.85f, -5.6f, 0.15f, 2.15f, -1.2f, 0.2f, 0.2f, 0.18f)
            }
            TargetStyle.FACTORY -> {
                val brick = floatArrayOf(0.62f, 0.32f, 0.24f)
                mb.box(-22f, 0f, -14f, 22f, 12f, 14f, brick[0], brick[1], brick[2], 1f, 0.3f, 0.3f, 0.32f, bottom = false)
                mb.box(-22f, 12f, -14f, -8f, 17f, 14f, brick[0] * 0.9f, brick[1] * 0.9f, brick[2] * 0.9f, 1f, 0.3f, 0.3f, 0.32f, bottom = false)
                for (x in floatArrayOf(4f, 14f)) {
                    mb.cylinderY(x, 12f, 6f, 2.2f, 42f, 1.6f, 8, 0.55f, 0.3f, 0.24f)
                    mb.cylinderY(x, 42f, 6f, 1.7f, 43.5f, 1.7f, 8, 0.15f, 0.15f, 0.15f)
                }
                for (z in floatArrayOf(-17f, 17f)) {
                    mb.cylinderY(-14f, 0f, z, 3f, 8f, 3f, 8, 0.75f, 0.75f, 0.72f)
                }
            }
            else -> {
                // terčový balon: červenobílá koule na laně
                mb.puff(0f, 2f, 0f, 7f, 7.5f, 1f)
                mb.blob(0f, 9.5f, 0f, 7.3f, 1.2f, 10, 0.95f, 0.95f, 0.95f)
                mb.blob(0f, 4f, 0f, 7.3f, 1.2f, 10, 0.9f, 0.12f, 0.1f)
                mb.box(-0.05f, -12f, -0.05f, 0.05f, 2f, 0.05f, 0.3f, 0.3f, 0.3f)
            }
        }
        return mb.build()
    }

    /** Heliport nemocnice: bílý kruh s červeným křížem (y = 0). */
    fun helipad(): MeshData {
        val mb = MeshBuilder(128)
        val seg = 24
        for (k in 0 until seg) {
            val a0 = (2 * PI * k / seg).toFloat()
            val a1 = (2 * PI * (k + 1) / seg).toFloat()
            mb.triangle(0f, 0f, 0f, kotlin.math.cos(a1) * 12f, 0f, kotlin.math.sin(a1) * 12f,
                kotlin.math.cos(a0) * 12f, 0f, kotlin.math.sin(a0) * 12f, 0.92f, 0.92f, 0.9f)
        }
        mb.box(-1.6f, 0.02f, -6f, 1.6f, 0.06f, 6f, 0.85f, 0.1f, 0.1f)
        mb.box(-6f, 0.02f, -1.6f, 6f, 0.06f, 1.6f, 0.85f, 0.1f, 0.1f)
        return mb.build()
    }

    /** Tři lopatky elektrárny v rovině XY (osa otáčení Z) a náboj. */
    fun turbineBlades(radius: Float): MeshData {
        val mb = MeshBuilder(128)
        for (i in 0 until 3) {
            val a = (2 * PI * i / 3).toFloat()
            mb.bladeZ(a, 1f, radius * 0.35f, 2.6f, 0.5f, 0.93f, 0.93f, 0.93f)
            mb.bladeZ(a, radius * 0.35f, radius, 1.5f, 0.35f, 0.93f, 0.93f, 0.93f)
        }
        mb.box(-1.4f, -1.4f, -1.6f, 1.4f, 1.4f, 1.2f, 0.9f, 0.9f, 0.9f)
        return mb.build()
    }
}
