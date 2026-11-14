package app.letadlo.gl

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import app.letadlo.game.ChunkMesher
import app.letadlo.game.Controls
import app.letadlo.game.CrashReason
import app.letadlo.game.FlightModel
import app.letadlo.game.FlightPhase
import app.letadlo.game.MapType
import app.letadlo.game.MeshBuilder
import app.letadlo.game.PlaneMesh
import app.letadlo.game.World
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
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
    val canRotate: Boolean,
    val calibrating: Boolean,
    val bankDeg: Float,
    val pitchDeg: Float,
)

class FlightResult(
    val map: MapType,
    val reason: CrashReason,
    val airTime: Float,
    val distance: Float,
    val maxAgl: Float,
    val tookOff: Boolean,
)

class GameRenderer(
    map: MapType,
    private val controls: Controls,
    private val listener: Listener,
) : GLSurfaceView.Renderer {

    interface Listener {
        fun onHud(state: HudState)
        fun onCrash(result: FlightResult)
        fun onImpact()
    }

    val world = World(map)
    val flight = FlightModel(world)

    private val radius = if (map == MapType.MOUNTAINS) 7 else 6
    private val fogEnd = (radius - 0.8f) * World.CHUNK
    private val fogStart = fogEnd * 0.28f
    private val chunks = ChunkManager(world, radius)
    private val fog = if (map == MapType.MOUNTAINS) floatArrayOf(0.72f, 0.81f, 0.91f) else floatArrayOf(0.76f, 0.85f, 0.93f)
    private val sun = run {
        val x = -0.42f; val y = 0.74f; val z = 0.52f
        val l = sqrt(x * x + y * y + z * z)
        floatArrayOf(x / l, y / l, z / l)
    }

    private lateinit var worldProg: GlProgram
    private lateinit var skyProg: GlProgram
    private lateinit var planeBody: GpuMesh
    private lateinit var propeller: GpuMesh
    private lateinit var shadow: GpuMesh
    private lateinit var ball: GpuMesh
    private lateinit var airport: GpuMesh
    private lateinit var water: GpuMesh
    private var skyVao = 0

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val viewProj = FloatArray(16)
    private val viewRot = FloatArray(16)
    private val skyVP = FloatArray(16)
    private val invSky = FloatArray(16)
    private val model = FloatArray(16)
    private val model2 = FloatArray(16)
    private val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    private var camX = 0f
    private var camY = 0f
    private var camZ = 0f
    private var camInit = false
    private var fwdX = 0f
    private var fwdZ = -1f

    private var lastNanos = 0L
    private var time = 0f
    private var hudTimer = 0f
    private var crashTime = 0f
    private var crashReported = false
    private var impactReported = false

    /** Volat na GL vlákně (přes queueEvent). */
    fun restart() {
        flight.reset()
        crashTime = 0f
        crashReported = false
        impactReported = false
        camInit = false
    }

    fun release() {
        chunks.shutdown()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(fog[0], fog[1], fog[2], 1f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        GLES30.glDisable(GLES30.GL_CULL_FACE)

        worldProg = GlProgram(Shaders.WORLD_VS, Shaders.WORLD_FS)
        skyProg = GlProgram(Shaders.SKY_VS, Shaders.SKY_FS)
        planeBody = GpuMesh(PlaneMesh.body())
        propeller = GpuMesh(PlaneMesh.propeller())
        shadow = GpuMesh(PlaneMesh.shadow())
        ball = GpuMesh(PlaneMesh.ball())
        airport = GpuMesh(ChunkMesher.buildings(world.airportBoxes))
        water = GpuMesh(waterQuad(fogEnd + 1500f))
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
                listener.onImpact()
            }
            crashTime += dt
            if (!crashReported && crashTime > 1.1f) {
                crashReported = true
                listener.onCrash(
                    FlightResult(
                        world.map, flight.crashReason ?: CrashReason.TERRAIN,
                        flight.airTime, flight.distance, flight.maxAltitudeAgl, flight.hasTakenOff,
                    )
                )
            }
        }

        updateCamera(dt)
        chunks.update(camX, camZ)
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
                    canRotate = flight.speed >= FlightModel.ROTATE_SPEED,
                    calibrating = controls.calibrating,
                    bankDeg = deg(flight.bank),
                    pitchDeg = deg(flight.pitch),
                )
            )
        }
    }

    private fun deg(r: Float): Float = (r * 180.0 / PI).toFloat()

    private fun updateCamera(dt: Float) {
        val f = flight
        val hx = sin(f.heading)
        val hz = -cos(f.heading)
        val crashed = f.phase == FlightPhase.CRASHED
        val tx: Float
        val ty: Float
        val tz: Float
        val lx: Float
        val ly: Float
        val lz: Float
        if (crashed) {
            val ang = f.heading + PI.toFloat() + crashTime * 0.22f
            val dist = 42f + min(crashTime, 4f) * 7f
            tx = f.x + sin(ang) * dist
            tz = f.z - cos(ang) * dist
            ty = f.y + 14f + min(crashTime, 4f) * 5f
            lx = f.x; ly = f.y + 4f; lz = f.z
        } else {
            val cp = cos(f.pitch * 0.6f)
            val sp = sin(f.pitch * 0.6f)
            val back = 19f
            tx = f.x - hx * back * cp
            tz = f.z - hz * back * cp
            ty = f.y + 5.5f - sp * back
            val ahead = 30f
            lx = f.x + hx * ahead * cos(f.pitch)
            ly = f.y + 2f + sin(f.pitch) * ahead
            lz = f.z + hz * ahead * cos(f.pitch)
        }
        if (!camInit) {
            camX = tx; camY = ty; camZ = tz
            camInit = true
        }
        val k = 1f - exp(-dt * if (crashed) 1.5f else 5.5f)
        camX += (tx - camX) * k
        camY += (ty - camY) * k
        camZ += (tz - camZ) * k
        val minY = world.surfaceHeight(camX, camZ) + 2.5f
        if (camY < minY) camY = minY

        val dx = lx - camX
        val dz = lz - camZ
        val dl = sqrt(dx * dx + dz * dz)
        if (dl > 0.01f) {
            fwdX = dx / dl
            fwdZ = dz / dl
        }
        // kamera se částečně naklání s letadlem
        val r = if (crashed) 0f else f.bank * 0.4f
        val rightX = -fwdZ
        val rightZ = fwdX
        val upX = sin(r) * rightX
        val upY = cos(r)
        val upZ = sin(r) * rightZ
        Matrix.setLookAtM(view, 0, camX, camY, camZ, lx, ly, lz, upX, upY, upZ)
    }

    private fun render() {
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
        skyProg.set("uSunDir", sun[0], sun[1], sun[2])
        skyProg.set("uFogColor", fog[0], fog[1], fog[2])
        GLES30.glBindVertexArray(skyVao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(true)

        // svět
        val p = worldProg
        p.use()
        p.setMatrix("uViewProj", viewProj)
        p.setMatrix("uModel", identity)
        p.set("uSunDir", sun[0], sun[1], sun[2])
        p.set("uCamPos", camX, camY, camZ)
        p.set("uFogColor", fog[0], fog[1], fog[2])
        p.set("uFog", fogStart, fogEnd)
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

        val f = flight
        val crashed = f.phase == FlightPhase.CRASHED
        if (!crashed || crashTime < 0.12f) {
            p.set("uMode", Shaders.MODE_PLANE)
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, f.x, f.y, f.z)
            Matrix.rotateM(model, 0, -deg(f.heading), 0f, 1f, 0f)
            Matrix.rotateM(model, 0, deg(f.pitch), 1f, 0f, 0f)
            Matrix.rotateM(model, 0, -deg(f.bank), 0f, 0f, 1f)
            p.setMatrix("uModel", model)
            planeBody.draw()
            System.arraycopy(model, 0, model2, 0, 16)
            Matrix.translateM(model2, 0, 0f, 0.05f, -3.72f)
            Matrix.rotateM(model2, 0, (f.propeller * 360f) % 360f, 0f, 0f, 1f)
            p.setMatrix("uModel", model2)
            propeller.draw()
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
            p.set("uTint", 0f, 0f, 0f, 0.38f * (1f - agl / 220f))
            GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL)
            GLES30.glPolygonOffset(-2f, -4f)
            shadow.draw()
            GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL)
        }

        if (crashed) drawExplosion(p)

        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glBindVertexArray(0)
    }

    private fun drawExplosion(p: GlProgram) {
        val f = flight
        p.set("uMode", Shaders.MODE_EFFECT)
        // ohnivá koule
        val t = crashTime
        val grow = World.smoothstep(0f, 0.6f, t)
        val fireAlpha = 1f - World.smoothstep(0.4f, 1.8f, t)
        if (fireAlpha > 0.01f) {
            ballAt(p, f.x, f.y + 2f + t * 3f, f.z, 4f + 20f * grow)
            p.set("uTint", 1f, 0.5f, 0.12f, fireAlpha * 0.85f)
            ball.draw()
            ballAt(p, f.x, f.y + 2f + t * 3f, f.z, 2f + 11f * grow)
            p.set("uTint", 1f, 0.9f, 0.45f, fireAlpha)
            ball.draw()
        }
        // kouř
        for (i in 0 until 6) {
            val st = t - i * 0.3f
            if (st <= 0f) continue
            val a = 0.5f * (1f - World.smoothstep(2.5f, 8f, st))
            if (a <= 0.01f) continue
            val drift = i * 2.3f
            ballAt(p, f.x + sin(i * 2.1f) * drift, f.y + 4f + st * 7f + i * 2f, f.z + cos(i * 1.7f) * drift, 6f + st * 4.5f)
            p.set("uTint", 0.22f, 0.21f, 0.2f, a)
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
