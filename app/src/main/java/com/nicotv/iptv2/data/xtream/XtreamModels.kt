package com.nicotv.iptv2.data.xtream

/** Modèles Xtream Codes — volontairement simples (pas de binding Gson strict) :
 * les panels Xtream sont notoirement incohérents d'un fournisseur à l'autre
 * (un champ numérique tantôt en int, tantôt en string, parfois absent). Le
 * parsing se fait à la main en JSONObject/JSONArray (cf. XtreamClient),
 * toujours avec des accès tolérants (optString/optInt), jamais de désérialisation
 * stricte qui planterait sur un panel qui dévie du schéma habituel. */

data class XtCategory(val id: String, val name: String)

data class XtStream(
    val streamId: String,
    val name: String,
    val icon: String,
    val categoryId: String,
    // Vide pour le live (extension fixe .ts côté lecture), sinon "mp4"/"mkv"/...
    val containerExtension: String = "",
    val rating: Float = 0f,
    val plot: String = ""
)

data class XtSeriesItem(
    val seriesId: String,
    val name: String,
    val cover: String,
    val categoryId: String,
    val plot: String = "",
    val rating: Float = 0f,
    val genre: String = "",
    val releaseDate: String = ""
)

data class XtEpisode(
    val id: String,
    val episodeNumber: Int,
    val seasonNumber: Int,
    val title: String,
    val containerExtension: String,
    val overview: String = "",
    val durationSecs: Int = 0
)

data class XtSeason(val number: Int, val name: String)

data class XtSeriesInfo(
    val seasons: List<XtSeason>,
    val episodesBySeason: Map<Int, List<XtEpisode>>
)

/** Détail d'un film VOD (get_vod_info) — appelé à la demande à l'ouverture de
 * la fiche film uniquement (pas au chargement du catalogue, cf.
 * PlaylistRepository.enrichMovieFromXtreamIfNeeded) : get_vod_streams (liste
 * en masse) ne renvoie souvent ni plot ni cast ni genre sur les panels réels —
 * ces champs ne sont disponibles que via cet appel par film. */
data class XtVodInfo(
    val plot: String = "",
    val genre: String = "",
    val rating: Float = 0f,
    val durationSecs: Int = 0,
    val backdropUrl: String = ""
)
