package app.letadlo

import android.app.Activity
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import app.letadlo.game.AircraftType
import app.letadlo.game.FlightPhase
import app.letadlo.game.MapType
import app.letadlo.gl.FlightResult
import app.letadlo.gl.GameRenderer
import app.letadlo.gl.HudState
import java.util.Locale
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay

class GameActivity : Activity(), GameRenderer.Listener {

    companion object {
        const val EXTRA_MAP = "map"
        const val EXTRA_AIRCRAFT = "aircraft"
    }

    private lateinit var map: MapType
    private lateinit var aircraft: AircraftType
    private lateinit var btnView: TextView
    private lateinit var btnTime: TextView
    private lateinit var miniMap: MiniMapView
    private lateinit var hudScore: TextView
    private lateinit var hudCombo: ProgressBar
    private lateinit var hudObjective: TextView
    private lateinit var hudLow: TextView
    private lateinit var hudPopup: TextView
    private val recentPopups = ArrayList<String>()
    private var steamBar: ProgressBar? = null
    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: GameRenderer
    private lateinit var tilt: TiltInput
    private var sound: EngineSound? = null

    private lateinit var hudSpeed: TextView
    private lateinit var hudAlt: TextView
    private lateinit var hudTime: TextView
    private lateinit var hint: TextView
    private lateinit var attitude: AttitudeView
    private lateinit var crashPanel: LinearLayout
    private lateinit var crashReason: TextView
    private lateinit var crashStats: TextView
    private lateinit var crashRecord: TextView

    private var calibratedMessageUntil = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        map = MapType.fromName(intent.getStringExtra(EXTRA_MAP))
        aircraft = AircraftType.fromName(intent.getStringExtra(EXTRA_AIRCRAFT))
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_game)
        findViewById<View>(R.id.hudLayer).applySafeInsets()
        hideSystemBars()

        tilt = TiltInput(this)
        tilt.invertPitch = Records.invertPitch(this)
        tilt.sensitivity = Records.sensitivity(this)
        if (Records.sound(this)) sound = EngineSound(aircraft)
        if (!tilt.available) {
            Toast.makeText(this, "Tento telefon nemá senzor náklonu.", Toast.LENGTH_LONG).show()
        }

        hudSpeed = findViewById(R.id.hudSpeed)
        hudAlt = findViewById(R.id.hudAlt)
        hudTime = findViewById(R.id.hudTime)
        hint = findViewById(R.id.hint)
        attitude = findViewById(R.id.attitude)
        miniMap = findViewById(R.id.miniMap)
        crashPanel = findViewById(R.id.crashPanel)
        crashReason = findViewById(R.id.crashReason)
        crashStats = findViewById(R.id.crashStats)
        crashRecord = findViewById(R.id.crashRecord)
        findViewById<TextView>(R.id.hudMap).text = "${map.title} · ${aircraft.title}"

        renderer = GameRenderer(map, aircraft, tilt, this)
        renderer.viewMode = if (Records.cockpit(this)) 1 else 0
        btnView = findViewById(R.id.btnView)
        btnTime = findViewById(R.id.btnTime)
        updateToggleIcons()
        btnView.setOnClickListener {
            // zvenku → z kabiny → (bombardér) pohled dolů → zvenku
            val modes = if (aircraft == AircraftType.BOMBER) 3 else 2
            renderer.viewMode = (renderer.viewMode + 1) % modes
            Records.setCockpit(this, renderer.viewMode == 1)
            updateToggleIcons()
        }
        hudScore = findViewById(R.id.hudScore)
        hudCombo = findViewById(R.id.hudCombo)
        hudObjective = findViewById(R.id.hudObjective)
        hudLow = findViewById(R.id.hudLow)
        hudPopup = findViewById(R.id.hudPopup)
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        val btnFire = findViewById<ImageView>(R.id.btnFire)
        if (aircraft.airship) {
            // vzducholoď: drž páru = zrychlení
            findViewById<View>(R.id.steamBox).visibility = View.VISIBLE
            steamBar = findViewById(R.id.steamBar)
            findViewById<View>(R.id.btnSteam).setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        renderer.flight.boostInput = true
                        v.isPressed = true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        renderer.flight.boostInput = false
                        v.isPressed = false
                        v.performClick()
                    }
                }
                true
            }
        }
        if (aircraft == AircraftType.FIGHTER || aircraft == AircraftType.BOMBER || aircraft.airship) {
            btnFire.visibility = View.VISIBLE
            if (aircraft == AircraftType.BOMBER) {
                btnFire.setImageResource(R.drawable.ic_bomb)
                btnFire.contentDescription = "Shodit bombu"
            }
            if (aircraft.airship) {
                btnFire.setImageResource(R.drawable.ic_cannon)
                btnFire.contentDescription = "Vystřelit z děla"
            }
            // drž = střílí bez omezení
            btnFire.setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        renderer.combat.trigger = true
                        v.isPressed = true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        renderer.combat.trigger = false
                        v.isPressed = false
                        v.performClick()
                    }
                }
                true
            }
        }
        btnTime.setOnClickListener {
            renderer.night = !renderer.night
            updateToggleIcons()
        }
        glView = findViewById(R.id.glView)
        glView.setEGLContextClientVersion(3)
        glView.setEGLConfigChooser(DepthConfigChooser())
        glView.preserveEGLContextOnPause = true
        glView.setRenderer(renderer)
        glView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        glView.setOnTouchListener { v, e ->
            if (e.action == MotionEvent.ACTION_DOWN && crashPanel.visibility != View.VISIBLE) {
                tilt.calibrate()
                calibratedMessageUntil = System.currentTimeMillis() + 1800
                v.performClick()
            }
            true
        }

        findViewById<Button>(R.id.btnAgain).setOnClickListener {
            crashPanel.visibility = View.GONE
            tilt.calibrate()
            sound?.running = true
            glView.queueEvent { renderer.restart() }
        }
        findViewById<Button>(R.id.btnMenu).setOnClickListener { finish() }
    }

    /** Ikona ukazuje, co je právě zapnuté: ✈️ zvenku / 💺 z kabiny, ☀️ den / 🌙 noc. */
    private fun updateToggleIcons() {
        btnView.text = when (renderer.viewMode) {
            1 -> "💺"
            2 -> "🎯"
            else -> "✈️"
        }
        btnTime.text = if (renderer.night) "🌙" else "☀️"
    }

    override fun onResume() {
        super.onResume()
        glView.onResume()
        tilt.start()
        sound?.start()
        hideSystemBars()
    }

    override fun onPause() {
        tilt.stop()
        sound?.stop()
        glView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        renderer.release()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        }
    }

    // ---------------------------------------------------------------- HUD

    override fun onHud(state: HudState) {
        runOnUiThread { showHud(state) }
    }

    private fun showHud(s: HudState) {
        sound?.let {
            it.speed = s.speedKmh / 3.6f
            if (s.phase != FlightPhase.CRASHED && crashPanel.visibility != View.VISIBLE) it.running = true
        }
        hudSpeed.text = String.format(Locale.ROOT, "%d km/h", s.speedKmh)
        hudAlt.text = String.format(Locale.ROOT, "výška %d m · nad zemí %d m", s.altitude, s.agl)
        hudTime.text = "let " + formatTime(s.airTime)
        attitude.set(s.bankDeg, s.pitchDeg)
        miniMap.setPose(s.x, s.z, s.headingDeg)
        miniMap.setMarkers(s.markers)
        hudScore.text = if (s.multiplier > 1) String.format(Locale.ROOT, "%,d  ×%d", s.score, s.multiplier).replace(',', ' ')
        else String.format(Locale.ROOT, "%,d", s.score).replace(',', ' ')
        hudCombo.visibility = if (s.multiplier > 1) View.VISIBLE else View.INVISIBLE
        hudCombo.progress = (s.comboFraction * 100f).toInt()
        hudObjective.text = s.objective
        hudLow.visibility = if (s.lowFlying) View.VISIBLE else View.INVISIBLE
        if (s.popups.isNotEmpty()) showPopups(s.popups)
        steamBar?.progress = (s.steam * 100f).toInt()
        sound?.boosting = s.boosting

        val text: String? = when {
            s.phase == FlightPhase.CRASHED -> null
            System.currentTimeMillis() < calibratedMessageUntil ->
                if (s.calibrating) "Kalibruji… drž telefon v klidu" else "Zkalibrováno ✓"
            s.calibrating -> "Drž telefon v pohodlné poloze – kalibruji…"
            s.phase == FlightPhase.ROLLING && s.readyForTakeoff && s.hasTakenOff -> "Přistání! Nakloň telefon k sobě a leť dál."
            s.phase == FlightPhase.ROLLING && s.readyForTakeoff -> "Teď! Nakloň horní hranu telefonu k sobě ⤴"
            s.phase == FlightPhase.ROLLING && aircraft.helicopter -> "Roztáčím rotor…"
            s.phase == FlightPhase.ROLLING && aircraft.airship -> "Zatápím v kotli…"
            s.phase == FlightPhase.ROLLING && !s.hasTakenOff ->
                String.format(Locale.ROOT, "Rozjezd… vzlet při %d km/h", (aircraft.rotateSpeed * 3.6f).toInt())
            s.phase == FlightPhase.AIRBORNE && s.airTime < 6f ->
                when {
                    aircraft.airship -> "Pluješ! K sobě = stoupání. Sbírej kola, střílej piráty."
                    aircraft.helicopter -> "Letíš! K sobě = stoupání, od sebe = klesání."
                    else -> "Letíš! Natáčením telefonu zatáčíš."
                }
            else -> null
        }
        if (text == null) {
            hint.visibility = View.GONE
        } else {
            hint.text = text
            hint.visibility = View.VISIBLE
        }
    }

    /** Vyskakující body – poslední dvě hlášky, po chvíli zmizí. */
    private fun showPopups(list: List<String>) {
        recentPopups.addAll(list)
        while (recentPopups.size > 2) recentPopups.removeAt(0)
        hudPopup.text = recentPopups.joinToString("\n")
        hudPopup.animate().cancel()
        hudPopup.alpha = 1f
        hudPopup.scaleX = 1.15f
        hudPopup.scaleY = 1.15f
        hudPopup.animate().scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(180).withEndAction {
            hudPopup.animate().alpha(0f).setStartDelay(1400).setDuration(500).withEndAction { recentPopups.clear() }
        }
    }

    override fun onCrash(result: FlightResult) {
        runOnUiThread { showCrash(result) }
    }

    override fun onMinimap(pixels: IntArray, size: Int, centerX: Float, centerZ: Float, metersPerPixel: Float) {
        runOnUiThread { miniMap.setImage(pixels, size, centerX, centerZ, metersPerPixel) }
    }

    override fun onWeapons(launches: Int, booms: Int) {
        sound?.let {
            repeat(launches) { _ ->
                when {
                    aircraft == AircraftType.BOMBER -> it.whistle()
                    aircraft.airship -> it.boom()
                    else -> it.launch()
                }
            }
            repeat(booms) { _ -> it.boom() }
        }
    }

    override fun onImpact() {
        sound?.explode()
    }

    private fun showCrash(r: FlightResult) {
        Records.submit(this, r.map, r.airTime, r.distance)
        val newRecord = Records.submitScore(this, r.map, r.aircraft, r.score)
        crashReason.text = r.reason.text
        val scoreText = String.format(Locale.ROOT, "Skóre %,d", r.score).replace(',', ' ')
        crashStats.text = if (r.tookOff) {
            val details = if (r.summary.isNotEmpty()) "\n${r.summary}" else ""
            String.format(
                Locale.ROOT,
                "%s%s\nlet %s  ·  %.1f km  ·  max. %d m nad zemí",
                scoreText, details, formatTime(r.airTime), r.distance / 1000f, r.maxAgl.toInt(),
            )
        } else {
            "Letadlo se ani neodlepilo od země."
        }
        val best = Records.bestScore(this, r.map, r.aircraft)
        crashRecord.text = when {
            newRecord && r.score > 0 -> "🏆 Nový rekord: ${r.aircraft.title} na mapě ${r.map.title}!"
            best > 0 -> String.format(Locale.ROOT, "Rekord %s / %s: %,d", r.aircraft.title, r.map.title, best).replace(',', ' ')
            else -> ""
        }
        crashRecord.visibility = if (crashRecord.text.isEmpty()) View.GONE else View.VISIBLE
        hint.visibility = View.GONE
        crashPanel.visibility = View.VISIBLE
    }

    private fun formatTime(seconds: Float): String {
        val s = seconds.toInt()
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
    }

    /** Vybere konfiguraci s 24bitovou hloubkou (kvůli velkým vzdálenostem), jinak cokoliv rozumného. */
    private class DepthConfigChooser : GLSurfaceView.EGLConfigChooser {
        override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
            val attempts = arrayOf(
                intArrayOf(8, 8, 8, 8, 24),
                intArrayOf(8, 8, 8, 0, 24),
                intArrayOf(8, 8, 8, 8, 16),
                intArrayOf(5, 6, 5, 0, 16),
            )
            for (a in attempts) {
                val spec = intArrayOf(
                    EGL10.EGL_RED_SIZE, a[0],
                    EGL10.EGL_GREEN_SIZE, a[1],
                    EGL10.EGL_BLUE_SIZE, a[2],
                    EGL10.EGL_ALPHA_SIZE, a[3],
                    EGL10.EGL_DEPTH_SIZE, a[4],
                    EGL10.EGL_RENDERABLE_TYPE, 0x40, // EGL_OPENGL_ES3_BIT_KHR
                    EGL10.EGL_NONE,
                )
                val count = IntArray(1)
                if (!egl.eglChooseConfig(display, spec, null, 0, count) || count[0] <= 0) continue
                val configs = arrayOfNulls<EGLConfig>(count[0])
                if (!egl.eglChooseConfig(display, spec, configs, count[0], count)) continue
                for (c in configs) {
                    if (c == null) continue
                    val v = IntArray(1)
                    fun attr(name: Int): Int {
                        return if (egl.eglGetConfigAttrib(display, c, name, v)) v[0] else 0
                    }
                    if (attr(EGL10.EGL_RED_SIZE) == a[0] && attr(EGL10.EGL_GREEN_SIZE) == a[1] &&
                        attr(EGL10.EGL_BLUE_SIZE) == a[2] && attr(EGL10.EGL_DEPTH_SIZE) >= a[4]
                    ) return c
                }
                configs.firstOrNull { it != null }?.let { return it }
            }
            throw IllegalStateException("Nenalezena vhodná EGL konfigurace")
        }
    }
}
