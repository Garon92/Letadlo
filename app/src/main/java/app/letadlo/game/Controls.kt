package app.letadlo.game

/** Zdroj ovládání: obě hodnoty v rozsahu -1..1. */
interface Controls {
    /** Kladné = zatáčka doprava. */
    val roll: Float

    /** Kladné = nos nahoru. */
    val pitch: Float

    /** Probíhá kalibrace neutrální polohy telefonu. */
    val calibrating: Boolean
}
