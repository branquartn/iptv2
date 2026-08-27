package com.nicotv.iptv2.data.xtream

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** Client Xtream Codes (player_api.php) : login + catégories + flux live/VOD/
 * séries. Parsing JSON à la main (org.json), tolérant aux variations de type
 * d'un panel à l'autre (cf. XtreamModels). */
class XtreamClient(
    private val client: OkHttpClient,
    host: String,
    private val username: String,
    private val password: String
) {
    // Normalise l'hôte saisi par l'utilisateur : ajoute http:// si aucun schéma
    // n'est fourni (la grande majorité des panels Xtream tournent en HTTP simple),
    // retire un slash final éventuel.
    val base: String = host.trim().let {
        val withScheme = if (it.startsWith("http://") || it.startsWith("https://")) it else "http://$it"
        withScheme.trimEnd('/')
    }

    class XtreamException(message: String) : IOException(message)

    private suspend fun callRaw(action: String?, extraParams: Map<String, String> = emptyMap()): String =
        withContext(Dispatchers.IO) {
            val urlBuilder = StringBuilder("$base/player_api.php?username=$username&password=$password")
            if (!action.isNullOrBlank()) urlBuilder.append("&action=$action")
            extraParams.forEach { (k, v) -> urlBuilder.append("&$k=$v") }
            val request = Request.Builder().url(urlBuilder.toString()).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw XtreamException("HTTP ${response.code}")
                response.body?.string() ?: throw XtreamException("Réponse vide")
            }
        }

    private suspend fun callArray(action: String, params: Map<String, String> = emptyMap()): JSONArray {
        val body = callRaw(action, params)
        // Un panel mal configuré (mauvais login, action inconnue) répond parfois
        // par un objet d'erreur ({}) plutôt qu'un tableau : traité comme liste vide
        // plutôt que de planter (l'utilisateur voit une catégorie vide, pas un crash).
        return try { JSONArray(body) } catch (e: Exception) { JSONArray() }
    }

    private suspend fun callObject(action: String, params: Map<String, String> = emptyMap()): JSONObject {
        val body = callRaw(action, params)
        return try { JSONObject(body) } catch (e: Exception) { JSONObject() }
    }

    /** Vérifie les identifiants. Lève XtreamException si le panel refuse
     * explicitement (auth=0) ou si la requête échoue (réseau/HTTP). */
    suspend fun login() {
        val json = callObject("")
        val userInfo = json.optJSONObject("user_info")
            ?: throw XtreamException("Réponse inattendue du serveur (pas un panel Xtream Codes ?)")
        val auth = userInfo.optInt("auth", userInfo.optString("auth", "0").toIntOrNull() ?: 0)
        if (auth != 1) {
            val msg = userInfo.optString("message", "")
            throw XtreamException(if (msg.isNotBlank()) msg else "Identifiants refusés")
        }
    }

    suspend fun getLiveCategories(): List<XtCategory> = parseCategories(callArray("get_live_categories"))
    suspend fun getVodCategories(): List<XtCategory> = parseCategories(callArray("get_vod_categories"))
    suspend fun getSeriesCategories(): List<XtCategory> = parseCategories(callArray("get_series_categories"))

    private fun parseCategories(arr: JSONArray): List<XtCategory> = buildList {
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            add(XtCategory(id = o.optString("category_id"), name = o.optString("category_name")))
        }
    }

    suspend fun getLiveStreams(): List<XtStream> = buildList {
        val arr = callArray("get_live_streams")
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            add(
                XtStream(
                    streamId = o.optString("stream_id"),
                    name = o.optString("name"),
                    icon = o.optString("stream_icon"),
                    categoryId = o.optString("category_id")
                )
            )
        }
    }

    suspend fun getVodStreams(): List<XtStream> = buildList {
        val arr = callArray("get_vod_streams")
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            add(
                XtStream(
                    streamId = o.optString("stream_id"),
                    name = o.optString("name"),
                    icon = o.optString("stream_icon"),
                    categoryId = o.optString("category_id"),
                    containerExtension = o.optString("container_extension", "mp4").ifBlank { "mp4" },
                    rating = o.optString("rating").toFloatOrNull() ?: 0f,
                    plot = o.optString("plot")
                )
            )
        }
    }

    suspend fun getSeriesList(): List<XtSeriesItem> = buildList {
        val arr = callArray("get_series")
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            add(
                XtSeriesItem(
                    seriesId = o.optString("series_id"),
                    name = o.optString("name"),
                    cover = o.optString("cover"),
                    categoryId = o.optString("category_id"),
                    plot = o.optString("plot"),
                    rating = o.optString("rating").toFloatOrNull() ?: 0f,
                    genre = o.optString("genre"),
                    releaseDate = o.optString("releaseDate", o.optString("release_date"))
                )
            )
        }
    }

    suspend fun getSeriesInfo(seriesId: String): XtSeriesInfo {
        val json = callObject("get_series_info", mapOf("series_id" to seriesId))

        val seasonsArr = json.optJSONArray("seasons") ?: JSONArray()
        val seasons = buildList {
            for (i in 0 until seasonsArr.length()) {
                val o = seasonsArr.optJSONObject(i) ?: continue
                val num = o.optInt("season_number", i + 1)
                add(XtSeason(number = num, name = o.optString("name", "Saison $num")))
            }
        }.sortedBy { it.number }

        val episodesObj = json.optJSONObject("episodes") ?: JSONObject()
        val episodesBySeason = mutableMapOf<Int, List<XtEpisode>>()
        episodesObj.keys().forEach { seasonKey ->
            val seasonNum = seasonKey.toIntOrNull() ?: return@forEach
            val arr = episodesObj.optJSONArray(seasonKey) ?: return@forEach
            val eps = buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val info = o.optJSONObject("info")
                    add(
                        XtEpisode(
                            id = o.optString("id"),
                            episodeNumber = o.optInt("episode_num", i + 1),
                            seasonNumber = seasonNum,
                            title = o.optString("title", "Épisode ${o.optInt("episode_num", i + 1)}"),
                            containerExtension = o.optString("container_extension", "mp4").ifBlank { "mp4" },
                            overview = info?.optString("plot").orEmpty(),
                            durationSecs = info?.optInt("duration_secs", 0) ?: 0
                        )
                    )
                }
            }.sortedBy { it.episodeNumber }
            episodesBySeason[seasonNum] = eps
        }

        // Certains panels ne renvoient pas "seasons" (juste "episodes") : on
        // reconstruit la liste des saisons depuis les clés d'episodes trouvées.
        val finalSeasons = seasons.ifEmpty {
            episodesBySeason.keys.sorted().map { XtSeason(it, "Saison $it") }
        }
        return XtSeriesInfo(finalSeasons, episodesBySeason)
    }

    fun liveStreamUrl(streamId: String): String = "$base/live/$username/$password/$streamId.ts"
    fun vodStreamUrl(streamId: String, ext: String): String = "$base/movie/$username/$password/$streamId.$ext"
    fun seriesEpisodeUrl(episodeId: String, ext: String): String = "$base/series/$username/$password/$episodeId.$ext"
}
