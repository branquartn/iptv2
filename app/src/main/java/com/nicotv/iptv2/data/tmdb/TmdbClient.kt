package com.nicotv.iptv2.data.tmdb

import com.nicotv.iptv2.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Secours "jaquette manquante" : quand une entrée M3U/Xtream n'a pas de
 * tvg-logo/stream_icon, on tente une recherche TMDb par titre pour récupérer
 * affiche/backdrop/synopsis/année/note. Best-effort — jamais bloquant, une
 * recherche qui échoue ou ne trouve rien laisse simplement l'entrée sans
 * jaquette (cf. PlaylistRepository.enrichArtwork).
 */
class TmdbClient(private val client: OkHttpClient) {

    data class Hit(
        val posterUrl: String = "",
        val backdropUrl: String = "",
        val overview: String = "",
        val year: String = "",
        val rating: Float = 0f
    )

    // Motifs courants dans les noms de chaînes IPTV (année, tags de qualité/langue)
    // qui parasitent la recherche TMDb si on les laisse dans la requête.
    private val yearTag = Regex("""\(?\b(19|20)\d{2}\b\)?""")
    private val qualityTag = Regex("""(?i)\b(4K|UHD|FHD|HD|SD|VF|VFF|VOSTFR|MULTI|CAM|TS)\b""")

    private fun cleanTitle(title: String): String =
        title.replace(yearTag, "").replace(qualityTag, "").replace(Regex("""\s+"""), " ").trim()

    suspend fun searchMovie(title: String): Hit? = search("movie", title, "title", "release_date")
    suspend fun searchTv(title: String): Hit? = search("tv", title, "name", "first_air_date")

    private suspend fun search(path: String, title: String, titleField: String, dateField: String): Hit? =
        withContext(Dispatchers.IO) {
            val query = cleanTitle(title)
            if (query.isBlank()) return@withContext null
            try {
                val url = "${AppConfig.Tmdb.BASE_URL}search/$path".toHttpUrl().newBuilder()
                    .addQueryParameter("api_key", AppConfig.Tmdb.API_KEY)
                    .addQueryParameter("language", AppConfig.Tmdb.LANGUAGE)
                    .addQueryParameter("query", query)
                    .build()
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    val body = response.body?.string() ?: return@withContext null
                    val results = JSONObject(body).optJSONArray("results") ?: return@withContext null
                    if (results.length() == 0) return@withContext null
                    val o = results.optJSONObject(0) ?: return@withContext null
                    val poster = o.optString("poster_path").takeIf { it.isNotBlank() && it != "null" }
                    val backdrop = o.optString("backdrop_path").takeIf { it.isNotBlank() && it != "null" }
                    Hit(
                        posterUrl = poster?.let { AppConfig.Tmdb.IMAGE_BASE_W500 + it }.orEmpty(),
                        backdropUrl = backdrop?.let { AppConfig.Tmdb.IMAGE_BASE_W780 + it }.orEmpty(),
                        overview = o.optString("overview"),
                        year = o.optString(dateField).take(4),
                        rating = o.optDouble("vote_average", 0.0).toFloat()
                    )
                }
            } catch (e: Exception) {
                null
            }
        }
}
