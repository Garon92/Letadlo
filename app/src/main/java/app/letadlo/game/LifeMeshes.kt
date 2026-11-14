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
