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
    fun everyPlaneAcceleratesOnRunwayAndTakesOffWhenPulledBack() {
        for (type in AircraftType.entries.filter { !it.vertical }) for (map in MapType.entries) {
            val f = FlightModel(World(map), type)
            var t = 0f
            while (f.speed < type.rotateSpeed && t < 40f) {
                f.update(dt, 0f, 0f)
                t += dt
            }
            assertEquals("$type/$map: bez přitažení zůstává na zemi", FlightPhase.ROLLING, f.phase)
            assertTrue("$type/$map: rozjezd trvá příliš dlouho ($t s)", t < 25f)
            assertTrue("$type/$map: zbývá aspoň 600 m dráhy (z=${f.z})", f.world.onRunway(f.x, f.z) && f.z > -World.RUNWAY_HALF_LENGTH + 600f)

            run(f, 14f, 0f, 0.8f)
            assertEquals("$type/$map: po přitažení letí (${f.crashReason})", FlightPhase.AIRBORNE, f.phase)
            assertTrue("$type/$map: nastoupal (${f.altitudeAgl} m)", f.altitudeAgl > 40f)
            assertTrue(f.hasTakenOff)
        }
    }

    @Test
    fun cruiseSpeedsAreRealistic() {
        val minKmh = mapOf(
            AircraftType.SPORT to 250f, AircraftType.FIGHTER to 2000f, AircraftType.AIRLINER to 750f,
            AircraftType.HELICOPTER to 230f, AircraftType.BOMBER to 420f, AircraftType.AIRSHIP to 110f, AircraftType.DRONE to 140f,
        )
        for (type in AircraftType.entries) {
            val f = FlightModel(World(MapType.COUNTRYSIDE), type)
            f.placeInAir(0f, 2000f, -20000f, 0f, if (type.vertical) 10f else type.rotateSpeed + 5f)
            run(f, 120f, 0f, 0f)
            val kmh = f.speed * 3.6f
            assertTrue("$type letí $kmh km/h", kmh >= minKmh.getValue(type))
        }
    }

    @Test
    fun fullClimbAfterTakeoffNeverNearsStall() {
        for (type in AircraftType.entries.filter { !it.vertical }) {
            val f = FlightModel(World(MapType.COUNTRYSIDE), type)
            f.placeInAir(0f, 800f, -20000f, 0f, type.rotateSpeed + 2f)
            var minSpeed = Float.MAX_VALUE
            val y0 = f.y
            var t = 0f
            while (t < 40f) {
                f.update(dt, 0f, 1f)
                minSpeed = minOf(minSpeed, f.speed)
                t += dt
            }
            assertEquals(FlightPhase.AIRBORNE, f.phase)
            assertTrue("$type: drží rychlost ${minSpeed * 3.6f} km/h nad pádovou ${type.stallSpeed * 3.6f}",
                minSpeed > type.stallSpeed + 7f)
            assertTrue("$type: nastoupal ${f.y - y0} m", f.y - y0 > 600f)
        }
    }

    @Test
    fun smallBankAlreadyTurns() {
        for (type in AircraftType.entries) {
            val f = FlightModel(World(MapType.COUNTRYSIDE), type)
            f.placeInAir(0f, 2000f, -20000f, 0f, if (type.vertical) 60f else type.rotateSpeed * 2f)
            run(f, 3f, 0.3f, 0f)
            val degPerS = Math.toDegrees(f.heading.toDouble()) / 3.0
            assertTrue("$type: třetinová výchylka točí ${"%.1f".format(degPerS)} °/s", degPerS > type.maxTurnDeg * 0.15)
        }
    }

    @Test
    fun helicopterTakesOffVerticallyFliesAndLandsAnywhere() {
        val w = World(MapType.COUNTRYSIDE)
        val f = FlightModel(w, AircraftType.HELICOPTER)
        run(f, 1f, 0f, 1f)
        assertEquals("rotor se teprve roztáčí", FlightPhase.ROLLING, f.phase)
        run(f, 2f, 0f, 0f)
        assertTrue(f.readyForTakeoff)
        val z0 = f.z
        run(f, 3f, 0f, 1f)
        assertEquals(FlightPhase.AIRBORNE, f.phase)
        assertTrue("stoupá skoro svisle (${f.altitudeAgl} m, posun ${z0 - f.z} m)", f.altitudeAgl > 15f && z0 - f.z < 60f)
        run(f, 15f, 0f, 0.3f)
        assertTrue("letí dopředu (${f.speed})", f.speed > 30f)
        // zpátky dolů – přistane mimo dráhu, pokud je tam rovina
        f.placeInAir(4000f, w.groundHeight(4000f, 4000f) + 40f, 4000f, 0f, 0f)
        var landed = false
        var t = 0f
        while (t < 30f && f.phase != FlightPhase.CRASHED) {
            f.update(dt, 0f, -0.25f)
            if (f.phase == FlightPhase.ROLLING) { landed = true; break }
            t += dt
        }
        if (!w.isWater(f.x, f.z)) assertTrue("přistál (${f.crashReason})", landed)
    }

    @Test
    fun tiltIsIgnoredBeforeRotationSpeed() {
        val f = FlightModel(World(MapType.COUNTRYSIDE))
        run(f, 2f, 0f, 1f)
        assertEquals(FlightPhase.ROLLING, f.phase)
        assertTrue(f.speed < AircraftType.SPORT.rotateSpeed)
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
        assertTrue("strop (${f.y})", f.y < AircraftType.SPORT.ceiling + 200f)
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
        f.placeInAir(0f, w.airportHeight + f.gearHeight + 8f, 600f, 0f, 45f)
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
