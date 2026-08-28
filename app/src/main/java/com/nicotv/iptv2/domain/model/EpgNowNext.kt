package com.nicotv.iptv2.domain.model

/** "En cours / à suivre" pour une chaîne live (mini-guide EPG, Xtream
 * uniquement — cf. PlaylistRepository.getShortEpg). [nowEnd] en secondes
 * unix, utilisé pour estimer la progression du créneau en cours dans l'UI. */
data class EpgNowNext(
    val nowTitle: String,
    val nowEnd: Long,
    val nextTitle: String
)
