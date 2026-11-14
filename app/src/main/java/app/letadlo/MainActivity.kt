package app.letadlo

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.CheckBox
import android.widget.TextView
import app.letadlo.game.MapType
import java.util.Locale

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bind(R.id.cardMountains, MapType.MOUNTAINS)
        bind(R.id.cardCity, MapType.CITY)
        bind(R.id.cardCountryside, MapType.COUNTRYSIDE)

        val invert = findViewById<CheckBox>(R.id.invertPitch)
        invert.isChecked = Records.invertPitch(this)
        invert.setOnCheckedChangeListener { _, checked -> Records.setInvertPitch(this, checked) }
    }

    override fun onResume() {
        super.onResume()
        showRecord(R.id.recordMountains, MapType.MOUNTAINS)
        showRecord(R.id.recordCity, MapType.CITY)
        showRecord(R.id.recordCountryside, MapType.COUNTRYSIDE)
    }

    private fun bind(id: Int, map: MapType) {
        findViewById<View>(id).setOnClickListener {
            startActivity(Intent(this, GameActivity::class.java).putExtra(GameActivity.EXTRA_MAP, map.name))
        }
    }

    private fun showRecord(id: Int, map: MapType) {
        val t = Records.bestTime(this, map)
        val d = Records.bestDistance(this, map)
        findViewById<TextView>(id).text = if (t <= 0f) {
            "zatím bez letu"
        } else {
            val s = t.toInt()
            String.format(Locale.ROOT, "rekord %d:%02d · %.1f km", s / 60, s % 60, d / 1000f)
        }
    }
}
