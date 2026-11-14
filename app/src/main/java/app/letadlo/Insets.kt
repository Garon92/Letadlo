package app.letadlo

import android.os.Build
import android.view.View
import android.view.WindowInsets
import kotlin.math.max

/**
 * Odsadí obsah view od výřezu kamery a systémových lišt
 * (Android 15 s targetSdk 35 kreslí aplikaci až k okrajům displeje).
 */
fun View.applySafeInsets() {
    val baseL = paddingLeft
    val baseT = paddingTop
    val baseR = paddingRight
    val baseB = paddingBottom
    setOnApplyWindowInsetsListener { v, insets ->
        val s = safeInsets(insets)
        v.setPadding(baseL + s[0], baseT + s[1], baseR + s[2], baseB + s[3])
        insets
    }
    requestApplyInsets()
}

@Suppress("DEPRECATION")
private fun safeInsets(insets: WindowInsets): IntArray {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        return intArrayOf(i.left, i.top, i.right, i.bottom)
    }
    var l = insets.systemWindowInsetLeft
    var t = insets.systemWindowInsetTop
    var r = insets.systemWindowInsetRight
    var b = insets.systemWindowInsetBottom
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        insets.displayCutout?.let {
            l = max(l, it.safeInsetLeft)
            t = max(t, it.safeInsetTop)
            r = max(r, it.safeInsetRight)
            b = max(b, it.safeInsetBottom)
        }
    }
    return intArrayOf(l, t, r, b)
}
