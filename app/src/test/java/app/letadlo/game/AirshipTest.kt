package app.letadlo.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class AirshipTest {

    private val dt = 1f / 60f

    @Test
    fun liftsOffVerticallyFloatsAndHoldsAltitude() {
        val w = World(MapType.COUNTRYSIDE)
        val f = FlightModel(w, AircraftType.AIRSHIP)
        repeat(60 * 3) { f.update(dt, 0f, 0f) }
        assertEquals("kotel je zatopený", true, f.readyForTakeoff)
        val z0 = f.z
        repeat(60 * 6) { f.update(dt, 0f, 1f) }
        assertEquals(FlightPhase.AIRBORNE, f.phase)
        assertTrue("stoupá skoro svisle (${f.altitudeAgl} m, posun ${z0 - f.z} m)", f.altitudeAgl > 30f && z0 - f.z < 120f)
        repeat(60 * 15) { f.update(dt, 0f, 1f) }
        val y0 = f.y
        repeat(60 * 20) { f.update(dt, 0f, 0.1f) }
        assertTrue("drží výšku (${f.y - y0})", abs(f.y - y0) < 25f)
        assertTrue("pluje cestovní rychlostí (${f.speed * 3.6f} km/h)", f.speed * 3.6f in 110f..135f)
    }

    @Test
    fun steamBoostIsFasterAndRunsOut() {
        val w = World(MapType.COUNTRYSIDE)
        val f = FlightModel(w, AircraftType.AIRSHIP)
        f.placeInAir(0f, 900f, -20000f, 0f, 35f)
        f.boostInput = true
        repeat(60 * 4) { f.update(dt, 0f, 0f) }
        assertTrue("pára zrychlí (${f.speed * 3.6f} km/h)", f.speed * 3.6f > 170f)
        repeat(60 * 6) { f.update(dt, 0f, 0f) }
        assertTrue("páry ubývá (${f.steam})", f.steam < 0.1f)
        assertTrue(!f.boosting)
        f.boostInput = false
        f.addSteam(0.5f)
        assertEquals(0.5f, f.steam, 0.05f)
    }

    @Test
    fun turnsGentlyButSurely() {
        val f = FlightModel(World(MapType.COUNTRYSIDE), AircraftType.AIRSHIP)
        f.placeInAir(0f, 900f, -20000f, 0f, 35f)
        repeat(60 * 6) { f.update(dt, 1f, 0f) }
        val deg = Math.toDegrees(f.heading.toDouble())
        assertTrue("zatočila ($deg°)", deg > 40.0)
        assertTrue("jen mírný náklon", abs(f.bank) < Math.toRadians(9.0))
    }

    @Test
    fun hitsMountainWithItsLongEnvelope() {
        val w = World(MapType.MOUNTAINS)
        val f = FlightModel(w, AircraftType.AIRSHIP)
        f.placeInAir(6000f, w.groundHeight(6000f, 6000f) + 200f, 6000f, 1f, 35f)
        repeat(60 * 120) { if (f.phase != FlightPhase.CRASHED) f.update(dt, 0f, -1f) }
        assertEquals(FlightPhase.CRASHED, f.phase)
    }

    @Test
    fun cannonShellsArcAndExplodeWhereAimed() {
        val w = World(MapType.COUNTRYSIDE)
        val life = Life(w)
        val combat = Combat(w, life)
        val f = FlightModel(w, AircraftType.AIRSHIP)
        f.placeInAir(3000f, w.groundHeight(3000f, 3000f) + 250f, 3000f, 0.5f, 35f)
        assertEquals(Weapon.CANNON, Combat.weaponFor(AircraftType.AIRSHIP))
        val predicted = FloatArray(3)
        combat.predictCannon(f, 1.5f, predicted)
        combat.trigger = true
        combat.update(dt, f, Weapon.CANNON)
        combat.trigger = false
        assertEquals(1, combat.shells.size)
        val s = combat.shells.first()
        var t = dt
        while (t < 1.5f - dt / 2) {
            combat.update(dt, f, Weapon.NONE)
            t += dt
        }
        if (combat.shells.isNotEmpty()) {
            val dx = s.x - predicted[0]
            val dy = s.y - predicted[1]
            val dz = s.z - predicted[2]
            assertTrue("koule letí podle zaměřovače (${sqrt(dx * dx + dy * dy + dz * dz)} m)", sqrt(dx * dx + dy * dy + dz * dz) < 12f)
        }
        var guard = 0
        while (combat.shells.isNotEmpty() && guard++ < 60 * 15) combat.update(dt, f, Weapon.NONE)
        assertTrue("dopad = výbuch", combat.blasts.isNotEmpty())
    }

    @Test
    fun gearChainGivesSteamPointsAndCombo() {
        val w = World(MapType.COUNTRYSIDE)
        val f = FlightModel(w, AircraftType.AIRSHIP)
        val m = Missions(w, AircraftType.AIRSHIP)
        f.placeInAir(0f, 700f, -15000f, 0f, 35f)
        m.update(dt, f, null)
        val gears = m.goals.filter { it.kind == GoalKind.GEAR }.sortedBy { it.order }
        assertEquals(10, gears.size)
        for (g in gears) assertTrue("kolo nad zemí", g.y > w.surfaceHeight(g.x, g.z) + 20f)
        f.addSteam(-1f)
        for (g in gears) {
            f.placeInAir(g.x, g.y, g.z, f.heading, 35f)
            m.update(dt, f, null)
        }
        assertTrue("pára se doplnila (${f.steam})", f.steam > 0.9f)
        assertTrue(m.popups.any { it.text.startsWith("Řetěz kompletní") })
        assertEquals(2, m.multiplier)
        assertEquals("nový řetěz", 10, m.goals.count { it.kind == GoalKind.GEAR })
    }

    @Test
    fun cannonShootsDownPirates() {
        val w = World(MapType.COUNTRYSIDE)
        val combat = Combat(w, Life(w))
        val f = FlightModel(w, AircraftType.AIRSHIP)
        val m = Missions(w, AircraftType.AIRSHIP)
        f.placeInAir(0f, 700f, -15000f, 0f, 35f)
        m.update(dt, f, combat)
        val pirates = m.goals.filter { it.kind == GoalKind.AIR_TARGET && it.style == TargetStyle.PIRATE }
        assertEquals(2, pirates.size)
        val p = pirates.first()
        val x0 = p.x
        m.update(1f, f, combat)
        assertTrue("pirát krouží", abs(p.x - x0) > 1f)
        combat.shells.add(Shell(p.x + 3f, p.y, p.z, 0f, 0f, -1f))
        m.update(dt, f, combat)
        assertTrue(m.popups.any { it.text.startsWith("Piráti sestřeleni") })
        assertEquals(1, m.targets)
    }
}
