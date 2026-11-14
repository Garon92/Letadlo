package app.letadlo.game

/** Druhy letadel a jejich letové vlastnosti (arkádové, ne realistické tabulky). */
enum class AircraftType(
    val title: String,
    val emoji: String,
    val helicopter: Boolean,
    /** Rychlost, při které jde letadlo zvednout z dráhy (m/s). */
    val rotateSpeed: Float,
    val stallSpeed: Float,
    val groundThrust: Float,
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
    val bankRate: Float,
    val pitchRate: Float,
    val turnFactor: Float,
    val ceiling: Float,
    /** Jak daleko výbuch rozhází auta a lidi. */
    val blastRadius: Float,
    /** Výška zvuku motoru (násobek). */
    val engineTone: Float,
) {
    SPORT(
        "Sportovní", "🛩️", false,
        rotateSpeed = 38f, stallSpeed = 30f, groundThrust = 6.5f, thrust = 9f, drag = 0.0016f,
        minSpeed = 18f, maxSpeed = 140f, gearHeight = 1.6f, radius = 3f,
        maxBankDeg = 62f, maxPitchUpDeg = 32f, maxPitchDownDeg = 35f,
        bankRate = 2.6f, pitchRate = 2.0f, turnFactor = 1.35f, ceiling = 3500f,
        blastRadius = 70f, engineTone = 1f,
    ),
    FIGHTER(
        "Stíhačka", "⚡", false,
        rotateSpeed = 62f, stallSpeed = 50f, groundThrust = 15f, thrust = 26f, drag = 0.0009f,
        minSpeed = 35f, maxSpeed = 290f, gearHeight = 2.15f, radius = 4f,
        maxBankDeg = 80f, maxPitchUpDeg = 45f, maxPitchDownDeg = 45f,
        bankRate = 4.0f, pitchRate = 3.0f, turnFactor = 1.8f, ceiling = 4500f,
        blastRadius = 95f, engineTone = 2.2f,
    ),
    AIRLINER(
        "Dopravní", "✈️", false,
        rotateSpeed = 64f, stallSpeed = 52f, groundThrust = 4.8f, thrust = 9.5f, drag = 0.00075f,
        minSpeed = 40f, maxSpeed = 200f, gearHeight = 3.45f, radius = 7f,
        maxBankDeg = 35f, maxPitchUpDeg = 18f, maxPitchDownDeg = 20f,
        bankRate = 1.1f, pitchRate = 0.9f, turnFactor = 1.4f, ceiling = 4000f,
        blastRadius = 150f, engineTone = 1.7f,
    ),
    HELICOPTER(
        "Vrtulník", "🚁", true,
        rotateSpeed = 0f, stallSpeed = 0f, groundThrust = 0f, thrust = 0f, drag = 0f,
        minSpeed = 0f, maxSpeed = 50f, gearHeight = 1.75f, radius = 4.5f,
        maxBankDeg = 30f, maxPitchUpDeg = 0f, maxPitchDownDeg = 0f,
        bankRate = 2.0f, pitchRate = 2.0f, turnFactor = 1.6f, ceiling = 3000f,
        blastRadius = 70f, engineTone = 0.75f,
    ),
    BOMBER(
        "Bombardér", "💣", false,
        rotateSpeed = 56f, stallSpeed = 45f, groundThrust = 5.0f, thrust = 8.5f, drag = 0.00085f,
        minSpeed = 35f, maxSpeed = 180f, gearHeight = 3.0f, radius = 7f,
        maxBankDeg = 40f, maxPitchUpDeg = 22f, maxPitchDownDeg = 25f,
        bankRate = 1.3f, pitchRate = 1.1f, turnFactor = 1.4f, ceiling = 4000f,
        blastRadius = 170f, engineTone = 0.85f,
    );

    companion object {
        fun fromName(name: String?): AircraftType = entries.firstOrNull { it.name == name } ?: SPORT
    }
}
