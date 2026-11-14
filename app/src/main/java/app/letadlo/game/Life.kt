package app.letadlo.game

import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/** Auto nebo chodec. */
class Agent(val car: Boolean, val variant: Int) {
    var active = false
    var state = NORMAL
    var x = 0f
    var y = 0f
    var z = 0f
    var dirX = 0f
    var dirZ = -1f
    var speed = 0f
    var walk = 0f
    var alongX = true
    // letová fáze po výbuchu
    var vx = 0f
    var vy = 0f
    var vz = 0f
    var yaw = 0f
    var pitch = 0f
    var roll = 0f
    var wYaw = 0f
    var wPitch = 0f
    var wRoll = 0f

    companion object {
        const val NORMAL = 0
        const val FLYING = 1
        const val LYING = 2
    }
}

class Flock(
    val cx: Float, val cz: Float, val alt: Float,
    val radius: Float, val angSpeed: Float,
    val count: Int, val span: Float, val gull: Boolean,
    val offsets: FloatArray, val phases: FloatArray,
) {
    var angle = 0f
    var scatter = 0f
}

class Boat(var x: Float, var z: Float, var heading: Float, val speed: Float, val sail: Boolean) {
    var bob = 0f
}

/**
 * Život ve světě kolem kamery: auta a lidé ve městě (a lidé ve vesnicích),
 * hejna ptáků, lodě na vodě. Všechno se rodí jen v okolí kamery.
 */
class Life(private val world: World, seed: Long = 7L) {

    companion object {
        const val MAX_CARS = 220
        const val MAX_PEOPLE = 280
        const val MAX_FLOCKS = 7
        const val MAX_BOATS = 6
        const val CAR_VARIANTS = 7
        const val PERSON_VARIANTS = 6
        private const val AGENT_RANGE = 950f
        private const val G = 9.81f
    }

    val cars = Array(MAX_CARS) { Agent(true, it % CAR_VARIANTS) }
    val people = Array(MAX_PEOPLE) { Agent(false, it % PERSON_VARIANTS) }
    val flocks = ArrayList<Flock>()
    val boats = ArrayList<Boat>()
    private val rnd = Random(seed)
    var time = 0f
        private set

    fun reset() {
        for (a in cars) a.active = false
        for (a in people) a.active = false
        flocks.clear()
        boats.clear()
    }

    fun update(dt: Float, camX: Float, camZ: Float) {
        time += dt
        updateAgents(cars, dt, camX, camZ)
        updateAgents(people, dt, camX, camZ)
        updateFlocks(dt, camX, camZ)
        updateBoats(dt, camX, camZ)
    }

    // ------------------------------------------------------------------ auta a lidé

    private fun cityNear(camX: Float, camZ: Float) =
        world.hasCity && world.distToCity(camX, camZ) < World.CITY_ROAD_RADIUS + AGENT_RANGE + 200f

    private fun onCityStreets(x: Float, z: Float): Boolean =
        world.distToCity(x, z) < World.CITY_ROAD_RADIUS - 40f &&
            abs(x - World.riverX(z)) > 175f && world.airportWeight(x, z) <= 0f

    private fun updateAgents(list: Array<Agent>, dt: Float, camX: Float, camZ: Float) {
        var spawns = 0
        val city = cityNear(camX, camZ)
        for (a in list) {
            if (!a.active) {
                if (spawns < 14 && (city || !a.car)) {
                    spawns++
                    if (a.car) spawnCar(a, camX, camZ) else spawnPerson(a, camX, camZ, city)
                }
                continue
            }
            val dx = a.x - camX
            val dz = a.z - camZ
            if (dx * dx + dz * dz > (AGENT_RANGE + 250f) * (AGENT_RANGE + 250f)) {
                a.active = false
                continue
            }
            when (a.state) {
                Agent.NORMAL -> if (a.car) driveCar(a, dt) else walkPerson(a, dt)
                Agent.FLYING -> fly(a, dt)
                else -> Unit
            }
        }
    }

    private fun randomNear(camX: Float, camZ: Float, minD: Float, maxD: Float, out: FloatArray) {
        val ang = rnd.nextFloat() * 2f * PI.toFloat()
        val d = minD + rnd.nextFloat() * (maxD - minD)
        out[0] = camX + cos(ang) * d
        out[1] = camZ + sin(ang) * d
    }

    private val tmp = FloatArray(2)

    private fun spawnCar(a: Agent, camX: Float, camZ: Float) {
        randomNear(camX, camZ, 40f, AGENT_RANGE, tmp)
        val alongX = rnd.nextBoolean()
        val dir = if (rnd.nextBoolean()) 1f else -1f
        var x = tmp[0]
        var z = tmp[1]
        // pravostranný provoz: dva jízdní pruhy uprostřed silnice
        if (alongX) {
            val base = World.CITY_Z + floor((z - World.CITY_Z) / World.BLOCK) * World.BLOCK
            z = base + if (dir > 0) 16.5f else 5.5f
        } else {
            val base = World.CITY_X + floor((x - World.CITY_X) / World.BLOCK) * World.BLOCK
            x = base + if (dir > 0) 5.5f else 16.5f
        }
        if (!onCityStreets(x, z)) return
        a.x = x; a.z = z; a.y = World.CITY_HEIGHT
        a.alongX = alongX
        a.dirX = if (alongX) dir else 0f
        a.dirZ = if (alongX) 0f else dir
        a.speed = if (a.variant >= LifeMeshes.carColors.size) 8f else 9f + rnd.nextFloat() * 7f
        reset(a)
    }

    private fun spawnPerson(a: Agent, camX: Float, camZ: Float, city: Boolean) {
        randomNear(camX, camZ, 20f, if (city) AGENT_RANGE * 0.75f else 700f, tmp)
        var x = tmp[0]
        var z = tmp[1]
        val dir = if (rnd.nextBoolean()) 1f else -1f
        val alongX = rnd.nextBoolean()
        if (city && onCityStreets(x, z)) {
            // chodník podél jedné ze stran bloku
            val off = if (rnd.nextBoolean()) World.ROAD + 2f else World.BLOCK - 2f
            if (alongX) {
                z = World.CITY_Z + floor((z - World.CITY_Z) / World.BLOCK) * World.BLOCK + off
            } else {
                x = World.CITY_X + floor((x - World.CITY_X) / World.BLOCK) * World.BLOCK + off
            }
            if (!onCityStreets(x, z)) return
            a.y = World.CITY_HEIGHT
            a.alongX = alongX
            a.dirX = if (alongX) dir else 0f
            a.dirZ = if (alongX) 0f else dir
        } else {
            if (!world.inVillage(x, z, 0f)) return
            val gh = world.groundHeight(x, z)
            if (gh < World.SEA_LEVEL + 0.5f) return
            a.y = gh
            val ang = rnd.nextFloat() * 2f * PI.toFloat()
            a.dirX = cos(ang)
            a.dirZ = sin(ang)
            a.alongX = false
        }
        a.x = x; a.z = z
        a.speed = 1.1f + rnd.nextFloat() * 0.7f
        reset(a)
    }

    private fun reset(a: Agent) {
        a.active = true
        a.state = Agent.NORMAL
        a.yaw = Rot.yawFor(a.dirX, a.dirZ)
        a.pitch = 0f
        a.roll = 0f
        a.walk = rnd.nextFloat() * 6f
    }

    private fun driveCar(a: Agent, dt: Float) {
        val nx = a.x + a.dirX * a.speed * dt
        val nz = a.z + a.dirZ * a.speed * dt
        if (!onCityStreets(nx + a.dirX * 12f, nz + a.dirZ * 12f)) {
            // otočka do protisměru
            a.dirX = -a.dirX
            a.dirZ = -a.dirZ
            if (a.alongX) {
                val base = World.CITY_Z + floor((a.z - World.CITY_Z) / World.BLOCK) * World.BLOCK
                a.z = base + (World.ROAD - (a.z - base))
            } else {
                val base = World.CITY_X + floor((a.x - World.CITY_X) / World.BLOCK) * World.BLOCK
                a.x = base + (World.ROAD - (a.x - base))
            }
            a.yaw = Rot.yawFor(a.dirX, a.dirZ)
            return
        }
        a.x = nx
        a.z = nz
    }

    private fun walkPerson(a: Agent, dt: Float) {
        a.walk += dt * a.speed * 4f
        if (rnd.nextFloat() < dt * 0.04f) {
            a.dirX = -a.dirX
            a.dirZ = -a.dirZ
        }
        val nx = a.x + a.dirX * a.speed * dt
        val nz = a.z + a.dirZ * a.speed * dt
        val ok = if (abs(a.y - World.CITY_HEIGHT) < 0.01f && world.hasCity) onCityStreets(nx, nz) else world.inVillage(nx, nz, 0f)
        if (!ok) {
            a.dirX = -a.dirX
            a.dirZ = -a.dirZ
        } else {
            a.x = nx
            a.z = nz
            if (!world.hasCity || abs(a.y - World.CITY_HEIGHT) >= 0.01f) a.y = world.groundHeight(nx, nz)
        }
        a.yaw = Rot.yawFor(a.dirX, a.dirZ)
    }

    private fun fly(a: Agent, dt: Float) {
        a.vy -= G * dt
        a.x += a.vx * dt
        a.y += a.vy * dt
        a.z += a.vz * dt
        a.yaw += a.wYaw * dt
        a.pitch += a.wPitch * dt
        a.roll += a.wRoll * dt
        val gh = world.groundHeight(a.x, a.z)
        if (a.y <= gh) {
            a.y = gh
            if (a.vy < -4f) {
                a.vy = -a.vy * 0.3f
                a.vx *= 0.5f; a.vz *= 0.5f
                a.wYaw *= 0.5f; a.wPitch *= 0.5f; a.wRoll *= 0.5f
            } else {
                a.state = Agent.LYING
                if (a.car) {
                    a.pitch = 0f
                    a.roll = if (abs(sin(a.roll)) < 0.7f && cos(a.roll) < 0f) PI.toFloat() else 0f
                } else {
                    a.pitch = (PI / 2).toFloat()
                    a.roll = 0f
                    a.y += 0.15f
                }
            }
        }
    }

    /** Výbuch rozhází auta a lidi v okolí. Vrátí počet zasažených. */
    fun explode(x: Float, y: Float, z: Float, radius: Float): Int {
        var n = 0
        for (list in arrayOf(cars, people)) for (a in list) {
            if (!a.active || a.state == Agent.FLYING) continue
            val dx = a.x - x
            val dy = a.y + 1f - y
            val dz = a.z - z
            val d = sqrt(dx * dx + dy * dy + dz * dz)
            if (d >= radius) continue
            val k = 1f - d / radius
            val hd = sqrt(dx * dx + dz * dz).coerceAtLeast(0.5f)
            val power = (if (a.car) 20f else 30f) * (0.35f + 0.65f * k)
            a.vx = dx / hd * power + (rnd.nextFloat() - 0.5f) * 6f
            a.vz = dz / hd * power + (rnd.nextFloat() - 0.5f) * 6f
            a.vy = (if (a.car) 9f else 13f) * (0.5f + k) + 4f + rnd.nextFloat() * 4f
            val spin = if (a.car) 3f else 9f
            a.wYaw = (rnd.nextFloat() - 0.5f) * spin
            a.wPitch = (rnd.nextFloat() - 0.5f) * spin * 1.5f
            a.wRoll = (rnd.nextFloat() - 0.5f) * spin * 1.5f
            a.state = Agent.FLYING
            a.y += 0.2f
            n++
        }
        for (f in flocks) {
            val dx = f.cx - x
            val dz = f.cz - z
            if (dx * dx + dz * dz < (radius * 4f) * (radius * 4f)) f.scatter = 0.01f
        }
        return n
    }

    // ------------------------------------------------------------------ ptáci

    private fun updateFlocks(dt: Float, camX: Float, camZ: Float) {
        val iter = flocks.iterator()
        while (iter.hasNext()) {
            val f = iter.next()
            f.angle += f.angSpeed * dt
            if (f.scatter > 0f) f.scatter += dt
            val dx = f.cx - camX
            val dz = f.cz - camZ
            if (dx * dx + dz * dz > 2200f * 2200f || f.scatter > 12f) iter.remove()
        }
        if (flocks.size < MAX_FLOCKS && rnd.nextFloat() < dt * 2f) {
            randomNear(camX, camZ, 250f, 1500f, tmp)
            val gh = world.surfaceHeight(tmp[0], tmp[1])
            val n = 6 + rnd.nextInt(10)
            val offs = FloatArray(n * 3)
            val ph = FloatArray(n)
            for (i in 0 until n) {
                offs[i * 3] = (rnd.nextFloat() - 0.5f) * 30f
                offs[i * 3 + 1] = (rnd.nextFloat() - 0.5f) * 8f
                offs[i * 3 + 2] = (rnd.nextFloat() - 0.5f) * 30f
                ph[i] = rnd.nextFloat() * 6.28f
            }
            val gull = world.map != MapType.MOUNTAINS && rnd.nextFloat() < 0.4f
            flocks.add(
                Flock(
                    tmp[0], tmp[1], gh + 35f + rnd.nextFloat() * 220f,
                    50f + rnd.nextFloat() * 110f,
                    (0.12f + rnd.nextFloat() * 0.14f) * if (rnd.nextBoolean()) 1f else -1f,
                    n, if (gull) 1.5f else 1.9f + rnd.nextFloat() * 0.6f, gull, offs, ph,
                ).also { fl -> fl.angle = rnd.nextFloat() * 6.28f }
            )
        }
    }

    /** Pozice ptáka i v hejnu – zapisuje x, y, z, yaw, mávnutí. */
    fun birdPose(f: Flock, i: Int, out: FloatArray) {
        val ang = f.angle + f.offsets[i * 3] * 0.004f
        val r = f.radius + f.offsets[i * 3 + 2] + f.scatter * f.scatter * 12f
        val sx = f.cx + cos(ang) * r
        val sz = f.cz + sin(ang) * r
        out[0] = sx
        out[1] = f.alt + f.offsets[i * 3 + 1] + f.scatter * 6f + sin(time * 0.7f + f.phases[i]) * 1.5f
        out[2] = sz
        // tečna ke kružnici
        val tx = -sin(ang) * f.angSpeed
        val tz = cos(ang) * f.angSpeed
        out[3] = Rot.yawFor(tx, tz)
        out[4] = time * (if (f.scatter > 0f) 14f else 7f) + f.phases[i]
    }

    // ------------------------------------------------------------------ lodě

    private fun updateBoats(dt: Float, camX: Float, camZ: Float) {
        val it = boats.iterator()
        while (it.hasNext()) {
            val b = it.next()
            b.bob += dt
            val fx = sin(b.heading)
            val fz = -cos(b.heading)
            if (world.groundHeight(b.x + fx * 15f, b.z + fz * 15f) > -1.5f) {
                b.heading += (PI * (0.6 + rnd.nextDouble() * 0.6)).toFloat()
            } else {
                b.x += fx * b.speed * dt
                b.z += fz * b.speed * dt
            }
            val dx = b.x - camX
            val dz = b.z - camZ
            if (dx * dx + dz * dz > 2800f * 2800f) it.remove()
        }
        if (boats.size < MAX_BOATS) {
            for (attempt in 0 until 3) {
                randomNear(camX, camZ, 200f, 1800f, tmp)
                if (world.groundHeight(tmp[0], tmp[1]) < -3f) {
                    boats.add(Boat(tmp[0], tmp[1], rnd.nextFloat() * 6.28f, 3f + rnd.nextFloat() * 4f, rnd.nextFloat() < 0.5f))
                    break
                }
            }
        }
    }

    val activeCars: Int get() = cars.count { it.active }
    val activePeople: Int get() = people.count { it.active }
}
