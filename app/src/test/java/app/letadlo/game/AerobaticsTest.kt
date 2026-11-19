package app.letadlo.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

class AerobaticsTest {

    private val dt = 1f / 60f

    private fun run(f: FlightModel, seconds: Float, r: Float, p: Float) {
        var t = 0f
        while (t < seconds && f.phase != FlightPhase.CRASHED) {
            f.update(dt, r, p)
            t += dt
        }
    }

    private fun fighter(): FlightModel = FlightModel(World(MapType.COUNTRYSIDE), AircraftType.FIGHTER).also {
        it.placeInAir(0f, 2500f, -20000f, 0f, 450f)
    }

    @Test
    fun fighterFliesAFullLoopAndRecovers() {
        val f = fighter()
        val h0 = f.heading
        run(f, 3.2f, 0f, FlightModel.MAX_INPUT)
        assertEquals("looping dokončen", 1, f.loops)
        assertEquals(FlightPhase.AIRBORNE, f.phase)
        run(f, 3f, 0f, 0f)
        assertTrue("srovnal se (sklon ${f.pitch})", abs(f.pitch) < 0.15f)
        assertTrue("křídla vodorovně (${f.bank})", abs(f.bank) < 0.15f)
        assertTrue("letí původním směrem", abs(FlightModel.wrapPi(f.heading - h0)) < 0.3f)
    }

    @Test
    fun halfLoopThenReleaseIsAnImmelmannTurn() {
        val f = fighter()
        val h0 = f.heading
        run(f, 1.5f, 0f, FlightModel.MAX_INPUT)
        assertTrue("vzhůru nohama nahoře (sklon ${f.pitch})", abs(f.pitch) > 2.5f)
        run(f, 3f, 0f, 0f)
        assertTrue("srovnal se", abs(f.pitch) < 0.2f && abs(f.bank) < 0.2f)
        val turned = abs(FlightModel.wrapPi(f.heading - h0))
        assertTrue("otočil se o ~180° (${Math.toDegrees(turned.toDouble())}°)", turned > PI.toFloat() - 0.4f)
    }

    @Test
    fun fighterRollsAndLevelsOut() {
        val f = fighter()
        run(f, 1.3f, FlightModel.MAX_INPUT, 0f)
        assertEquals("výkrut", 1, f.rolls)
        run(f, 2f, 0f, 0f)
        assertTrue("křídla vodorovně (${f.bank})", abs(f.bank) < 0.2f)
    }

    @Test
    fun normalRangeStaysStable() {
        val f = fighter()
        run(f, 8f, 0f, 1f)
        assertEquals(0, f.loops)
        assertTrue("sklon nepřekročí maximum", f.pitch <= FlightModel.rad(AircraftType.FIGHTER.maxPitchUpDeg) + 0.01f)
        val g = fighter()
        run(g, 5f, 1f, 0f)
        assertEquals(0, g.rolls)
        assertTrue("náklon nepřekročí maximum", abs(g.bank) <= FlightModel.rad(AircraftType.FIGHTER.maxBankDeg) + 0.01f)
    }

    @Test
    fun heavyPlanesRollSlowlyHelicopterDoesNot() {
        val a = FlightModel(World(MapType.COUNTRYSIDE), AircraftType.AIRLINER)
        a.placeInAir(0f, 2500f, -20000f, 0f, 230f)
        run(a, 7.5f, FlightModel.MAX_INPUT, 0f)
        assertTrue("dopravní pomalý výkrut (${a.rolls})", a.rolls >= 1)

        val h1 = FlightModel(World(MapType.COUNTRYSIDE), AircraftType.HELICOPTER)
        val h2 = FlightModel(World(MapType.COUNTRYSIDE), AircraftType.HELICOPTER)
        h1.placeInAir(0f, 800f, -20000f, 0f, 40f)
        h2.placeInAir(0f, 800f, -20000f, 0f, 40f)
        run(h1, 3f, FlightModel.MAX_INPUT, FlightModel.MAX_INPUT)
        run(h2, 3f, 1f, 1f)
        assertEquals("vrtulník bere jen běžný rozsah", h2.y, h1.y, 0.01f)
        assertEquals(0, h1.rolls + h1.loops)
    }

    @Test
    fun aerobaticsScorePoints() {
        val w = World(MapType.COUNTRYSIDE)
        val f = FlightModel(w, AircraftType.FIGHTER)
        val m = Missions(w, AircraftType.FIGHTER)
        f.placeInAir(0f, 2500f, -20000f, 0f, 450f)
        var t = 0f
        while (t < 3.2f) {
            f.update(dt, 0f, FlightModel.MAX_INPUT)
            m.update(dt, f, null)
            t += dt
        }
        t = 0f
        while (t < 1.3f) {
            f.update(dt, FlightModel.MAX_INPUT, 0f)
            m.update(dt, f, null)
            t += dt
        }
        assertTrue(m.popups.any { it.text.contains("ooping") })
        assertTrue(m.popups.any { it.text.contains("ýkrut") })
        assertEquals(2, m.aerobatics)
    }
}
