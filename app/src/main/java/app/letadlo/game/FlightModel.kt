package app.letadlo.game

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

enum class FlightPhase { ROLLING, AIRBORNE, CRASHED }

enum class CrashReason(val text: String) {
    TERRAIN("Narazil jsi do země."),
    MOUNTAIN("Narazil jsi do hory."),
    BUILDING("Narazil jsi do budovy."),
    TREE("Zachytil jsi o stromy."),
    TURBINE("Narazil jsi do větrné elektrárny."),
    WATER("Spadl jsi do vody."),
    OFF_RUNWAY("Sjel jsi z dráhy."),
}

/**
 * Jednoduchý arkádový letový model. Žádné palivo – motor táhne pořád.
 * Náklon telefonu určuje cílový náklon křídel a cílový sklon nosu
 * (u vrtulníku stoupání/klesání).
 *
 * Souřadnice: Y nahoru, kurz 0 = let směrem -Z, kladný kurz = doprava (+X).
 */
class FlightModel(val world: World, val type: AircraftType = AircraftType.SPORT) {

    companion object {
        const val G = 9.81f
        const val ROLL_FRICTION = 0.25f
        const val HELI_SPIN_UP = 2.5f
        const val HELI_CLIMB = 28f
        const val HELI_SINK = 30f
        /** Kolem neutrální polohy vrtulník drží výšku. */
        const val HELI_HOLD = 0.18f
        const val AIRSHIP_CLIMB = 14f
        const val AIRSHIP_SINK = 16f
        /** Rychlost vzducholodi s párou naplno. */
        const val AIRSHIP_BOOST = 60f

        fun rad(deg: Float): Float = (deg * PI / 180.0).toFloat()
    }

    private val maxBank = rad(type.maxBankDeg)
    private val maxPitchUp = rad(type.maxPitchUpDeg)
    private val maxPitchDown = rad(type.maxPitchDownDeg)
    private val maxRotatePitch = rad(12f)
    private val maxTurn = rad(type.maxTurnDeg)

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
    /** Otáčky vrtule/rotoru (počet otáček, jen pro animaci). */
    var propeller = 0f; private set
    /** Roztočení rotoru vrtulníku 0..1. */
    var rotorSpin = 0f; private set

    /** Počet přistání a svislá rychlost při posledním dosednutí (pro hodnocení). */
    var landings = 0; private set
    var touchdownVs = 0f; private set
    var lastLandingOnRunway = false; private set

    /** Brzdění na zemi (dopravní letadlo po dokončeném letu). */
    var brake = false

    /** Pára vzducholodi 0..1 – tlačítkem se z ní zrychluje, ozubená kola ji doplňují. */
    var steam = 1f; private set

    /** Hráč drží tlačítko páry. */
    @Volatile var boostInput = false

    val boosting: Boolean
        get() = type.airship && boostInput && steam > 0.02f && phase == FlightPhase.AIRBORNE

    fun addSteam(v: Float) {
        steam = (steam + v).coerceIn(0f, 1f)
    }

    val gearHeight: Float get() = type.gearHeight

    val altitudeAgl: Float get() = y - max(world.groundHeight(x, z), World.SEA_LEVEL) - type.gearHeight

    /** Je možné vzlétnout (letadlo má rychlost, vrtulník roztočený rotor)? */
    val readyForTakeoff: Boolean
        get() = if (type.vertical) rotorSpin >= 1f else speed >= type.rotateSpeed

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
        y = world.groundHeight(x, z) + type.gearHeight
        phase = FlightPhase.ROLLING
        crashReason = null
        totalTime = 0f
        airTime = 0f
        distance = 0f
        maxAltitudeAgl = 0f
        hasTakenOff = false
        rotorSpin = 0f
        brake = false
        steam = 1f
        boostInput = false
    }

    /** Umístí letadlo rovnou do vzduchu (pro testy). */
    fun placeInAir(px: Float, py: Float, pz: Float, headingRad: Float, spd: Float) {
        reset()
        x = px; y = py; z = pz
        heading = headingRad
        speed = spd
        phase = FlightPhase.AIRBORNE
        hasTakenOff = true
        rotorSpin = 1f
    }

    /**
     * @param rollIn  -1..1, kladné = zatáčka doprava
     * @param pitchIn -1..1, kladné = nos nahoru / stoupání
     */
    fun update(dt: Float, rollIn: Float, pitchIn: Float) {
        if (phase == FlightPhase.CRASHED || dt <= 0f) return
        val r = rollIn.coerceIn(-1f, 1f)
        val p = pitchIn.coerceIn(-1f, 1f)
        totalTime += dt
        propeller += when {
            type.helicopter -> dt * 6.5f * rotorSpin
            type.airship -> dt * rotorSpin * (1.5f + speed * 0.06f)
            else -> dt * (20f + speed * 0.6f)
        }
        if (propeller > 1000f) propeller -= 1000f
        if (type.vertical) {
            when (phase) {
                FlightPhase.ROLLING -> updateHeliGround(dt, p)
                FlightPhase.AIRBORNE -> if (type.airship) updateAirshipAir(dt, r, p) else updateHeliAir(dt, r, p)
                FlightPhase.CRASHED -> Unit
            }
        } else {
            when (phase) {
                FlightPhase.ROLLING -> updateGround(dt, p)
                FlightPhase.AIRBORNE -> updateAir(dt, r, p)
                FlightPhase.CRASHED -> Unit
            }
        }
    }

    private fun approach(current: Float, target: Float, dt: Float, rate: Float): Float =
        current + (target - current) * (1f - exp(-rate * dt))

    private fun wrapHeading() {
        if (heading > PI.toFloat()) heading -= (2 * PI).toFloat()
        if (heading < -PI.toFloat()) heading += (2 * PI).toFloat()
    }

    // ------------------------------------------------------------------ letadla

    private fun updateGround(dt: Float, p: Float) {
        val accel = if (brake) -7f else type.groundThrust - type.drag * speed * speed - ROLL_FRICTION
        speed = max(0f, speed + accel * dt)
        bank = approach(bank, 0f, dt, 6f)
        val canRotate = speed >= type.rotateSpeed && !brake
        val wantPitch = if (canRotate && p > 0.12f) min(p * maxPitchUp, maxRotatePitch) else 0f
        pitch = approach(pitch, wantPitch, dt, 3f)
        val step = speed * dt
        x += sin(heading) * step
        z -= cos(heading) * step
        y = world.groundHeight(x, z) + type.gearHeight
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
        bank = approach(bank, r * maxBank, dt, type.bankRate)

        var targetPitch = if (p >= 0f) p * maxPitchUp else p * maxPitchDown
        if (y > type.ceiling) targetPitch = min(targetPitch, -rad(3f))
        val stall = type.stallSpeed
        if (speed < stall + 6f) {
            // při malé rychlosti padá nos dolů
            val k = ((stall + 6f - speed) / 12f).coerceIn(0f, 1f)
            targetPitch = min(targetPitch, lerpF(targetPitch, -rad(25f), k))
        }
        pitch = approach(pitch, targetPitch, dt, type.pitchRate)

        // rychlost otáčení úměrná náklonu – stejně ostrá odezva při každé rychlosti
        heading += maxTurn * (bank / maxBank) * dt
        wrapHeading()

        // v zatáčce trochu víc odporu, ve stoupání ubývá rychlost, v klesání přibývá
        val bankDrag = 1f + 0.35f * abs(sin(bank))
        val accel = type.thrust - type.drag * bankDrag * speed * speed - G * sin(pitch) * 0.85f
        speed = (speed + accel * dt).coerceIn(type.minSpeed, type.maxSpeed)

        val sink = if (speed < stall) (stall - speed) * 1.3f else 0f
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
        if (phase == FlightPhase.AIRBORNE) maxAltitudeAgl = max(maxAltitudeAgl, altitudeAgl)
    }

    // ------------------------------------------------------------------ vrtulník

    private fun updateHeliGround(dt: Float, p: Float) {
        rotorSpin = min(1f, rotorSpin + dt / HELI_SPIN_UP)
        speed = approach(speed, 0f, dt, 1.5f)
        bank = approach(bank, 0f, dt, 6f)
        pitch = approach(pitch, 0f, dt, 4f)
        x += sin(heading) * speed * dt
        z -= cos(heading) * speed * dt
        y = landingHeight() + type.gearHeight
        verticalSpeed = 0f
        if (rotorSpin >= 1f && p > HELI_HOLD) {
            phase = FlightPhase.AIRBORNE
            hasTakenOff = true
            verticalSpeed = 1.5f
            y += 0.05f
        }
    }

    private fun updateHeliAir(dt: Float, r: Float, p: Float) {
        airTime += dt
        rotorSpin = 1f
        bank = approach(bank, r * maxBank, dt, type.bankRate)
        // kolem neutrálu drží výšku, mimo něj rychle stoupá/klesá
        val pp = if (abs(p) < HELI_HOLD) 0f else (p - sign(p) * HELI_HOLD) / (1f - HELI_HOLD)
        var targetVy = if (pp >= 0f) pp * HELI_CLIMB else pp * HELI_SINK
        if (y > type.ceiling) targetVy = min(targetVy, -1f)
        verticalSpeed = approach(verticalSpeed, targetVy, dt, 3.2f)

        // u země se vrtulník vznáší a jen pomalu popojíždí, výš zrychlí na cestovní rychlost
        val agl = altitudeAgl
        val targetSpeed = type.maxSpeed * World.smoothstep(2f, 25f, agl)
        speed = approach(speed, targetSpeed, dt, 0.35f)

        heading += maxTurn * (bank / maxBank) * dt
        wrapHeading()

        // nos se při letu dopředu sklopí
        pitch = approach(pitch, -rad(8f) * (speed / type.maxSpeed) + verticalSpeed * 0.008f, dt, 2f)

        val dx = sin(heading) * speed * dt
        val dz = -cos(heading) * speed * dt
        x += dx
        z += dz
        y += verticalSpeed * dt
        distance += sqrt(dx * dx + dz * dz)

        checkCollisions()
        if (phase == FlightPhase.AIRBORNE) maxAltitudeAgl = max(maxAltitudeAgl, altitudeAgl)
    }

    // ------------------------------------------------------------------ vzducholoď

    private fun updateAirshipAir(dt: Float, r: Float, p: Float) {
        airTime += dt
        rotorSpin = 1f
        // kotel páru pomalu doplňuje, zrychlení ji spotřebovává
        steam = if (boosting) max(0f, steam - dt * 0.16f) else min(1f, steam + dt * 0.025f)
        bank = approach(bank, r * maxBank, dt, type.bankRate)
        val pp = if (abs(p) < HELI_HOLD) 0f else (p - sign(p) * HELI_HOLD) / (1f - HELI_HOLD)
        var targetVy = if (pp >= 0f) pp * AIRSHIP_CLIMB else pp * AIRSHIP_SINK
        if (y > type.ceiling) targetVy = min(targetVy, -1f)
        verticalSpeed = approach(verticalSpeed, targetVy, dt, 1.1f)

        val cruise = if (boosting) AIRSHIP_BOOST else type.maxSpeed
        val targetSpeed = cruise * World.smoothstep(3f, 20f, altitudeAgl)
        speed = approach(speed, targetSpeed, dt, if (boosting) 0.6f else 0.3f)

        heading += maxTurn * (bank / maxBank) * dt
        wrapHeading()
        // líné houpání velkého balonu
        pitch = approach(pitch, verticalSpeed * 0.012f + sin(totalTime * 0.7f) * 0.012f, dt, 1.5f)

        val dx = sin(heading) * speed * dt
        val dz = -cos(heading) * speed * dt
        x += dx
        z += dz
        y += verticalSpeed * dt
        distance += sqrt(dx * dx + dz * dz)

        checkCollisions()
        if (phase == FlightPhase.AIRBORNE) maxAltitudeAgl = max(maxAltitudeAgl, altitudeAgl)
    }

    /** Vzducholoď je dlouhá – kontroluje se příď, střed, záď balonu a gondola. */
    private fun airshipHit(): ObstacleKind? {
        val fx = sin(heading)
        val fz = -cos(heading)
        return world.hitObstacle(x, y, z, 8.5f)
            ?: world.hitObstacle(x + fx * 19f, y, z + fz * 19f, 6.5f)
            ?: world.hitObstacle(x - fx * 19f, y, z - fz * 19f, 6.5f)
            ?: world.hitObstacle(x, y - 10.5f, z, 3.5f)
    }

    // ------------------------------------------------------------------ kolize

    private fun lerpF(a: Float, b: Float, t: Float) = a + (b - a) * t

    /** Kam může dosednout: terén, u vrtulníku i rovné střechy. */
    private fun landingHeight(): Float {
        val gh = max(world.groundHeight(x, z), World.SEA_LEVEL)
        if (!type.vertical) return gh
        val roof = world.roofTop(x, z) ?: return gh
        return max(gh, roof)
    }

    private fun checkCollisions() {
        val heli = type.vertical
        val roof = if (heli) world.roofTop(x, z) else null
        val gh = if (roof != null) max(roof, world.groundHeight(x, z)) else world.groundHeight(x, z)
        if (roof == null && gh < World.SEA_LEVEL && y - type.gearHeight * 0.5f <= World.SEA_LEVEL) {
            crash(CrashReason.WATER)
            return
        }
        if (y - type.gearHeight <= gh) {
            val gentle = verticalSpeed > -4.5f && abs(bank) < rad(15f) && (heli || pitch > -rad(8f))
            if (gentle && (heli || world.onRunway(x, z))) {
                // přistání – letadlo pokračuje po dráze (motor táhne dál), vrtulník stojí
                touchdownVs = verticalSpeed
                lastLandingOnRunway = world.onRunway(x, z)
                landings++
                y = gh + type.gearHeight
                phase = FlightPhase.ROLLING
                pitch = 0f
                verticalSpeed = 0f
                return
            }
            crash(if (world.map == MapType.MOUNTAINS && gh > world.airportHeight + 150f) CrashReason.MOUNTAIN else CrashReason.TERRAIN)
            y = gh + 0.5f
            return
        }
        // vrtulník: kolizní koule nad ližinami, aby šlo sednout na střechu i z ní vzlétnout
        val hit = when {
            type.airship -> airshipHit()
            heli -> world.hitObstacle(x, y + 1.5f, z, 3f)
            else -> world.hitObstacle(x, y, z, type.radius)
        }
        when (hit) {
            ObstacleKind.BUILDING -> crash(CrashReason.BUILDING)
            ObstacleKind.TREE -> crash(CrashReason.TREE)
            ObstacleKind.TURBINE -> crash(CrashReason.TURBINE)
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
