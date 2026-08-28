package com.nicotv.iptv2.domain.model

/** Chaîne live (TV en direct), issue d'une playlist M3U ou d'un compte Xtream Codes. */
data class Channel(
    val id: Long,
    val name: String,
    val streamUrl: String,
    val logoUrl: String = "",
    val category: String = "",
    val isFavorite: Boolean = false,
    // Vide si issue d'un M3U : pas de mini-guide EPG possible dans ce cas
    // (cf. PlaylistRepository.getShortEpg).
    val xtreamStreamId: String = ""
)
