package app.letadlo.gl

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import app.letadlo.game.Agent
import app.letadlo.game.AircraftMeshes
import app.letadlo.game.AircraftType
import app.letadlo.game.ChunkMesher
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
        fun onMinimap(pixels: IntArray, size: Int, centerX: Float, centerZ: Float, metersPerPixel: Float)
    }

    val world = World(map)
    val flight = FlightModel(world, aircraft)
    private val life = Life(world)
    private val batch = DynamicBatch()

    /** Noc (měsíc, hvězdy, rozsvícená okna) – přepíná se tlačítkem. */
    @Volatile var night = false

    /** Pohled z kabiny místo pohledu zvenku. */
    @Volatile var cockpit = false

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
        val ids = IntArray(1)
        GLES30.glGenVertexArrays(1, ids, 0)
        skyVao = ids[0]

        chunks.onContextCreated()
        chunks.loadNow(flight.x, flight.z, 1)
        lastNanos = System.nanoTime()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        val aspect = width.toFloat() / max(1, height)
        Matrix.perspectiveM(proj, 0, 52f, aspect, 1.5f, fogEnd + 800f)
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
                victims = life.explode(flight.x, flight.y, flight.z, aircraft.blastRadius)
                listener.onImpact()
            }
            crashTime += dt
            if (!crashReported && crashTime > 1.4f) {
                crashReported = true
                listener.onCrash(
                    FlightResult(
                        world.map, aircraft, flight.crashReason ?: CrashReason.TERRAIN,
                        flight.airTime, flight.distance, flight.maxAltitudeAgl, flight.hasTakenOff, victims,
                    )
                )
            }
        }

        updateCamera(dt)
        life.update(dt, camX, camZ)
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
        if (cockpit && !crashed) {
            planeModel(model)
            v4b[0] = eye[0]; v4b[1] = eye[1]; v4b[2] = eye[2]; v4b[3] = 1f
            Matrix.multiplyMV(v4, 0, model, 0, v4b, 0)
            camX = v4[0]; camY = v4[1]; camZ = v4[2]
            v4b[0] = 0f; v4b[1] = 0f; v4b[2] = -1f; v4b[3] = 0f
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
        Matrix.setLookAtM(view, 0, camX, camY, camZ, lx, ly, lz, sin(r) * rightX, cos(r), sin(r) * rightZ)
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
        dynamic.upload(batch)
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
        for ((pa, mesh, rot) in parked) {
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
        if (!inCockpit && (!crashed || crashTime < 0.12f)) {
            planeModel(model)
            p.setMatrix("uModel", model)
            body.draw()
            drawRotors(p, rotors, f.propeller)
        }

        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDepthMask(false)

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

        if (crashed) drawExplosion(p)

        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)

        if (inCockpit) drawCockpit(p)
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

    private fun drawExplosion(p: GlProgram) {
        val f = flight
        p.set("uMode", Shaders.MODE_EFFECT)
        val t = crashTime
        val s = 0.7f + 0.3f * camScale
        val grow = World.smoothstep(0f, 0.6f, t)
        val fireAlpha = 1f - World.smoothstep(0.4f, 1.8f, t)
        if (fireAlpha > 0.01f) {
            ballAt(p, f.x, f.y + 2f + t * 3f, f.z, (4f + 20f * grow) * s)
            p.set("uTint", 1f, 0.5f, 0.12f, fireAlpha * 0.85f)
            ball.draw()
            ballAt(p, f.x, f.y + 2f + t * 3f, f.z, (2f + 11f * grow) * s)
            p.set("uTint", 1f, 0.9f, 0.45f, fireAlpha)
            ball.draw()
        }
        for (i in 0 until 6) {
            val st = t - i * 0.3f
            if (st <= 0f) continue
            val a = 0.5f * (1f - World.smoothstep(2.5f, 8f, st))
            if (a <= 0.01f) continue
            val drift = i * 2.3f
            ballAt(p, f.x + sin(i * 2.1f) * drift, f.y + 4f + st * 7f + i * 2f, f.z + cos(i * 1.7f) * drift, (6f + st * 4.5f) * s)
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
