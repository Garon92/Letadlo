package app.letadlo.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlightModelTest {

    private val dt = 1f / 60f

    private fun run(f: FlightModel, seconds: Float, roll: Float, pitch: Float, stopOnCrash: Boolean = true) {
        var t = 0f
        while (t < seconds) {
            f.update(dt, roll, pitch)
            t += dt
            if (stopOnCrash && f.phase == FlightPhase.CRASHED) return
        }
    }

    @Test
    fun acceleratesOnRunwayAndTakesOffWhenPulledBack() {
        for (map in MapType.entries) {
            val f = FlightModel(World(map))
            var t = 0f
            while (f.speed < FlightModel.ROTATE_SPEED && t < 30f) {
                f.update(dt, 0f, 0f)
                t += dt
            }
            assertEquals("$map: bez přitažení zůstává na zemi", FlightPhase.ROLLING, f.phase)
            assertTrue("$map: rozjezd trvá příliš dlouho ($t s)", t < 15f)
            assertTrue("$map: na dráze", f.world.onRunway(f.x, f.z))

            run(f, 10f, 0f, 0.7f)
            assertEquals("$map: po přitažení letí (${f.crashReason})", FlightPhase.AIRBORNE, f.phase)
            assertTrue("$map: nastoupal (${f.altitudeAgl} m)", f.altitudeAgl > 40f)
            assertTrue(f.hasTakenOff)
        }
    }

    @Test
    fun tiltIsIgnoredBeforeRotationSpeed() {
        val f = FlightModel(World(MapType.COUNTRYSIDE))
        run(f, 2f, 0f, 1f)
        assertEquals(FlightPhase.ROLLING, f.phase)
        assertTrue(f.speed < FlightModel.ROTATE_SPEED)
    }

    @Test
    fun rollingOffTheRunwayEndsTheFlight() {
        val f = FlightModel(World(MapType.COUNTRYSIDE))
        run(f, 90f, 0f, 0f)
        assertEquals(FlightPhase.CRASHED, f.phase)
        assertEquals(CrashReason.OFF_RUNWAY, f.crashReason)
    }

    @Test
    fun noFuelPlaneFliesForever() {
        val w = World(MapType.COUNTRYSIDE)
        val f = FlightModel(w)
        f.placeInAir(4000f, 1500f, 4000f, 0.3f, 70f)
        run(f, 600f, 0f, 0f)
        assertEquals(FlightPhase.AIRBORNE, f.phase)
        assertTrue("rychlost se ustálí", f.speed in 60f..90f)
        assertTrue("letí zhruba vodorovně", f.y in 1300f..1700f)
        assertTrue("uletěl přes 30 km", f.distance > 30_000f)
    }

    @Test
    fun turningChangesHeading() {
        val f = FlightModel(World(MapType.COUNTRYSIDE))
        f.placeInAir(0f, 1500f, -8000f, 0f, 70f)
        run(f, 5f, 1f, 0f)
        assertTrue("zatočil doprava (kurz ${f.heading})", f.heading > 0.3f)
        assertTrue("náklon křídel doprava", f.bank > 0.5f)
        val f2 = FlightModel(World(MapType.COUNTRYSIDE))
        f2.placeInAir(0f, 1500f, -8000f, 0f, 70f)
        run(f2, 5f, -1f, 0f)
        assertTrue("zatočil doleva", f2.heading < -0.3f)
    }

    @Test
    fun divingIntoGroundCrashes() {
        for (map in MapType.entries) {
            val f = FlightModel(World(map))
            f.placeInAir(6000f, f.world.groundHeight(6000f, 6000f) + 300f, 6000f, 1f, 70f)
            run(f, 60f, 0f, -1f)
            assertEquals("$map", FlightPhase.CRASHED, f.phase)
            assertNotNull(f.crashReason)
            assertNotEquals(CrashReason.OFF_RUNWAY, f.crashReason)
        }
    }

    @Test
    fun ceilingStopsClimbing() {
        val f = FlightModel(World(MapType.COUNTRYSIDE))
        f.placeInAir(0f, 3000f, -8000f, 0f, 80f)
        run(f, 120f, 0f, 1f)
        assertEquals(FlightPhase.AIRBORNE, f.phase)
        assertTrue("strop (${f.y})", f.y < FlightModel.CEILING + 200f)
    }

    @Test
    fun fallingIntoRiverIsWaterCrash() {
        val w = World(MapType.CITY)
        val z0 = -12000f
        val x0 = World.riverX(z0)
        assertTrue(w.isWater(x0, z0))
        val f = FlightModel(w)
        f.placeInAir(x0, 3f, z0, 0f, 50f)
        run(f, 10f, 0f, -1f)
        assertEquals(FlightPhase.CRASHED, f.phase)
        assertEquals(CrashReason.WATER, f.crashReason)
    }

    @Test
    fun flyingIntoSkyscraperCrashes() {
        val w = World(MapType.CITY)
        val ccx = World.chunkOf(World.CITY_X)
        val ccz = World.chunkOf(World.CITY_Z)
        var target: Box? = null
        loop@ for (dz in -2..2) for (dx in -2..2) {
            for (b in w.content(ccx + dx, ccz + dz).buildings) {
                if (b.maxY - b.minY > 80f) {
                    target = b
                    break@loop
                }
            }
        }
        assertNotNull("ve středu města jsou mrakodrapy", target)
        val b = target!!
        val f = FlightModel(w)
        val mx = (b.minX + b.maxX) / 2f
        f.placeInAir(mx, b.minY + 40f, b.maxZ + 60f, 0f, 70f)
        run(f, 5f, 0f, 0f)
        assertEquals(FlightPhase.CRASHED, f.phase)
        assertTrue(f.crashReason == CrashReason.BUILDING || f.crashReason == CrashReason.TREE)
    }

    @Test
    fun gentleTouchdownOnRunwayLands() {
        val w = World(MapType.COUNTRYSIDE)
        val f = FlightModel(w)
        f.placeInAir(0f, w.airportHeight + FlightModel.GEAR_HEIGHT + 8f, 600f, 0f, 45f)
        var landed = false
        var t = 0f
        while (t < 8f && f.phase != FlightPhase.CRASHED) {
            f.update(dt, 0f, -0.1f)
            if (f.phase == FlightPhase.ROLLING) landed = true
            t += dt
        }
        assertTrue("přistál (${f.crashReason})", landed)
    }
}
