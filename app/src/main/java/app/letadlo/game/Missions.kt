package app.letadlo.game

import java.util.Locale
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

enum class GoalKind { RING, GROUND_TARGET, AIR_TARGET, PICKUP, DROPOFF, RUNWAY }

/** Cíl mise: kruh k průletu, terč ke zničení, místo k přistání… */
class Goal(val kind: GoalKind, var x: Float, var y: Float, var z: Float, val radius: Float, val style: Int = 0) {
    /** Vodorovná normála kruhu (směr průletu). */
    var nx = 0f
    var nz = -1f
    var prevSide = Float.NaN
    /** Další kruh v pořadí – jen ukázka trasy. */
    var preview = false
    var age = 0f
    var vx = 0f
    var vz = 0f
    var spawnDist = 1000f
    var dead = false
}

class Popup(val text: String, val points: Int)

/** Terče: 0 radar, 1 nádrže s palivem, 2 tank, 3 továrna, 10 terčový balon. */
object TargetStyle {
    const val RADAR = 0
    const val FUEL = 1
    const val TANK = 2
    const val FACTORY = 3
    const val BALLOON = 10

    fun radius(style: Int): Float = when (style) {
        RADAR -> 9f
        FUEL -> 13f
        TANK -> 6f
        FACTORY -> 24f
        else -> 8f
    }

    fun name(style: Int): String = when (style) {
        RADAR -> "Radar zničen"
        FUEL -> "Sklad paliva"
        TANK -> "Tank zničen"
        FACTORY -> "Továrna zničena"
        else -> "Balon sestřelen"
    }

    fun points(style: Int): Int = when (style) {
        RADAR -> 400
        FUEL -> 500
        TANK -> 450
        FACTORY -> 700
        else -> 300
    }
}

/**
 * Herní cíle a body. Každé letadlo má vlastní náplň:
 *  - sportovní: kruhy nízko nad krajinou, přistání
 *  - stíhačka: kruhy v údolích a ulicích, balony a pozemní cíle pro rakety
 *  - bombardér: velké pozemní cíle
 *  - vrtulník: záchrana zraněných (i ze střech) a let do nemocnice
 *  - dopravní: trasa přes body, přistávací koridor a hodnocené přistání
 * Všichni sbírají body za nízký let a těsné průlety, kombo násobí body.
 */
class Missions(private val world: World, val type: AircraftType, seed: Long = 5L) {

    companion object {
        const val MAX_MULT = 8
        const val COMBO_TIME = 22f
        const val ROUTE_RINGS = 4
        /** Nemocniční heliport u terminálu letiště. */
        const val HOSPITAL_X = -70f
        const val HOSPITAL_Z = 40f
    }

    private enum class Stage { ROUTE, APPROACH, LAND, BRAKING, DONE }

    var score = 0; private set
    var multiplier = 1; private set
    var comboLeft = 0f; private set
    var rings = 0; private set
    var targets = 0; private set
    var rescued = 0; private set
    var landingsScored = 0; private set
    var nearMisses = 0; private set
    val goals = ArrayList<Goal>()
    val popups = ArrayList<Popup>()
    var objective = ""; private set
    var lowFlying = false; private set
    var carrying = false; private set

    private val rnd = Random(seed)
    private var started = false
    private var lowAccum = 0f
    private var lowTimer = 0f
    private var nearCooldown = 0f
    private var seenLandings = 0
    private var rescueTimer = 0f
    private var stage = Stage.ROUTE
    private var routeDone = 0
    private var stageTimer = 0f

    private val ringRadius = when (type) {
        AircraftType.SPORT -> 17f
        AircraftType.FIGHTER -> 24f
        AircraftType.AIRLINER -> 60f
        AircraftType.BOMBER -> 45f
        AircraftType.HELICOPTER -> 16f
    }
    private val lowLimit = when (type) {
        AircraftType.SPORT -> 45f
        AircraftType.FIGHTER -> 110f
        AircraftType.AIRLINER -> 120f
        AircraftType.BOMBER -> 90f
        AircraftType.HELICOPTER -> 25f
    }
    private val cruise = if (type.helicopter) type.maxSpeed else sqrt(type.thrust / type.drag)

    fun reset() {
        score = 0; multiplier = 1; comboLeft = 0f
        rings = 0; targets = 0; rescued = 0; landingsScored = 0; nearMisses = 0
        goals.clear(); popups.clear()
        started = false; lowAccum = 0f; lowTimer = 0f; nearCooldown = 0f; seenLandings = 0
        carrying = false; rescueTimer = 0f
        stage = Stage.ROUTE; routeDone = 0; stageTimer = 0f
        objective = ""
    }

    // ------------------------------------------------------------------ hlavní smyčka

    fun update(dt: Float, f: FlightModel, combat: Combat?) {
        if (f.phase == FlightPhase.CRASHED) {
            combat?.recentExplosions?.clear()
            return
        }
        if (!started && (f.hasTakenOff || type.helicopter)) {
            started = true
            seenLandings = f.landings
            start(f)
        }
        if (comboLeft > 0f) {
            comboLeft -= dt
            if (comboLeft <= 0f && multiplier > 1) {
                multiplier = 1
                popups.add(Popup("Kombo vypršelo", 0))
            }
        }
        nearCooldown -= dt
        updateLowFlying(dt, f)
        updateNearMiss(f)
        updateLandings(f)

        for (g in goals) {
            g.age += dt
            if (g.kind == GoalKind.GROUND_TARGET && (g.vx != 0f || g.vz != 0f)) {
                val nx = g.x + g.vx * dt
                val nz = g.z + g.vz * dt
                val gh = world.groundHeight(nx, nz)
                if (gh < World.SEA_LEVEL + 1f || world.hitObstacle(nx, gh + 2f, nz, 4f) != null) {
                    g.vx = -g.vx; g.vz = -g.vz
                } else {
                    g.x = nx; g.z = nz; g.y = gh
                }
            } else if (g.kind == GoalKind.AIR_TARGET) {
                g.x += g.vx * dt
                g.z += g.vz * dt
                g.y += sin(g.age * 0.8f) * 0.6f * dt
            }
        }

        updateRings(f)
        if (combat != null) updateTargets(f, combat)
        if (type.helicopter) updateRescue(dt, f)
        if (type == AircraftType.AIRLINER) updateAirliner(dt, f)
        if (started) refreshTargets(f)
        objective = describe(f)
        combat?.recentExplosions?.clear()
    }

    private fun start(f: FlightModel) {
        when (type) {
            AircraftType.SPORT -> startRings(f)
            AircraftType.FIGHTER -> startRings(f)
            AircraftType.AIRLINER -> {
                stage = Stage.ROUTE
                routeDone = 0
                startRings(f)
            }
            AircraftType.HELICOPTER -> spawnPickup(f)
            AircraftType.BOMBER -> Unit
        }
    }

    private fun award(base: Int, label: String, comboUp: Boolean) {
        val pts = base * multiplier
        score += pts
        popups.add(Popup("$label +$pts", pts))
        if (comboUp) {
            if (multiplier < MAX_MULT) multiplier++
            comboLeft = COMBO_TIME
            if (multiplier >= 3) popups.add(Popup("Kombo ×$multiplier", 0))
        } else if (multiplier > 1) {
            comboLeft = min(COMBO_TIME, comboLeft + 3f)
        }
    }

    private fun breakCombo(label: String) {
        if (multiplier > 1) popups.add(Popup("$label – kombo ztraceno", 0)) else popups.add(Popup(label, 0))
        multiplier = 1
        comboLeft = 0f
    }

    // ------------------------------------------------------------------ nízký let a těsné průlety

    private fun updateLowFlying(dt: Float, f: FlightModel) {
        val agl = f.altitudeAgl
        lowFlying = f.phase == FlightPhase.AIRBORNE && agl > 1.5f && agl < lowLimit && f.speed > cruise * 0.3f
        if (lowFlying) {
            val close = 1f - agl / lowLimit
            val rate = 70f * (f.speed / cruise).coerceIn(0f, 1.5f) * close * close
            lowAccum += rate * dt
            lowTimer += dt
            if (lowTimer > 2.5f) flushLow()
        } else if (lowAccum > 0f) {
            flushLow()
        }
    }

    private fun flushLow() {
        val base = lowAccum.toInt()
        lowAccum = 0f
        lowTimer = 0f
        if (base > 0) award(base, "Nízký let", false)
    }

    private fun updateNearMiss(f: FlightModel) {
        if (f.phase != FlightPhase.AIRBORNE || nearCooldown > 0f || f.speed < cruise * 0.45f) return
        val probe = if (type.helicopter) world.hitObstacle(f.x, f.y + 1.5f, f.z, 14f) else world.hitObstacle(f.x, f.y, f.z, type.radius + 14f)
        if (probe != null) {
            nearCooldown = 1.6f
            nearMisses++
            award(150, "Těsně!", false)
        }
    }

    private fun updateLandings(f: FlightModel) {
        if (f.landings <= seenLandings) return
        seenLandings = f.landings
        if (type.helicopter || !f.lastLandingOnRunway) return
        val vs = f.touchdownVs
        val (stars, base) = when {
            vs > -1.3f -> Pair("★★★", 1500)
            vs > -2.6f -> Pair("★★", 800)
            else -> Pair("★", 400)
        }
        landingsScored++
        award(base, "Přistání $stars", true)
        if (type == AircraftType.AIRLINER && stage == Stage.LAND) {
            award(2500, "Let dokončen", false)
            stage = Stage.BRAKING
            f.brake = true
            goals.removeAll { it.kind == GoalKind.RUNWAY }
        }
    }

    // ------------------------------------------------------------------ kruhy

    private fun usesRings() = type == AircraftType.SPORT || type == AircraftType.FIGHTER ||
        (type == AircraftType.AIRLINER && (stage == Stage.ROUTE || stage == Stage.APPROACH))

    private fun startRings(f: FlightModel) {
        goals.removeAll { it.kind == GoalKind.RING }
        val first = spawnRing(f.x, f.y, f.z, f.heading, f.speed) ?: return
        goals.add(first)
        addPreview(first, f.heading, f.speed)
    }

    private fun addPreview(from: Goal, heading: Float, speed: Float) {
        if (type == AircraftType.AIRLINER && routeDone + 1 >= ROUTE_RINGS) return
        val h = kotlin.math.atan2(from.nx, -from.nz).takeIf { !it.isNaN() } ?: heading
        val p = spawnRing(from.x, from.y, from.z, h, speed) ?: return
        p.preview = true
        goals.add(p)
    }

    private fun ringDistance(speed: Float): Float = when (type) {
        AircraftType.SPORT -> 650f
        AircraftType.FIGHTER -> (speed * 4.5f).coerceIn(900f, 2600f)
        AircraftType.AIRLINER -> 3800f
        AircraftType.BOMBER -> 2200f
        AircraftType.HELICOPTER -> 600f
    }

    private fun ringHeights(city: Boolean): Pair<Float, Float> = when {
        city && type != AircraftType.AIRLINER -> Pair(16f, 50f)
        type == AircraftType.SPORT -> Pair(10f, 45f)
        type == AircraftType.FIGHTER -> Pair(15f, 80f)
        type == AircraftType.AIRLINER -> Pair(150f, 450f)
        else -> Pair(40f, 160f)
    }

    /** Najde průchozí místo pro kruh před daným bodem; v horách dává přednost údolím, ve městě ulicím. */
    fun spawnRing(fromX: Float, fromY: Float, fromZ: Float, heading: Float, speed: Float): Goal? {
        val dist = ringDistance(speed)
        var best: Goal? = null
        var bestScore = Float.MAX_VALUE
        for (k in 0 until 18) {
            val ang = heading + (rnd.nextFloat() - 0.5f) * 1.3f
            val d = dist * (0.8f + 0.4f * rnd.nextFloat())
            var px = fromX + sin(ang) * d
            var pz = fromZ - cos(ang) * d
            var nx = px - fromX
            var nz = pz - fromZ
            val city = world.hasCity && world.distToCity(px, pz) < World.CITY_RADIUS - 200f && type != AircraftType.AIRLINER
            var r = ringRadius
            if (city) {
                // kruh nad ulicí mezi domy, natočený podél ulice
                r = min(r, 13f)
                if (abs(nx) > abs(nz)) {
                    pz = World.CITY_Z + floor((pz - World.CITY_Z) / World.BLOCK) * World.BLOCK + World.ROAD / 2f
                    nz = 0f
                } else {
                    px = World.CITY_X + floor((px - World.CITY_X) / World.BLOCK) * World.BLOCK + World.ROAD / 2f
                    nx = 0f
                }
            }
            val nl = sqrt(nx * nx + nz * nz)
            if (nl < 1e-3f) continue
            nx /= nl; nz /= nl
            val gh = max(world.groundHeight(px, pz), World.SEA_LEVEL)
            val (lo, hi) = ringHeights(city)
            val py = gh + r + lo + rnd.nextFloat() * (hi - lo)
            if (py > type.ceiling - 50f) continue
            if (!ringClear(px, py, pz, r, nx, nz)) continue
            val sc = if (world.map == MapType.MOUNTAINS && type != AircraftType.AIRLINER) gh else rnd.nextFloat()
            if (sc < bestScore) {
                bestScore = sc
                best = Goal(GoalKind.RING, px, py, pz, r).also {
                    it.nx = nx; it.nz = nz
                    it.spawnDist = sqrt((px - fromX) * (px - fromX) + (pz - fromZ) * (pz - fromZ))
                }
            }
        }
        if (best == null) {
            // nouzově vysoko nad terénem
            val px = fromX + sin(heading) * dist
            val pz = fromZ - cos(heading) * dist
            val py = max(world.groundHeight(px, pz), World.SEA_LEVEL) + 280f
            best = Goal(GoalKind.RING, px, min(py, type.ceiling - 60f), pz, ringRadius).also {
                it.nx = sin(heading); it.nz = -cos(heading); it.spawnDist = dist
            }
        }
        return best
    }

    /** Kruh nesmí zasahovat do terénu ani do budov a stromů. */
    fun ringClear(px: Float, py: Float, pz: Float, r: Float, nx: Float, nz: Float): Boolean {
        if (world.hitObstacle(px, py, pz, r * 0.95f) != null) return false
        val sx = -nz
        val sz = nx
        for (k in 0 until 10) {
            val a = (2.0 * PI * k / 10).toFloat()
            val qx = px + sx * cos(a) * r
            val qz = pz + sz * cos(a) * r
            val qy = py + sin(a) * r
            if (qy < max(world.groundHeight(qx, qz), World.SEA_LEVEL) + 3f) return false
            if (world.hitObstacle(qx, qy, qz, 2f) != null) return false
        }
        return true
    }

    private fun ringResult(g: Goal, f: FlightModel): Int {
        val dx = f.x - g.x
        val dy = f.y - g.y
        val dz = f.z - g.z
        val side = dx * g.nx + dz * g.nz
        val prev = g.prevSide
        g.prevSide = side
        if (!prev.isNaN() && (prev < 0f) != (side < 0f)) {
            val lx = dx - side * g.nx
            val lz = dz - side * g.nz
            val lateral = sqrt(lx * lx + dy * dy + lz * lz)
            if (lateral <= g.radius * 1.05f) return 1
            if (lateral < g.radius * 3f) return -1
        }
        val d = sqrt(dx * dx + dy * dy + dz * dz)
        if (g.age > 8f && d > g.spawnDist * 1.6f + 900f) return -1
        return 0
    }

    private fun updateRings(f: FlightModel) {
        if (!usesRings()) return
        val current = goals.firstOrNull { it.kind == GoalKind.RING && !it.preview } ?: return
        when (ringResult(current, f)) {
            1 -> {
                goals.remove(current)
                rings++
                val base = when (type) {
                    AircraftType.AIRLINER -> 350
                    AircraftType.FIGHTER -> 250
                    else -> 200
                }
                val label = when {
                    type == AircraftType.AIRLINER && stage == Stage.APPROACH -> "Koridor"
                    type == AircraftType.AIRLINER -> "Bod trasy"
                    else -> "Kruh"
                }
                award(base, label, true)
                if (type == AircraftType.AIRLINER) {
                    val before = stage
                    onAirlinerRing(f)
                    // trasa skončila – brány koridoru už jsou připravené
                    if (before != stage && stage == Stage.APPROACH) return
                }
                val next = goals.firstOrNull { it.kind == GoalKind.RING && it.preview }
                if (next != null) {
                    next.preview = false
                    next.prevSide = Float.NaN
                    if (!(type == AircraftType.AIRLINER && stage == Stage.APPROACH)) addPreview(next, f.heading, f.speed)
                } else if (!(type == AircraftType.AIRLINER && stage != Stage.ROUTE)) {
                    startRings(f)
                }
            }
            -1 -> {
                breakCombo("Vedle!")
                if (type == AircraftType.AIRLINER && stage == Stage.APPROACH) {
                    startApproach()
                } else {
                    startRings(f)
                }
            }
            else -> Unit
        }
    }

    // ------------------------------------------------------------------ dopravní letadlo

    private fun onAirlinerRing(f: FlightModel) {
        if (stage == Stage.ROUTE) {
            routeDone++
            if (routeDone >= ROUTE_RINGS) {
                popups.add(Popup("Trasa splněna – leť do přistávacího koridoru", 0))
                startApproach()
            }
        } else if (stage == Stage.APPROACH && goals.none { it.kind == GoalKind.RING }) {
            stage = Stage.LAND
            goals.add(Goal(GoalKind.RUNWAY, 0f, world.airportHeight, World.RUNWAY_HALF_LENGTH - 300f, 30f))
            popups.add(Popup("Přistaň na dráze", 0))
        }
    }

    /** Tři brány na 3° sestupové ose k prahu dráhy (přílet od +Z, let směrem -Z). */
    private fun startApproach() {
        stage = Stage.APPROACH
        goals.removeAll { it.kind == GoalKind.RING || it.kind == GoalKind.RUNWAY }
        val threshold = World.RUNWAY_HALF_LENGTH
        val dists = floatArrayOf(7000f, 4500f, 2200f)
        for ((i, d) in dists.withIndex()) {
            val z = threshold + d
            val y = world.airportHeight + d * 0.0524f + 35f
            goals.add(Goal(GoalKind.RING, 0f, max(y, world.groundHeight(0f, z) + 70f), z, ringRadius).also {
                it.nx = 0f; it.nz = -1f
                it.preview = i > 0
                it.spawnDist = 9000f
            })
        }
    }

    private fun updateAirliner(dt: Float, f: FlightModel) {
        when (stage) {
            Stage.LAND -> {
                // přeletěl a vzdálil se – nový nálet
                if (f.phase == FlightPhase.AIRBORNE && sqrt(f.x * f.x + f.z * f.z) > 9000f) startApproach()
            }
            Stage.BRAKING -> if (f.phase == FlightPhase.ROLLING && f.speed < 2f) {
                stage = Stage.DONE
                stageTimer = 3f
                popups.add(Popup("Stojíš – další let za chvíli", 0))
            }
            Stage.DONE -> {
                stageTimer -= dt
                if (stageTimer <= 0f) {
                    f.brake = false
                    stage = Stage.ROUTE
                    routeDone = 0
                    startRings(f)
                }
            }
            else -> Unit
        }
    }

    // ------------------------------------------------------------------ cíle (stíhačka, bombardér)

    private fun refreshTargets(f: FlightModel) {
        val wantAir = if (type == AircraftType.FIGHTER) 2 else 0
        val wantGround = when (type) {
            AircraftType.FIGHTER -> 2
            AircraftType.BOMBER -> 3
            else -> 0
        }
        // vzdálené a zmizelé cíle nahradit novými před letadlem
        goals.removeAll {
            (it.kind == GoalKind.GROUND_TARGET || it.kind == GoalKind.AIR_TARGET) &&
                (it.dead || dist2(it, f) > 9000f * 9000f)
        }
        var air = goals.count { it.kind == GoalKind.AIR_TARGET }
        var ground = goals.count { it.kind == GoalKind.GROUND_TARGET }
        var attempts = 0
        while (air < wantAir && attempts++ < 3) {
            spawnAirTarget(f)?.let { goals.add(it); air++ }
        }
        attempts = 0
        while (ground < wantGround && attempts++ < 3) {
            val style = if (type == AircraftType.BOMBER) {
                intArrayOf(TargetStyle.FACTORY, TargetStyle.FUEL, TargetStyle.RADAR, TargetStyle.FACTORY)[rnd.nextInt(4)]
            } else {
                intArrayOf(TargetStyle.RADAR, TargetStyle.FUEL, TargetStyle.TANK)[rnd.nextInt(3)]
            }
            spawnGroundTarget(f, style)?.let { goals.add(it); ground++ }
        }
    }

    private fun dist2(g: Goal, f: FlightModel): Float {
        val dx = g.x - f.x
        val dz = g.z - f.z
        return dx * dx + dz * dz
    }

    fun spawnGroundTarget(f: FlightModel, style: Int): Goal? {
        val r = TargetStyle.radius(style)
        for (k in 0 until 24) {
            val ang = f.heading + (rnd.nextFloat() - 0.5f) * 1.8f
            val d = 1400f + rnd.nextFloat() * 2600f
            val px = f.x + sin(ang) * d
            val pz = f.z - cos(ang) * d
            val gh = world.groundHeight(px, pz)
            if (gh < World.SEA_LEVEL + 1f) continue
            val slope = abs(world.groundHeight(px + r, pz) - world.groundHeight(px - r, pz)) +
                abs(world.groundHeight(px, pz + r) - world.groundHeight(px, pz - r))
            if (slope > 6f) continue
            if (world.airportWeight(px, pz) > 0.3f) continue
            if (world.hitObstacle(px, gh + 4f, pz, r + 4f) != null) continue
            val g = Goal(GoalKind.GROUND_TARGET, px, gh, pz, r, style)
            if (style == TargetStyle.TANK) {
                val a = rnd.nextFloat() * 2f * PI.toFloat()
                g.vx = cos(a) * 5f
                g.vz = sin(a) * 5f
            }
            return g
        }
        return null
    }

    private fun spawnAirTarget(f: FlightModel): Goal? {
        for (k in 0 until 10) {
            val ang = f.heading + (rnd.nextFloat() - 0.5f) * 2.2f
            val d = 1200f + rnd.nextFloat() * 1800f
            val px = f.x + sin(ang) * d
            val pz = f.z - cos(ang) * d
            val gh = world.surfaceHeight(px, pz)
            val py = min(gh + 120f + rnd.nextFloat() * 330f, type.ceiling - 150f)
            if (py < gh + 60f) continue
            if (world.hitObstacle(px, py, pz, 15f) != null) continue
            return Goal(GoalKind.AIR_TARGET, px, py, pz, 8f, TargetStyle.BALLOON).also {
                val a = rnd.nextFloat() * 2f * PI.toFloat()
                it.vx = cos(a) * 3f
                it.vz = sin(a) * 3f
            }
        }
        return null
    }

    private fun updateTargets(f: FlightModel, combat: Combat) {
        // přímý zásah balonu raketou
        for (g in goals) {
            if (g.kind != GoalKind.AIR_TARGET || g.dead) continue
            val hit = combat.rockets.firstOrNull {
                val dx = it.x - g.x; val dy = it.y - g.y; val dz = it.z - g.z
                dx * dx + dy * dy + dz * dz < (g.radius + 5f) * (g.radius + 5f)
            }
            if (hit != null) combat.detonate(hit)
        }
        val secondary = ArrayList<FloatArray>()
        for (e in combat.recentExplosions) {
            for (g in goals) {
                if (g.dead || (g.kind != GoalKind.GROUND_TARGET && g.kind != GoalKind.AIR_TARGET)) continue
                val dx = g.x - e[0]
                val dy = (g.y + g.radius * 0.5f) - e[1]
                val dz = g.z - e[2]
                if (sqrt(dx * dx + dy * dy + dz * dz) < e[3] * 0.5f + g.radius) {
                    g.dead = true
                    targets++
                    award(TargetStyle.points(g.style), TargetStyle.name(g.style), true)
                    if (g.kind == GoalKind.GROUND_TARGET) {
                        val boom = when (g.style) {
                            TargetStyle.FUEL -> 120f
                            TargetStyle.FACTORY -> 150f
                            else -> 80f
                        }
                        secondary.add(floatArrayOf(g.x, g.y + 3f, g.z, boom))
                    }
                }
            }
        }
        for (s in secondary) combat.explosion(s[0], s[1], s[2], s[3])
    }

    // ------------------------------------------------------------------ záchrana (vrtulník)

    private fun landedAt(g: Goal, f: FlightModel): Boolean {
        if (f.phase != FlightPhase.ROLLING) return false
        val dx = f.x - g.x
        val dz = f.z - g.z
        return dx * dx + dz * dz < (g.radius + 4f) * (g.radius + 4f) && abs(f.y - f.gearHeight - g.y) < 4f
    }

    private fun updateRescue(dt: Float, f: FlightModel) {
        rescueTimer += dt
        val pickup = goals.firstOrNull { it.kind == GoalKind.PICKUP }
        val drop = goals.firstOrNull { it.kind == GoalKind.DROPOFF }
        if (pickup != null && landedAt(pickup, f)) {
            goals.remove(pickup)
            carrying = true
            popups.add(Popup("Zraněný na palubě! Leť do nemocnice", 0))
            goals.add(Goal(GoalKind.DROPOFF, HOSPITAL_X, world.airportHeight, HOSPITAL_Z, 14f))
        } else if (drop != null && landedAt(drop, f)) {
            goals.remove(drop)
            carrying = false
            rescued++
            val bonus = (max(0f, 150f - rescueTimer) * 10f).toInt()
            award(1000 + bonus, "Záchrana", true)
            rescueTimer = 0f
            spawnPickup(f)
        } else if (pickup == null && drop == null && started) {
            spawnPickup(f)
        }
    }

    /** Místo, kde čeká zraněný: střecha mrakodrapu, horský svah, louka u vesnice… */
    fun spawnPickup(f: FlightModel) {
        rescueTimer = 0f
        if (world.hasCity && world.distToCity(f.x, f.z) < World.CITY_ROAD_RADIUS + 2500f) {
            val roofs = ArrayList<Box>()
            val c0x = World.chunkOf(f.x)
            val c0z = World.chunkOf(f.z)
            for (dz in -4..4) for (dx in -4..4) {
                val c = world.cachedContent(c0x + dx, c0z + dz) ?: continue
                for (b in c.buildings) {
                    if (b.destroyed) continue
                    val h = b.top - b.minY
                    val bx = (b.minX + b.maxX) / 2f
                    val bz = (b.minZ + b.maxZ) / 2f
                    val ddx = bx - f.x
                    val ddz = bz - f.z
                    val d2 = ddx * ddx + ddz * ddz
                    if (h in 35f..230f && b.maxX - b.minX > 18f && b.maxZ - b.minZ > 18f && d2 in 500f * 500f..2800f * 2800f) roofs.add(b)
                }
            }
            if (roofs.isNotEmpty()) {
                val b = roofs[rnd.nextInt(roofs.size)]
                goals.add(Goal(GoalKind.PICKUP, (b.minX + b.maxX) / 2f, b.top, (b.minZ + b.maxZ) / 2f, 9f, 1))
                return
            }
        }
        var best: Goal? = null
        var bestH = -Float.MAX_VALUE
        for (k in 0 until 30) {
            val ang = rnd.nextFloat() * 2f * PI.toFloat()
            val d = 700f + rnd.nextFloat() * 1900f
            val px = f.x + sin(ang) * d
            val pz = f.z - cos(ang) * d
            val gh = world.groundHeight(px, pz)
            if (gh < World.SEA_LEVEL + 2f) continue
            val slope = abs(world.groundHeight(px + 12f, pz) - world.groundHeight(px - 12f, pz)) +
                abs(world.groundHeight(px, pz + 12f) - world.groundHeight(px, pz - 12f))
            if (slope > 4f) continue
            if (world.forestMask(px, pz) > 0.15f || world.hitObstacle(px, gh + 3f, pz, 14f) != null) continue
            // v horách co nejvýš na svahu, jinde kdekoli
            val score = if (world.map == MapType.MOUNTAINS) gh else rnd.nextFloat()
            if (score > bestH) {
                bestH = score
                best = Goal(GoalKind.PICKUP, px, gh, pz, 10f, 0)
            }
        }
        best?.let { goals.add(it) }
    }

    // ------------------------------------------------------------------ text pro HUD

    private fun fmtDist(d: Float): String =
        if (d >= 1000f) String.format(Locale.ROOT, "%.1f km", d / 1000f) else "${d.toInt()} m"

    private fun nearest(f: FlightModel, kinds: Set<GoalKind>): Goal? =
        goals.filter { it.kind in kinds && !it.preview && !it.dead }.minByOrNull { dist2(it, f) }

    private fun describe(f: FlightModel): String {
        fun d(g: Goal) = fmtDist(sqrt(dist2(g, f)))
        return when (type) {
            AircraftType.HELICOPTER -> {
                val drop = goals.firstOrNull { it.kind == GoalKind.DROPOFF }
                val pick = goals.firstOrNull { it.kind == GoalKind.PICKUP }
                when {
                    drop != null -> "🏥 Do nemocnice na letišti · ${d(drop)}"
                    pick != null -> "🆘 Přistaň u zraněného · ${d(pick)}"
                    else -> "🚁 Hledám zraněné…"
                }
            }
            AircraftType.AIRLINER -> when (stage) {
                Stage.ROUTE -> nearest(f, setOf(GoalKind.RING))?.let { "✈️ Bod trasy ${routeDone + 1}/$ROUTE_RINGS · ${d(it)}" } ?: "✈️ Vzlétni a leť po trase"
                Stage.APPROACH -> nearest(f, setOf(GoalKind.RING))?.let { "🛬 Přistávací koridor · ${d(it)}" } ?: "🛬 Koridor"
                Stage.LAND -> "🛬 Přistaň na dráze co nejměkčeji"
                Stage.BRAKING -> "🅿️ Brzdíme…"
                Stage.DONE -> "✅ Let dokončen"
            }
            AircraftType.BOMBER -> nearest(f, setOf(GoalKind.GROUND_TARGET))?.let { "💣 Zbombarduj cíl · ${d(it)}" } ?: "💣 Hledám cíle…"
            AircraftType.FIGHTER -> {
                val ring = nearest(f, setOf(GoalKind.RING))
                val t = goals.count { (it.kind == GoalKind.GROUND_TARGET || it.kind == GoalKind.AIR_TARGET) && !it.dead }
                if (ring != null) "◯ Kruh · ${d(ring)}   🎯 cíle: $t" else "🎯 cíle: $t"
            }
            AircraftType.SPORT -> nearest(f, setOf(GoalKind.RING))?.let { "◯ Kruh · ${d(it)}" } ?: "◯ Vzlétni a leť kruhy"
        }
    }

    /** Pro minimapu: [x, z, druh] pro každý aktivní cíl. */
    fun markers(): FloatArray {
        val list = goals.filter { !it.dead }
        val out = FloatArray(list.size * 3)
        for ((i, g) in list.withIndex()) {
            out[i * 3] = g.x
            out[i * 3 + 1] = g.z
            out[i * 3 + 2] = if (g.preview) -1f else g.kind.ordinal.toFloat()
        }
        return out
    }
}
