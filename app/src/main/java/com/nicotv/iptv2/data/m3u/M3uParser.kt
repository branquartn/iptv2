package com.nicotv.iptv2.data.m3u

/** Une ligne #EXTINF + son URL de flux. */
data class M3uEntry(
    val name: String,
    val logo: String = "",
    val groupTitle: String = "",
    val url: String
)

/** Parseur M3U/M3U8 minimal (#EXTINF + attributs tvg-*/group-title, puis l'URL
 * sur la ligne suivante) — pas de dépendance externe, format IPTV standard. */
object M3uParser {

    // Chaîne normale (pas de raw string """...""") : le motif se termine par un
    // guillemet, qui collisionnerait avec le délimiteur """ d'une chaîne brute.
    private val attrRegex = Regex("([a-zA-Z0-9_-]+)=\"([^\"]*)\"")

    fun parse(text: String): List<M3uEntry> {
        val entries = mutableListOf<M3uEntry>()
        var pendingName = ""
        var pendingLogo = ""
        var pendingGroup = ""

        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach

            if (line.startsWith("#EXTINF", ignoreCase = true)) {
                // Tout après la dernière virgule = nom affiché du canal/film.
                pendingName = line.substringAfterLast(',').trim()
                pendingLogo = ""
                pendingGroup = ""
                attrRegex.findAll(line).forEach { m ->
                    when (m.groupValues[1].lowercase()) {
                        "tvg-logo" -> pendingLogo = m.groupValues[2]
                        "group-title" -> pendingGroup = m.groupValues[2]
                    }
                }
            } else if (!line.startsWith("#")) {
                // Ligne d'URL (pas de directive #) : clôture l'entrée en attente.
                if (pendingName.isNotBlank() && line.isNotBlank()) {
                    entries.add(M3uEntry(pendingName, pendingLogo, pendingGroup, line))
                }
                pendingName = ""
            }
            // Autres directives (#EXTM3U, #EXTGRP, #EXTVLCOPT...) ignorées.
        }
        return entries
    }

    // ── Classification VOD / séries / live (M3U ne le dit pas explicitement —
    // heuristique sur group-title, comme la plupart des lecteurs IPTV grand public) ──

    private val vodGroupHints = listOf("vod", "film", "movie", "pelicula", "película", "filme")
    private val seriesGroupHints = listOf("serie", "série", "series", "séries", "show", "dizi")
    private val episodeRegex = Regex("""(?i)[\s._-]*S(\d{1,2})\s*E(\d{1,3})[\s._-]*""")
    private val episodeRegexAlt = Regex("""(?i)[\s._-]*(\d{1,2})x(\d{1,3})[\s._-]*""")

    enum class Kind { LIVE, MOVIE, EPISODE }

    fun classify(entry: M3uEntry): Kind {
        val group = entry.groupTitle.lowercase()
        if (episodeRegex.containsMatchIn(entry.name) || episodeRegexAlt.containsMatchIn(entry.name)) return Kind.EPISODE
        if (seriesGroupHints.any { group.contains(it) }) return Kind.EPISODE
        if (vodGroupHints.any { group.contains(it) }) return Kind.MOVIE
        return Kind.LIVE
    }

    /** Découpe "Show Name S01E02 Title" en (titre de série, n° saison, n° épisode,
     * titre d'épisode restant). Retourne null si aucun motif SxxEyy/1x02 trouvé. */
    data class ParsedEpisode(val seriesTitle: String, val season: Int, val episode: Int, val episodeTitle: String)

    fun parseEpisodeTitle(name: String): ParsedEpisode? {
        val match = episodeRegex.find(name) ?: episodeRegexAlt.find(name) ?: return null
        val season = match.groupValues[1].toIntOrNull() ?: return null
        val episode = match.groupValues[2].toIntOrNull() ?: return null
        val seriesTitle = name.substring(0, match.range.first).trim(' ', '-', ':', '_', '.').ifBlank { name }
        val rest = name.substring(match.range.last + 1).trim(' ', '-', ':', '_', '.')
        return ParsedEpisode(seriesTitle, season, episode, rest.ifBlank { "Épisode $episode" })
    }
}
