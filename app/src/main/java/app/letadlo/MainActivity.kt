package app.letadlo

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.util.TypedValue
import android.view.Gravity
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import app.letadlo.game.AircraftType
import app.letadlo.game.MapType
import java.util.Locale

class MainActivity : Activity() {

    private val chips = ArrayList<TextView>()
    private var selected = AircraftType.SPORT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<View>(R.id.menuContent).applySafeInsets()

        selected = Records.aircraft(this)
        val row = findViewById<LinearLayout>(R.id.aircraftRow)
        val dp = resources.displayMetrics.density
        for (t in AircraftType.entries) {
            val chip = TextView(this).apply {
                text = "${t.emoji} ${t.title}"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                gravity = Gravity.CENTER
                setPadding((14 * dp).toInt(), (8 * dp).toInt(), (14 * dp).toInt(), (8 * dp).toInt())
                setBackgroundResource(R.drawable.chip_bg)
                isClickable = true
                isFocusable = true
                tag = t
                setOnClickListener {
                    selected = t
                    Records.setAircraft(this@MainActivity, t)
                    refreshChips()
                    refreshRecords()
                }
            }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.marginEnd = (8 * dp).toInt()
            row.addView(chip, lp)
            chips.add(chip)
        }
        refreshChips()

        bind(R.id.cardMountains, MapType.MOUNTAINS)
        bind(R.id.cardCity, MapType.CITY)
        bind(R.id.cardCountryside, MapType.COUNTRYSIDE)

        val invert = findViewById<CheckBox>(R.id.invertPitch)
        invert.isChecked = Records.invertPitch(this)
        invert.setOnCheckedChangeListener { _, checked -> Records.setInvertPitch(this, checked) }

        val sensLabel = findViewById<TextView>(R.id.sensitivityLabel)
        val sens = findViewById<SeekBar>(R.id.sensitivity)
        sens.max = 10
        sens.progress = Records.sensitivity(this)
        showSensitivity(sensLabel, sens.progress)
        sens.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                Records.setSensitivity(this@MainActivity, value)
                showSensitivity(sensLabel, value)
            }

            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })

        val sound = findViewById<CheckBox>(R.id.sound)
        sound.isChecked = Records.sound(this)
        sound.setOnCheckedChangeListener { _, checked -> Records.setSound(this, checked) }
    }

    override fun onResume() {
        super.onResume()
        refreshRecords()
    }

    private fun refreshRecords() {
        showRecord(R.id.recordMountains, MapType.MOUNTAINS)
        showRecord(R.id.recordCity, MapType.CITY)
        showRecord(R.id.recordCountryside, MapType.COUNTRYSIDE)
    }

    private fun showSensitivity(label: TextView, value: Int) {
        label.text = String.format(
            Locale.ROOT, "Citlivost náklonu: %d  (plná zatáčka při %.0f°)", value, TiltInput.fullRollDeg(value),
        )
    }

    private fun refreshChips() {
        for (c in chips) {
            val on = c.tag == selected
            c.isSelected = on
            c.setTextColor(if (on) 0xFF0E2238.toInt() else 0xFFFFFFFF.toInt())
        }
    }

    private fun bind(id: Int, map: MapType) {
        findViewById<View>(id).setOnClickListener {
            startActivity(
                Intent(this, GameActivity::class.java)
                    .putExtra(GameActivity.EXTRA_MAP, map.name)
                    .putExtra(GameActivity.EXTRA_AIRCRAFT, selected.name)
            )
        }
    }

    private fun showRecord(id: Int, map: MapType) {
        val best = Records.bestScore(this, map, selected)
        findViewById<TextView>(id).text = if (best <= 0) {
            "${selected.emoji} zatím bez skóre"
        } else {
            String.format(Locale.ROOT, "${selected.emoji} rekord %,d", best).replace(',', ' ')
        }
    }
}
