package app.letadlo.game

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

enum class FlightPhase { ROLLING, AIRBORNE, CRASHED }

enum class CrashReason(val text: String) {
    TERRAIN("Narazil jsi do země."),
    MOUNTAIN("Narazil jsi do hory."),
    BUILDING("Narazil jsi do budovy."),
    TREE("Zachytil jsi o stromy."),
    WATER("Spadl jsi do vody."),
    OFF_RUNWAY("Sjel jsi z dráhy."),
}

/**
 * Jednoduchý arkádový letový model. Žádné palivo – motor táhne pořád.
 * Náklon telefonu určuje cílový náklon křídel a cílový sklon nosu.
 *
 * Souřadnice: Y nahoru, kurz 0 = let směrem -Z, kladný kurz = doprava (+X).
 */
class FlightModel(val world: World) {

    companion object {
        const val G = 9.81f
        const val ROTATE_SPEED = 38f          // m/s (~137 km/h) – rychlost pro vzlet
        const val STALL_SPEED = 30f
        const val GROUND_THRUST = 6.5f
        const val THRUST = 9f
        const val DRAG = 0.0016f              // ustálená rychlost ~75 m/s (270 km/h)
        const val ROLL_FRICTION = 0.25f
        const val GEAR_HEIGHT = 1.6f          // výška středu letadla nad zemí na podvozku
        const val PLANE_RADIUS = 3f
        const val CEILING = 3500f
        val MAX_BANK = rad(62f)
        val MAX_PITCH_UP = rad(32f)
        val MAX_PITCH_DOWN = rad(35f)
        val MAX_ROTATE_PITCH = rad(12f)
        const val TURN_FACTOR = 1.35f

        fun rad(deg: Float): Float = (deg * PI / 180.0).toFloat()
    }

    var x = 0f; private set
    var y = 0f; private set
    var z = 0f; private set
    var heading = 0f; private set
    var pitch = 0f; private set
    var bank = 0f; private set
    var speed = 0f; private set
    var verticalSpeed = 0f; private set
    var phase = FlightPhase.ROLLING; private set
    var crashReason: CrashReason? = null; private set

    /** Čas od startu (včetně rozjezdu). */
    var totalTime = 0f; private set
    /** Čas strávený ve vzduchu. */
    var airTime = 0f; private set
    /** Uletěná vodorovná vzdálenost ve vzduchu (m). */
    var distance = 0f; private set
    var maxAltitudeAgl = 0f; private set
    var hasTakenOff = false; private set
    var propeller = 0f; private set

    val altitudeAgl: Float get() = y - max(world.groundHeight(x, z), World.SEA_LEVEL) - GEAR_HEIGHT

    init {
        reset()
    }

    fun reset() {
        x = 0f
        z = World.START_Z
        heading = 0f
        pitch = 0f
        bank = 0f
        speed = 0f
        verticalSpeed = 0f
        y = world.groundHeight(x, z) + GEAR_HEIGHT
        phase = FlightPhase.ROLLING
        crashReason = null
        totalTime = 0f
        airTime = 0f
        distance = 0f
        maxAltitudeAgl = 0f
        hasTakenOff = false
    }

    /** Umístí letadlo rovnou do vzduchu (pro testy). */
    fun placeInAir(px: Float, py: Float, pz: Float, headingRad: Float, spd: Float) {
        reset()
        x = px; y = py; z = pz
        heading = headingRad
        speed = spd
        phase = FlightPhase.AIRBORNE
        hasTakenOff = true
    }

    /**
     * @param rollIn  -1..1, kladné = zatáčka doprava
     * @param pitchIn -1..1, kladné = nos nahoru
     */
    fun update(dt: Float, rollIn: Float, pitchIn: Float) {
        if (phase == FlightPhase.CRASHED || dt <= 0f) return
        val r = rollIn.coerceIn(-1f, 1f)
        val p = pitchIn.coerceIn(-1f, 1f)
        totalTime += dt
        propeller += dt * (20f + speed * 0.6f)
        if (propeller > 1000f) propeller -= 1000f
        when (phase) {
            FlightPhase.ROLLING -> updateGround(dt, p)
            FlightPhase.AIRBORNE -> updateAir(dt, r, p)
            FlightPhase.CRASHED -> Unit
        }
    }

    private fun approach(current: Float, target: Float, dt: Float, rate: Float): Float =
        current + (target - current) * (1f - exp(-rate * dt))

    private fun updateGround(dt: Float, p: Float) {
        val accel = GROUND_THRUST - DRAG * speed * speed - ROLL_FRICTION
        speed = max(0f, speed + accel * dt)
        bank = approach(bank, 0f, dt, 6f)
        val canRotate = speed >= ROTATE_SPEED
        val wantPitch = if (canRotate && p > 0.12f) min(p * MAX_PITCH_UP, MAX_ROTATE_PITCH) else 0f
        pitch = approach(pitch, wantPitch, dt, 3f)
        val step = speed * dt
        x += sin(heading) * step
        z -= cos(heading) * step
        y = world.groundHeight(x, z) + GEAR_HEIGHT
        verticalSpeed = 0f
        if (canRotate && pitch > rad(2f)) {
            phase = FlightPhase.AIRBORNE
            hasTakenOff = true
            return
        }
        if (!world.onRunway(x, z)) crash(CrashReason.OFF_RUNWAY)
    }

    private fun updateAir(dt: Float, r: Float, p: Float) {
        airTime += dt
        bank = approach(bank, r * MAX_BANK, dt, 2.6f)

        var targetPitch = if (p >= 0f) p * MAX_PITCH_UP else p * MAX_PITCH_DOWN
        if (y > CEILING) targetPitch = min(targetPitch, -rad(3f))
        if (speed < STALL_SPEED + 6f) {
            // při malé rychlosti padá nos dolů
            val k = ((STALL_SPEED + 6f - speed) / 12f).coerceIn(0f, 1f)
            targetPitch = min(targetPitch, lerpF(targetPitch, -rad(25f), k))
        }
        pitch = approach(pitch, targetPitch, dt, 2.0f)

        val turnRate = G * tan(bank) / max(speed, 25f) * TURN_FACTOR
        heading += turnRate * dt
        if (heading > PI.toFloat()) heading -= (2 * PI).toFloat()
        if (heading < -PI.toFloat()) heading += (2 * PI).toFloat()

        // v zatáčce trochu víc odporu, ve stoupání ubývá rychlost, v klesání přibývá
        val bankDrag = 1f + 0.35f * abs(sin(bank))
        val accel = THRUST - DRAG * bankDrag * speed * speed - G * sin(pitch) * 0.85f
        speed = (speed + accel * dt).coerceIn(18f, 140f)

        val sink = if (speed < STALL_SPEED) (STALL_SPEED - speed) * 1.3f else 0f
        val cp = cos(pitch)
        val dx = sin(heading) * cp * speed * dt
        val dz = -cos(heading) * cp * speed * dt
        val dy = sin(pitch) * speed * dt - sink * dt
        x += dx
        y += dy
        z += dz
        verticalSpeed = dy / dt
        distance += sqrt(dx * dx + dz * dz)

        checkCollisions()
        if (phase == FlightPhase.AIRBORNE) {
            maxAltitudeAgl = max(maxAltitudeAgl, altitudeAgl)
        }
    }

    private fun lerpF(a: Float, b: Float, t: Float) = a + (b - a) * t

    private fun checkCollisions() {
        val gh = world.groundHeight(x, z)
        if (gh < World.SEA_LEVEL && y - 0.8f <= World.SEA_LEVEL) {
            crash(CrashReason.WATER)
            return
        }
        if (y - GEAR_HEIGHT <= gh) {
            val gentle = verticalSpeed > -4.5f && abs(bank) < rad(15f) && pitch > -rad(8f)
            if (world.onRunway(x, z) && gentle) {
                // přistání na dráze – pokračuje se po zemi (motor táhne dál)
                y = gh + GEAR_HEIGHT
                phase = FlightPhase.ROLLING
                pitch = 0f
                verticalSpeed = 0f
                return
            }
            crash(if (world.map == MapType.MOUNTAINS && gh > world.airportHeight + 150f) CrashReason.MOUNTAIN else CrashReason.TERRAIN)
            y = gh + 0.5f
            return
        }
        when (world.hitObstacle(x, y, z, PLANE_RADIUS)) {
            ObstacleKind.BUILDING -> crash(CrashReason.BUILDING)
            ObstacleKind.TREE -> crash(CrashReason.TREE)
            null -> Unit
        }
    }

    private fun crash(reason: CrashReason) {
        phase = FlightPhase.CRASHED
        crashReason = reason
        speed = 0f
        verticalSpeed = 0f
    }
}
