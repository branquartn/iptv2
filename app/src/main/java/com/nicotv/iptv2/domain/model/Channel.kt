package com.nicotv.iptv2.domain.model

/** Chaîne live (TV en direct), issue d'une playlist M3U ou d'un compte Xtream Codes. */
data class Channel(
    val id: Long,
    val name: String,
    val streamUrl: String,
    val logoUrl: String = "",
    val category: String = "",
    val isFavorite: Boolean = false
)
