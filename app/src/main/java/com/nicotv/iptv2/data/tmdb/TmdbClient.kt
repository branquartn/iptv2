package com.nicotv.iptv2.data.tmdb

import com.nicotv.iptv2.AppConfig
import com.nicotv.iptv2.util.cleanTitleForMatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class TmdbCastMember(val id: Int, val name: String, val character: String, val profilePath: String?) {
    val profileUrl: String get() = profilePath?.let { AppConfig.Tmdb.IMAGE_BASE_W185 + it } ?: ""
}

data class TmdbCrewMember(val id: Int, val name: String, val job: String, val profilePath: String?)

data class TmdbCredits(val cast: List<TmdbCastMember> = emptyList(), val crew: List<TmdbCrewMember> = emptyList())

data class TmdbPerson(val id: Int, val name: String, val biography: String, val profilePath: String?) {
    val profileUrl: String get() = profilePath?.let { AppConfig.Tmdb.IMAGE_BASE_W185 + it } ?: ""
}

/** Un film/série recommandé, ou une entrée de filmographie acteur (cast/crew de
 * combined_credits) — mêmes champs pour les deux usages. */
data class TmdbWork(
    val id: Int,
    val isTv: Boolean,
    val title: String,
    val posterPath: String?,
    val backdropPath: String? = null,
    val overview: String = "",
    val year: String = "",
    val rating: Float = 0f,
    val job: String = "" // renseigné uniquement pour une entrée crew (combined_credits)
)

/**
 * Secours "jaquette manquante" (recherche par titre) + fiche film complète
 * (casting, réalisateur, recommandations, bande-annonce, fiche acteur) — même
 * moteur que la présentation NicoTV, sans backend : tout est interrogé
 * directement sur TMDb depuis l'app. Parsing JSON à la main (org.json),
 * jamais de désérialisation stricte (best-effort, un échec réseau ne doit
 * jamais bloquer l'écran).
 */
class TmdbClient(private val client: OkHttpClient) {

    data class Hit(
        val id: Int = 0,
        val posterUrl: String = "",
        val backdropUrl: String = "",
        val overview: String = "",
        val year: String = "",
        val rating: Float = 0f
    )

    // Noms de fichiers scene-release réels ("Movie.Title.2020.FRENCH.1080p.
    // BluRay.x264-GROUP") bien plus fréquents dans les M3U/Xtream qu'un titre
    // propre — sans ce nettoyage, la recherche TMDb ne trouve simplement rien.
    // util.cleanTitleForMatch (tags qualité/langue/codec + année) — partagé
    // avec PlaylistRepository, qui compare un titre catalogue à un titre TMDb
    // pour le badge ✓ des films similaires/filmographie.
    private fun cleanTitle(title: String): String = title.cleanTitleForMatch()

    private fun urlFor(path: String, params: Map<String, String> = emptyMap()): HttpUrl {
        val builder = "${AppConfig.Tmdb.BASE_URL}$path".toHttpUrl().newBuilder()
            .addQueryParameter("api_key", AppConfig.Tmdb.API_KEY)
            .addQueryParameter("language", AppConfig.Tmdb.LANGUAGE)
        params.forEach { (k, v) -> builder.addQueryParameter(k, v) }
        return builder.build()
    }

    private suspend fun get(url: HttpUrl): String? = withContext(Dispatchers.IO) {
        try {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) null else response.body?.string()
            }
        } catch (e: Exception) {
            null
        }
    }

    // ── Recherche par titre (secours jaquette manquante + résolution tmdbId) ──

    suspend fun searchMovie(title: String): Hit? = search("movie", title, "release_date")
    suspend fun searchTv(title: String): Hit? = search("tv", title, "first_air_date")

    private suspend fun search(path: String, title: String, dateField: String): Hit? {
        val query = cleanTitle(title)
        if (query.isBlank()) return null
        val body = get(urlFor("search/$path", mapOf("query" to query))) ?: return null
        return try {
            val results = JSONObject(body).optJSONArray("results") ?: return null
            if (results.length() == 0) return null
            val o = results.optJSONObject(0) ?: return null
            val poster = o.optString("poster_path").takeIf { it.isNotBlank() && it != "null" }
            val backdrop = o.optString("backdrop_path").takeIf { it.isNotBlank() && it != "null" }
            Hit(
                id = o.optInt("id"),
                posterUrl = poster?.let { AppConfig.Tmdb.IMAGE_BASE_W500 + it }.orEmpty(),
                backdropUrl = backdrop?.let { AppConfig.Tmdb.IMAGE_BASE_W780 + it }.orEmpty(),
                overview = o.optString("overview"),
                year = o.optString(dateField).take(4),
                rating = o.optDouble("vote_average", 0.0).toFloat()
            )
        } catch (e: Exception) {
            null
        }
    }

    // ── Fiche film (casting, réalisateur, recommandations, bande-annonce) ────

    suspend fun getMovieCredits(movieId: Int): TmdbCredits {
        val body = get(urlFor("movie/$movieId/credits")) ?: return TmdbCredits()
        return try {
            val json = JSONObject(body)
            val cast = json.optJSONArray("cast")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { o ->
                        TmdbCastMember(o.optInt("id"), o.optString("name"), o.optString("character"), o.optNullableString("profile_path"))
                    }
                }
            }.orEmpty()
            val crew = json.optJSONArray("crew")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { o ->
                        TmdbCrewMember(o.optInt("id"), o.optString("name"), o.optString("job"), o.optNullableString("profile_path"))
                    }
                }
            }.orEmpty()
            TmdbCredits(cast, crew)
        } catch (e: Exception) {
            TmdbCredits()
        }
    }

    suspend fun getMovieRecommendations(movieId: Int): List<TmdbWork> {
        val body = get(urlFor("movie/$movieId/recommendations")) ?: return emptyList()
        return try {
            val results = JSONObject(body).optJSONArray("results") ?: return emptyList()
            (0 until results.length()).mapNotNull { i ->
                results.optJSONObject(i)?.let { o ->
                    TmdbWork(
                        id = o.optInt("id"), isTv = false, title = o.optString("title"),
                        posterPath = o.optNullableString("poster_path"), backdropPath = o.optNullableString("backdrop_path"),
                        overview = o.optString("overview"), year = o.optString("release_date").take(4),
                        rating = o.optDouble("vote_average", 0.0).toFloat()
                    )
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Priorité française, sinon la première bande-annonce YouTube trouvée. */
    suspend fun getTrailerKey(id: Int, isTv: Boolean): String? {
        val path = if (isTv) "tv/$id/videos" else "movie/$id/videos"
        val body = get(urlFor(path)) ?: return null
        return try {
            val results = JSONObject(body).optJSONArray("results") ?: return null
            val trailers = (0 until results.length()).mapNotNull { results.optJSONObject(it) }
                .filter { it.optString("site") == "YouTube" && it.optString("type") == "Trailer" }
            (trailers.firstOrNull { it.optString("iso_639_1") == "fr" } ?: trailers.firstOrNull())
                ?.optString("key")?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun getGenresAndRuntime(id: Int, isTv: Boolean): Pair<String, Int> {
        val path = if (isTv) "tv/$id" else "movie/$id"
        val body = get(urlFor(path)) ?: return "" to 0
        return try {
            val json = JSONObject(body)
            val genres = json.optJSONArray("genres")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name") }
            }.orEmpty().joinToString(" • ")
            val runtime = if (isTv) {
                json.optJSONArray("episode_run_time")?.let { if (it.length() > 0) it.optInt(0) else 0 } ?: 0
            } else {
                json.optInt("runtime", 0)
            }
            genres to runtime
        } catch (e: Exception) {
            "" to 0
        }
    }

    // ── Fiche acteur / réalisateur ────────────────────────────────────────────

    suspend fun getPerson(personId: Int): TmdbPerson? {
        val body = get(urlFor("person/$personId")) ?: return null
        return try {
            val o = JSONObject(body)
            TmdbPerson(o.optInt("id"), o.optString("name"), o.optString("biography"), o.optNullableString("profile_path"))
        } catch (e: Exception) {
            null
        }
    }

    /** Filmographie (cast) et films réalisés (crew, filtré job=Director côté
     * appelant) — mêmes champs, films et séries mélangés. */
    suspend fun getPersonCombinedCredits(personId: Int): Pair<List<TmdbWork>, List<TmdbWork>> {
        val body = get(urlFor("person/$personId/combined_credits")) ?: return emptyList<TmdbWork>() to emptyList()
        return try {
            val json = JSONObject(body)
            fun parseArray(key: String) = json.optJSONArray(key)?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { o ->
                        val isTv = o.optString("media_type") == "tv"
                        TmdbWork(
                            id = o.optInt("id"), isTv = isTv,
                            title = (if (isTv) o.optString("name") else o.optString("title")),
                            posterPath = o.optNullableString("poster_path"),
                            year = (if (isTv) o.optString("first_air_date") else o.optString("release_date")).take(4),
                            rating = o.optDouble("vote_average", 0.0).toFloat(),
                            job = o.optString("job")
                        )
                    }
                }
            }.orEmpty()
            parseArray("cast") to parseArray("crew")
        } catch (e: Exception) {
            emptyList<TmdbWork>() to emptyList()
        }
    }

    private fun JSONObject.optNullableString(key: String): String? =
        optString(key).takeIf { it.isNotBlank() && it != "null" }
}
