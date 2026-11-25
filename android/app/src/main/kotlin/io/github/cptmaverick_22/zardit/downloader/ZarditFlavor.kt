package io.github.cptmaverick_22.zardit.downloader

object ZarditFlavor {

    private val fetchLines = listOf(
        "Awakening Charizard…",
        "Scanning tall grass for formats…",
        "Checking the Pokédex…",
        "Using Ember on the link…",
        "Professor Oak is inspecting the URL…",
        "Summoning Rotom Dex…",
    )

    private val startLines = listOf(
        "Awakening Charizard…",
        "Charging Flamethrower…",
        "Locking onto target…",
        "Deploying Poké Ball…",
        "Warming up the tail flame…",
        "Preparing Hyper Beam…",
    )

    private val mergeLines = listOf(
        "Evolving media with Fire Spin…",
        "Fusing streams with Dragon Rage…",
        "Polishing the Poké Ball…",
        "Using Blast Burn on the final mux…",
        "Charizard used Fly to save the file!",
    )

    private val activeLines = listOf(
        "Awakening Charizard…",
        "Charging Flamethrower…",
        "Charizard is flying at full speed!",
        "Blazing through the bytes…",
        "Using Dragon Claw on the stream…",
        "Super effective download!",
        "Catching wild packets…",
        "Tail flame burning bright!",
        "Flare Blitz in progress…",
        "Pikachu is boosting the Wi-Fi!",
        "Using Quick Attack on the server…",
        "Critical hit! Full speed ahead!",
    )

    fun randomFetchLine(): String = fetchLines.random()

    fun formatProgressLine(rawLine: String, percent: Float): String {
        val lower = rawLine.lowercase()
        return when {
            lower.contains("merger") || lower.contains("ffmpeg") || lower.contains("extractaudio") || lower.contains("embedthumbnail") ->
                mergeLines[(System.currentTimeMillis() / 2200L % mergeLines.size).toInt()]
            percent <= 0f ->
                startLines[(System.currentTimeMillis() / 1800L % startLines.size).toInt()]
            else -> {
                val pctInt = percent.toInt().coerceIn(0, 100)
                val idx = (pctInt / 9) % activeLines.size
                "$pctInt% · ${activeLines[idx]}"
            }
        }
    }
}
