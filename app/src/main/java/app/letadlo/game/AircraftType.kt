package app.letadlo.game

/**
 * Druhy letadel a jejich letové vlastnosti. Tah a odpor jsou nastavené tak,
 * aby cestovní rychlosti odpovídaly skutečnosti a stoupání naplno šlo udržet
 * s rezervou nad pádovou rychlostí.
 */
enum class AircraftType(
    val title: String,
    val emoji: String,
    val helicopter: Boolean,
    /** Rychlost, při které jde letadlo zvednout z dráhy (m/s). */
    val rotateSpeed: Float,
    val stallSpeed: Float,
    val groundThrust: Float,
    /** Tah ve vzduchu (m/s²). Ustálená vodorovná rychlost = sqrt(thrust / drag). */
    val thrust: Float,
    val drag: Float,
    val minSpeed: Float,
    val maxSpeed: Float,
    /** Výška středu letadla nad zemí, když stojí na podvozku. */
    val gearHeight: Float,
    /** Poloměr kolizní koule. */
    val radius: Float,
    val maxBankDeg: Float,
    val maxPitchUpDeg: Float,
    val maxPitchDownDeg: Float,
    /** Jak rychle se křídla naklopí do požadovaného náklonu (1/s). */
    val bankRate: Float,
    val pitchRate: Float,
    /** Rychlost otáčení kurzu při plném náklonu (°/s). */
    val maxTurnDeg: Float,
    val ceiling: Float,
    /** Jak daleko výbuch rozhází auta a lidi. */
    val blastRadius: Float,
    /** Výška zvuku motoru (násobek). */
    val engineTone: Float,
) {
    SPORT(
        "Sportovní", "🛩️", false,
        rotateSpeed = 36f, stallSpeed = 28f, groundThrust = 6.5f, thrust = 9f, drag = 0.0013f,
        minSpeed = 18f, maxSpeed = 120f, gearHeight = 1.6f, radius = 3f,
        maxBankDeg = 65f, maxPitchUpDeg = 35f, maxPitchDownDeg = 40f,
        bankRate = 4f, pitchRate = 3.2f, maxTurnDeg = 25f, ceiling = 3500f,
        blastRadius = 70f, engineTone = 1f,
    ),
    FIGHTER(
        "Stíhačka", "⚡", false,
        rotateSpeed = 80f, stallSpeed = 62f, groundThrust = 18f, thrust = 30f, drag = 0.0000804f,
        minSpeed = 50f, maxSpeed = 720f, gearHeight = 2.15f, radius = 4f,
        maxBankDeg = 85f, maxPitchUpDeg = 50f, maxPitchDownDeg = 50f,
        bankRate = 6f, pitchRate = 4.5f, maxTurnDeg = 30f, ceiling = 4500f,
        blastRadius = 95f, engineTone = 2.2f,
    ),
    AIRLINER(
        "Dopravní", "✈️", false,
        rotateSpeed = 75f, stallSpeed = 60f, groundThrust = 4.4f, thrust = 4.5f, drag = 0.0000808f,
        minSpeed = 45f, maxSpeed = 280f, gearHeight = 3.45f, radius = 7f,
        maxBankDeg = 35f, maxPitchUpDeg = 20f, maxPitchDownDeg = 22f,
        bankRate = 2f, pitchRate = 1.6f, maxTurnDeg = 9f, ceiling = 4000f,
        blastRadius = 150f, engineTone = 1.7f,
    ),
    HELICOPTER(
        "Vrtulník", "🚁", true,
        rotateSpeed = 0f, stallSpeed = 0f, groundThrust = 0f, thrust = 0f, drag = 0f,
        minSpeed = 0f, maxSpeed = 75f, gearHeight = 1.75f, radius = 4.5f,
        maxBankDeg = 35f, maxPitchUpDeg = 0f, maxPitchDownDeg = 0f,
        bankRate = 4f, pitchRate = 3f, maxTurnDeg = 40f, ceiling = 3000f,
        blastRadius = 70f, engineTone = 0.75f,
    ),
    BOMBER(
        "Bombardér", "💣", false,
        rotateSpeed = 50f, stallSpeed = 40f, groundThrust = 5f, thrust = 6.5f, drag = 0.000367f,
        minSpeed = 32f, maxSpeed = 170f, gearHeight = 3.0f, radius = 7f,
        maxBankDeg = 45f, maxPitchUpDeg = 25f, maxPitchDownDeg = 28f,
        bankRate = 2.4f, pitchRate = 2f, maxTurnDeg = 12f, ceiling = 4000f,
        blastRadius = 170f, engineTone = 0.85f,
    ),
    AIRSHIP(
        "Vzducholoď", "⚙️", false,
        rotateSpeed = 0f, stallSpeed = 0f, groundThrust = 0f, thrust = 0f, drag = 0f,
        minSpeed = 0f, maxSpeed = 35f, gearHeight = 13.2f, radius = 9f,
        maxBankDeg = 8f, maxPitchUpDeg = 0f, maxPitchDownDeg = 0f,
        bankRate = 1.5f, pitchRate = 1f, maxTurnDeg = 16f, ceiling = 3000f,
        blastRadius = 160f, engineTone = 0.6f,
    );

    /** Steampunk vzducholoď – vznáší se, letí pomalu, má páru na zrychlení. */
    val airship: Boolean get() = this == AIRSHIP

    /** Svislý vzlet a přistání kdekoli (vrtulník, vzducholoď). */
    val vertical: Boolean get() = helicopter || airship

    /** Cestovní rychlost (m/s). */
    val cruise: Float get() = if (vertical) maxSpeed else kotlin.math.sqrt(thrust / drag)

    companion object {
        fun fromName(name: String?): AircraftType = entries.firstOrNull { it.name == name } ?: SPORT
    }
}
