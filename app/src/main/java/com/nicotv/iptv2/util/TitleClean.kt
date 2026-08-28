package com.nicotv.iptv2.util

/** Tags qualité/langue/codec de release scene/Xtream ("4K-EN - Avatar",
 * "Movie.2020.FRENCH.1080p.x264-GROUP") — partagé entre TmdbClient.cleanTitle
 * (recherche : retire aussi l'année) et Movie/SeriesEntity.displayTitle
 * (affichage : garde l'année, "Avatar (2009)" reste utile à l'écran). */
private val QUALITY_LANG_TAG = Regex(
    """(?i)\b(4K|3D|2160p|1080p|720p|480p|UHD|FHD|HDR10?|DV|ATMOS|HD|SD|WEB[- ]?DL|WEBRip|BluRay|BDRip|DVDRip|HDRip|HDTV|CAM|TS|""" +
    """x264|x265|HEVC|H264|H265|AAC|AC3|DTS|""" +
    """VF|VFF|VFQ|VO|VOST|VOSTFR|MULTI|FRENCH|TRUEFRENCH|ENGLISH|SUBFRENCH|""" +
    // FR (oublié au premier passage — c'est justement le tag le plus courant
    // sur les panels FR : "FR - Ghost (1990)", "FR| Movie", signalé 28/08/2026).
    """FR|EN|DE|ES|IT|PT|NL|PL|RU|AR|TR)\b"""
)
// Suffixe "-GROUPE" en toute fin de nom (groupe de release) : seulement en fin
// de chaîne, sinon un vrai tiret dans le titre ("Spider-Man") serait tronqué.
private val RELEASE_GROUP_SUFFIX = Regex("""(?i)-[a-z0-9]{2,15}$""")
// Tirets/espaces résiduels après suppression des tags ci-dessus (ex.
// "4K-EN - Avatar" → "- - Avatar" une fois 4K et EN retirés) — seulement en
// DÉBUT/FIN de chaîne (^/$), jamais au milieu : ne touche pas "Spider-Man".
private val EDGE_DASHES = Regex("""^[\s\-]+|[\s\-]+$""")

/** Titre "propre" pour l'affichage — retire les tags qualité/langue/codec
 * ajoutés par la plupart des panels Xtream/M3U ("4K-EN - Avatar (2009)" →
 * "Avatar (2009)"), garde l'année (contrairement à TmdbClient.cleanTitle,
 * pensé pour la recherche). Demande explicite (28/08/2026) : "voir le vrai
 * nom du film" dans les murs d'affiches/fiches, pas le nom brut de la
 * playlist. */
fun String.stripReleaseTags(): String {
    var t = this.replace(Regex("""[._+]"""), " ")
    t = t.replace(RELEASE_GROUP_SUFFIX, "")
    t = t.replace(QUALITY_LANG_TAG, " ")
    t = t.replace(Regex("""\s+"""), " ").trim()
    return t.replace(EDGE_DASHES, "").trim()
}
