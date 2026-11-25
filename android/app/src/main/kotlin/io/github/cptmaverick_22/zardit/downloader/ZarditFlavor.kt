package io.github.cptmaverick_22.zardit.downloader

object ZarditFlavor {

    private val fetchLines = listOf(
        "Awakening Charizard…",
        "Scanning tall grass for formats…",
        "Checking the Pokédex…",
        "Using Ember on the link…",
    )

    private val startLines = listOf(
        "Awakening Charizard…",
        "Charging Flamethrower…",
        "Locking onto target…",
        "Deploying Poké Ball…",
    )

    private val mergeLines = listOf(
        "Evolving media with Fire Spin…",
        "Fusing video & audio streams…",
        "Polishing the Poké Ball…",
    )

    private val activeLines = listOf(
        "Charizard is flying at full speed!",
        "Blazing through the stream…",
        "Using Dragon Claw on the bytes…",
        "Super effective download!",
    )

    fun randomFetchLine(): String = fetchLines.random()

    fun formatProgressLine(rawLine: String, percent: Float, etaText: String): String {
        val lower = rawLine.lowercase()
        return when {
            lower.contains("merger") || lower.contains("ffmpeg") || lower.contains("extractaudio") || lower.contains("embedthumbnail") ->
                mergeLines[(System.currentTimeMillis() / 2500L % mergeLines.size).toInt()]
            percent <= 0f ->
                startLines[(System.currentTimeMillis() / 2000L % startLines.size).toInt()]
            else -> {
                val flavor = activeLines[(percent.toInt() / 25).coerceIn(0, activeLines.lastIndex)]
                val base = "${percent.toInt()}%$etaText"
                "$base · $flavor"
            }
        }
    }
}
