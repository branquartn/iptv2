package com.nicotv.iptv2.domain.model

data class Series(
    val id: Long,
    val title: String,
    val posterUrl: String = "",
    val backdropUrl: String = "",
    val overview: String = "",
    val firstAirYear: String = "",
    val rating: Float = 0f,
    val genres: List<String> = emptyList(),
    val category: String = "",
    val seasons: List<Season> = emptyList()
)

data class Season(
    val number: Int,
    val name: String,
    val episodes: List<Episode> = emptyList()
)

data class Episode(
    val number: Int,
    val title: String,
    val streamUrl: String,
    val overview: String = "",
    val duration: Int = 0
)
