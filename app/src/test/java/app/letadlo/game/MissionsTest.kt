package app.letadlo.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2

class MissionsTest {

    private val dt = 1f / 60f

    private fun step(f: FlightModel, m: Missions, c: Combat?, seconds: Float, roll: Float = 0f, pitch: Float = 0f) {
        var t = 0f
        while (t < seconds && f.phase != FlightPhase.CRASHED) {
            f.update(dt, roll, pitch)
            c?.update(dt, f, Weapon.NONE)
            m.update(dt, f, c)
            t += dt
        }
    }

    /** Proletí aktuálním kruhem: postaví letadlo kus před něj a nechá ho proletět. */
    private fun flyThroughCurrentRing(f: FlightModel, m: Missions, c: Combat?, speed: Float) {
        val g = m.goals.first { it.kind == GoalKind.RING && !it.preview }
        f.placeInAir(g.x - g.nx * 60f, g.y, g.z - g.nz * 60f, atan2(g.nx, -g.nz), speed)
        g.prevSide = Float.NaN
        step(f, m, c, 120f / speed + 0.3f)
    }

    @Test
    fun helicopterHoldsAltitudeAndClimbsFast() {
        val w = World(MapType.COUNTRYSIDE)
        val f = FlightModel(w, AircraftType.HELICOPTER)
        f.placeInAir(3000f, w.groundHeight(3000f, 3000f) + 500f, 3000f, 0f, 40f)
        val y0 = f.y
        repeat(60 * 20) { f.update(dt, 0f, 0.1f) }
        assertTrue("drží výšku i s mírným náklonem (${f.y - y0} m)", abs(f.y - y0) < 10f)
        val y1 = f.y
        repeat(60 * 10) { f.update(dt, 0f, 1f) }
        assertTrue("rychle stoupá (${f.y - y1} m za 10 s)", f.y - y1 > 200f)
    }

    @Test
    fun helicopterLandsOnRoofAndTakesOffAgain() {
        val w = World(MapType.CITY)
        val ccx = World.chunkOf(World.CITY_X)
        val ccz = World.chunkOf(World.CITY_Z)
        for (dz in -1..1) for (dx in -1..1) w.content(ccx + dx, ccz + dz)
        val b = w.content(ccx, ccz).buildings.first { it.maxX - it.minX > 25f && it.maxZ - it.minZ > 25f && it.maxY - it.minY > 50f }
        val cx = (b.minX + b.maxX) / 2f
        val cz = (b.minZ + b.maxZ) / 2f
        val f = FlightModel(w, AircraftType.HELICOPTER)
        f.placeInAir(cx, b.top + f.gearHeight + 8f, cz, 0f, 0f)
        var t = 0f
        while (t < 15f && f.phase == FlightPhase.AIRBORNE) {
            f.update(dt, 0f, -0.25f)
            t += dt
        }
        assertEquals("sedí na střeše (${f.crashReason})", FlightPhase.ROLLING, f.phase)
        assertEquals("sedí na nejvyšší střeše v místě", w.roofTop(cx, cz)!! + f.gearHeight, f.y, 0.2f)
        repeat(60 * 4) { f.update(dt, 0f, 1f) }
        assertEquals("vzlétl ze střechy (${f.crashReason})", FlightPhase.AIRBORNE, f.phase)
        assertTrue(f.y > b.top + 30f)
    }

    @Test
    fun ringsGiveScoreAndBuildCombo() {
        val w = World(MapType.COUNTRYSIDE)
        val f = FlightModel(w, AircraftType.SPORT)
        val m = Missions(w, AircraftType.SPORT)
        f.placeInAir(2000f, 600f, 2000f, 0f, 70f)
        m.update(dt, f, null)
        assertTrue("je aktivní kruh", m.goals.any { it.kind == GoalKind.RING && !it.preview })
        assertTrue("je vidět i další kruh", m.goals.any { it.kind == GoalKind.RING && it.preview })
        repeat(3) { flyThroughCurrentRing(f, m, null, 70f) }
        assertEquals(3, m.rings)
        assertEquals(4, m.multiplier)
        assertTrue(m.score >= 200 + 400 + 600)
        assertTrue(m.popups.any { it.text.startsWith("Kruh") })
    }

    @Test
    fun ringsNeverTouchTerrainOrBuildings() {
        for (map in MapType.entries) for (type in listOf(AircraftType.SPORT, AircraftType.FIGHTER, AircraftType.AIRLINER)) {
            val w = World(map)
            val m = Missions(w, type)
            var x = 0f
            var z = -6000f
            repeat(12) {
                val g = m.spawnRing(x, 400f, z, 0.3f, 150f)
                assertNotNull(g)
                val ring = g!!
                assertTrue("$map/$type kruh nad zemí", ring.y - ring.radius > w.groundHeight(ring.x, ring.z))
                assertTrue("$map/$type pod stropem", ring.y < type.ceiling)
                x = ring.x; z = ring.z
            }
        }
    }

    @Test
    fun fighterDestroysGroundAndAirTargets() {
        val w = World(MapType.COUNTRYSIDE)
        val life = Life(w)
        val combat = Combat(w, life)
        val f = FlightModel(w, AircraftType.FIGHTER)
        val m = Missions(w, AircraftType.FIGHTER)
        f.placeInAir(0f, 900f, -15000f, 0f, 300f)
        m.update(dt, f, combat)
        val ground = m.goals.first { it.kind == GoalKind.GROUND_TARGET }
        val air = m.goals.first { it.kind == GoalKind.AIR_TARGET }
        combat.explosion(ground.x, ground.y + 2f, ground.z, Combat.ROCKET_BLAST)
        m.update(dt, f, combat)
        assertEquals(1, m.targets)
        // raketa těsně u balonu vybuchne a sestřelí ho
        combat.rockets.add(Rocket(air.x + 4f, air.y, air.z, 0f, 0f, -1f))
        m.update(dt, f, combat)
        assertEquals(2, m.targets)
        assertTrue(m.score > 0)
        assertTrue("místo zničených se objeví nové cíle", m.goals.count { it.kind == GoalKind.GROUND_TARGET } >= 2)
    }

    @Test
    fun bomberGetsBigGroundTargets() {
        val w = World(MapType.COUNTRYSIDE)
        val f = FlightModel(w, AircraftType.BOMBER)
        val m = Missions(w, AircraftType.BOMBER)
        f.placeInAir(0f, 600f, -15000f, 0f, 120f)
        m.update(dt, f, Combat(w, Life(w)))
        assertEquals(3, m.goals.count { it.kind == GoalKind.GROUND_TARGET })
        assertTrue(m.objective.contains("cíl"))
    }

    @Test
    fun helicopterRescueCycle() {
        val w = World(MapType.MOUNTAINS)
        val f = FlightModel(w, AircraftType.HELICOPTER)
        val m = Missions(w, AircraftType.HELICOPTER)
        m.update(dt, f, null)
        val pick = m.goals.first { it.kind == GoalKind.PICKUP }
        f.placeInAir(pick.x, pick.y + f.gearHeight + 4f, pick.z, 0f, 0f)
        step(f, m, null, 6f, pitch = -0.25f)
        assertTrue("zraněný nastoupil (${f.phase} ${f.crashReason})", m.carrying)
        val drop = m.goals.first { it.kind == GoalKind.DROPOFF }
        f.placeInAir(drop.x, drop.y + f.gearHeight + 4f, drop.z, 0f, 0f)
        step(f, m, null, 6f, pitch = -0.25f)
        assertEquals(1, m.rescued)
        assertFalse(m.carrying)
        assertTrue(m.score >= 1000)
        assertTrue("další zraněný", m.goals.any { it.kind == GoalKind.PICKUP })
    }

    @Test
    fun airlinerRouteApproachAndRatedLanding() {
        val w = World(MapType.COUNTRYSIDE)
        val f = FlightModel(w, AircraftType.AIRLINER)
        val m = Missions(w, AircraftType.AIRLINER)
        f.placeInAir(0f, 800f, -3000f, 0f, 150f)
        m.update(dt, f, null)
        repeat(Missions.ROUTE_RINGS) { flyThroughCurrentRing(f, m, null, 150f) }
        assertTrue(m.objective, m.objective.contains("koridor"))
        repeat(3) { flyThroughCurrentRing(f, m, null, 120f) }
        assertTrue(m.objective, m.objective.contains("Přistaň"))
        // měkké dosednutí na dráhu
        f.placeInAir(0f, w.airportHeight + f.gearHeight + 5f, 1300f, 0f, 78f)
        var t = 0f
        while (t < 20f && f.phase == FlightPhase.AIRBORNE) {
            f.update(dt, 0f, -0.08f)
            m.update(dt, f, null)
            t += dt
        }
        assertEquals(FlightPhase.ROLLING, f.phase)
        m.update(dt, f, null)
        assertTrue(m.popups.any { it.text.startsWith("Přistání ★") })
        assertTrue(m.popups.any { it.text.startsWith("Let dokončen") })
        assertTrue("brzdí", f.brake)
        step(f, m, null, 20f)
        assertFalse("po zastavení se chystá další let", f.brake)
        assertTrue(m.objective, m.objective.contains("trasy") || m.objective.contains("Vzlétni"))
    }

    @Test
    fun lowFlyingAndLandingsScore() {
        val w = World(MapType.COUNTRYSIDE)
        val f = FlightModel(w, AircraftType.FIGHTER)
        val m = Missions(w, AircraftType.FIGHTER)
        f.placeInAir(0f, w.groundHeight(0f, -2000f) + 25f, -2000f, 3.14159f, 300f)
        step(f, m, null, 3f)
        assertTrue("body za nízký let", m.popups.any { it.text.startsWith("Nízký let") })

        val s = FlightModel(w, AircraftType.SPORT)
        val ms = Missions(w, AircraftType.SPORT)
        s.placeInAir(0f, w.airportHeight + s.gearHeight + 6f, 800f, 0f, 45f)
        var t = 0f
        while (t < 10f && s.phase == FlightPhase.AIRBORNE) {
            s.update(dt, 0f, -0.08f)
            ms.update(dt, s, null)
            t += dt
        }
        ms.update(dt, s, null)
        assertTrue(ms.popups.any { it.text.startsWith("Přistání") })
    }
}
