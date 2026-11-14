package app.letadlo.gl

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import app.letadlo.game.Agent
import app.letadlo.game.AircraftMeshes
import app.letadlo.game.AircraftType
import app.letadlo.game.ChunkMesher
import app.letadlo.game.Combat
import app.letadlo.game.Fire
import app.letadlo.game.Goal
import app.letadlo.game.GoalKind
import app.letadlo.game.Missions
import app.letadlo.game.TargetStyle
import app.letadlo.game.Controls
import app.letadlo.game.CrashReason
import app.letadlo.game.DynamicBatch
import app.letadlo.game.FlightModel
import app.letadlo.game.FlightPhase
import app.letadlo.game.Life
import app.letadlo.game.LifeMeshes
import app.letadlo.game.MapType
import app.letadlo.game.MeshBuilder
import app.letadlo.game.ParkedAircraft
import app.letadlo.game.RotorSpec
import app.letadlo.game.Rot
import app.letadlo.game.World
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class HudState(
    val speedKmh: Int,
    val altitude: Int,
    val agl: Int,
    val phase: FlightPhase,
    val airTime: Float,
    val hasTakenOff: Boolean,
    val readyForTakeoff: Boolean,
    val calibrating: Boolean,
    val bankDeg: Float,
    val pitchDeg: Float,
    val x: Float,
    val z: Float,
    val headingDeg: Float,
    val score: Int,
    val multiplier: Int,
    val comboFraction: Float,
    val objective: String,
    val lowFlying: Boolean,
    val popups: List<String>,
    val markers: FloatArray,
)

class FlightResult(
    val map: MapType,
    val aircraft: AircraftType,
    val reason: CrashReason,
    val airTime: Float,
    val distance: Float,
    val maxAgl: Float,
    val tookOff: Boolean,
    val victims: Int,
    val score: Int,
    val summary: String,
)

class GameRenderer(
    map: MapType,
    val aircraft: AircraftType,
    private val controls: Controls,
    private val listener: Listener,
) : GLSurfaceView.Renderer {

    interface Listener {
        fun onHud(state: HudState)
        fun onCrash(result: FlightResult)
        fun onImpact()
        fun onWeapons(launches: Int, booms: Int)
        fun onMinimap(pixels: IntArray, size: Int, centerX: Float, centerZ: Float, metersPerPixel: Float)
    }

    val world = World(map)
    val flight = FlightModel(world, aircraft)
    private val life = Life(world)
    private val batch = DynamicBatch()

    /** Noc (měsíc, hvězdy, rozsvícená okna) – přepíná se tlačítkem. */
    @Volatile var night = false

    /** Pohled z kabiny místo pohledu zvenku. */
    /** 0 = zvenku, 1 = z kabiny, 2 = pohled dolů (bombardér). */
    @Volatile var viewMode = 0

    val cockpit: Boolean get() = viewMode == 1

    /** Cíle, body a kombo. */
    val missions = Missions(world, aircraft)

    private val radius = if (map == MapType.MOUNTAINS || aircraft == AircraftType.FIGHTER || aircraft == AircraftType.AIRLINER) 7 else 6
    private val fogEnd = (radius - 0.8f) * World.CHUNK
    private val fogStart = fogEnd * 0.28f
    private val chunks = ChunkManager(world, radius)
    private val dayFog = if (map == MapType.MOUNTAINS) floatArrayOf(0.72f, 0.81f, 0.91f) else floatArrayOf(0.76f, 0.85f, 0.93f)
    private val nightFog = floatArrayOf(0.045f, 0.06f, 0.1f)
    private val sun = norm(-0.42f, 0.74f, 0.52f)
    private val moon = norm(0.35f, 0.5f, -0.79f)
    private val eye = AircraftMeshes.eye(aircraft)
    private val camScale = when (aircraft) {
        AircraftType.SPORT -> 1f
        AircraftType.FIGHTER -> 1.35f
        AircraftType.AIRLINER -> 2.7f
        AircraftType.HELICOPTER -> 1.3f
        AircraftType.BOMBER -> 2.3f
    }

    // šablony pohyblivých věcí (CPU)
    private val carT = Array(Life.CAR_VARIANTS) { LifeMeshes.car(it) }
    private val personT = Array(Life.PERSON_VARIANTS) { LifeMeshes.person(it) }
    private val boatT = arrayOf(LifeMeshes.boat(false), LifeMeshes.boat(true))
    private val bladesT = LifeMeshes.turbineBlades(34f)
    private val rocketT = LifeMeshes.rocket()
    private val puffLightT = LifeMeshes.puff(false)
    private val puffDarkT = LifeMeshes.puff(true)
    private val flameT = LifeMeshes.flame()
    private val noRotation = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

    /** Rakety, výbuchy, zničení. */
    val combat = Combat(world, life)
    private val weapon = Combat.weaponFor(aircraft)
    private val bombT = LifeMeshes.bomb()
    private val impact = FloatArray(3)
    private val targetT = HashMap<Int, app.letadlo.game.MeshData>().apply {
        for (st in intArrayOf(TargetStyle.RADAR, TargetStyle.FUEL, TargetStyle.TANK, TargetStyle.FACTORY, TargetStyle.BALLOON)) {
            put(st, LifeMeshes.target(st))
        }
    }
    private val rescueeT = LifeMeshes.person(1)
    private val helipadT = LifeMeshes.helipad()
    private lateinit var ringMesh: GpuMesh
    private lateinit var diamondMesh: GpuMesh
    private var aspect = 1.8f
    private var fov = 52f
    private val decalBatch = DynamicBatch(1024)
    private val decals = DynamicMesh()
    private lateinit var reticle: GpuMesh
    private val bladeYaw = atan2(World.TURBINE_DIR_X, World.TURBINE_DIR_Z)

    private lateinit var worldProg: GlProgram
    private lateinit var skyProg: GlProgram
    private lateinit var body: GpuMesh
    private lateinit var rotors: List<Pair<RotorSpec, GpuMesh>>
    private lateinit var cockpitMesh: GpuMesh
    private lateinit var shadow: GpuMesh
    private lateinit var ball: GpuMesh
    private lateinit var airport: GpuMesh
    private lateinit var water: GpuMesh
    private lateinit var parked: List<Triple<ParkedAircraft, GpuMesh, List<Pair<RotorSpec, GpuMesh>>>>
    private val dynamic = DynamicMesh()
    private var skyVao = 0

    private val proj = FloatArray(16)
    private val projNear = FloatArray(16)
    private val view = FloatArray(16)
    private val viewProj = FloatArray(16)
    private val viewRot = FloatArray(16)
    private val skyVP = FloatArray(16)
    private val invSky = FloatArray(16)
    private val model = FloatArray(16)
    private val model2 = FloatArray(16)
    private val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val v4 = FloatArray(4)
    private val v4b = FloatArray(4)
    private val pose = FloatArray(5)

    private var camX = 0f
    private var camY = 0f
    private var camZ = 0f
    private var camInit = false
    // kamera zvenku se vyhlazuje relativně k letadlu, takže nezaostává ani ve 2000 km/h
    private var offX = 0f
    private var offY = 0f
    private var offZ = 0f
    private val rot = FloatArray(16)
    private val rotT = FloatArray(16)
    private val localView = FloatArray(16)
    private var fwdX = 0f
    private var fwdZ = -1f

    private var lastNanos = 0L
    private var time = 0f
    private var hudTimer = 0f
    private var crashTime = 0f
    private var crashReported = false
    private var impactReported = false
    private var victims = 0

    private fun norm(x: Float, y: Float, z: Float): FloatArray {
        val l = sqrt(x * x + y * y + z * z)
        return floatArrayOf(x / l, y / l, z / l)
    }

    /** Volat na GL vlákně (přes queueEvent). */
    fun restart() {
        flight.reset()
        life.reset()
        combat.reset()
        missions.reset()
        chunks.loadNow(flight.x, flight.z, 1)
        crashTime = 0f
        crashReported = false
        impactReported = false
        victims = 0
        camInit = false
    }

    fun release() {
        chunks.shutdown()
    }

    private fun rotorMeshes(type: AircraftType) = AircraftMeshes.rotors(type).map { Pair(it, GpuMesh(it.mesh)) }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glFrontFace(GLES30.GL_CCW)
        GLES30.glCullFace(GLES30.GL_BACK)

        worldProg = GlProgram(Shaders.WORLD_VS, Shaders.WORLD_FS)
        skyProg = GlProgram(Shaders.SKY_VS, Shaders.SKY_FS)
        body = GpuMesh(AircraftMeshes.body(aircraft))
        rotors = rotorMeshes(aircraft)
        cockpitMesh = GpuMesh(AircraftMeshes.cockpit(aircraft))
        shadow = GpuMesh(AircraftMeshes.shadow(aircraft))
        ball = GpuMesh(AircraftMeshes.ball())
        airport = GpuMesh(ChunkMesher.buildings(world.airportBoxes))
        water = GpuMesh(waterQuad(fogEnd + 1500f))
        parked = world.parkedAircraft.map { Triple(it, GpuMesh(AircraftMeshes.body(it.type)), rotorMeshes(it.type)) }
        dynamic.init()
        decals.init()
        reticle = GpuMesh(LifeMeshes.reticle())
        ringMesh = GpuMesh(LifeMeshes.ring())
        diamondMesh = GpuMesh(LifeMeshes.diamond())
        val ids = IntArray(1)
        GLES30.glGenVertexArrays(1, ids, 0)
        skyVao = ids[0]

        chunks.onContextCreated()
        chunks.loadNow(flight.x, flight.z, 1)
        lastNanos = System.nanoTime()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        aspect = width.toFloat() / max(1, height)
        Matrix.perspectiveM(proj, 0, fov, aspect, 1.5f, fogEnd + 800f)
        Matrix.perspectiveM(projNear, 0, 62f, aspect, 0.05f, 120f)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        var dt = (now - lastNanos) / 1_000_000_000f
        lastNanos = now
        if (dt > 0.05f) dt = 0.05f
        if (dt < 0f) dt = 0f
        time += dt

        val roll = controls.roll
        val pitch = if (controls.calibrating) 0f else controls.pitch
        val sub = 3
        for (i in 0 until sub) flight.update(dt / sub, roll, pitch)

        if (flight.phase == FlightPhase.CRASHED) {
            if (!impactReported) {
                impactReported = true
                victims = combat.explosion(flight.x, flight.y, flight.z, aircraft.blastRadius)
                combat.booms = 0 // zvuk havárie obstará onImpact
                val gy = world.surfaceHeight(flight.x, flight.z)
                combat.fires.add(Fire(flight.x, gy, flight.z, 3f * camScale))
                listener.onImpact()
            }
            crashTime += dt
            if (!crashReported && crashTime > 1.4f) {
                crashReported = true
                listener.onCrash(
                    FlightResult(
                        world.map, aircraft, flight.crashReason ?: CrashReason.TERRAIN,
                        flight.airTime, flight.distance, flight.maxAltitudeAgl, flight.hasTakenOff, victims,
                        missions.score, summary(),
                    )
                )
            }
        }

        updateCamera(dt)
        life.update(dt, camX, camZ)
        combat.update(dt, flight, weapon)
        missions.update(dt, flight, combat)
        // širší zorné pole s rychlostí – pocit rychlosti
        val speedK = ((flight.speed - 60f) / 450f).coerceIn(0f, 1f)
        val wantFov = 52f + 14f * speedK
        if (abs(wantFov - fov) > 0.05f) {
            fov += (wantFov - fov) * min(1f, dt * 2f)
            Matrix.perspectiveM(proj, 0, fov, aspect, 1.5f, fogEnd + 800f)
        }
        if (combat.dirtyChunks.isNotEmpty()) {
            for (k in combat.dirtyChunks) chunks.rebuild((k shr 32).toInt(), k.toInt())
            combat.dirtyChunks.clear()
        }
        if (combat.launches > 0 || combat.booms > 0) {
            listener.onWeapons(combat.launches, combat.booms)
            combat.launches = 0
            combat.booms = 0
        }
        chunks.update(camX, camZ)
        buildBatch()
        render()

        hudTimer += dt
        if (hudTimer > 0.1f) {
            hudTimer = 0f
            listener.onHud(
                HudState(
                    speedKmh = (flight.speed * 3.6f).toInt(),
                    altitude = flight.y.toInt(),
                    agl = max(0f, flight.altitudeAgl).toInt(),
                    phase = flight.phase,
                    airTime = flight.airTime,
                    hasTakenOff = flight.hasTakenOff,
                    readyForTakeoff = flight.readyForTakeoff,
                    calibrating = controls.calibrating,
                    bankDeg = deg(flight.bank),
                    pitchDeg = deg(flight.pitch),
                    x = flight.x,
                    z = flight.z,
                    headingDeg = deg(flight.heading),
                    score = missions.score,
                    multiplier = missions.multiplier,
                    comboFraction = (missions.comboLeft / Missions.COMBO_TIME).coerceIn(0f, 1f),
                    objective = missions.objective,
                    lowFlying = missions.lowFlying,
                    popups = missions.popups.map { it.text }.also { missions.popups.clear() },
                    markers = missions.markers(),
                )
            )
        }
        mapTimer += dt
        if (mapTimer > 0.4f) {
            mapTimer = 0f
            buildMinimap()
        }
    }

    // ------------------------------------------------------------------ mapa

    private var mapTimer = 1f
    private val mapN = 72
    private val mapRange = 3000f

    /** Obraz okolí z už spočítaných chunků (barvy terénu, voda, vesnice, stín svahů). */
    private fun buildMinimap() {
        val px = IntArray(mapN * mapN)
        val step = mapRange * 2f / mapN
        val cx = flight.x
        val cz = flight.z
        for (j in 0 until mapN) for (i in 0 until mapN) {
            val wx = cx + (i + 0.5f - mapN / 2f) * step
            val wz = cz + (j + 0.5f - mapN / 2f) * step
            val c = world.cachedContent(World.chunkOf(wx), World.chunkOf(wz))
            if (c == null) {
                px[j * mapN + i] = 0
                continue
            }
            val li = ((wx - c.cx * World.CHUNK) / World.CELL + 0.5f).toInt().coerceIn(0, World.CELLS)
            val lj = ((wz - c.cz * World.CHUNK) / World.CELL + 0.5f).toInt().coerceIn(0, World.CELLS)
            val idx = lj * World.VERTS + li
            val h = c.heights[idx]
            var r: Float
            var g: Float
            var b: Float
            if (h < World.SEA_LEVEL) {
                r = 0.18f; g = 0.42f; b = 0.68f
            } else {
                r = c.colors[idx * 4]; g = c.colors[idx * 4 + 1]; b = c.colors[idx * 4 + 2]
                if (c.colors[idx * 4 + 3] < 0.5f) { r = r * 0.6f + 0.3f; g = g * 0.6f + 0.25f; b = b * 0.6f + 0.08f }
                if (world.inVillage(wx, wz, 0f)) { r = 0.78f; g = 0.42f; b = 0.32f }
                // stínování svahů (světlo od severozápadu)
                val nx = c.normals[idx * 3]
                val ny = c.normals[idx * 3 + 1]
                val nz = c.normals[idx * 3 + 2]
                val shade = (0.55f + 0.6f * (-0.55f * nx + 0.7f * ny - 0.45f * nz)).coerceIn(0.45f, 1.25f)
                r *= shade; g *= shade; b *= shade
            }
            px[j * mapN + i] = (0xFF shl 24) or
                ((r * 255f).toInt().coerceIn(0, 255) shl 16) or
                ((g * 255f).toInt().coerceIn(0, 255) shl 8) or
                (b * 255f).toInt().coerceIn(0, 255)
        }
        listener.onMinimap(px, mapN, cx, cz, step)
    }

    private fun summary(): String {
        val m = missions
        val parts = ArrayList<String>()
        if (m.rings > 0) parts += "kruhy ${m.rings}"
        if (m.targets > 0) parts += "cíle ${m.targets}"
        if (m.rescued > 0) parts += "zachráněno ${m.rescued}"
        if (m.landingsScored > 0) parts += "přistání ${m.landingsScored}"
        if (m.nearMisses > 0) parts += "těsně ${m.nearMisses}×"
        return parts.joinToString("  ·  ")
    }

    private fun deg(r: Float): Float = (r * 180.0 / PI).toFloat()

    private fun planeModel(out: FloatArray) {
        val f = flight
        Matrix.setIdentityM(out, 0)
        Matrix.translateM(out, 0, f.x, f.y, f.z)
        Matrix.rotateM(out, 0, -deg(f.heading), 0f, 1f, 0f)
        Matrix.rotateM(out, 0, deg(f.pitch), 1f, 0f, 0f)
        Matrix.rotateM(out, 0, -deg(f.bank), 0f, 0f, 1f)
    }

    // ------------------------------------------------------------------ kamera

    private fun updateCamera(dt: Float) {
        val f = flight
        val crashed = f.phase == FlightPhase.CRASHED
        if (viewMode == 2 && !crashed) {
            bombView()
            return
        }
        if (cockpit && !crashed) {
            planeModel(model)
            v4b[0] = eye[0]; v4b[1] = eye[1]; v4b[2] = eye[2]; v4b[3] = 1f
            Matrix.multiplyMV(v4, 0, model, 0, v4b, 0)
            camX = v4[0]; camY = v4[1]; camZ = v4[2]
            val sh = shake()
            v4b[0] = sin(time * 37f) * sh; v4b[1] = sin(time * 29f + 1f) * sh; v4b[2] = -1f; v4b[3] = 0f
            Matrix.multiplyMV(v4, 0, model, 0, v4b, 0)
            val lx = camX + v4[0]
            val ly = camY + v4[1]
            val lz = camZ + v4[2]
            val hl = sqrt(v4[0] * v4[0] + v4[2] * v4[2])
            if (hl > 0.05f) {
                fwdX = v4[0] / hl
                fwdZ = v4[2] / hl
            }
            v4b[0] = 0f; v4b[1] = 1f; v4b[2] = 0f; v4b[3] = 0f
            Matrix.multiplyMV(v4, 0, model, 0, v4b, 0)
            Matrix.setLookAtM(view, 0, camX, camY, camZ, lx, ly, lz, v4[0], v4[1], v4[2])
            offX = camX - f.x; offY = camY - f.y; offZ = camZ - f.z
            camInit = true
            return
        }

        val hx = sin(f.heading)
        val hz = -cos(f.heading)
        val s = camScale
        val tx: Float
        val ty: Float
        val tz: Float
        val lx: Float
        val ly: Float
        val lz: Float
        if (crashed) {
            val ang = f.heading + PI.toFloat() + crashTime * 0.22f
            val dist = (42f + min(crashTime, 4f) * 7f) * (0.6f + 0.4f * s)
            tx = f.x + sin(ang) * dist
            tz = f.z - cos(ang) * dist
            ty = f.y + 14f + min(crashTime, 4f) * 5f
            lx = f.x; ly = f.y + 4f; lz = f.z
        } else {
            val cp = cos(f.pitch * 0.6f)
            val sp = sin(f.pitch * 0.6f)
            val back = 19f * s
            tx = f.x - hx * back * cp
            tz = f.z - hz * back * cp
            ty = f.y + 5.5f * s - sp * back
            val ahead = 30f * s
            lx = f.x + hx * ahead * cos(f.pitch)
            ly = f.y + 2f * s + sin(f.pitch) * ahead
            lz = f.z + hz * ahead * cos(f.pitch)
        }
        if (!camInit) {
            offX = tx - f.x; offY = ty - f.y; offZ = tz - f.z
            camInit = true
        }
        val k = 1f - exp(-dt * if (crashed) 1.5f else 5.5f)
        offX += (tx - f.x - offX) * k
        offY += (ty - f.y - offY) * k
        offZ += (tz - f.z - offZ) * k
        camX = f.x + offX
        camY = f.y + offY
        camZ = f.z + offZ
        val minY = world.surfaceHeight(camX, camZ) + 2.5f
        if (camY < minY) camY = minY

        val dx = lx - camX
        val dz = lz - camZ
        val dl = sqrt(dx * dx + dz * dz)
        if (dl > 0.01f) {
            fwdX = dx / dl
            fwdZ = dz / dl
        }
        val r = if (crashed) 0f else f.bank * 0.4f
        val rightX = -fwdZ
        val rightZ = fwdX
        val sh = if (crashed) 0f else shake() * 30f * s
        Matrix.setLookAtM(
            view, 0, camX, camY, camZ, lx + sin(time * 37f) * sh, ly + sin(time * 29f + 1f) * sh, lz,
            sin(r) * rightX, cos(r), sin(r) * rightZ,
        )
    }

    /** Lehké chvění obrazu při rychlém letu nízko nad zemí. */
    private fun shake(): Float {
        val f = flight
        if (f.phase != FlightPhase.AIRBORNE) return 0f
        val fast = ((f.speed - 70f) / 450f).coerceIn(0f, 1f)
        val low = (1f - f.altitudeAgl / 80f).coerceIn(0f, 1f)
        return 0.0035f * fast * (0.3f + 0.7f * low)
    }

    /** Bombardér: kamera pod přídí míří šikmo dolů, aby bylo vidět, kam bomby padají. */
    private fun bombView() {
        planeModel(model)
        v4b[0] = 0f; v4b[1] = -3.4f; v4b[2] = -8f; v4b[3] = 1f
        Matrix.multiplyMV(v4, 0, model, 0, v4b, 0)
        camX = v4[0]; camY = v4[1]; camZ = v4[2]
        v4b[0] = 0f; v4b[1] = -0.94f; v4b[2] = -0.34f; v4b[3] = 0f
        Matrix.multiplyMV(v4, 0, model, 0, v4b, 0)
        val lx = camX + v4[0]
        val ly = camY + v4[1]
        val lz = camZ + v4[2]
        val hl = sqrt(v4[0] * v4[0] + v4[2] * v4[2])
        if (hl > 0.05f) {
            fwdX = v4[0] / hl
            fwdZ = v4[2] / hl
        }
        v4b[0] = 0f; v4b[1] = 0.34f; v4b[2] = -0.94f; v4b[3] = 0f
        Matrix.multiplyMV(v4, 0, model, 0, v4b, 0)
        Matrix.setLookAtM(view, 0, camX, camY, camZ, lx, ly, lz, v4[0], v4[1], v4[2])
        offX = camX - flight.x; offY = camY - flight.y; offZ = camZ - flight.z
        camInit = true
    }

    // ------------------------------------------------------------------ pohyblivé věci

    private fun near(x: Float, z: Float, range: Float): Boolean {
        val dx = x - camX
        val dz = z - camZ
        val d2 = dx * dx + dz * dz
        if (d2 > range * range) return false
        return d2 < 900f || dx * fwdX + dz * fwdZ > -40f
    }

    private fun buildBatch() {
        batch.clear()
        for (a in life.cars) {
            if (a.active && near(a.x, a.z, 750f)) batch.add(carT[a.variant], a.x, a.y, a.z, a.yaw, a.pitch, a.roll)
        }
        for (a in life.people) {
            if (!a.active || !near(a.x, a.z, 450f)) continue
            val bob = if (a.state == Agent.NORMAL) abs(sin(a.walk)) * 0.06f else 0f
            batch.add(personT[a.variant], a.x, a.y + bob, a.z, a.yaw, a.pitch, a.roll, 1.1f)
        }
        for (f in life.flocks) {
            for (i in 0 until f.count) {
                life.birdPose(f, i, pose)
                if (!near(pose[0], pose[2], 1200f)) continue
                if (f.gull) batch.addBird(pose[0], pose[1], pose[2], pose[3], pose[4], f.span, 0.93f, 0.93f, 0.9f)
                else batch.addBird(pose[0], pose[1], pose[2], pose[3], pose[4], f.span, 0.2f, 0.17f, 0.15f)
            }
        }
        for (b in life.boats) {
            if (near(b.x, b.z, 1500f)) {
                batch.add(boatT[if (b.sail) 1 else 0], b.x, World.SEA_LEVEL + sin(b.bob * 1.3f) * 0.08f, b.z,
                    -b.heading, 0f, sin(b.bob) * 0.05f)
            }
        }
        chunks.forEachLoaded { cx, cz ->
            for (t in world.content(cx, cz).turbines) {
                if (!near(t.x, t.z, 3500f)) continue
                batch.add(
                    bladesT, t.x + World.TURBINE_DIR_X * 4.5f, t.y + t.hubHeight, t.z + World.TURBINE_DIR_Z * 4.5f,
                    bladeYaw, 0f, time * 1.3f + t.phase,
                )
            }
        }
        // cíle misí
        for (g in missions.goals) {
            if (g.dead || !near(g.x, g.z, 6000f)) continue
            when (g.kind) {
                GoalKind.GROUND_TARGET -> {
                    val yaw = if (g.vx != 0f || g.vz != 0f) Rot.yawFor(g.vx, g.vz) else g.x * 0.01f
                    batch.add(targetT.getValue(g.style), g.x, g.y, g.z, yaw)
                }
                GoalKind.AIR_TARGET -> batch.add(targetT.getValue(TargetStyle.BALLOON), g.x, g.y - 2f, g.z, g.age * 0.2f)
                GoalKind.PICKUP -> batch.add(rescueeT, g.x, g.y, g.z, time * 0.8f)
                GoalKind.DROPOFF -> batch.add(helipadT, g.x, g.y + 0.15f, g.z, 0f)
                else -> Unit
            }
        }

        // rakety, kouř a plameny
        for (r in combat.rockets) {
            val v = sqrt(r.vx * r.vx + r.vy * r.vy + r.vz * r.vz).coerceAtLeast(1f)
            batch.add(rocketT, r.x, r.y, r.z, Rot.yawFor(r.vx, r.vz), kotlin.math.asin((r.vy / v).coerceIn(-1f, 1f)))
        }
        for (bm in combat.bombs) {
            val v = sqrt(bm.vx * bm.vx + bm.vy * bm.vy + bm.vz * bm.vz).coerceAtLeast(1f)
            batch.add(bombT, bm.x, bm.y, bm.z, Rot.yawFor(bm.vx, bm.vz), kotlin.math.asin((bm.vy / v).coerceIn(-1f, 1f)))
        }
        for (pf in combat.puffs) {
            if (!near(pf.x, pf.z, 2500f)) continue
            val k = pf.age / pf.life
            val sc = if (pf.dark) pf.size * (0.5f + pf.age * 0.9f) * (1f - 0.3f * k) else pf.size * (0.6f + pf.age * 1.8f) * (1f - k)
            batch.add(if (pf.dark) puffDarkT else puffLightT, pf.x, pf.y, pf.z, pf.age, 0f, 0f, sc)
        }
        for (fire in combat.fires) {
            if (!near(fire.x, fire.z, 3000f)) continue
            val fade = 1f - fire.age / Combat.FIRE_LIFE
            val sc = fire.size * 0.55f * fade * (1f + 0.22f * sin(time * 13f + fire.x))
            batch.add(flameT, fire.x, fire.y, fire.z, time * 0.7f, 0f, 0f, sc)
        }
        dynamic.upload(batch)

        decalBatch.clear()
        for (m in combat.scorches) decalBatch.addMatrix(m, noRotation, 0f, 0f, 0f)
        decals.upload(decalBatch)
    }

    // ------------------------------------------------------------------ vykreslení

    private fun setLighting(p: GlProgram) {
        val n = night
        val fog = if (n) nightFog else dayFog
        val l = if (n) moon else sun
        p.set("uSunDir", l[0], l[1], l[2])
        p.set("uFogColor", fog[0], fog[1], fog[2])
        p.set("uFog", fogStart, fogEnd)
        p.set("uNight", if (n) 1f else 0f)
        if (n) {
            p.set("uLightColor", 0.16f, 0.19f, 0.27f)
            p.set("uAmbSky", 0.06f, 0.075f, 0.12f)
            p.set("uAmbGround", 0.025f, 0.03f, 0.045f)
            p.set("uSkyColor", 0.05f, 0.07f, 0.12f)
        } else {
            p.set("uLightColor", 0.78f, 0.75f, 0.69f)
            p.set("uAmbSky", 0.50f, 0.58f, 0.70f)
            p.set("uAmbGround", 0.30f, 0.29f, 0.27f)
            p.set("uSkyColor", 0.62f, 0.76f, 0.90f)
        }
        val f = flight
        if (n && f.phase != FlightPhase.CRASHED) {
            // přistávací světlo míří kousek pod osu letadla
            planeModel(model2)
            v4b[0] = 0f; v4b[1] = -0.5f; v4b[2] = -3f * camScale; v4b[3] = 1f
            Matrix.multiplyMV(v4, 0, model2, 0, v4b, 0)
            p.set("uSpot", v4[0], v4[1], v4[2], 1.8f)
            v4b[0] = 0f; v4b[1] = -0.22f; v4b[2] = -1f; v4b[3] = 0f
            Matrix.multiplyMV(v4, 0, model2, 0, v4b, 0)
            val l2 = sqrt(v4[0] * v4[0] + v4[1] * v4[1] + v4[2] * v4[2])
            p.set("uSpotDir", v4[0] / l2, v4[1] / l2, v4[2] / l2)
        } else {
            p.set("uSpot", 0f, 0f, 0f, 0f)
            p.set("uSpotDir", 0f, -1f, 0f)
        }
    }

    private fun render() {
        val n = night
        val fog = if (n) nightFog else dayFog
        GLES30.glClearColor(fog[0], fog[1], fog[2], 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        Matrix.multiplyMM(viewProj, 0, proj, 0, view, 0)

        // obloha
        System.arraycopy(view, 0, viewRot, 0, 16)
        viewRot[12] = 0f; viewRot[13] = 0f; viewRot[14] = 0f
        Matrix.multiplyMM(skyVP, 0, proj, 0, viewRot, 0)
        Matrix.invertM(invSky, 0, skyVP, 0)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(false)
        skyProg.use()
        skyProg.setMatrix("uInvViewProj", invSky)
        val l = if (n) moon else sun
        skyProg.set("uSunDir", l[0], l[1], l[2])
        skyProg.set("uFogColor", fog[0], fog[1], fog[2])
        if (n) skyProg.set("uZenith", 0.008f, 0.015f, 0.045f) else skyProg.set("uZenith", 0.22f, 0.45f, 0.80f)
        skyProg.set("uNight", if (n) 1f else 0f)
        GLES30.glBindVertexArray(skyVao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(true)

        // svět
        val p = worldProg
        p.use()
        p.setMatrix("uViewProj", viewProj)
        p.setMatrix("uModel", identity)
        p.set("uCamPos", camX, camY, camZ)
        setLighting(p)
        p.set(
            "uRunway",
            -World.RUNWAY_HALF_WIDTH, -World.RUNWAY_HALF_LENGTH,
            World.RUNWAY_HALF_WIDTH, World.RUNWAY_HALF_LENGTH,
        )
        p.set("uRunwayY", world.airportHeight)
        p.set("uCity", World.CITY_X, World.CITY_Z, if (world.hasCity) World.CITY_ROAD_RADIUS else 0f, World.CITY_HEIGHT)
        p.set("uCityGrid", World.BLOCK, World.ROAD, World.CITY_RADIUS - 100f, 0f)
        p.set("uTint", 1f, 1f, 1f, 1f)
        p.set("uTime", time)

        val maxDist = fogEnd + World.CHUNK
        p.set("uMode", Shaders.MODE_TERRAIN)
        chunks.drawTerrain(camX, camZ, fwdX, fwdZ, 1700f, maxDist)

        p.set("uMode", Shaders.MODE_OBJECTS)
        chunks.drawObjects(camX, camZ, fwdX, fwdZ, 2300f, maxDist)
        airport.draw()
        chunks.drawClouds(camX, camZ, fwdX, fwdZ, maxDist)

        p.set("uMode", Shaders.MODE_WATER)
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, camX, World.SEA_LEVEL, camZ)
        p.setMatrix("uModel", model)
        water.draw()

        // odstavená letadla, auta, lidé, ptáci, lodě
        p.set("uMode", Shaders.MODE_PLANE)
        for ((i, entry) in parked.withIndex()) {
            if (world.parkedDestroyed[i]) continue
            val (pa, mesh, rot) = entry
            val gy = world.airportHeight + pa.type.gearHeight
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, pa.x, gy, pa.z)
            Matrix.rotateM(model, 0, deg(pa.yaw), 0f, 1f, 0f)
            p.setMatrix("uModel", model)
            mesh.draw()
            drawRotors(p, rot, 0f)
        }
        p.setMatrix("uModel", identity)
        dynamic.draw()

        val f = flight
        val crashed = f.phase == FlightPhase.CRASHED
        val inCockpit = cockpit && !crashed
        if (!inCockpit && !(viewMode == 2 && !crashed) && (!crashed || crashTime < 0.12f)) {
            planeModel(model)
            p.setMatrix("uModel", model)
            body.draw()
            drawRotors(p, rotors, f.propeller)
        }

        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDepthMask(false)

        // ohořelá zem po výbuších
        p.set("uMode", Shaders.MODE_DECAL)
        p.setMatrix("uModel", identity)
        GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL)
        GLES30.glPolygonOffset(-2f, -4f)
        decals.draw()
        GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL)

        // stín pod letadlem
        val agl = f.altitudeAgl
        if (!crashed && agl < 220f) {
            val gy = world.surfaceHeight(f.x, f.z)
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, f.x, gy + 0.2f, f.z)
            Matrix.rotateM(model, 0, -deg(f.heading), 0f, 1f, 0f)
            p.setMatrix("uModel", model)
            p.set("uMode", Shaders.MODE_SHADOW)
            p.set("uTint", 0f, 0f, 0f, 0.38f * (1f - agl / 220f) * if (n) 0.35f else 1f)
            GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL)
            GLES30.glPolygonOffset(-2f, -4f)
            shadow.draw()
            GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL)
        }

        drawGoals(p)
        for (b in combat.blasts) drawBlast(p, b.x, b.y, b.z, b.age, b.size)

        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)

        if (inCockpit) drawCockpit(p)
        if (weapon != app.letadlo.game.Weapon.NONE && !crashed) drawReticle(p)
        if (!crashed) drawMarkers(p)
        GLES30.glBindVertexArray(0)
    }

    private fun drawRotors(p: GlProgram, list: List<Pair<RotorSpec, GpuMesh>>, turns: Float) {
        for ((spec, mesh) in list) {
            System.arraycopy(model, 0, model2, 0, 16)
            Matrix.translateM(model2, 0, spec.x, spec.y, spec.z)
            when (spec.axis) {
                'y' -> Matrix.rotateM(model2, 0, -90f, 1f, 0f, 0f)
                'x' -> Matrix.rotateM(model2, 0, 90f, 0f, 1f, 0f)
                else -> Unit
            }
            Matrix.rotateM(model2, 0, (turns * 360f * spec.speed) % 360f, 0f, 0f, 1f)
            p.setMatrix("uModel", model2)
            mesh.draw()
        }
    }

    /** Pohled z kabiny: vlastní letadlo a palubní deska ve zvláštním průchodu s blízkou ořezovou rovinou. */
    private fun drawCockpit(p: GlProgram) {
        GLES30.glClear(GLES30.GL_DEPTH_BUFFER_BIT)
        // Vše v souřadnicích letadla: daleko od počátku světa by velká čísla
        // způsobila chvění a problikávání budíků.
        Matrix.setLookAtM(localView, 0, eye[0], eye[1], eye[2], eye[0], eye[1], eye[2] - 1f, 0f, 1f, 0f)
        Matrix.multiplyMM(viewProj, 0, projNear, 0, localView, 0)
        p.setMatrix("uViewProj", viewProj)
        p.set("uCamPos", eye[0], eye[1], eye[2])
        // světlo převedené do souřadnic letadla
        val f = flight
        Matrix.setIdentityM(rot, 0)
        Matrix.rotateM(rot, 0, -deg(f.heading), 0f, 1f, 0f)
        Matrix.rotateM(rot, 0, deg(f.pitch), 1f, 0f, 0f)
        Matrix.rotateM(rot, 0, -deg(f.bank), 0f, 0f, 1f)
        Matrix.transposeM(rotT, 0, rot, 0)
        val l = if (night) moon else sun
        v4b[0] = l[0]; v4b[1] = l[1]; v4b[2] = l[2]; v4b[3] = 0f
        Matrix.multiplyMV(v4, 0, rotT, 0, v4b, 0)
        p.set("uSunDir", v4[0], v4[1], v4[2])
        p.set("uSpot", 0f, 0f, 0f, 0f)
        p.set("uMode", Shaders.MODE_PLANE)
        Matrix.setIdentityM(model, 0)
        p.setMatrix("uModel", model)
        // zevnitř trupu nejsou vidět jeho vnitřní stěny, jen křídla a příď zvenku
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        body.draw()
        drawRotors(p, rotors, f.propeller)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        p.setMatrix("uModel", identity)
        cockpitMesh.draw()
    }

    /** Zářící kruhy k průletu a kruhy na místech přistání. */
    private fun drawGoals(p: GlProgram) {
        p.set("uMode", Shaders.MODE_GLOW)
        val pulse = 1f + 0.04f * sin(time * 5f)
        for (g in missions.goals) {
            if (g.dead) continue
            when (g.kind) {
                GoalKind.RING -> {
                    ringModel(g, if (g.preview) 1f else pulse)
                    if (g.preview) p.set("uTint", 0.4f, 0.9f, 1f, 0.4f) else p.set("uTint", 1f, 0.8f, 0.15f, 0.9f)
                }
                GoalKind.PICKUP, GoalKind.DROPOFF -> {
                    flatRingModel(g.x, g.y + 0.6f, g.z, g.radius * pulse)
                    if (g.kind == GoalKind.PICKUP) p.set("uTint", 0.3f, 1f, 0.4f, 0.85f) else p.set("uTint", 0.35f, 0.65f, 1f, 0.85f)
                }
                else -> continue
            }
            p.setMatrix("uModel", model)
            ringMesh.draw()
        }
    }

    private fun ringModel(g: Goal, scale: Float) {
        val r = g.radius * scale
        Matrix.setIdentityM(model, 0)
        model[0] = -g.nz * r; model[1] = 0f; model[2] = g.nx * r
        model[4] = 0f; model[5] = r; model[6] = 0f
        model[8] = g.nx * r; model[9] = 0f; model[10] = g.nz * r
        model[12] = g.x; model[13] = g.y; model[14] = g.z
    }

    private fun flatRingModel(x: Float, y: Float, z: Float, r: Float) {
        Matrix.setIdentityM(model, 0)
        model[0] = r
        model[5] = 0f; model[6] = -r
        model[9] = r; model[10] = 0f
        model[12] = x; model[13] = y; model[14] = z
    }

    /** Značky cílů – vždy viditelné, stejně velké na obrazovce, barva podle druhu. */
    private fun drawMarkers(p: GlProgram) {
        Matrix.multiplyMM(viewProj, 0, proj, 0, view, 0)
        p.setMatrix("uViewProj", viewProj)
        p.set("uCamPos", camX, camY, camZ)
        p.set("uMode", Shaders.MODE_GLOW)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        for (g in missions.goals) {
            if (g.dead || g.preview) continue
            val my = when (g.kind) {
                GoalKind.RING -> g.y + g.radius * 1.6f
                GoalKind.GROUND_TARGET -> g.y + g.radius * 2f + 14f
                GoalKind.AIR_TARGET -> g.y + 22f
                GoalKind.PICKUP, GoalKind.DROPOFF -> g.y + 22f
                GoalKind.RUNWAY -> g.y + 40f
            }
            val dx = g.x - camX
            val dy = my - camY
            val dz = g.z - camZ
            val d = sqrt(dx * dx + dy * dy + dz * dz)
            if (d < 40f) continue
            val s = d * 0.017f
            Matrix.setIdentityM(model, 0)
            model[0] = view[0] * s; model[1] = view[4] * s; model[2] = view[8] * s
            model[4] = view[1] * s; model[5] = view[5] * s; model[6] = view[9] * s
            model[8] = view[2] * s; model[9] = view[6] * s; model[10] = view[10] * s
            model[12] = g.x; model[13] = my; model[14] = g.z
            p.setMatrix("uModel", model)
            when (g.kind) {
                GoalKind.RING -> p.set("uTint", 1f, 0.82f, 0.2f, 0.85f)
                GoalKind.GROUND_TARGET -> p.set("uTint", 1f, 0.22f, 0.18f, 0.9f)
                GoalKind.AIR_TARGET -> p.set("uTint", 1f, 0.55f, 0.1f, 0.9f)
                GoalKind.PICKUP -> p.set("uTint", 0.3f, 1f, 0.4f, 0.9f)
                GoalKind.DROPOFF -> p.set("uTint", 0.35f, 0.65f, 1f, 0.9f)
                GoalKind.RUNWAY -> p.set("uTint", 1f, 1f, 1f, 0.9f)
            }
            diamondMesh.draw()
        }
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    }

    /** Průhledný zelený zaměřovač v místě, kam míří nos (a kam poletí rakety). */
    private fun drawReticle(p: GlProgram) {
        val f = flight
        Matrix.multiplyMM(viewProj, 0, proj, 0, view, 0)
        p.setMatrix("uViewProj", viewProj)
        p.set("uCamPos", camX, camY, camZ)
        Matrix.setIdentityM(model, 0)
        if (weapon == app.letadlo.game.Weapon.BOMBS) {
            // bombardér: kruh na zemi tam, kam by teď bomba dopadla (velikost = dosah ničení)
            if (!combat.predictBombImpact(f, impact)) return
            val s = Combat.BOMB_BLAST * 0.42f
            model[0] = s
            model[5] = 0f
            model[6] = -s
            model[9] = s
            model[10] = 0f
            model[12] = impact[0]; model[13] = impact[1] + 1f; model[14] = impact[2]
        } else {
            val d = 900f
            val cp = cos(f.pitch)
            val ax = f.x + sin(f.heading) * cp * d
            val ay = f.y + sin(f.pitch) * d
            val az = f.z - cos(f.heading) * cp * d
            val dx = ax - camX
            val dy = ay - camY
            val dz = az - camZ
            val s = sqrt(dx * dx + dy * dy + dz * dz) * 0.028f
            // billboard natočený ke kameře (pravá a horní osa z pohledové matice)
            model[0] = view[0] * s; model[1] = view[4] * s; model[2] = view[8] * s
            model[4] = view[1] * s; model[5] = view[5] * s; model[6] = view[9] * s
            model[8] = view[2] * s; model[9] = view[6] * s; model[10] = view[10] * s
            model[12] = ax; model[13] = ay; model[14] = az
        }
        p.setMatrix("uModel", model)
        p.set("uMode", Shaders.MODE_EFFECT)
        p.set("uTint", 0.35f, 1f, 0.45f, 0.72f)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        reticle.draw()
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    }

    private fun drawBlast(p: GlProgram, bx: Float, by: Float, bz: Float, t: Float, s: Float) {
        p.set("uMode", Shaders.MODE_EFFECT)
        val grow = World.smoothstep(0f, 0.6f, t)
        val fireAlpha = 1f - World.smoothstep(0.4f, 1.8f, t)
        if (fireAlpha > 0.01f) {
            ballAt(p, bx, by + 2f + t * 3f, bz, (4f + 20f * grow) * s)
            p.set("uTint", 1f, 0.5f, 0.12f, fireAlpha * 0.85f)
            ball.draw()
            ballAt(p, bx, by + 2f + t * 3f, bz, (2f + 11f * grow) * s)
            p.set("uTint", 1f, 0.9f, 0.45f, fireAlpha)
            ball.draw()
        }
        for (i in 0 until 6) {
            val st = t - i * 0.3f
            if (st <= 0f) continue
            val a = 0.5f * (1f - World.smoothstep(2.5f, 8f, st))
            if (a <= 0.01f) continue
            val drift = i * 2.3f
            ballAt(p, bx + sin(i * 2.1f) * drift * s, by + 4f + st * 7f + i * 2f, bz + cos(i * 1.7f) * drift * s, (6f + st * 4.5f) * s)
            if (night) p.set("uTint", 0.12f, 0.1f, 0.09f, a) else p.set("uTint", 0.22f, 0.21f, 0.2f, a)
            ball.draw()
        }
    }

    private fun ballAt(p: GlProgram, x: Float, y: Float, z: Float, s: Float) {
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, x, y, z)
        Matrix.scaleM(model, 0, s, s, s)
        p.setMatrix("uModel", model)
    }

    private fun waterQuad(half: Float) = MeshBuilder(4).apply {
        quad(-half, 0f, half, half, 0f, half, half, 0f, -half, -half, 0f, -half, 0.1f, 0.3f, 0.4f)
    }.build()
}
