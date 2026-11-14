package app.letadlo.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldTest {

    @Test
    fun runwayIsFlatOnEveryMap() {
        for (map in MapType.entries) {
            val w = World(map)
            var z = -World.RUNWAY_HALF_LENGTH
            while (z <= World.RUNWAY_HALF_LENGTH) {
                for (x in listOf(-25f, 0f, 25f)) {
                    assertEquals("$map ($x,$z)", w.airportHeight, w.groundHeight(x, z), 0.01f)
                }
                z += 37f
            }
            assertTrue(w.onRunway(0f, World.START_Z))
            assertFalse(w.isWater(0f, World.START_Z))
        }
    }

    @Test
    fun terrainIsDeterministic() {
        val a = World(MapType.MOUNTAINS)
        val b = World(MapType.MOUNTAINS)
        for (i in 0 until 50) {
            val x = i * 731f - 9000f
            val z = i * -517f + 4000f
            assertEquals(a.rawHeight(x, z), b.rawHeight(x, z), 0f)
        }
    }

    @Test
    fun cachedAndUncachedHeightsMatch() {
        val w = World(MapType.MOUNTAINS)
        val pts = (0 until 40).map { Pair(it * 53.3f - 300f, it * 41.7f - 900f) }
        val before = pts.map { w.groundHeight(it.first, it.second) }
        for (cz in -2..1) for (cx in -1..4) w.content(cx, cz)
        val after = pts.map { w.groundHeight(it.first, it.second) }
        for (i in pts.indices) assertEquals(before[i], after[i], 1e-3f)
    }

    @Test
    fun chunkEdgesMatch() {
        val w = World(MapType.MOUNTAINS)
        val a = w.content(3, -2)
        val right = w.content(4, -2)
        val below = w.content(3, -1)
        for (k in 0..World.CELLS) {
            assertEquals(a.h(World.CELLS, k), right.h(0, k), 0f)
            assertEquals(a.h(k, World.CELLS), below.h(k, 0), 0f)
        }
    }

    @Test
    fun mapsHaveTheirCharacter() {
        val mountains = World(MapType.MOUNTAINS)
        var maxH = 0f
        for (i in -30..30) for (j in -30..30) maxH = maxOf(maxH, mountains.rawHeight(i * 200f, j * 200f))
        assertTrue("hory mají vysoké štíty ($maxH m)", maxH > 1200f)

        val city = World(MapType.CITY)
        var buildings = 0
        val ccx = World.chunkOf(World.CITY_X)
        val ccz = World.chunkOf(World.CITY_Z)
        for (dz in -3..3) for (dx in -3..3) buildings += city.content(ccx + dx, ccz + dz).buildings.size
        assertTrue("město má budovy ($buildings)", buildings > 150)

        val country = World(MapType.COUNTRYSIDE)
        var trees = 0
        var fields = 0
        for (dz in -4..4) for (dx in -4..4) {
            val c = country.content(dx, dz - 8)
            trees += c.trees.size
            for (k in c.colors.indices step 4) if (c.colors[k + 3] < 0.5f) fields++
        }
        assertTrue("krajina má lesy ($trees)", trees > 200)
        assertTrue("krajina má pole ($fields)", fields > 1000)
    }

    @Test
    fun villagesSuburbsAndClouds() {
        val country = World(MapType.COUNTRYSIDE)
        var houses = 0
        var clouds = 0
        for (dz in -8..8) for (dx in -8..8) {
            val c = country.content(dx, dz)
            houses += c.houses.size
            clouds += c.clouds.size
            for (h in c.houses) {
                assertFalse("dům ve vodě", country.groundHeight(h.x, h.z) < World.SEA_LEVEL)
                for (t in c.trees) {
                    val inside = kotlin.math.abs(t.x - h.x) < h.halfW && kotlin.math.abs(t.z - h.z) < h.halfD
                    assertFalse("strom v domě", inside)
                }
            }
        }
        assertTrue("v krajině jsou vesnice ($houses domů)", houses > 40)
        assertTrue("jsou mraky ($clouds)", clouds > 30)

        val city = World(MapType.CITY)
        var suburb = 0
        val ccx = World.chunkOf(World.CITY_X)
        val ccz = World.chunkOf(World.CITY_Z)
        for (dz in -7..7) for (dx in -7..7) {
            for (h in city.content(ccx + dx, ccz + dz).houses) {
                if (city.distToCity(h.x, h.z) > World.CITY_RADIUS - 200f) suburb++
            }
        }
        assertTrue("město má předměstí ($suburb domků)", suburb > 200)
    }

    @Test
    fun houseIsAnObstacle() {
        val w = World(MapType.COUNTRYSIDE)
        var house: House? = null
        loop@ for (dz in -8..8) for (dx in -8..8) {
            val hs = w.content(dx, dz).houses
            if (hs.isNotEmpty()) {
                house = hs[0]
                break@loop
            }
        }
        val h = house!!
        assertEquals(ObstacleKind.BUILDING, w.hitObstacle(h.x, h.y + h.wallH * 0.5f, h.z, 1f))
        assertEquals(null, w.hitObstacle(h.x, h.y + h.wallH + h.roofH + 40f, h.z, 3f))
    }

    @Test
    fun noObstaclesOnRunway() {
        for (map in MapType.entries) {
            val w = World(map)
            var z = World.RUNWAY_HALF_LENGTH
            while (z > -World.RUNWAY_HALF_LENGTH) {
                assertEquals(null, w.hitObstacle(0f, w.airportHeight + 3f, z, AircraftType.AIRLINER.radius))
                z -= 50f
            }
        }
    }

    @Test
    fun meshesAreValid() {
        val w = World(MapType.CITY)
        val m = ChunkMesher.build(w, w.content(World.chunkOf(World.CITY_X), World.chunkOf(World.CITY_Z)))
        for (mesh in listOf(m.terrainNear, m.terrainFar, m.trees, m.buildings)) {
            for (i in 0 until mesh.indexCount) assertTrue(mesh.indices[i] in 0 until mesh.vertexCount)
        }
        assertTrue(m.terrainNear.indexCount > m.terrainFar.indexCount)
        assertTrue(m.buildings.indexCount > 0)
        val meshes = ArrayList<MeshData>()
        for (t in AircraftType.entries) {
            meshes += AircraftMeshes.body(t)
            meshes += AircraftMeshes.cockpit(t)
            meshes += AircraftMeshes.shadow(t)
            meshes += AircraftMeshes.rotors(t).map { it.mesh }
        }
        meshes += AircraftMeshes.ball()
        for (v in 0 until Life.CAR_VARIANTS) meshes += LifeMeshes.car(v)
        for (v in 0 until Life.PERSON_VARIANTS) meshes += LifeMeshes.person(v)
        meshes += LifeMeshes.boat(true)
        meshes += LifeMeshes.boat(false)
        meshes += LifeMeshes.turbineBlades(34f)
        meshes += LifeMeshes.ring()
        meshes += LifeMeshes.diamond()
        meshes += LifeMeshes.helipad()
        meshes += LifeMeshes.rocket()
        meshes += LifeMeshes.bomb()
        for (st in intArrayOf(TargetStyle.RADAR, TargetStyle.FUEL, TargetStyle.TANK, TargetStyle.FACTORY, TargetStyle.BALLOON)) {
            meshes += LifeMeshes.target(st)
        }
        for (mesh in meshes) {
            assertTrue(mesh.indexCount > 0)
            for (i in 0 until mesh.indexCount) assertTrue(mesh.indices[i] in 0 until mesh.vertexCount)
        }
    }
}
