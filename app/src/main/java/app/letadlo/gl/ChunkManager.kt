package app.letadlo.gl

import app.letadlo.game.ChunkMesher
import app.letadlo.game.ChunkMeshes
import app.letadlo.game.World
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.sqrt

/**
 * Drží kolem kamery načtené čtverce terénu. Generování běží na pozadí,
 * nahrávání na GPU na GL vlákně (pár chunků za snímek).
 */
class ChunkManager(private val world: World, private val radius: Int) {

    private class GpuChunk(
        val cx: Int,
        val cz: Int,
        val near: GpuMesh,
        val far: GpuMesh,
        val trees: GpuMesh,
        val buildings: GpuMesh,
        val clouds: GpuMesh,
    ) {
        fun release() {
            near.release(); far.release(); trees.release(); buildings.release(); clouds.release()
        }
    }

    private val loaded = HashMap<Long, GpuChunk>()
    private val pending = HashSet<Long>()
    private val ready = ConcurrentLinkedQueue<ChunkMeshes>()
    private val executor: ExecutorService = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "letadlo-chunky").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
        }
    }
    private var frame = 0
    private var centerCx = Int.MIN_VALUE
    private var centerCz = Int.MIN_VALUE
    private val wanted = ArrayList<LongArray>()

    val loadedCount: Int get() = loaded.size

    /** Po ztrátě GL kontextu jsou všechny staré buffery neplatné – jen je zapomeneme. */
    fun onContextCreated() {
        loaded.clear()
        pending.clear()
        ready.clear()
        centerCx = Int.MIN_VALUE
    }

    /** Synchronně připraví chunky kolem bodu (na startu, aby nebyla vidět prázdnota). */
    fun loadNow(x: Float, z: Float, r: Int) {
        val ccx = World.chunkOf(x)
        val ccz = World.chunkOf(z)
        for (dz in -r..r) for (dx in -r..r) {
            val k = World.key(ccx + dx, ccz + dz)
            if (loaded.containsKey(k)) continue
            val meshes = ChunkMesher.build(world, world.content(ccx + dx, ccz + dz))
            loaded[k] = upload(meshes)
        }
    }

    private fun upload(m: ChunkMeshes) = GpuChunk(
        m.cx, m.cz,
        GpuMesh(m.terrainNear), GpuMesh(m.terrainFar), GpuMesh(m.trees), GpuMesh(m.buildings), GpuMesh(m.clouds),
    )

    fun update(camX: Float, camZ: Float) {
        frame++
        val ccx = World.chunkOf(camX)
        val ccz = World.chunkOf(camZ)

        // 1) nahrát hotové sítě
        var uploads = 0
        while (uploads < 4) {
            val m = ready.poll() ?: break
            val k = World.key(m.cx, m.cz)
            pending.remove(k)
            if (m.terrainNear.isEmpty) continue // generování selhalo – zkusí se znovu
            if (Math.abs(m.cx - ccx) > radius + 1 || Math.abs(m.cz - ccz) > radius + 1) continue
            loaded.remove(k)?.release()
            loaded[k] = upload(m)
            uploads++
        }

        // 2) objednat chybějící (nejbližší první)
        if (!executor.isShutdown && (ccx != centerCx || ccz != centerCz || frame % 20 == 0)) {
            centerCx = ccx
            centerCz = ccz
            wanted.clear()
            val r2 = radius * radius + radius
            for (dz in -radius..radius) for (dx in -radius..radius) {
                val d2 = dx * dx + dz * dz
                if (d2 > r2) continue
                val k = World.key(ccx + dx, ccz + dz)
                if (loaded.containsKey(k) || pending.contains(k)) continue
                wanted.add(longArrayOf(d2.toLong(), (ccx + dx).toLong(), (ccz + dz).toLong()))
            }
            wanted.sortBy { it[0] }
            for (w in wanted) {
                if (pending.size >= 12) break
                val cx = w[1].toInt()
                val cz = w[2].toInt()
                val k = World.key(cx, cz)
                pending.add(k)
                try {
                    executor.execute {
                        try {
                            ready.add(ChunkMesher.build(world, world.content(cx, cz)))
                        } catch (t: Throwable) {
                            // když generování selže, chunk se zkusí příště znovu
                            ready.add(ChunkMeshes(cx, cz, EMPTY, EMPTY, EMPTY, EMPTY, EMPTY))
                        }
                    }
                } catch (e: java.util.concurrent.RejectedExecutionException) {
                    pending.remove(k)
                    break
                }
            }
        }

        // 3) uvolnit vzdálené
        if (frame % 30 == 0) {
            val it = loaded.entries.iterator()
            while (it.hasNext()) {
                val c = it.next().value
                if (Math.abs(c.cx - ccx) > radius + 1 || Math.abs(c.cz - ccz) > radius + 1) {
                    c.release()
                    it.remove()
                }
            }
            world.evictFar(ccx, ccz, radius + 3)
        }
    }

    private fun visible(c: GpuChunk, camX: Float, camZ: Float, fwdX: Float, fwdZ: Float, maxDist: Float): Float {
        val half = World.CHUNK * 0.5f
        val dx = (c.cx * World.CHUNK + half) - camX
        val dz = (c.cz * World.CHUNK + half) - camZ
        val d = sqrt(dx * dx + dz * dz)
        if (d - World.CHUNK * 0.71f > maxDist) return -1f
        if (d > World.CHUNK && dx * fwdX + dz * fwdZ < -World.CHUNK * 0.75f) return -1f
        return d
    }

    fun drawTerrain(camX: Float, camZ: Float, fwdX: Float, fwdZ: Float, lodDist: Float, maxDist: Float) {
        for (c in loaded.values) {
            val d = visible(c, camX, camZ, fwdX, fwdZ, maxDist)
            if (d < 0f) continue
            if (d < lodDist) c.near.draw() else c.far.draw()
        }
    }

    fun drawObjects(camX: Float, camZ: Float, fwdX: Float, fwdZ: Float, treeDist: Float, maxDist: Float) {
        for (c in loaded.values) {
            val d = visible(c, camX, camZ, fwdX, fwdZ, maxDist)
            if (d < 0f) continue
            c.buildings.draw()
            if (d < treeDist) c.trees.draw()
        }
    }

    fun drawClouds(camX: Float, camZ: Float, fwdX: Float, fwdZ: Float, maxDist: Float) {
        for (c in loaded.values) {
            if (visible(c, camX, camZ, fwdX, fwdZ, maxDist) < 0f) continue
            c.clouds.draw()
        }
    }

    /** Znovu postaví sítě chunku (např. po zničení budov). */
    fun rebuild(cx: Int, cz: Int) {
        val k = World.key(cx, cz)
        if (!loaded.containsKey(k) || executor.isShutdown) return
        pending.add(k)
        try {
            executor.execute {
                try {
                    ready.add(ChunkMesher.build(world, world.content(cx, cz)))
                } catch (t: Throwable) {
                    ready.add(ChunkMeshes(cx, cz, EMPTY, EMPTY, EMPTY, EMPTY, EMPTY))
                }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            pending.remove(k)
        }
    }

    /** Projde souřadnice všech načtených chunků. */
    fun forEachLoaded(action: (cx: Int, cz: Int) -> Unit) {
        for (c in loaded.values) action(c.cx, c.cz)
    }

    fun shutdown() {
        executor.shutdownNow()
    }

    companion object {
        private val EMPTY = app.letadlo.game.MeshData(FloatArray(0), 0, IntArray(0), 0)
    }
}
