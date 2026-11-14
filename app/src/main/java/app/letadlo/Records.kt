package app.letadlo

import android.content.Context
import app.letadlo.game.AircraftType
import app.letadlo.game.MapType

/** Rekordy (nejdelší let) a nastavení uložené v telefonu. */
object Records {
    private const val PREFS = "letadlo"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun bestTime(c: Context, map: MapType): Float = prefs(c).getFloat("best_time_${map.name}", 0f)

    fun bestDistance(c: Context, map: MapType): Float = prefs(c).getFloat("best_dist_${map.name}", 0f)

    /** Uloží výsledek; vrátí true, pokud jde o nový rekord v délce letu. */
    fun submit(c: Context, map: MapType, airTime: Float, distance: Float): Boolean {
        if (airTime < 1f) return false
        val p = prefs(c)
        val e = p.edit()
        var record = false
        if (airTime > bestTime(c, map)) {
            e.putFloat("best_time_${map.name}", airTime)
            record = true
        }
        if (distance > bestDistance(c, map)) e.putFloat("best_dist_${map.name}", distance)
        e.apply()
        return record
    }

    fun sound(c: Context): Boolean = prefs(c).getBoolean("sound", true)

    fun setSound(c: Context, v: Boolean) {
        prefs(c).edit().putBoolean("sound", v).apply()
    }

    fun bestScore(c: Context, map: MapType, a: AircraftType): Int = prefs(c).getInt("score_${map.name}_${a.name}", 0)

    /** Uloží skóre; vrátí true, pokud je to nový rekord pro mapu a letadlo. */
    fun submitScore(c: Context, map: MapType, a: AircraftType, score: Int): Boolean {
        if (score <= bestScore(c, map, a)) return false
        prefs(c).edit().putInt("score_${map.name}_${a.name}", score).apply()
        return true
    }

    fun aircraft(c: Context): AircraftType = AircraftType.fromName(prefs(c).getString("aircraft", null))

    fun setAircraft(c: Context, t: AircraftType) {
        prefs(c).edit().putString("aircraft", t.name).apply()
    }

    fun cockpit(c: Context): Boolean = prefs(c).getBoolean("cockpit", false)

    fun setCockpit(c: Context, v: Boolean) {
        prefs(c).edit().putBoolean("cockpit", v).apply()
    }

    fun sensitivity(c: Context): Int = prefs(c).getInt("sensitivity", TiltInput.DEFAULT_SENSITIVITY)

    fun setSensitivity(c: Context, v: Int) {
        prefs(c).edit().putInt("sensitivity", v).apply()
    }

    fun invertPitch(c: Context): Boolean = prefs(c).getBoolean("invert_pitch", false)

    fun setInvertPitch(c: Context, v: Boolean) {
        prefs(c).edit().putBoolean("invert_pitch", v).apply()
    }
}
