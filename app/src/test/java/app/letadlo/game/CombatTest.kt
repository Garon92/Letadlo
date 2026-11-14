package app.letadlo.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CombatTest {

    private val dt = 1f / 60f

    @Test
    fun rocketFliesAlongTheNoseAndExplodesOnTheGround() {
        val w = World(MapType.COUNTRYSIDE)
        val life = Life(w)
        val combat = Combat(w, life)
        val f = FlightModel(w, AircraftType.FIGHTER)
        // míří dolů pod úhlem, raketa musí dopadnout na zem
        f.placeInAir(3000f, w.groundHeight(3000f, 3000f) + 400f, 3000f, 0.4f, 200f)
        repeat(60) { f.update(dt, 0f, -1f) }
        combat.fire(f)
        val r = combat.rockets.first()
        val dir = floatArrayOf(r.vx, r.vy, r.vz)
        val len = kotlin.math.sqrt(dir[0] * dir[0] + dir[1] * dir[1] + dir[2] * dir[2])
        // směr rakety = směr nosu
        assertEquals(kotlin.math.sin(f.heading) * kotlin.math.cos(f.pitch), dir[0] / len, 1e-3f)
        assertEquals(kotlin.math.sin(f.pitch), dir[1] / len, 1e-3f)
        var t = 0f
        while (combat.rockets.isNotEmpty() && t < 6f) {
            combat.update(dt, f, Weapon.NONE)
            t += dt
        }
        assertTrue("výbuch", combat.blasts.isNotEmpty())
        val b = combat.blasts.first()
        assertTrue("na zemi", abs(b.y - maxOf(w.groundHeight(b.x, b.z), World.SEA_LEVEL)) < 2f)
        if (w.groundHeight(b.x, b.z) > World.SEA_LEVEL) assertTrue("spálená zem", combat.scorches.isNotEmpty())
        assertTrue("zvuk", combat.launches == 1 && combat.booms == 1)
    }

    @Test
    fun holdingTriggerFiresContinuously() {
        val w = World(MapType.COUNTRYSIDE)
        val combat = Combat(w, Life(w))
        val f = FlightModel(w, AircraftType.FIGHTER)
        f.placeInAir(0f, 2500f, -20000f, 0f, 300f)
        combat.trigger = true
        repeat(60 * 3) { combat.update(dt, f, Weapon.ROCKETS) }
        assertTrue("neomezená palba (${combat.launches})", combat.launches >= 14)
        val g = Combat(w, Life(w))
        g.trigger = true
        repeat(60) { g.update(dt, f, Weapon.NONE) }
        assertEquals("jiná letadla nestřílí", 0, g.launches)
    }

    @Test
    fun explosionDestroysBuildingsPermanentlyAndBurnsTrees() {
        val w = World(MapType.CITY)
        val combat = Combat(w, Life(w))
        val ccx = World.chunkOf(World.CITY_X)
        val ccz = World.chunkOf(World.CITY_Z)
        val c = w.content(ccx, ccz)
        val b = c.buildings.first { it.maxY - it.minY > 30f }
        val idx = c.buildings.indexOf(b)
        val cx = (b.minX + b.maxX) / 2f
        val cz = (b.minZ + b.maxZ) / 2f
        assertEquals(ObstacleKind.BUILDING, w.hitObstacle(cx, b.minY + 25f, cz, 1f))
        combat.explosion(cx, b.minY + 20f, b.maxZ + 2f, Combat.ROCKET_BLAST)
        assertTrue(b.destroyed)
        assertTrue("trosky jsou nízko", b.top < b.minY + 12f)
        assertEquals("v troskách se dá proletět nad nimi", null, w.hitObstacle(cx, b.minY + 25f, cz, 1f))
        assertTrue("hoří", combat.fires.isNotEmpty())
        assertTrue("přestavět chunk", combat.dirtyChunks.contains(World.key(ccx, ccz)))
        // po odletu a návratu (zahození cache) zůstane budova zničená
        w.evictFar(ccx + 100, ccz + 100, 1)
        assertTrue(w.content(ccx, ccz).buildings[idx].destroyed)

        val country = World(MapType.COUNTRYSIDE)
        val cc = Combat(country, Life(country))
        val ch = (-6..6).flatMap { z -> (-6..6).map { x -> country.content(x, z) } }.first { it.trees.size > 30 }
        val tree = ch.trees.first { it.kind != PropKind.ROCK }
        cc.explosion(tree.x, tree.y + 2f, tree.z, Combat.ROCKET_BLAST)
        assertTrue("strom ohořel", tree.burnt)
        val mesh = ChunkMesher.build(country, ch)
        for (i in 0 until mesh.trees.indexCount) assertTrue(mesh.trees.indices[i] < mesh.trees.vertexCount)
    }

    @Test
    fun crashBlowsUpParkedPlanes() {
        val w = World(MapType.COUNTRYSIDE)
        val combat = Combat(w, Life(w))
        val p = w.parkedAircraft[0]
        combat.explosion(p.x, w.airportHeight + 2f, p.z, AircraftType.AIRLINER.blastRadius)
        assertTrue(w.parkedDestroyed[0])
        assertEquals("trosky letadla už nepřekáží", null, w.hitObstacle(p.x, w.airportHeight + 3f, p.z, 1f))
    }

    @Test
    fun bomberDropsHeavyBombsWhereTheMarkerShows() {
        val w = World(MapType.CITY)
        val life = Life(w)
        val combat = Combat(w, life)
        val f = FlightModel(w, AircraftType.BOMBER)
        f.placeInAir(World.CITY_X, 450f, World.CITY_Z + 2500f, 0f, 120f)
        repeat(60) { f.update(dt, 0f, 0f) }
        val predicted = FloatArray(3)
        assertTrue(combat.predictBombImpact(f, predicted))
        combat.trigger = true
        combat.update(dt, f, Weapon.BOMBS)
        combat.trigger = false
        assertEquals(1, combat.bombs.size)
        assertEquals(Weapon.BOMBS, Combat.weaponFor(AircraftType.BOMBER))
        var t = 0f
        while (combat.bombs.isNotEmpty() && t < 30f) {
            combat.update(dt, f, Weapon.BOMBS)
            t += dt
        }
        val b = combat.blasts.first()
        val miss = kotlin.math.sqrt((b.x - predicted[0]) * (b.x - predicted[0]) + (b.z - predicted[2]) * (b.z - predicted[2]))
        assertTrue("dopadla u značky (odchylka $miss m)", miss < 40f)
        assertTrue("silná bomba", b.size > 2f)

        // stisk drží → padají další bomby, ale pomaleji než rakety
        val g = Combat(w, Life(w))
        g.trigger = true
        repeat(60 * 3) { g.update(dt, f, Weapon.BOMBS) }
        assertTrue("neomezeně (${g.launches})", g.launches in 4..7)
    }
}
