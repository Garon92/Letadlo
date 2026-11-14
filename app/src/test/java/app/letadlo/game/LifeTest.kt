package app.letadlo.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class LifeTest {

    private val dt = 1f / 30f

    @Test
    fun cityHasCarsAndPeopleOnStreets() {
        val w = World(MapType.CITY)
        val life = Life(w)
        repeat(300) { life.update(dt, World.CITY_X, World.CITY_Z) }
        assertTrue("auta (${life.activeCars})", life.activeCars > 40)
        assertTrue("lidé (${life.activePeople})", life.activePeople > 60)
        for (a in life.cars) if (a.active) {
            assertTrue("auto ve městě", w.distToCity(a.x, a.z) < World.CITY_ROAD_RADIUS)
            // auto jede v jízdním pruhu (uprostřed silnice, ne v blocích domů)
            val lane = if (a.alongX) a.z - World.CITY_Z else a.x - World.CITY_X
            val l = ((lane % World.BLOCK) + World.BLOCK) % World.BLOCK
            assertTrue("pruh $l", l < World.ROAD)
            assertEquals(null, w.hitObstacle(a.x, a.y + 1f, a.z, 0.5f).takeIf { it == ObstacleKind.BUILDING })
        }
    }

    @Test
    fun explosionThrowsNearbyCarsAndPeople() {
        val w = World(MapType.CITY)
        val life = Life(w)
        repeat(300) { life.update(dt, World.CITY_X, World.CITY_Z) }
        val target = life.cars.first { it.active }
        val before = floatArrayOf(target.x, target.z)
        val hit = life.explode(target.x, target.y + 2f, target.z, 80f)
        assertTrue("zasaženo ($hit)", hit >= 1)
        assertEquals(Agent.FLYING, target.state)
        var maxY = target.y
        repeat(300) {
            life.update(dt, World.CITY_X, World.CITY_Z)
            maxY = maxOf(maxY, target.y)
        }
        assertTrue("vyletělo do vzduchu ($maxY)", maxY > World.CITY_HEIGHT + 3f)
        assertEquals("po dopadu leží", Agent.LYING, target.state)
        val dx = target.x - before[0]
        val dz = target.z - before[1]
        assertTrue("odletělo", sqrt(dx * dx + dz * dz) > 3f)
        assertTrue("leží na zemi", abs(target.y - w.groundHeight(target.x, target.z)) < 0.5f)
    }

    @Test
    fun birdsAndBoatsAppear() {
        val w = World(MapType.COUNTRYSIDE)
        val life = Life(w)
        repeat(600) { life.update(dt, 0f, -6000f) }
        assertTrue("ptáci (${life.flocks.size})", life.flocks.isNotEmpty())
        val pose = FloatArray(5)
        for (f in life.flocks) for (i in 0 until f.count) {
            life.birdPose(f, i, pose)
            assertTrue("pták nad zemí", pose[1] > w.groundHeight(pose[0], pose[2]))
        }
        val city = World(MapType.CITY)
        val cl = Life(city)
        val rz = -9000f
        repeat(600) { cl.update(dt, World.riverX(rz), rz) }
        assertTrue("lodě na řece (${cl.boats.size})", cl.boats.isNotEmpty())
        for (b in cl.boats) assertTrue(city.groundHeight(b.x, b.z) < 0f)
    }

    @Test
    fun countrysideHasTurbinesHerdsAndRocksInMountains() {
        val c = World(MapType.COUNTRYSIDE)
        var turbines = 0
        var animals = 0
        for (dz in -12..12) for (dx in -12..12) {
            val ch = c.content(dx, dz)
            turbines += ch.turbines.size
            animals += ch.trees.count { it.kind == PropKind.COW || it.kind == PropKind.SHEEP }
        }
        assertTrue("elektrárny ($turbines)", turbines > 5)
        assertTrue("zvířata ($animals)", animals > 30)
        val tb = (-12..12).flatMap { dz -> (-12..12).flatMap { dx -> c.content(dx, dz).turbines } }.first()
        assertEquals(ObstacleKind.TURBINE, c.hitObstacle(tb.x, tb.y + 30f, tb.z, 2f))

        val m = World(MapType.MOUNTAINS)
        var rocks = 0
        for (dz in -6..6) for (dx in -6..6) rocks += m.content(dx, dz).trees.count { it.kind == PropKind.ROCK }
        assertTrue("balvany ($rocks)", rocks > 20)
    }

    @Test
    fun parkedAircraftAreObstacles() {
        val w = World(MapType.COUNTRYSIDE)
        for (p in w.parkedAircraft) {
            assertEquals(ObstacleKind.BUILDING, w.hitObstacle(p.x, w.airportHeight + 2f, p.z, 1f))
        }
    }

    @Test
    fun batchTransformsTemplates() {
        val b = DynamicBatch(16)
        val car = LifeMeshes.car(0)
        b.add(car, 100f, 5f, -50f, 1.2f)
        b.add(car, 0f, 0f, 0f, 0f)
        b.addBird(0f, 50f, 0f, 0.3f, 1f, 2f, 0.2f, 0.2f, 0.2f)
        assertEquals(car.vertexCount * 2 + 9, b.vertexCount)
        for (i in 0 until b.indexCount) assertTrue(b.indices[i] in 0 until b.vertexCount)
        // první auto je posunuté kolem (100, 5, -50)
        var sx = 0f
        for (i in 0 until car.vertexCount) sx += b.vertices[i * MeshBuilder.STRIDE]
        assertEquals(100f, sx / car.vertexCount, 3f)
    }
}
