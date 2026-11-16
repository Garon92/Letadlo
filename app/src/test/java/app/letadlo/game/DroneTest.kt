package app.letadlo.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2

class DroneTest {

    private val dt = 1f / 60f

    @Test
    fun armsQuicklyTakesOffAndFlies() {
        val w = World(MapType.COUNTRYSIDE)
        val f = FlightModel(w, AircraftType.DRONE)
        repeat(70) { f.update(dt, 0f, 0f) }
        assertTrue("motory zapnuté za ~1 s", f.readyForTakeoff)
        repeat(60 * 3) { f.update(dt, 0f, 0.6f) }
        assertEquals(FlightPhase.AIRBORNE, f.phase)
        assertTrue("vyletěl (${f.altitudeAgl} m)", f.altitudeAgl > 20f)
        repeat(60 * 6) { f.update(dt, 0f, 0f) }
        assertTrue("letí ~150 km/h (${f.speed * 3.6f})", f.speed * 3.6f in 135f..165f)
    }

    @Test
    fun divesFastAndTurnsOnADime() {
        val w = World(MapType.COUNTRYSIDE)
        val f = FlightModel(w, AircraftType.DRONE)
        f.placeInAir(0f, 1200f, -20000f, 0f, 42f)
        repeat(60 * 4) { f.update(dt, 0f, -0.8f) }
        assertTrue("střemhlav přes 190 km/h (${f.speed * 3.6f})", f.speed * 3.6f > 190f)
        val g = FlightModel(w, AircraftType.DRONE)
        g.placeInAir(0f, 1200f, -20000f, 0f, 42f)
        var t = 0f
        while (Math.toDegrees(g.heading.toDouble()) < 90.0 && t < 3f) {
            g.update(dt, 1f, 0f)
            t += dt
        }
        assertTrue("otočka o 90° za ${"%.2f".format(t)} s", t < 1.2f)
    }

    @Test
    fun flipCompletesAndCounts() {
        val f = FlightModel(World(MapType.COUNTRYSIDE), AircraftType.DRONE)
        f.placeInAir(0f, 500f, -20000f, 0f, 42f)
        assertTrue(f.startFlip())
        assertTrue("během salta nejde začít další", !f.startFlip())
        repeat(15) { f.update(dt, 0f, 0f) }
        assertTrue("uprostřed salta je dron natočený", abs(f.flipRoll) > 1f)
        repeat(30) { f.update(dt, 0f, 0f) }
        assertEquals(1, f.flips)
        assertEquals(0f, f.flipRoll, 1e-4f)
        val ground = FlightModel(World(MapType.COUNTRYSIDE), AircraftType.DRONE)
        assertTrue("na zemi salto nejde", !ground.startFlip())
    }

    @Test
    fun smallDroneStillCrashesIntoTrees() {
        val w = World(MapType.MOUNTAINS)
        val c = (-6..6).flatMap { z -> (-6..6).map { x -> w.content(x, z) } }.first { ch -> ch.trees.any { it.conifer } }
        val tree = c.trees.first { it.conifer }
        val f = FlightModel(w, AircraftType.DRONE)
        f.placeInAir(tree.x, tree.y + tree.height * 0.4f, tree.z + 30f, 0f, 42f)
        repeat(60 * 2) { if (f.phase != FlightPhase.CRASHED) f.update(dt, 0f, 0f) }
        assertEquals(FlightPhase.CRASHED, f.phase)
        assertEquals(CrashReason.TREE, f.crashReason)
    }

    @Test
    fun neonGatesAreLowClearAndScore() {
        for (map in MapType.entries) {
            val w = World(map)
            val m = Missions(w, AircraftType.DRONE)
            var x = if (map == MapType.CITY) World.CITY_X + 11f else 3000f
            var z = if (map == MapType.CITY) World.CITY_Z + 1000f else 3000f
            var h = 0f
            repeat(10) {
                val g = m.spawnGate(x, z, h)!!
                val gh = w.surfaceHeight(g.x, g.z)
                assertTrue("$map brána nízko (${g.y - gh} m nad zemí)", g.y - gh < 30f)
                assertTrue("$map brána průletná", m.gateClear(g.x, g.y, g.z, g.radius, g.nx, g.nz))
                x = g.x; z = g.z; h = atan2(g.nx, -g.nz)
            }
        }
        val w = World(MapType.COUNTRYSIDE)
        val f = FlightModel(w, AircraftType.DRONE)
        val m = Missions(w, AircraftType.DRONE)
        f.placeInAir(3000f, w.groundHeight(3000f, 3000f) + 10f, 3000f, 0f, 42f)
        m.update(dt, f, null)
        repeat(10) {
            val g = m.goals.first { it.kind == GoalKind.GATE && !it.preview }
            f.placeInAir(g.x - g.nx * 10f, g.y, g.z - g.nz * 10f, atan2(g.nx, -g.nz), 42f)
            g.prevSide = Float.NaN
            repeat(30) {
                f.update(dt, 0f, 0f)
                m.update(dt, f, null)
            }
        }
        assertEquals(10, m.gates)
        assertTrue(m.popups.any { it.text.startsWith("Úsek") })
        assertEquals(Missions.MAX_MULT, m.multiplier)
        f.startFlip()
        repeat(40) {
            f.update(dt, 0f, 0f)
            m.update(dt, f, null)
        }
        assertTrue(m.popups.any { it.text.contains("alto") })
    }
}
