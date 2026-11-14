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
) {
    /** Zničená budova – zbydou nízké ohořelé trosky. */
    @Volatile var destroyed = false

    val top: Float get() = if (destroyed) minY + 2f + min(7f, (maxY - minY) * 0.1f) else maxY
}

/** Druh drobného objektu v krajině. Kolize mají jen stromy a balvany. */
enum class PropKind(val collides: Boolean) {
    CONIFER(true), DECIDUOUS(true), BIRCH(true), BUSH(false), ROCK(true), COW(false), SHEEP(false),
}

class Tree(
    val x: Float, val y: Float, val z: Float,
    val height: Float, val radius: Float,
    val kind: PropKind, val shade: Float,
    val yaw: Float = 0f,
) {
    val conifer: Boolean get() = kind == PropKind.CONIFER

    /** Ohořelý strom po výbuchu. */
    @Volatile var burnt = false
}

/** Letadlo zaparkované u hangárů. Yaw: příď (-Z šablony) otočená pomocí Ry(yaw). */
class ParkedAircraft(val type: AircraftType, val x: Float, val z: Float, val yaw: Float)

/** Větrná elektrárna – věž s rotorem natočeným po větru. */
class Turbine(val x: Float, val y: Float, val z: Float, val hubHeight: Float, val rotorRadius: Float, val phase: Float)

/** Domek se sedlovou střechou (vesnice, předměstí). Hřeben vede podél X nebo Z. */
class House(
    val x: Float, val y: Float, val z: Float,
    val halfW: Float, val halfD: Float,
    val wallH: Float, val roofH: Float,
    val ridgeAlongX: Boolean,
    val wr: Float, val wg: Float, val wb: Float,
    val rr: Float, val rg: Float, val rb: Float,
) {
    @Volatile var destroyed = false

    val top: Float get() = if (destroyed) y + 1.5f else y + wallH + roofH * 0.6f
}

/** Co výbuch zničil: chunky k přestavění a místa, kde hoří trosky. */
class DamageReport(val chunks: List<Long>, val fires: List<FloatArray>, val anything: Boolean)

/** Jeden „chuchvalec" mraku (zploštělá koule). */
class Cloud(val x: Float, val y: Float, val z: Float, val rx: Float, val ry: Float, val shade: Float)

enum class ObstacleKind { BUILDING, TREE, TURBINE }

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
    val houses: List<House>,
    val clouds: List<Cloud>,
    val turbines: List<Turbine>,
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
        const val RUNWAY_HALF_LENGTH = 1500f
        const val START_Z = 1430f
        /** Volný koridor pro vzlet (bez stromů a vesnic). */
        const val CORRIDOR_Z = 4500f
        const val SEA_LEVEL = 0f

        const val CITY_X = 900f
        const val CITY_Z = -6200f
        const val CITY_RADIUS = 3200f
        const val CITY_HEIGHT = 30f
        const val BLOCK = 150f
        const val ROAD = 22f
        /** Kam až sahají ulice (za centrem jsou předměstí s domky). */
        const val CITY_ROAD_RADIUS = CITY_RADIUS + 850f
        const val VILLAGE_CELL = 2600f

        private const val TREE_GRID = 40f
        /** Směr větru – rotory elektráren se na něj natáčejí. */
        const val TURBINE_DIR_X = 0.8f
        const val TURBINE_DIR_Z = 0.6f
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
        val dz = max(abs(z) - (RUNWAY_HALF_LENGTH + 150f), 0f)
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
        h = lerp(CITY_HEIGHT, h, smoothstep(CITY_ROAD_RADIUS + 60f, CITY_ROAD_RADIUS + 1200f, dc))
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
        if (abs(x) < 240f && abs(z) < CORRIDOR_Z) return 0f // volný koridor pro vzlet
        val f = Noise.fbm(x / 1400f, z / 1400f, 3, 91)
        return when (map) {
            MapType.MOUNTAINS -> smoothstep(-0.08f, 0.08f, f)
            MapType.COUNTRYSIDE -> smoothstep(0.12f, 0.22f, f)
            MapType.CITY -> if (distToCity(x, z) < CITY_ROAD_RADIUS + 300f) 0f else smoothstep(0.10f, 0.20f, f)
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
                if (distToCity(x, z) < CITY_ROAD_RADIUS + 60f && abs(h - CITY_HEIGHT) < 1f) {
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

    /** Obsah chunku jen pokud už je spočítaný (nic negeneruje). */
    fun cachedContent(cx: Int, cz: Int): ChunkContent? = cache[key(cx, cz)]

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
        val houses = ArrayList<House>()
        val clouds = ArrayList<Cloud>()
        if (hasCity) placeCity(cx, cz, heights, buildings, trees, houses)
        placeVillages(cx, cz, houses)
        placeTrees(cx, cz, heights, normals, trees)
        placeClouds(cx, cz, clouds)
        val turbines = ArrayList<Turbine>()
        placeHerdsAndRocks(cx, cz, heights, normals, trees)
        placeTurbines(cx, cz, heights, normals, turbines)
        for (i in buildings.indices) if (destroyedIds.contains(objId(cx, cz, 0, i))) buildings[i].destroyed = true
        for (i in houses.indices) if (destroyedIds.contains(objId(cx, cz, 1, i))) houses[i].destroyed = true
        for (i in trees.indices) if (destroyedIds.contains(objId(cx, cz, 2, i))) trees[i].burnt = true
        return ChunkContent(cx, cz, heights, normals, colors, buildings, trees, houses, clouds, turbines)
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
                    !(abs(x) < 240f && abs(z) < CORRIDOR_Z)
                ) f = 0.025f
                if (chance >= f) continue
                if (inVillage(x, z, 18f)) continue
                val h = localHeight(heights, cx, cz, x, z)
                if (h < SEA_LEVEL + 1.5f) continue
                val vi = ((x - cx * CHUNK) / CELL).toInt().coerceIn(0, CELLS)
                val vj = ((z - cz * CHUNK) / CELL).toInt().coerceIn(0, CELLS)
                val ny = normals[(vj * VERTS + vi) * 3 + 1]
                if (ny < 0.72f) continue
                val r2 = Noise.hash01(gx, gz, 4)
                if (map == MapType.MOUNTAINS && h > 1080f + r2 * 120f) continue
                val forestEdge = f in 0.03f..0.3f
                val kind = when {
                    forestEdge && Noise.hash01(gx, gz, 7) < 0.35f -> PropKind.BUSH
                    map == MapType.MOUNTAINS -> if (h < 700f && r2 < 0.12f) PropKind.BIRCH else PropKind.CONIFER
                    map == MapType.COUNTRYSIDE -> when {
                        r2 < 0.28f -> PropKind.CONIFER
                        r2 < 0.48f -> PropKind.BIRCH
                        else -> PropKind.DECIDUOUS
                    }
                    else -> when {
                        r2 < 0.35f -> PropKind.CONIFER
                        r2 < 0.5f -> PropKind.BIRCH
                        else -> PropKind.DECIDUOUS
                    }
                }
                val hr = Noise.hash01(gx, gz, 5)
                val height = when (kind) {
                    PropKind.CONIFER -> 12f + 10f * hr
                    PropKind.BIRCH -> 11f + 7f * hr
                    PropKind.BUSH -> 2f + 2f * hr
                    else -> 9f + 7f * hr
                }
                val radius = when (kind) {
                    PropKind.CONIFER -> height * 0.27f
                    PropKind.BIRCH -> height * 0.22f
                    PropKind.BUSH -> height * 0.8f
                    else -> height * 0.36f
                }
                out.add(Tree(x, h - 0.5f, z, height, radius, kind, 0.8f + 0.4f * Noise.hash01(gx, gz, 6)))
            }
        }
    }

    private fun placeCity(
        cx: Int, cz: Int, heights: FloatArray,
        buildings: MutableList<Box>, trees: MutableList<Tree>, houses: MutableList<House>,
    ) {
        val x0 = cx * CHUNK
        val z0 = cz * CHUNK
        // chunk vlastní bloky, jejichž roh leží uvnitř něj
        val bxStart = ceil((x0 - CITY_X) / BLOCK).toInt()
        val bzStart = ceil((z0 - CITY_Z) / BLOCK).toInt()
        var bz = bzStart
        while (CITY_Z + bz * BLOCK < z0 + CHUNK) {
            var bx = bxStart
            while (CITY_X + bx * BLOCK < x0 + CHUNK) {
                placeBlock(bx, bz, buildings, trees, houses)
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

    private fun placeBlock(bx: Int, bz: Int, buildings: MutableList<Box>, trees: MutableList<Tree>, houses: MutableList<House>) {
        val minX = CITY_X + bx * BLOCK + ROAD + 4f
        val minZ = CITY_Z + bz * BLOCK + ROAD + 4f
        val inner = BLOCK - ROAD - 8f
        val centerX = minX + inner / 2f
        val centerZ = minZ + inner / 2f
        val dc = distToCity(centerX, centerZ)
        if (dc > CITY_ROAD_RADIUS - 90f) return
        if (abs(centerX - riverX(centerZ)) < 210f) return
        if (airportWeight(centerX, centerZ) > 0f) return
        if (dc > CITY_RADIUS - 120f) {
            // předměstí: rodinné domky na menších parcelách
            for (j in 0 until 3) for (i in 0 until 3) {
                if (i == 1 && j == 1) continue
                val hx = bx * 3 + i
                val hz = bz * 3 + j
                if (Noise.hash01(hx, hz, 40) < 0.18f) continue
                val px = minX + 20f + i * 40f + (Noise.hash01(hx, hz, 41) - 0.5f) * 6f
                val pz = minZ + 20f + j * 40f + (Noise.hash01(hx, hz, 42) - 0.5f) * 6f
                houses.add(makeHouse(px, CITY_HEIGHT, pz, Noise.hash01(hx, hz, 43), Noise.hash01(hx, hz, 44), Noise.hash01(hx, hz, 45) < 0.5f))
            }
            return
        }
        val t = 1f - dc / CITY_RADIUS
        val ground = CITY_HEIGHT

        // park
        if (Noise.hash01(bx, bz, 7) < 0.07f) {
            for (j in 0 until 4) for (i in 0 until 4) {
                val tx = minX + 15f + i * 30f + (Noise.hash01(bx * 4 + i, bz * 4 + j, 8) - 0.5f) * 10f
                val tz = minZ + 15f + j * 30f + (Noise.hash01(bx * 4 + i, bz * 4 + j, 9) - 0.5f) * 10f
                val th = 8f + 6f * Noise.hash01(bx * 4 + i, bz * 4 + j, 10)
                trees.add(Tree(tx, ground - 0.5f, tz, th, th * 0.38f, PropKind.DECIDUOUS, 0.9f))
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

    // ------------------------------------------------------------------ vesnice

    private class Village(val x: Float, val z: Float, val radius: Float, val vx: Int, val vz: Int, val alongX: Boolean)

    private val noVillage = Any()
    private val villageCache = ConcurrentHashMap<Long, Any>()

    private fun villageInCell(vx: Int, vz: Int): Village? {
        val k = key(vx, vz)
        val cached = villageCache[k]
        if (cached != null) return cached as? Village
        val v = computeVillage(vx, vz)
        villageCache[k] = v ?: noVillage
        return v
    }

    private fun computeVillage(vx: Int, vz: Int): Village? {
        val chance = when (map) {
            MapType.COUNTRYSIDE -> 0.6f
            MapType.CITY -> 0.4f
            MapType.MOUNTAINS -> 0.4f
        }
        if (Noise.hash01(vx, vz, 201) >= chance) return null
        val x = (vx + 0.25f + 0.5f * Noise.hash01(vx, vz, 202)) * VILLAGE_CELL
        val z = (vz + 0.25f + 0.5f * Noise.hash01(vx, vz, 203)) * VILLAGE_CELL
        val radius = 150f + 130f * Noise.hash01(vx, vz, 204)
        if (airportWeight(x, z) > 0f) return null
        if (abs(x) < 400f + radius && abs(z) < CORRIDOR_Z) return null
        if (map == MapType.CITY && distToCity(x, z) < CITY_ROAD_RADIUS + 600f + radius) return null
        if (map == MapType.CITY && abs(x - riverX(z)) < 250f + radius) return null
        val h = rawHeight(x, z)
        if (h < SEA_LEVEL + 4f) return null
        if (map == MapType.MOUNTAINS && h > 750f) return null
        val slope = abs(rawHeight(x + 80f, z) - rawHeight(x - 80f, z)) + abs(rawHeight(x, z + 80f) - rawHeight(x, z - 80f))
        if (slope > 45f) return null
        return Village(x, z, radius, vx, vz, Noise.hash01(vx, vz, 205) < 0.5f)
    }

    /** Leží bod uvnitř některé vesnice (plus okraj)? */
    fun inVillage(x: Float, z: Float, margin: Float): Boolean {
        val v = villageInCell(floor(x / VILLAGE_CELL).toInt(), floor(z / VILLAGE_CELL).toInt()) ?: return false
        val dx = x - v.x
        val dz = z - v.z
        val r = v.radius + margin
        return dx * dx + dz * dz < r * r
    }

    private val wallPalette = arrayOf(
        floatArrayOf(0.95f, 0.94f, 0.90f),
        floatArrayOf(0.93f, 0.86f, 0.68f),
        floatArrayOf(0.94f, 0.80f, 0.70f),
        floatArrayOf(0.86f, 0.88f, 0.84f),
        floatArrayOf(0.97f, 0.91f, 0.78f),
    )
    private val roofPalette = arrayOf(
        floatArrayOf(0.66f, 0.24f, 0.16f),
        floatArrayOf(0.58f, 0.20f, 0.14f),
        floatArrayOf(0.42f, 0.27f, 0.20f),
        floatArrayOf(0.30f, 0.31f, 0.34f),
    )

    private fun makeHouse(x: Float, y: Float, z: Float, r1: Float, r2: Float, ridgeAlongX: Boolean): House {
        val wall = wallPalette[(r1 * wallPalette.size).toInt().coerceAtMost(wallPalette.size - 1)]
        val roof = roofPalette[(r2 * roofPalette.size).toInt().coerceAtMost(roofPalette.size - 1)]
        val long = 5f + 2.5f * r2
        val short = 3.8f + 1.4f * r1
        return House(
            x, y, z,
            if (ridgeAlongX) long else short, if (ridgeAlongX) short else long,
            4.8f + 2.5f * r1 * r2, 3.2f + 1.5f * r1,
            ridgeAlongX,
            wall[0], wall[1], wall[2], roof[0], roof[1], roof[2],
        )
    }

    private fun placeVillages(cx: Int, cz: Int, out: MutableList<House>) {
        val x0 = cx * CHUNK
        val z0 = cz * CHUNK
        val vx0 = floor((x0 - 400f) / VILLAGE_CELL).toInt()
        val vx1 = floor((x0 + CHUNK + 400f) / VILLAGE_CELL).toInt()
        val vz0 = floor((z0 - 400f) / VILLAGE_CELL).toInt()
        val vz1 = floor((z0 + CHUNK + 400f) / VILLAGE_CELL).toInt()
        for (vz in vz0..vz1) for (vx in vx0..vx1) {
            val v = villageInCell(vx, vz) ?: continue
            if (v.x + v.radius + 30f < x0 || v.x - v.radius - 30f > x0 + CHUNK) continue
            if (v.z + v.radius + 30f < z0 || v.z - v.radius - 30f > z0 + CHUNK) continue
            villageHouses(v) { h ->
                if (h.x >= x0 && h.x < x0 + CHUNK && h.z >= z0 && h.z < z0 + CHUNK) out.add(h)
            }
        }
    }

    private inline fun villageHouses(v: Village, add: (House) -> Unit) {
        val spacing = 24f
        val n = (v.radius / spacing).toInt()
        var idx = 0
        for (street in 0..1) {
            val len = if (street == 0) n else n / 2 + 1
            val streetAlongX = v.alongX != (street == 1)
            for (k in -len..len) {
                if (k == 0) continue
                for (side in -1..1 step 2) {
                    idx++
                    val sx = v.vx * 1009 + idx
                    val sz = v.vz * 917 + street
                    if (Noise.hash01(sx, sz, 210) < 0.2f) continue
                    val along = k * spacing + (Noise.hash01(sx, sz, 211) - 0.5f) * 6f
                    val off = side * (13f + Noise.hash01(sx, sz, 212) * 6f)
                    val hx = if (streetAlongX) v.x + along else v.x + off
                    val hz = if (streetAlongX) v.z + off else v.z + along
                    val base = groundBase(hx, hz, 6f)
                    if (base < SEA_LEVEL + 1f) continue
                    val ridgeAlongX = if (Noise.hash01(sx, sz, 213) < 0.65f) streetAlongX else !streetAlongX
                    add(makeHouse(hx, base, hz, Noise.hash01(sx, sz, 214), Noise.hash01(sx, sz, 215), ridgeAlongX))
                }
            }
        }
        // kostel s věží na rohu návsi
        val chx = v.x + 24f
        val chz = v.z + 26f
        val cb = groundBase(chx, chz, 12f)
        if (cb > SEA_LEVEL + 1f) {
            add(House(chx, cb, chz, 6.5f, 12f, 10f, 6.5f, false, 0.96f, 0.95f, 0.9f, 0.32f, 0.33f, 0.36f))
            add(House(chx, cb, chz + 15f, 3.2f, 3.2f, 22f, 10f, true, 0.96f, 0.95f, 0.9f, 0.20f, 0.42f, 0.36f))
        }
    }

    /** Nejnižší bod terénu pod půdorysem (aby dům nevisel ve vzduchu). */
    private fun groundBase(x: Float, z: Float, r: Float): Float =
        min(min(groundHeight(x - r, z - r), groundHeight(x + r, z - r)), min(groundHeight(x - r, z + r), groundHeight(x + r, z + r)))

    // ------------------------------------------------------------------ zvířata, balvany, elektrárny

    private fun placeHerdsAndRocks(cx: Int, cz: Int, heights: FloatArray, normals: FloatArray, out: MutableList<Tree>) {
        val x0 = cx * CHUNK
        val z0 = cz * CHUNK
        // balvany v horách
        if (map == MapType.MOUNTAINS) {
            for (k in 0 until 24) {
                val x = x0 + Noise.hash01(cx * 64 + k, cz, 401) * CHUNK
                val z = z0 + Noise.hash01(cx, cz * 64 + k, 402) * CHUNK
                val h = localHeight(heights, cx, cz, x, z)
                if (h < 500f || airportWeight(x, z) > 0f) continue
                val s = 2.5f + 6f * Noise.hash01(cx * 64 + k, cz * 64 + k, 403)
                out.add(Tree(x, h - s * 0.3f, z, s, s * 1.2f, PropKind.ROCK, 0.85f + 0.3f * Noise.hash01(k, cx + cz, 404)))
            }
        }
        // stádo krav nebo ovcí na louce
        if (map == MapType.CITY && distToCity(x0, z0) < CITY_ROAD_RADIUS + 1500f) return
        val herdChance = when (map) {
            MapType.COUNTRYSIDE -> 0.4f
            MapType.MOUNTAINS -> 0.25f
            MapType.CITY -> 0.2f
        }
        if (Noise.hash01(cx, cz, 410) >= herdChance) return
        val hx = x0 + (0.2f + 0.6f * Noise.hash01(cx, cz, 411)) * CHUNK
        val hz = z0 + (0.2f + 0.6f * Noise.hash01(cx, cz, 412)) * CHUNK
        val hh = localHeight(heights, cx, cz, hx, hz)
        if (hh < SEA_LEVEL + 3f || forestMask(hx, hz) > 0.1f || inVillage(hx, hz, 40f) || airportWeight(hx, hz) > 0f) return
        if (map == MapType.MOUNTAINS && hh > 1100f) return
        val sheep = map == MapType.MOUNTAINS || Noise.hash01(cx, cz, 413) < 0.35f
        val n = if (sheep) 8 + (Noise.hash01(cx, cz, 414) * 8f).toInt() else 5 + (Noise.hash01(cx, cz, 414) * 6f).toInt()
        for (k in 0 until n) {
            val ax = hx + (Noise.hash01(cx * 31 + k, cz, 415) - 0.5f) * 70f
            val az = hz + (Noise.hash01(cx, cz * 31 + k, 416) - 0.5f) * 70f
            val ah = localHeight(heights, cx, cz, ax.coerceIn(x0, x0 + CHUNK - 0.1f), az.coerceIn(z0, z0 + CHUNK - 0.1f))
            if (ah < SEA_LEVEL + 1f) continue
            val shade = Noise.hash01(cx * 31 + k, cz * 31 + k, 417)
            out.add(
                Tree(
                    ax, ah, az, if (sheep) 1.1f else 1.6f, 1f,
                    if (sheep) PropKind.SHEEP else PropKind.COW, shade,
                    yaw = Noise.hash01(k, cx * 7 + cz, 418) * 6.283f,
                )
            )
        }
    }

    private fun placeTurbines(cx: Int, cz: Int, heights: FloatArray, normals: FloatArray, out: MutableList<Turbine>) {
        if (map == MapType.MOUNTAINS) return
        val x0 = cx * CHUNK
        val z0 = cz * CHUNK
        for (j in 0 until 2) for (i in 0 until 2) {
            val gx = cx * 2 + i
            val gz = cz * 2 + j
            val x = x0 + (i + 0.3f + 0.4f * Noise.hash01(gx, gz, 420)) * (CHUNK / 2f)
            val z = z0 + (j + 0.3f + 0.4f * Noise.hash01(gx, gz, 421)) * (CHUNK / 2f)
            val farm = Noise.fbm(x / 4000f, z / 4000f, 2, 422)
            if (farm < 0.28f) continue
            if (abs(x) < 2500f && abs(z) < 4500f) continue
            if (map == MapType.CITY && distToCity(x, z) < CITY_ROAD_RADIUS + 1200f) continue
            if (forestMask(x, z) > 0.05f || inVillage(x, z, 150f)) continue
            val h = localHeight(heights, cx, cz, x, z)
            if (h < SEA_LEVEL + 5f) continue
            val vi = ((x - x0) / CELL).toInt().coerceIn(0, CELLS)
            val vj = ((z - z0) / CELL).toInt().coerceIn(0, CELLS)
            if (normals[(vj * VERTS + vi) * 3 + 1] < 0.9f) continue
            out.add(Turbine(x, h - 1f, z, 72f, 34f, Noise.hash01(gx, gz, 423) * 6.283f))
        }
    }

    // ------------------------------------------------------------------ mraky

    private fun placeClouds(cx: Int, cz: Int, out: MutableList<Cloud>) {
        for (k in 0 until 2) {
            if (Noise.hash01(cx, cz, 301 + k) > (if (k == 0) 0.4f else 0.12f)) continue
            val ccx = (cx + 0.15f + 0.7f * Noise.hash01(cx, cz, 311 + k)) * CHUNK
            val ccz = (cz + 0.15f + 0.7f * Noise.hash01(cx, cz, 313 + k)) * CHUNK
            val hb = Noise.hash01(cx, cz, 315 + k)
            val base = if (map == MapType.MOUNTAINS) 2300f + 500f * hb else 1050f + 450f * hb
            val puffs = 4 + (Noise.hash01(cx, cz, 317 + k) * 5f).toInt()
            for (p in 0 until puffs) {
                val hx = cx * 31 + p
                val hz = cz * 17 + k
                val r = 40f + 50f * Noise.hash01(hx, hz, 323)
                out.add(
                    Cloud(
                        ccx + (Noise.hash01(hx, hz, 321) - 0.5f) * 230f,
                        base + Noise.hash01(hx, hz, 324) * 35f,
                        ccz + (Noise.hash01(hx, hz, 322) - 0.5f) * 160f,
                        r, r * 0.62f, 0.92f + 0.08f * Noise.hash01(hx, hz, 325),
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

    /** Letadla odstavená vedle hangárů (přídí k dráze). */
    val parkedAircraft: List<ParkedAircraft> = listOf(
        ParkedAircraft(AircraftType.AIRLINER, -125f, 720f, -1.5708f),
        ParkedAircraft(AircraftType.FIGHTER, -95f, 575f, -1.5708f),
        ParkedAircraft(AircraftType.BOMBER, -150f, 860f, -1.5708f),
        ParkedAircraft(AircraftType.HELICOPTER, -75f, 135f, -1.5708f),
    )

    private val parkedBoxes: List<Box> = parkedAircraft.map { p ->
        val (len, span, h) = when (p.type) {
            AircraftType.AIRLINER -> Triple(20f, 17f, 11f)
            AircraftType.BOMBER -> Triple(12f, 16f, 6f)
            AircraftType.FIGHTER -> Triple(9f, 5f, 5f)
            AircraftType.HELICOPTER -> Triple(6f, 5.5f, 4f)
            AircraftType.SPORT -> Triple(4f, 5f, 3f)
        }
        // příď míří do +X, délka leží podél X
        Box(p.x - len, airportHeight, p.z - span, p.x + len, airportHeight + h, p.z + span, 0f, 0f, 0f, false)
    }

    /** Vrátí druh překážky, do které koule (x,y,z,r) narazila, jinak null. */
    fun hitObstacle(x: Float, y: Float, z: Float, r: Float): ObstacleKind? {
        // Nad nejvyššími možnými překážkami nemá smysl hledat.
        if (y - r > groundHeight(x, z) + 400f) return null
        if (abs(x) < 400f && abs(z) < 1300f) {
            for (bld in airportBoxes) {
                if (x + r > bld.minX && x - r < bld.maxX && y + r > bld.minY && y - r < bld.top &&
                    z + r > bld.minZ && z - r < bld.maxZ
                ) return ObstacleKind.BUILDING
            }
            for (i in parkedBoxes.indices) {
                if (parkedDestroyed[i]) continue
                val bld = parkedBoxes[i]
                if (x + r > bld.minX && x - r < bld.maxX && y + r > bld.minY && y - r < bld.top &&
                    z + r > bld.minZ && z - r < bld.maxZ
                ) return ObstacleKind.BUILDING
            }
        }
        val cxa = chunkOf(x - BLOCK - 10f)
        val cxb = chunkOf(x + 25f)
        val cza = chunkOf(z - BLOCK - 10f)
        val czb = chunkOf(z + 25f)
        for (cz in cza..czb) for (cx in cxa..cxb) {
            val c = content(cx, cz)
            for (bld in c.buildings) {
                if (x + r > bld.minX && x - r < bld.maxX && y + r > bld.minY && y - r < bld.top &&
                    z + r > bld.minZ && z - r < bld.maxZ
                ) return ObstacleKind.BUILDING
            }
            for (h in c.houses) {
                if (abs(x - h.x) < h.halfW + r && abs(z - h.z) < h.halfD + r &&
                    y - r < h.top && y + r > h.y - 1f
                ) return ObstacleKind.BUILDING
            }
            for (tb in c.turbines) {
                if (abs(x - tb.x) < 3f + r && abs(z - tb.z) < 3f + r && y - r < tb.y + tb.hubHeight) return ObstacleKind.TURBINE
                val dy = y - (tb.y + tb.hubHeight)
                val dx = x - tb.x
                val dz = z - tb.z
                val rr = tb.rotorRadius * 0.85f + r
                // rotor stojí kolmo na vítr (osa podél TURBINE_DIR)
                val along = dx * TURBINE_DIR_X + dz * TURBINE_DIR_Z
                val side = -dx * TURBINE_DIR_Z + dz * TURBINE_DIR_X
                if (abs(along) < 4f + r && side * side + dy * dy < rr * rr) return ObstacleKind.TURBINE
            }
            for (t in c.trees) {
                if (!t.kind.collides) continue
                val tr = t.radius * 0.7f + r
                if (abs(x - t.x) < tr && abs(z - t.z) < tr && y - r < t.y + t.height && y + r > t.y) {
                    return ObstacleKind.TREE
                }
            }
        }
        return null
    }

    // ------------------------------------------------------------------ ničení

    private val destroyedIds: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    /** Zničená odstavená letadla (stejné pořadí jako [parkedAircraft]). */
    val parkedDestroyed = BooleanArray(4)

    private fun objId(cx: Int, cz: Int, kind: Int, idx: Int): Long =
        ((cx.toLong() and 0xFFFFFL) shl 44) or ((cz.toLong() and 0xFFFFFL) shl 24) or
            (kind.toLong() shl 20) or (idx.toLong() and 0xFFFFFL)

    private fun distToBox(x: Float, y: Float, z: Float, b: Box): Float {
        val dx = max(max(b.minX - x, 0f), x - b.maxX)
        val dy = max(max(b.minY - y, 0f), y - b.top)
        val dz = max(max(b.minZ - z, 0f), z - b.maxZ)
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /**
     * Výbuch v bodě (x,y,z): budovy a domky v dosahu se zřítí, stromy ohoří,
     * odstavená letadla shoří. Zničení si svět pamatuje i po odletu.
     */
    fun damage(x: Float, y: Float, z: Float, radius: Float): DamageReport {
        val chunks = ArrayList<Long>()
        val fires = ArrayList<FloatArray>()
        val cxa = chunkOf(x - radius - BLOCK - 10f)
        val cxb = chunkOf(x + radius + 25f)
        val cza = chunkOf(z - radius - BLOCK - 10f)
        val czb = chunkOf(z + radius + 25f)
        for (cz in cza..czb) for (cx in cxa..cxb) {
            val c = content(cx, cz)
            var changed = false
            c.buildings.forEachIndexed { i, b ->
                if (!b.destroyed && distToBox(x, y, z, b) < radius) {
                    b.destroyed = true
                    destroyedIds.add(objId(cx, cz, 0, i))
                    fires.add(floatArrayOf((b.minX + b.maxX) / 2f, b.top, (b.minZ + b.maxZ) / 2f, (b.maxX - b.minX) * 0.25f))
                    changed = true
                }
            }
            c.houses.forEachIndexed { i, h ->
                val dx = max(abs(x - h.x) - h.halfW, 0f)
                val dz = max(abs(z - h.z) - h.halfD, 0f)
                val dy = max(max(h.y - y, 0f), y - h.top)
                if (!h.destroyed && sqrt(dx * dx + dy * dy + dz * dz) < radius) {
                    h.destroyed = true
                    destroyedIds.add(objId(cx, cz, 1, i))
                    fires.add(floatArrayOf(h.x, h.top, h.z, 3f))
                    changed = true
                }
            }
            val treeR = radius * 1.4f
            c.trees.forEachIndexed { i, t ->
                if (t.burnt || t.kind == PropKind.ROCK) return@forEachIndexed
                val dx = t.x - x
                val dz = t.z - z
                val dy = max(t.y - y, y - (t.y + t.height)).coerceAtLeast(0f)
                if (dx * dx + dz * dz + dy * dy < treeR * treeR) {
                    t.burnt = true
                    destroyedIds.add(objId(cx, cz, 2, i))
                    changed = true
                }
            }
            if (changed) chunks.add(key(cx, cz))
        }
        var anything = chunks.isNotEmpty()
        parkedAircraft.forEachIndexed { i, p ->
            val dx = p.x - x
            val dz = p.z - z
            val dy = airportHeight - y
            if (!parkedDestroyed[i] && dx * dx + dz * dz + dy * dy < (radius + 15f) * (radius + 15f)) {
                parkedDestroyed[i] = true
                fires.add(floatArrayOf(p.x, airportHeight, p.z, 6f))
                anything = true
            }
        }
        return DamageReport(chunks, fires, anything)
    }

    /** Nejvyšší bod terénu i staveb pod (x,z) – pro kameru a stín. */
    fun surfaceHeight(x: Float, z: Float): Float = max(groundHeight(x, z), SEA_LEVEL)

    fun minHeightAround(x: Float, z: Float, r: Float): Float =
        min(min(groundHeight(x - r, z), groundHeight(x + r, z)), min(groundHeight(x, z - r), groundHeight(x, z + r)))
}
