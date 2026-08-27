package com.nicotv.iptv.data.network.model

import com.google.gson.annotations.SerializedName
import com.nicotv.iptv.AppConfig

// ---- Séries TV ----

data class TmdbTvSearchResponse(
    @SerializedName("results") val results: List<TmdbTvResult> = emptyList()
)

data class TmdbTvResult(
    @SerializedName("id") val id: Int = 0,
    @SerializedName("name") val name: String = "",
    @SerializedName("poster_path") val posterPath: String? = null,
    @SerializedName("backdrop_path") val backdropPath: String? = null,
    @SerializedName("overview") val overview: String = "",
    @SerializedName("first_air_date") val firstAirDate: String = "",
    @SerializedName("vote_average") val voteAverage: Float = 0f
)

data class TmdbTvDetail(
    @SerializedName("id") val id: Int = 0,
    @SerializedName("name") val name: String = "",
    @SerializedName("poster_path") val posterPath: String? = null,
    @SerializedName("backdrop_path") val backdropPath: String? = null,
    @SerializedName("overview") val overview: String = "",
    @SerializedName("first_air_date") val firstAirDate: String = "",
    @SerializedName("vote_average") val voteAverage: Float = 0f,
    @SerializedName("genres") val genres: List<TmdbGenre> = emptyList()
)

data class TmdbSeasonDetail(
    @SerializedName("episodes") val episodes: List<TmdbEpisode> = emptyList()
)

data class TmdbEpisode(
    @SerializedName("episode_number") val episodeNumber: Int = 0,
    @SerializedName("name") val name: String = "",
    @SerializedName("overview") val overview: String = "",
    @SerializedName("still_path") val stillPath: String? = null,
    @SerializedName("runtime") val runtime: Int = 0
)

data class TmdbSearchResponse(
    @SerializedName("results") val results: List<TmdbMovieResult> = emptyList()
)

data class TmdbMovieResult(
    @SerializedName("id") val id: Int = 0,
    @SerializedName("title") val title: String = "",
    @SerializedName("poster_path") val posterPath: String? = null,
    @SerializedName("backdrop_path") val backdropPath: String? = null,
    @SerializedName("overview") val overview: String = "",
    @SerializedName("release_date") val releaseDate: String = "",
    @SerializedName("vote_average") val voteAverage: Float = 0f,
    @SerializedName("genre_ids") val genreIds: List<Int> = emptyList()
)

data class TmdbMovieDetail(
    @SerializedName("id") val id: Int = 0,
    @SerializedName("title") val title: String = "",
    @SerializedName("poster_path") val posterPath: String? = null,
    @SerializedName("backdrop_path") val backdropPath: String? = null,
    @SerializedName("overview") val overview: String = "",
    @SerializedName("release_date") val releaseDate: String = "",
    @SerializedName("vote_average") val voteAverage: Float = 0f,
    @SerializedName("runtime") val runtime: Int = 0,
    @SerializedName("genres") val genres: List<TmdbGenre> = emptyList()
)

data class TmdbGenre(
    @SerializedName("id") val id: Int,
    @SerializedName("name") val name: String
)

// ---- Casting / réalisateur / fiche acteur ----

data class TmdbCredits(
    @SerializedName("cast") val cast: List<TmdbCastMember> = emptyList(),
    @SerializedName("crew") val crew: List<TmdbCrewMember> = emptyList()
)

data class TmdbCastMember(
    @SerializedName("id") val id: Int = 0,
    @SerializedName("name") val name: String = "",
    @SerializedName("character") val character: String = "",
    @SerializedName("profile_path") val profilePath: String? = null
) {
    val profileUrl: String get() = profilePath?.let { AppConfig.Tmdb.IMAGE_BASE_W185 + it } ?: ""
}

data class TmdbCrewMember(
    @SerializedName("id") val id: Int = 0,
    @SerializedName("name") val name: String = "",
    @SerializedName("job") val job: String = "",
    @SerializedName("profile_path") val profilePath: String? = null
)

data class TmdbPerson(
    @SerializedName("id") val id: Int = 0,
    @SerializedName("name") val name: String = "",
    @SerializedName("biography") val biography: String = "",
    @SerializedName("profile_path") val profilePath: String? = null
) {
    val profileUrl: String get() = profilePath?.let { AppConfig.Tmdb.IMAGE_BASE_W185 + it } ?: ""
}

// Filmographie : mêmes champs qu'un résultat de recherche multi (film OU série).
data class TmdbCombinedCredits(
    @SerializedName("cast") val cast: List<TmdbMultiResult> = emptyList(),
    // crew : rôles techniques (chaque entrée porte un job). Sert à la fiche
    // réalisateur → on filtre job == "Director".
    @SerializedName("crew") val crew: List<TmdbMultiResult> = emptyList()
)

data class TmdbVideosResponse(
    @SerializedName("results") val results: List<TmdbVideo> = emptyList()
)

data class TmdbVideo(
    @SerializedName("key") val key: String = "",
    @SerializedName("site") val site: String = "",
    @SerializedName("type") val type: String = "",
    @SerializedName("iso_639_1") val lang: String = ""
)

data class TmdbMultiSearchResponse(
    @SerializedName("results") val results: List<TmdbMultiResult> = emptyList()
)

data class TmdbMultiResult(
    @SerializedName("id") val id: Int = 0,
    @SerializedName("media_type") val mediaType: String = "",
    @SerializedName("title") val title: String? = null,        // movies
    @SerializedName("name") val name: String? = null,          // tv + acteurs (person)
    @SerializedName("poster_path") val posterPath: String? = null,
    @SerializedName("backdrop_path") val backdropPath: String? = null,
    @SerializedName("profile_path") val profilePath: String? = null,   // acteurs (person) uniquement
    @SerializedName("overview") val overview: String = "",
    @SerializedName("release_date") val releaseDate: String? = null,     // movies
    @SerializedName("first_air_date") val firstAirDate: String? = null,  // tv
    @SerializedName("vote_average") val voteAverage: Float = 0f,
    @SerializedName("genre_ids") val genreIds: List<Int> = emptyList(),
    // Présent uniquement dans le tableau crew de combined_credits (réalisateur,
    // scénariste…) — vide pour un rôle cast ou un résultat de recherche.
    @SerializedName("job") val job: String = "",
    // Uniquement sur les résultats "person" de search/multi — département principal
    // ("Directing", "Acting", …) : distingue réalisateur/acteur pour ouvrir la bonne
    // filmographie (crew filtré vs cast), cf. isDirector.
    @SerializedName("known_for_department") val knownForDepartment: String = ""
) {
    val displayTitle: String get() = title ?: name ?: ""
    val displayYear: String get() = (releaseDate ?: firstAirDate ?: "").take(4)
    val isMovie: Boolean get() = mediaType == "movie"
    val isTv: Boolean get() = mediaType == "tv"
    val isPerson: Boolean get() = mediaType == "person"
    val isDirector: Boolean get() = knownForDepartment == "Directing"
    val posterUrl: String get() = posterPath?.let { "https://image.tmdb.org/t/p/w342$it" } ?: ""
    val backdropUrl: String get() = backdropPath?.let { AppConfig.Tmdb.IMAGE_BASE_W780 + it } ?: ""
    val profileUrl: String get() = profilePath?.let { AppConfig.Tmdb.IMAGE_BASE_W185 + it } ?: ""
}
