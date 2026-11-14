package app.letadlo.game

/** Hotové sítě jednoho chunku (CPU strana). */
class ChunkMeshes(
    val cx: Int,
    val cz: Int,
    val terrainNear: MeshData,
    val terrainFar: MeshData,
    val trees: MeshData,
    val buildings: MeshData,
)

/** Převádí obsah chunku na trojúhelníky. Běží na pracovním vlákně. */
object ChunkMesher {

    private const val SKIRT = 45f

    fun build(world: World, c: ChunkContent): ChunkMeshes = ChunkMeshes(
        c.cx, c.cz,
        terrain(c, 1),
        terrain(c, 2),
        trees(c.trees),
        buildings(c.buildings),
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
        val trunkH = t.height * 0.3f
        val tw = 0.25f + t.height * 0.02f
        mb.box(t.x - tw, t.y, t.z - tw, t.x + tw, t.y + trunkH + 0.5f, t.z + tw, 0.36f, 0.25f, 0.16f, bottom = false)
        if (t.conifer) {
            mb.cone(t.x, t.y + trunkH, t.z, t.radius, t.height - trunkH, 6,
                0.12f * t.shade, 0.29f * t.shade, 0.14f * t.shade)
        } else {
            val cr = t.radius
            mb.blob(t.x, t.y + trunkH + cr * 0.75f, t.z, cr, cr * 1.05f, 6,
                0.24f * t.shade, 0.44f * t.shade, 0.16f * t.shade)
        }
    }

    fun buildings(list: List<Box>): MeshData {
        val mb = MeshBuilder(list.size * 24 + 4)
        for (b in list) {
            mb.box(
                b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ,
                b.r, b.g, b.b, if (b.windows) 0.5f else 1f,
                b.r * 0.55f, b.g * 0.55f, b.b * 0.56f, bottom = false,
            )
        }
        return mb.build()
    }
}
