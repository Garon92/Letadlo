package app.letadlo.game

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Kvádr (budova) – zároveň slouží jako kolizní těleso. */
class Box(
    val minX: Float, val minY: Float, val minZ: Float,
    val maxX: Float, val maxY: Float, val maxZ: Float,
    val r: Float, val g: Float, val b: Float,
    val windows: Boolean,
)

class Tree(
    val x: Float, val y: Float, val z: Float,
    val height: Float, val radius: Float,
    val conifer: Boolean, val shade: Float,
)

enum class ObstacleKind { BUILDING, TREE }

/** Vše, co je potřeba pro vykreslení a kolize jednoho čtverce mapy. */
class ChunkContent(
    val cx: Int,
    val cz: Int,
    /** Výšky mřížky (CELLS+1)×(CELLS+1). */
    val heights: FloatArray,
    /** Normály pro každý vrchol mřížky (xyz). */
    val normals: FloatArray,
    /** Barvy vrcholů (rgba, a = 0 znamená pole). */
    val colors: FloatArray,
    val buildings: List<Box>,
    val trees: List<Tree>,
) {
    fun h(i: Int, j: Int): Float = heights[j * World.VERTS + i]
}

/**
 * Nekonečný procedurální svět. Terén je dán čistou funkcí výšky, takže kolize
 * i vykreslování používají přesně stejná data (stejná triangulace mřížky).
 */
class World(val map: MapType) {

    companion object {
        const val CELL = 20f
        const val CELLS = 32
        const val VERTS = CELLS + 1
        const val CHUNK = CELL * CELLS

        const val RUNWAY_HALF_WIDTH = 25f
        const val RUNWAY_HALF_LENGTH = 1000f
        const val START_Z = 930f
        const val SEA_LEVEL = 0f

        const val CITY_X = 900f
        const val CITY_Z = -5200f
        const val CITY_RADIUS = 3200f
        const val CITY_HEIGHT = 30f
        const val BLOCK = 150f
        const val ROAD = 22f

        private const val TREE_GRID = 40f
        private const val TREES_PER_SIDE = 16 // CHUNK / TREE_GRID

        fun key(cx: Int, cz: Int): Long = (cx.toLong() shl 32) or (cz.toLong() and 0xffffffffL)
        fun chunkOf(v: Float): Int = floor(v / CHUNK).toInt()

        fun smoothstep(e0: Float, e1: Float, x: Float): Float {
            val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }

        fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

        /** Interpolace přesně podle triangulace mřížky (úhlopříčka 10–01). */
        fun triInterp(h00: Float, h10: Float, h01: Float, h11: Float, fx: Float, fz: Float): Float =
            if (fx + fz <= 1f) h00 + (h10 - h00) * fx + (h01 - h00) * fz
            else h11 + (h01 - h11) * (1f - fx) + (h10 - h11) * (1f - fz)

        fun riverX(z: Float): Float = 2600f + 700f * sin(z / 1700f) + 300f * sin(z / 650f + 1f)
    }

    val airportHeight: Float = when (map) {
        MapType.MOUNTAINS -> 220f
        MapType.CITY -> CITY_HEIGHT
        MapType.COUNTRYSIDE -> 70f
    }

    val hasCity: Boolean = map == MapType.CITY

    private val cache = ConcurrentHashMap<Long, ChunkContent>()

    // ------------------------------------------------------------------ výška

    fun airportWeight(x: Float, z: Float): Float {
        val dx = max(abs(x) - 160f, 0f)
        val dz = max(abs(z) - 1150f, 0f)
        val d = sqrt(dx * dx + dz * dz)
        return 1f - smoothstep(0f, 700f, d)
    }

    fun rawHeight(x: Float, z: Float): Float {
        val h = when (map) {
            MapType.MOUNTAINS -> mountainsHeight(x, z)
            MapType.CITY -> cityTerrainHeight(x, z)
            MapType.COUNTRYSIDE -> countrysideHeight(x, z)
        }
        val w = airportWeight(x, z)
        return if (w <= 0f) h else lerp(h, airportHeight, w)
    }

    private fun mountainsHeight(x: Float, z: Float): Float {
        val base = 200f + Noise.fbm(x / 3500f, z / 3500f, 4, 11) * 150f
        // Údolí podél osy dráhy, dál od letiště se začne vlnit a nakonec zmizí.
        val wind = Noise.fbm(z / 5000f, 0.37f, 2, 5) * 1100f * smoothstep(1500f, 5000f, abs(z))
        val dx = abs(x - wind)
        val valley = max(smoothstep(260f, 1700f, dx), smoothstep(9000f, 16000f, abs(z)))
        val ridge = Noise.ridged(x / 2600f, z / 2600f, 5, 23)
        val massif = 0.55f + 0.45f * Noise.fbm(x / 9000f, z / 9000f, 2, 31)
        val peaks = ridge.pow(1.45f) * 2400f * massif
        return base + peaks * valley
    }

    private fun cityTerrainHeight(x: Float, z: Float): Float {
        val dcx = x - CITY_X
        val dcz = z - CITY_Z
        val dc = sqrt(dcx * dcx + dcz * dcz)
        var h = 35f + Noise.fbm(x / 3000f, z / 3000f, 4, 41) * 60f
        h += Noise.ridged(x / 4000f, z / 4000f, 4, 43).pow(2f) * 520f * smoothstep(5000f, 12000f, dc)
        h = lerp(CITY_HEIGHT, h, smoothstep(CITY_RADIUS, CITY_RADIUS + 1200f, dc))
        val d = abs(x - riverX(z))
        return lerp(-7f, h, smoothstep(55f, 130f, d))
    }

    private fun countrysideHeight(x: Float, z: Float): Float {
        var h = 38f + Noise.fbm(x / 2600f, z / 2600f, 5, 61) * 170f
        h += Noise.fbm(x / 700f, z / 700f, 3, 67) * 18f
        h += max(0f, Noise.fbm(x / 6000f, z / 6000f, 3, 71)) * 420f
        return h
    }

    /** Výška povrchu přesně tak, jak je vykreslena (lineárně v trojúhelnících mřížky). */
    fun groundHeight(x: Float, z: Float): Float {
        val gx = floor(x / CELL)
        val gz = floor(z / CELL)
        val fx = x / CELL - gx
        val fz = z / CELL - gz
        val ix = gx.toInt()
        val iz = gz.toInt()
        val c = cache[key(Math.floorDiv(ix, CELLS), Math.floorDiv(iz, CELLS))]
        if (c != null) {
            val li = ix - c.cx * CELLS
            val lj = iz - c.cz * CELLS
            return triInterp(c.h(li, lj), c.h(li + 1, lj), c.h(li, lj + 1), c.h(li + 1, lj + 1), fx, fz)
        }
        val x0 = ix * CELL
        val z0 = iz * CELL
        val x1 = (ix + 1) * CELL
        val z1 = (iz + 1) * CELL
        return triInterp(rawHeight(x0, z0), rawHeight(x1, z0), rawHeight(x0, z1), rawHeight(x1, z1), fx, fz)
    }

    fun isWater(x: Float, z: Float): Boolean = groundHeight(x, z) < SEA_LEVEL

    fun onRunway(x: Float, z: Float): Boolean =
        abs(x) <= RUNWAY_HALF_WIDTH + 6f && abs(z) <= RUNWAY_HALF_LENGTH + 20f

    fun distToCity(x: Float, z: Float): Float {
        val dx = x - CITY_X
        val dz = z - CITY_Z
        return sqrt(dx * dx + dz * dz)
    }

    // ------------------------------------------------------------------ vzhled

    fun forestMask(x: Float, z: Float): Float {
        if (airportWeight(x, z) > 0f) return 0f
        if (abs(x) < 240f && abs(z) < 3500f) return 0f // volný koridor pro vzlet
        val f = Noise.fbm(x / 1400f, z / 1400f, 3, 91)
        return when (map) {
            MapType.MOUNTAINS -> smoothstep(-0.08f, 0.08f, f)
            MapType.COUNTRYSIDE -> smoothstep(0.12f, 0.22f, f)
            MapType.CITY -> if (distToCity(x, z) < CITY_RADIUS + 400f) 0f else smoothstep(0.10f, 0.20f, f)
        }
    }

    private fun groundColor(x: Float, z: Float, h: Float, ny: Float, out: FloatArray, o: Int) {
        val v = Noise.fbm(x / 350f, z / 350f, 2, 5) * 0.5f + 0.5f
        val forest = forestMask(x, z)
        var r = lerp(0.36f, 0.29f, v)
        var g = lerp(0.53f, 0.45f, v)
        var b = lerp(0.22f, 0.17f, v)
        // les = tmavší podklad
        r = lerp(r, 0.17f, forest * 0.8f)
        g = lerp(g, 0.31f, forest * 0.8f)
        b = lerp(b, 0.12f, forest * 0.8f)
        var a = 1f

        when (map) {
            MapType.MOUNTAINS -> {
                val alpine = smoothstep(800f, 1150f, h)
                r = lerp(r, 0.47f, alpine * 0.6f)
                g = lerp(g, 0.50f, alpine * 0.6f)
                b = lerp(b, 0.30f, alpine * 0.6f)
                val rock = max(smoothstep(0.86f, 0.66f, ny), smoothstep(1100f, 1450f, h))
                r = lerp(r, 0.47f + v * 0.05f, rock)
                g = lerp(g, 0.44f + v * 0.04f, rock)
                b = lerp(b, 0.41f, rock)
                val snow = smoothstep(1250f, 1500f, h + v * 180f) * smoothstep(0.45f, 0.7f, ny)
                r = lerp(r, 0.95f, snow)
                g = lerp(g, 0.96f, snow)
                b = lerp(b, 0.99f, snow)
            }
            MapType.CITY -> {
                if (distToCity(x, z) < CITY_RADIUS + 60f && abs(h - CITY_HEIGHT) < 1f) {
                    r = 0.55f; g = 0.55f; b = 0.54f
                }
            }
            MapType.COUNTRYSIDE -> {
                val fields = Noise.fbm(x / 2200f, z / 2200f, 2, 77)
                if (forest < 0.25f && ny > 0.94f && h > SEA_LEVEL + 4f && fields > -0.3f &&
                    airportWeight(x, z) <= 0f
                ) a = 0f
            }
        }

        // písek u vody, dno pod vodou
        val sand = smoothstep(4f, 0.5f, h - SEA_LEVEL)
        r = lerp(r, 0.68f, sand)
        g = lerp(g, 0.62f, sand)
        b = lerp(b, 0.46f, sand)
        if (sand > 0.5f) a = 1f

        out[o] = r
        out[o + 1] = g
        out[o + 2] = b
        out[o + 3] = a
    }

    // ------------------------------------------------------------------ chunky

    fun content(cx: Int, cz: Int): ChunkContent {
        val k = key(cx, cz)
        cache[k]?.let { return it }
        val c = generate(cx, cz)
        return cache.putIfAbsent(k, c) ?: c
    }

    fun hasContent(cx: Int, cz: Int): Boolean = cache.containsKey(key(cx, cz))

    /** Uvolní data chunků, které jsou dál než [radius] od středu. */
    fun evictFar(centerCx: Int, centerCz: Int, radius: Int) {
        val it = cache.entries.iterator()
        while (it.hasNext()) {
            val c = it.next().value
            if (abs(c.cx - centerCx) > radius || abs(c.cz - centerCz) > radius) it.remove()
        }
    }

    private fun generate(cx: Int, cz: Int): ChunkContent {
        val b = VERTS + 2 // mřížka s okrajem pro výpočet normál
        val hb = FloatArray(b * b)
        for (j in 0 until b) {
            val gz = cz * CELLS + j - 1
            for (i in 0 until b) {
                val gx = cx * CELLS + i - 1
                hb[j * b + i] = rawHeight(gx * CELL, gz * CELL)
            }
        }
        val heights = FloatArray(VERTS * VERTS)
        val normals = FloatArray(VERTS * VERTS * 3)
        val colors = FloatArray(VERTS * VERTS * 4)
        for (j in 0 until VERTS) {
            for (i in 0 until VERTS) {
                val hc = hb[(j + 1) * b + i + 1]
                val hl = hb[(j + 1) * b + i]
                val hr = hb[(j + 1) * b + i + 2]
                val hd = hb[j * b + i + 1]
                val hu = hb[(j + 2) * b + i + 1]
                var nx = hl - hr
                var ny = 2f * CELL
                var nz = hd - hu
                val len = sqrt(nx * nx + ny * ny + nz * nz)
                nx /= len; ny /= len; nz /= len
                val idx = j * VERTS + i
                heights[idx] = hc
                normals[idx * 3] = nx
                normals[idx * 3 + 1] = ny
                normals[idx * 3 + 2] = nz
                val gx = cx * CELLS + i
                val gz = cz * CELLS + j
                groundColor(gx * CELL, gz * CELL, hc, ny, colors, idx * 4)
            }
        }

        val trees = ArrayList<Tree>()
        val buildings = ArrayList<Box>()
        if (hasCity) placeCity(cx, cz, heights, buildings, trees)
        placeTrees(cx, cz, heights, normals, trees)
        return ChunkContent(cx, cz, heights, normals, colors, buildings, trees)
    }

    private fun localHeight(heights: FloatArray, cx: Int, cz: Int, x: Float, z: Float): Float {
        val lx = (x - cx * CHUNK) / CELL
        val lz = (z - cz * CHUNK) / CELL
        val i = floor(lx).toInt().coerceIn(0, CELLS - 1)
        val j = floor(lz).toInt().coerceIn(0, CELLS - 1)
        val fx = (lx - i).coerceIn(0f, 1f)
        val fz = (lz - j).coerceIn(0f, 1f)
        return triInterp(
            heights[j * VERTS + i], heights[j * VERTS + i + 1],
            heights[(j + 1) * VERTS + i], heights[(j + 1) * VERTS + i + 1], fx, fz,
        )
    }

    private fun placeTrees(cx: Int, cz: Int, heights: FloatArray, normals: FloatArray, out: MutableList<Tree>) {
        for (j in 0 until TREES_PER_SIDE) {
            for (i in 0 until TREES_PER_SIDE) {
                val gx = cx * TREES_PER_SIDE + i
                val gz = cz * TREES_PER_SIDE + j
                val x = (gx + 0.15f + 0.7f * Noise.hash01(gx, gz, 1)) * TREE_GRID
                val z = (gz + 0.15f + 0.7f * Noise.hash01(gx, gz, 2)) * TREE_GRID
                val chance = Noise.hash01(gx, gz, 3)
                var f = forestMask(x, z) * 0.85f
                if (map == MapType.COUNTRYSIDE && f < 0.05f && airportWeight(x, z) <= 0f &&
                    !(abs(x) < 240f && abs(z) < 3500f)
                ) f = 0.025f
                if (chance >= f) continue
                val h = localHeight(heights, cx, cz, x, z)
                if (h < SEA_LEVEL + 1.5f) continue
                val vi = ((x - cx * CHUNK) / CELL).toInt().coerceIn(0, CELLS)
                val vj = ((z - cz * CHUNK) / CELL).toInt().coerceIn(0, CELLS)
                val ny = normals[(vj * VERTS + vi) * 3 + 1]
                if (ny < 0.72f) continue
                val r2 = Noise.hash01(gx, gz, 4)
                if (map == MapType.MOUNTAINS && h > 1080f + r2 * 120f) continue
                val conifer = when (map) {
                    MapType.MOUNTAINS -> true
                    MapType.COUNTRYSIDE -> r2 < 0.3f
                    MapType.CITY -> r2 < 0.4f
                }
                val height = if (conifer) 12f + 10f * Noise.hash01(gx, gz, 5) else 9f + 7f * Noise.hash01(gx, gz, 5)
                val radius = if (conifer) height * 0.27f else height * 0.36f
                out.add(Tree(x, h - 0.5f, z, height, radius, conifer, 0.8f + 0.4f * Noise.hash01(gx, gz, 6)))
            }
        }
    }

    private fun placeCity(cx: Int, cz: Int, heights: FloatArray, buildings: MutableList<Box>, trees: MutableList<Tree>) {
        val x0 = cx * CHUNK
        val z0 = cz * CHUNK
        // chunk vlastní bloky, jejichž roh leží uvnitř něj
        val bxStart = ceil((x0 - CITY_X) / BLOCK).toInt()
        val bzStart = ceil((z0 - CITY_Z) / BLOCK).toInt()
        var bz = bzStart
        while (CITY_Z + bz * BLOCK < z0 + CHUNK) {
            var bx = bxStart
            while (CITY_X + bx * BLOCK < x0 + CHUNK) {
                placeBlock(bx, bz, buildings, trees)
                bx++
            }
            bz++
        }
    }

    private val facadePalette = arrayOf(
        floatArrayOf(0.78f, 0.77f, 0.73f),
        floatArrayOf(0.84f, 0.78f, 0.64f),
        floatArrayOf(0.64f, 0.38f, 0.30f),
        floatArrayOf(0.90f, 0.90f, 0.88f),
        floatArrayOf(0.62f, 0.64f, 0.66f),
        floatArrayOf(0.86f, 0.70f, 0.52f),
    )
    private val glassPalette = arrayOf(
        floatArrayOf(0.42f, 0.58f, 0.70f),
        floatArrayOf(0.30f, 0.38f, 0.46f),
        floatArrayOf(0.55f, 0.66f, 0.72f),
        floatArrayOf(0.36f, 0.50f, 0.52f),
    )

    private fun placeBlock(bx: Int, bz: Int, buildings: MutableList<Box>, trees: MutableList<Tree>) {
        val minX = CITY_X + bx * BLOCK + ROAD + 4f
        val minZ = CITY_Z + bz * BLOCK + ROAD + 4f
        val inner = BLOCK - ROAD - 8f
        val centerX = minX + inner / 2f
        val centerZ = minZ + inner / 2f
        val dc = distToCity(centerX, centerZ)
        if (dc > CITY_RADIUS - 120f) return
        if (abs(centerX - riverX(centerZ)) < 210f) return
        if (airportWeight(centerX, centerZ) > 0f) return
        val t = 1f - dc / CITY_RADIUS
        val ground = CITY_HEIGHT

        // park
        if (Noise.hash01(bx, bz, 7) < 0.07f) {
            for (j in 0 until 4) for (i in 0 until 4) {
                val tx = minX + 15f + i * 30f + (Noise.hash01(bx * 4 + i, bz * 4 + j, 8) - 0.5f) * 10f
                val tz = minZ + 15f + j * 30f + (Noise.hash01(bx * 4 + i, bz * 4 + j, 9) - 0.5f) * 10f
                val th = 8f + 6f * Noise.hash01(bx * 4 + i, bz * 4 + j, 10)
                trees.add(Tree(tx, ground - 0.5f, tz, th, th * 0.38f, false, 0.9f))
            }
            return
        }

        val single = t > 0.55f && Noise.hash01(bx, bz, 11) < 0.4f
        val n = if (single) 1 else 2
        val lot = inner / n
        for (j in 0 until n) for (i in 0 until n) {
            val sx = bx * 2 + i
            val sz = bz * 2 + j
            val r1 = Noise.hash01(sx, sz, 12)
            val r2 = Noise.hash01(sx, sz, 13)
            val r3 = Noise.hash01(sx, sz, 14)
            val r4 = Noise.hash01(sx, sz, 15)
            val mx = 3f + 8f * r1
            val mz = 3f + 8f * r2
            val lx0 = minX + i * lot + mx
            val lz0 = minZ + j * lot + mz
            val lx1 = minX + (i + 1) * lot - mx
            val lz1 = minZ + (j + 1) * lot - mz
            val tall = t.pow(2.2f) * 240f * (0.3f + 0.7f * r3)
            val height = 8f + 16f * r4 + tall
            val color = if (height > 70f) glassPalette[(r1 * glassPalette.size).toInt().coerceAtMost(glassPalette.size - 1)]
            else facadePalette[(r2 * facadePalette.size).toInt().coerceAtMost(facadePalette.size - 1)]
            buildings.add(Box(lx0, ground - 2f, lz0, lx1, ground + height, lz1, color[0], color[1], color[2], true))
            if (height > 60f && r3 < 0.55f) {
                val insetX = (lx1 - lx0) * (0.15f + 0.1f * r1)
                val insetZ = (lz1 - lz0) * (0.15f + 0.1f * r2)
                val topH = height * (0.18f + 0.15f * r4)
                buildings.add(
                    Box(
                        lx0 + insetX, ground + height, lz0 + insetZ,
                        lx1 - insetX, ground + height + topH, lz1 - insetZ,
                        color[0] * 0.95f, color[1] * 0.95f, color[2] * 0.95f, true,
                    )
                )
            }
        }
    }

    // ------------------------------------------------------------------ kolize

    /** Letištní budovy vedle dráhy (hangáry, věž, terminál). */
    val airportBoxes: List<Box> = run {
        val g = airportHeight
        listOf(
            Box(-135f, g - 1f, 380f, -80f, g + 11f, 430f, 0.62f, 0.64f, 0.66f, false),
            Box(-135f, g - 1f, 445f, -80f, g + 11f, 495f, 0.62f, 0.64f, 0.66f, false),
            Box(-150f, g - 1f, 90f, -95f, g + 9f, 190f, 0.86f, 0.84f, 0.80f, true),
            Box(-112f, g - 1f, 230f, -102f, g + 24f, 240f, 0.88f, 0.88f, 0.86f, false),
            Box(-114f, g + 24f, 228f, -100f, g + 29f, 242f, 0.30f, 0.42f, 0.52f, false),
            Box(-116f, g + 29f, 226f, -98f, g + 30f, 244f, 0.75f, 0.2f, 0.18f, false),
        )
    }

    /** Vrátí druh překážky, do které koule (x,y,z,r) narazila, jinak null. */
    fun hitObstacle(x: Float, y: Float, z: Float, r: Float): ObstacleKind? {
        // Nad nejvyššími možnými překážkami nemá smysl hledat.
        if (y - r > groundHeight(x, z) + 400f) return null
        if (abs(x) < 400f && abs(z) < 1300f) {
            for (bld in airportBoxes) {
                if (x + r > bld.minX && x - r < bld.maxX && y + r > bld.minY && y - r < bld.maxY &&
                    z + r > bld.minZ && z - r < bld.maxZ
                ) return ObstacleKind.BUILDING
            }
        }
        val cxa = chunkOf(x - BLOCK - 10f)
        val cxb = chunkOf(x + 10f)
        val cza = chunkOf(z - BLOCK - 10f)
        val czb = chunkOf(z + 10f)
        for (cz in cza..czb) for (cx in cxa..cxb) {
            val c = content(cx, cz)
            for (bld in c.buildings) {
                if (x + r > bld.minX && x - r < bld.maxX && y + r > bld.minY && y - r < bld.maxY &&
                    z + r > bld.minZ && z - r < bld.maxZ
                ) return ObstacleKind.BUILDING
            }
            for (t in c.trees) {
                val tr = t.radius * 0.7f + r
                if (abs(x - t.x) < tr && abs(z - t.z) < tr && y - r < t.y + t.height && y + r > t.y) {
                    return ObstacleKind.TREE
                }
            }
        }
        return null
    }

    /** Nejvyšší bod terénu i staveb pod (x,z) – pro kameru a stín. */
    fun surfaceHeight(x: Float, z: Float): Float = max(groundHeight(x, z), SEA_LEVEL)

    fun minHeightAround(x: Float, z: Float, r: Float): Float =
        min(min(groundHeight(x - r, z), groundHeight(x + r, z)), min(groundHeight(x, z - r), groundHeight(x, z + r)))
}
