package app.letadlo.game

import kotlin.math.min

/** Hotové sítě jednoho chunku (CPU strana). */
class ChunkMeshes(
    val cx: Int,
    val cz: Int,
    val terrainNear: MeshData,
    val terrainFar: MeshData,
    val trees: MeshData,
    val buildings: MeshData,
    val clouds: MeshData,
)

/** Převádí obsah chunku na trojúhelníky. Běží na pracovním vlákně. */
object ChunkMesher {

    private const val SKIRT = 45f

    fun build(world: World, c: ChunkContent): ChunkMeshes = ChunkMeshes(
        c.cx, c.cz,
        terrain(c, 1),
        terrain(c, 2),
        trees(c.trees),
        buildings(c.buildings, c.houses, c.turbines),
        clouds(c.clouds),
    )

    private fun terrain(c: ChunkContent, step: Int): MeshData {
        val n = World.CELLS / step + 1
        val mb = MeshBuilder(n * n + n * 4 + 8)
        val first = IntArray(n * n)
        for (jj in 0 until n) {
            for (ii in 0 until n) {
                val i = ii * step
                val j = jj * step
                val idx = j * World.VERTS + i
                val gx = c.cx * World.CELLS + i
                val gz = c.cz * World.CELLS + j
                first[jj * n + ii] = mb.vertex(
                    gx * World.CELL, c.heights[idx], gz * World.CELL,
                    c.normals[idx * 3], c.normals[idx * 3 + 1], c.normals[idx * 3 + 2],
                    c.colors[idx * 4], c.colors[idx * 4 + 1], c.colors[idx * 4 + 2], c.colors[idx * 4 + 3],
                )
            }
        }
        for (jj in 0 until n - 1) {
            for (ii in 0 until n - 1) {
                val a = first[jj * n + ii]
                val b = first[jj * n + ii + 1]
                val cc = first[(jj + 1) * n + ii]
                val d = first[(jj + 1) * n + ii + 1]
                mb.tri(a, cc, b)
                mb.tri(b, cc, d)
            }
        }
        // „sukně" po okrajích zakryje případné škvíry mezi různými úrovněmi detailu
        skirt(mb, c, step, n, first) { k -> k }                         // jj = 0
        skirt(mb, c, step, n, first) { k -> (n - 1) * n + k }           // jj = n-1
        skirt(mb, c, step, n, first) { k -> k * n }                     // ii = 0
        skirt(mb, c, step, n, first) { k -> k * n + n - 1 }             // ii = n-1
        return mb.build()
    }

    private inline fun skirt(mb: MeshBuilder, c: ChunkContent, step: Int, n: Int, first: IntArray, pick: (Int) -> Int) {
        var prevTop = -1
        var prevBottom = -1
        for (k in 0 until n) {
            val g = pick(k)
            val jj = g / n
            val ii = g % n
            val idx = (jj * step) * World.VERTS + ii * step
            val gx = c.cx * World.CELLS + ii * step
            val gz = c.cz * World.CELLS + jj * step
            val bottom = mb.vertex(
                gx * World.CELL, c.heights[idx] - SKIRT, gz * World.CELL,
                c.normals[idx * 3], c.normals[idx * 3 + 1], c.normals[idx * 3 + 2],
                c.colors[idx * 4] * 0.8f, c.colors[idx * 4 + 1] * 0.8f, c.colors[idx * 4 + 2] * 0.8f, 1f,
            )
            val top = first[g]
            if (prevTop >= 0) {
                mb.tri(prevTop, top, bottom)
                mb.tri(prevTop, bottom, prevBottom)
            }
            prevTop = top
            prevBottom = bottom
        }
    }

    fun trees(list: List<Tree>): MeshData {
        val mb = MeshBuilder(list.size * 20 + 4)
        for (t in list) addTree(mb, t)
        return mb.build()
    }

    fun addTree(mb: MeshBuilder, t: Tree) {
        if (t.burnt) {
            addBurntTree(mb, t)
            return
        }
        val s = t.shade
        when (t.kind) {
            PropKind.ROCK -> {
                mb.blob(t.x, t.y + t.height * 0.35f, t.z, t.radius, t.height * 0.55f, 5, 0.5f * s, 0.49f * s, 0.47f * s)
                return
            }
            PropKind.BUSH -> {
                mb.blob(t.x, t.y + t.height * 0.45f, t.z, t.radius, t.height * 0.55f, 5, 0.2f * s, 0.38f * s, 0.14f * s)
                return
            }
            PropKind.COW -> {
                val brown = s < 0.5f
                val r = if (brown) 0.45f else 0.92f
                val g = if (brown) 0.3f else 0.9f
                val b = if (brown) 0.2f else 0.86f
                mb.obox(t.x, t.y + 0.7f, t.z, 1.05f, 0.42f, 0.75f, t.yaw, r, g, b)
                mb.obox(t.x, t.y + 1.1f, t.z, 0.32f, 0.24f, 0.42f, t.yaw, r * 0.6f, g * 0.6f, b * 0.6f, offAlong = 1.3f)
                for (fa in floatArrayOf(-0.75f, 0.75f)) for (sd in floatArrayOf(-0.28f, 0.28f)) {
                    mb.obox(t.x, t.y, t.z, 0.09f, 0.09f, 0.72f, t.yaw, 0.2f, 0.17f, 0.15f, offAlong = fa, offSide = sd)
                }
                return
            }
            PropKind.SHEEP -> {
                mb.obox(t.x, t.y + 0.35f, t.z, 0.6f, 0.38f, 0.55f, t.yaw, 0.93f, 0.92f, 0.88f)
                mb.obox(t.x, t.y + 0.6f, t.z, 0.2f, 0.16f, 0.3f, t.yaw, 0.15f, 0.14f, 0.14f, offAlong = 0.7f)
                for (fa in floatArrayOf(-0.4f, 0.4f)) for (sd in floatArrayOf(-0.22f, 0.22f)) {
                    mb.obox(t.x, t.y, t.z, 0.06f, 0.06f, 0.38f, t.yaw, 0.15f, 0.14f, 0.14f, offAlong = fa, offSide = sd)
                }
                return
            }
            else -> Unit
        }
        val birch = t.kind == PropKind.BIRCH
        val trunkH = t.height * (if (birch) 0.45f else 0.3f)
        val tw = (if (birch) 0.18f else 0.25f) + t.height * 0.02f
        if (birch) {
            mb.box(t.x - tw, t.y, t.z - tw, t.x + tw, t.y + trunkH + 0.5f, t.z + tw, 0.88f, 0.87f, 0.83f, bottom = false)
        } else {
            mb.box(t.x - tw, t.y, t.z - tw, t.x + tw, t.y + trunkH + 0.5f, t.z + tw, 0.36f, 0.25f, 0.16f, bottom = false)
        }
        when (t.kind) {
            PropKind.CONIFER -> mb.cone(t.x, t.y + trunkH, t.z, t.radius, t.height - trunkH, 6,
                0.12f * s, 0.29f * s, 0.14f * s)
            PropKind.BIRCH -> {
                val cr = t.radius
                mb.blob(t.x, t.y + trunkH + cr * 1.1f, t.z, cr, cr * 1.6f, 6, 0.42f * s, 0.6f * s, 0.22f * s)
            }
            else -> {
                val cr = t.radius
                mb.blob(t.x, t.y + trunkH + cr * 0.75f, t.z, cr, cr * 1.05f, 6, 0.24f * s, 0.44f * s, 0.16f * s)
            }
        }
    }

    /** Ohořelý strom: černý kmen a zbytek tmavé koruny (keře a zvířata jen zčernají). */
    private fun addBurntTree(mb: MeshBuilder, t: Tree) {
        val c = 0.07f
        when (t.kind) {
            PropKind.BUSH, PropKind.COW, PropKind.SHEEP -> {
                mb.blob(t.x, t.y + 0.3f, t.z, t.radius * 0.6f, 0.5f, 5, c, c, c)
                return
            }
            PropKind.ROCK -> return
            else -> Unit
        }
        val tw = 0.2f + t.height * 0.02f
        mb.box(t.x - tw, t.y, t.z - tw, t.x + tw, t.y + t.height * 0.75f, t.z + tw, 0.08f, 0.07f, 0.06f, bottom = false)
        if (t.conifer) {
            mb.cone(t.x, t.y + t.height * 0.35f, t.z, t.radius * 0.45f, t.height * 0.6f, 5, c, c * 0.9f, c * 0.8f)
        } else {
            mb.blob(t.x, t.y + t.height * 0.7f, t.z, t.radius * 0.5f, t.radius * 0.45f, 5, c, c * 0.9f, c * 0.8f)
        }
    }

    /** Věž a gondola elektrárny (lopatky se kreslí zvlášť, protože se točí). */
    fun addTurbineTower(mb: MeshBuilder, t: Turbine) {
        mb.cylinderY(t.x, t.y, t.z, 2.2f, t.y + t.hubHeight, 1.3f, 8, 0.92f, 0.92f, 0.92f)
        val yaw = kotlin.math.atan2(World.TURBINE_DIR_Z, World.TURBINE_DIR_X)
        mb.obox(t.x, t.y + t.hubHeight - 1.6f, t.z, 4.5f, 1.6f, 3.2f, yaw, 0.9f, 0.9f, 0.9f, offAlong = -1.5f)
    }

    fun buildings(list: List<Box>, houses: List<House> = emptyList(), turbines: List<Turbine> = emptyList()): MeshData {
        val mb = MeshBuilder(list.size * 24 + houses.size * 34 + turbines.size * 60 + 4)
        for (t in turbines) addTurbineTower(mb, t)
        for (b in list) {
            if (b.destroyed) {
                addRubble(mb, b.minX, b.minY, b.minZ, b.maxX, b.top, b.maxZ)
                continue
            }
            mb.box(
                b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ,
                b.r, b.g, b.b, if (b.windows) 0.5f else 1f,
                b.r * 0.55f, b.g * 0.55f, b.b * 0.56f, bottom = false,
            )
        }
        for (h in houses) addHouse(mb, h)
        return mb.build()
    }

    /** Ohořelé trosky: nízká hromada a pár kusů suti. */
    private fun addRubble(mb: MeshBuilder, minX: Float, minY: Float, minZ: Float, maxX: Float, top: Float, maxZ: Float) {
        val inset = min((maxX - minX), (maxZ - minZ)) * 0.08f
        mb.box(minX + inset, minY, minZ + inset, maxX - inset, top, maxZ - inset,
            0.16f, 0.14f, 0.12f, 1f, 0.09f, 0.08f, 0.07f, bottom = false)
        val cx = (minX + maxX) / 2f
        val cz = (minZ + maxZ) / 2f
        val sx = (maxX - minX) * 0.3f
        val sz = (maxZ - minZ) * 0.3f
        for (k in 0 until 4) {
            val ox = if (k % 2 == 0) -sx else sx
            val oz = if (k < 2) -sz else sz
            val r = min(sx, sz) * 0.55f
            mb.blob(cx + ox, top, cz + oz, r, r * 0.7f, 5, 0.22f, 0.2f, 0.18f)
        }
    }

    fun addHouse(mb: MeshBuilder, h: House) {
        if (h.destroyed) {
            addRubble(mb, h.x - h.halfW, h.y - 1f, h.z - h.halfD, h.x + h.halfW, h.top, h.z + h.halfD)
            return
        }
        val x0 = h.x - h.halfW
        val x1 = h.x + h.halfW
        val z0 = h.z - h.halfD
        val z1 = h.z + h.halfD
        val y0 = h.y - 1f
        val y1 = h.y + h.wallH
        val y2 = y1 + h.roofH
        val o = 0.6f
        mb.box(x0, y0, z0, x1, y1, z1, h.wr, h.wg, h.wb, 0.75f, h.wr, h.wg, h.wb, bottom = false)
        if (h.ridgeAlongX) {
            mb.quad(x0 - o, y1, z1 + o, x1 + o, y1, z1 + o, x1 + o, y2, h.z, x0 - o, y2, h.z, h.rr, h.rg, h.rb)
            mb.quad(x1 + o, y1, z0 - o, x0 - o, y1, z0 - o, x0 - o, y2, h.z, x1 + o, y2, h.z, h.rr * 0.9f, h.rg * 0.9f, h.rb * 0.9f)
            mb.triangle(x0, y1, z0, x0, y1, z1, x0, y2, h.z, h.wr, h.wg, h.wb)
            mb.triangle(x1, y1, z1, x1, y1, z0, x1, y2, h.z, h.wr, h.wg, h.wb)
        } else {
            mb.quad(x1 + o, y1, z1 + o, x1 + o, y1, z0 - o, h.x, y2, z0 - o, h.x, y2, z1 + o, h.rr, h.rg, h.rb)
            mb.quad(x0 - o, y1, z0 - o, x0 - o, y1, z1 + o, h.x, y2, z1 + o, h.x, y2, z0 - o, h.rr * 0.9f, h.rg * 0.9f, h.rb * 0.9f)
            mb.triangle(x1, y1, z0, x0, y1, z0, h.x, y2, z0, h.wr, h.wg, h.wb)
            mb.triangle(x0, y1, z1, x1, y1, z1, h.x, y2, z1, h.wr, h.wg, h.wb)
        }
    }

    fun clouds(list: List<Cloud>): MeshData {
        val mb = MeshBuilder(list.size * 80 + 4)
        for (c in list) mb.puff(c.x, c.y, c.z, c.rx, c.ry, c.shade)
        return mb.build()
    }
}
