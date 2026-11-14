package app.letadlo.game

enum class MapType(val title: String) {
    MOUNTAINS("Hory"),
    CITY("Město"),
    COUNTRYSIDE("Krajina");

    companion object {
        fun fromName(name: String?): MapType = entries.firstOrNull { it.name == name } ?: COUNTRYSIDE
    }
}
