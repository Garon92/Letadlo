package app.letadlo.game

import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

class Rocket(var x: Float, var y: Float, var z: Float, val vx: Float, val vy: Float, val vz: Float) {
    var age = 0f
    var trail = 0f
}

/** Volně padající bomba. */
class Bomb(var x: Float, var y: Float, var z: Float, var vx: Float, var vy: Float, var vz: Float) {
    var age = 0f
}

enum class Weapon { NONE, ROCKETS, BOMBS }

/** Ohnivá koule s kouřem. */
class Blast(val x: Float, val y: Float, val z: Float, val size: Float) {
    var age = 0f
}

/** Obláček kouře (stopa rakety, kouř z trosek). */
class Puff(val x: Float, var y: Float, val z: Float, val size: Float, val dark: Boolean, val life: Float, val rise: Float) {
    var age = 0f
}

/** Hořící trosky. */
class Fire(val x: Float, val y: Float, val z: Float, val size: Float) {
    var age = 0f
    var smoke = 0f
}

/**
 * Rakety stíhačky a následky výbuchů: ohnivé koule, rozmetané auta a lidé,
 * zřícené budovy, ohořelá zem a hořící trosky.
 */
class Combat(private val world: World, private val life: Life, seed: Long = 11L) {

    companion object {
        const val ROCKET_SPEED = 500f
        const val ROCKET_LIFE = 6f
        const val FIRE_INTERVAL = 0.18f
        const val ROCKET_BLAST = 55f
        const val MAX_SCORCHES = 80
        const val MAX_FIRES = 40
        const val MAX_PUFFS = 900
        const val FIRE_LIFE = 45f
        const val BLAST_LIFE = 5f
        const val BOMB_INTERVAL = 0.55f
        const val BOMB_BLAST = 140f
        const val BOMB_LIFE = 40f
        private const val G = 9.81f
        private const val BOMB_DRAG = 0.02f

        fun weaponFor(type: AircraftType): Weapon = when (type) {
            AircraftType.FIGHTER -> Weapon.ROCKETS
            AircraftType.BOMBER -> Weapon.BOMBS
            else -> Weapon.NONE
        }
    }

    val bombs = ArrayList<Bomb>()

    val rockets = ArrayList<Rocket>()
    val blasts = ArrayList<Blast>()
    val puffs = ArrayList<Puff>()
    val fires = ArrayList<Fire>()
    val scorches = ArrayList<MeshData>()

    /** Chunky s nově zničenými budovami – renderer je přestaví. */
    val dirtyChunks = ArrayList<Long>()

    /** Tlačítko palby je stisknuté. */
    @Volatile var trigger = false

    /** Počet vypuštěných raket / výbuchů od posledního přečtení (pro zvuk). */
    var launches = 0
    var booms = 0

    private val rnd = Random(seed)
    private var cooldown = 0f
    private var side = 1f

    /** Vyčistí letící střely a kouř (zničení ve světě zůstává). */
    fun reset() {
        rockets.clear()
        bombs.clear()
        blasts.clear()
        puffs.clear()
        trigger = false
        cooldown = 0f
    }

    fun update(dt: Float, f: FlightModel, weapon: Weapon) {
        cooldown -= dt
        if (weapon != Weapon.NONE && trigger && f.phase != FlightPhase.CRASHED && cooldown <= 0f) {
            if (weapon == Weapon.ROCKETS) {
                fire(f)
                cooldown = FIRE_INTERVAL
            } else {
                dropBomb(f)
                cooldown = BOMB_INTERVAL
            }
        }
        updateRockets(dt)
        updateBombs(dt)

        val bi = blasts.iterator()
        while (bi.hasNext()) {
            val b = bi.next()
            b.age += dt
            if (b.age > BLAST_LIFE) bi.remove()
        }
        val fi = fires.iterator()
        while (fi.hasNext()) {
            val fire = fi.next()
            fire.age += dt
            fire.smoke += dt
            if (fire.age < FIRE_LIFE * 0.85f && fire.smoke > 0.14f) {
                fire.smoke = 0f
                puffs.add(
                    Puff(
                        fire.x + (rnd.nextFloat() - 0.5f) * fire.size, fire.y + 1f,
                        fire.z + (rnd.nextFloat() - 0.5f) * fire.size, 2f + fire.size * 0.5f, true, 6f, 7f,
                    )
                )
            }
            if (fire.age > FIRE_LIFE) fi.remove()
        }
        val pi = puffs.iterator()
        while (pi.hasNext()) {
            val p = pi.next()
            p.age += dt
            p.y += p.rise * dt
            if (p.age > p.life) pi.remove()
        }
        while (puffs.size > MAX_PUFFS) puffs.removeAt(0)
    }

    /** Raketa z křídla, letí přesně ve směru nosu (tam, kam míří zaměřovač). */
    fun fire(f: FlightModel) {
        val ch = cos(f.heading)
        val sh = sin(f.heading)
        val cp = cos(f.pitch)
        val sp = sin(f.pitch)
        val fx = sh * cp
        val fy = sp
        val fz = -ch * cp
        val cb = cos(f.bank)
        val sb = sin(f.bank)
        // pravá strana letadla (s ohledem na náklon)
        val rx = ch * cb
        val ry = -sb
        val rz = sh * cb
        side = -side
        val off = 2.6f * side
        val speed = f.speed + ROCKET_SPEED
        rockets.add(
            Rocket(
                f.x + rx * off + fx * 2f, f.y + ry * off - 0.4f + fy * 2f, f.z + rz * off + fz * 2f,
                fx * speed, fy * speed, fz * speed,
            )
        )
        launches++
    }

    /** Rychlost letadla jako vektor (stejně jako ho posouvá letový model). */
    private fun velocity(f: FlightModel, out: FloatArray) {
        val cp = cos(f.pitch)
        out[0] = sin(f.heading) * cp * f.speed
        out[1] = f.verticalSpeed
        out[2] = -cos(f.heading) * cp * f.speed
    }

    private val tmpV = FloatArray(3)

    /** Bomba z pumovnice pod trupem – zdědí rychlost letadla a padá. */
    fun dropBomb(f: FlightModel) {
        velocity(f, tmpV)
        bombs.add(Bomb(f.x, f.y - 2.2f, f.z, tmpV[0], tmpV[1] - 2f, tmpV[2]))
        launches++
    }

    private fun updateBombs(dt: Float) {
        val it = bombs.iterator()
        while (it.hasNext()) {
            val b = it.next()
            b.age += dt
            val v = sqrt(b.vx * b.vx + b.vy * b.vy + b.vz * b.vz)
            val steps = max(1, (v * dt / 6f).toInt())
            val sdt = dt / steps
            var hit = false
            for (s in 0 until steps) {
                stepBomb(b, sdt)
                val ground = max(world.groundHeight(b.x, b.z), World.SEA_LEVEL)
                if (b.y <= ground) {
                    b.y = ground
                    hit = true
                    break
                }
                if (world.hitObstacle(b.x, b.y, b.z, 1f) != null) {
                    hit = true
                    break
                }
            }
            if (hit) {
                explosion(b.x, b.y, b.z, BOMB_BLAST)
                it.remove()
            } else if (b.age > BOMB_LIFE) {
                it.remove()
            }
        }
    }

    private fun stepBomb(b: Bomb, dt: Float) {
        val k = 1f - BOMB_DRAG * dt
        b.vx *= k
        b.vz *= k
        b.vy = (b.vy - G * dt) * k
        b.x += b.vx * dt
        b.y += b.vy * dt
        b.z += b.vz * dt
    }

    private val probe = Bomb(0f, 0f, 0f, 0f, 0f, 0f)

    /** Kam by teď puštěná bomba dopadla (pro zaměřovací kruh). Vrací false, když nedopadne do 30 s. */
    fun predictBombImpact(f: FlightModel, out: FloatArray): Boolean {
        velocity(f, tmpV)
        probe.x = f.x; probe.y = f.y - 2.2f; probe.z = f.z
        probe.vx = tmpV[0]; probe.vy = tmpV[1] - 2f; probe.vz = tmpV[2]
        var t = 0f
        while (t < 30f) {
            stepBomb(probe, 0.1f)
            t += 0.1f
            val ground = max(world.groundHeight(probe.x, probe.z), World.SEA_LEVEL)
            if (probe.y <= ground) {
                out[0] = probe.x; out[1] = ground; out[2] = probe.z
                return true
            }
        }
        return false
    }

    private fun updateRockets(dt: Float) {
        val it = rockets.iterator()
        while (it.hasNext()) {
            val r = it.next()
            r.age += dt
            val v = sqrt(r.vx * r.vx + r.vy * r.vy + r.vz * r.vz)
            val steps = max(1, (v * dt / 6f).toInt())
            val sdt = dt / steps
            var hit = false
            for (s in 0 until steps) {
                r.x += r.vx * sdt
                r.y += r.vy * sdt
                r.z += r.vz * sdt
                val ground = max(world.groundHeight(r.x, r.z), World.SEA_LEVEL)
                if (r.y <= ground) {
                    r.y = ground
                    hit = true
                    break
                }
                if (world.hitObstacle(r.x, r.y, r.z, 1.2f) != null) {
                    hit = true
                    break
                }
            }
            r.trail += dt
            while (r.trail > 0.035f) {
                r.trail -= 0.035f
                puffs.add(Puff(r.x, r.y, r.z, 0.9f, false, 1.1f, 0.6f))
            }
            if (hit) {
                explosion(r.x, r.y, r.z, ROCKET_BLAST)
                it.remove()
            } else if (r.age > ROCKET_LIFE) {
                it.remove()
            }
        }
    }

    /** Výbuch: ohnivá koule, rozházená auta a lidé, zničené stavby, spálená zem. Vrací počet zasažených. */
    fun explosion(x: Float, y: Float, z: Float, radius: Float): Int {
        blasts.add(Blast(x, y, z, radius / ROCKET_BLAST))
        booms++
        val hit = life.explode(x, y, z, radius)
        val rep = world.damage(x, y, z, radius * 0.42f)
        dirtyChunks.addAll(rep.chunks)
        for (fp in rep.fires) if (fires.size < MAX_FIRES) fires.add(Fire(fp[0], fp[1], fp[2], fp[3]))
        val gh = world.groundHeight(x, z)
        if (gh >= World.SEA_LEVEL - 0.5f && y - gh < radius * 0.7f) addScorch(x, z, radius * 0.38f)
        return hit
    }

    private fun addScorch(x: Float, z: Float, r: Float) {
        val mb = MeshBuilder(64)
        val seg = 14
        val ringR = floatArrayOf(0.35f, 0.72f, 1f)
        val alpha = floatArrayOf(0.9f, 0.72f, 0f)
        val center = mb.vertex(x, world.groundHeight(x, z) + 0.3f, z, 0f, 1f, 0f, 0.04f, 0.035f, 0.03f, 0.92f)
        val rings = Array(3) { IntArray(seg) }
        for (k in 0 until 3) for (s in 0 until seg) {
            val a = (2.0 * PI * s / seg).toFloat()
            val rr = r * ringR[k] * (0.8f + 0.4f * rnd.nextFloat())
            val px = x + cos(a) * rr
            val pz = z + sin(a) * rr
            val c = 0.05f + 0.035f * k
            rings[k][s] = mb.vertex(px, world.groundHeight(px, pz) + 0.3f, pz, 0f, 1f, 0f, c, c * 0.85f, c * 0.7f, alpha[k])
        }
        for (s in 0 until seg) {
            val s1 = (s + 1) % seg
            mb.tri(center, rings[0][s1], rings[0][s])
            for (k in 0 until 2) {
                mb.tri(rings[k][s], rings[k][s1], rings[k + 1][s1])
                mb.tri(rings[k][s], rings[k + 1][s1], rings[k + 1][s])
            }
        }
        scorches.add(mb.build())
        while (scorches.size > MAX_SCORCHES) scorches.removeAt(0)
    }
}
