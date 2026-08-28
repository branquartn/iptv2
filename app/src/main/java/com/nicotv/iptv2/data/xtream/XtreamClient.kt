package com.nicotv.iptv2.data.xtream

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

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

    // Encodage indispensable : un identifiant/mot de passe avec '+', '&', '%',
    // espace... casse silencieusement la requête sinon (paramètre tronqué ou
    // mal interprété par le panel) — cause fréquente d'échec de connexion Xtream
    // jamais remontée comme erreur explicite.
    private fun enc(v: String): String = URLEncoder.encode(v, "UTF-8")

    private suspend fun callRaw(action: String?, extraParams: Map<String, String> = emptyMap()): String =
        withContext(Dispatchers.IO) {
            val urlBuilder = StringBuilder("$base/player_api.php?username=${enc(username)}&password=${enc(password)}")
            if (!action.isNullOrBlank()) urlBuilder.append("&action=$action")
            extraParams.forEach { (k, v) -> urlBuilder.append("&$k=${enc(v)}") }
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
        // Loggé (visible via adb logcat/bugreport) plutôt que totalement silencieux :
        // un catalogue vide inexpliqué est sinon impossible à diagnostiquer à distance.
        return try {
            JSONArray(body)
        } catch (e: Exception) {
            Log.w("XtreamClient", "$action: réponse non-tableau (${body.take(200)})")
            JSONArray()
        }
    }

    private suspend fun callObject(action: String, params: Map<String, String> = emptyMap()): JSONObject {
        val body = callRaw(action, params)
        return try {
            JSONObject(body)
        } catch (e: Exception) {
            Log.w("XtreamClient", "$action: réponse non-objet (${body.take(200)})")
            JSONObject()
        }
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

    /** [categoryId] optionnel : certains panels renvoient une liste VIDE sur
     * get_vod_streams/get_series sans category_id (contrairement à
     * get_live_streams, qui lui répond toujours) — ils exigent un appel par
     * catégorie. Repli géré côté appelant (cf. PlaylistRepository.loadXtream) :
     * essai global d'abord (1 seul appel, cas le plus courant), puis par
     * catégorie seulement si besoin. */
    suspend fun getVodStreams(categoryId: String? = null): List<XtStream> = buildList {
        val arr = callArray("get_vod_streams", categoryId?.let { mapOf("category_id" to it) } ?: emptyMap())
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

    /** [categoryId] optionnel : même repli que [getVodStreams]. */
    suspend fun getSeriesList(categoryId: String? = null): List<XtSeriesItem> = buildList {
        val arr = callArray("get_series", categoryId?.let { mapOf("category_id" to it) } ?: emptyMap())
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

    /** Mini-guide "en cours / à suivre" d'une chaîne (get_short_epg) — [limit]
     * bas (2 par défaut : programme actuel + suivant) puisque appelé à la
     * demande pour chaque chaîne affichée, pas au chargement du catalogue.
     * "title"/"description" sont encodés en base64 par le panel (norme XMLTV
     * reprise par Xtream Codes) — décodage tolérant : un champ déjà en clair
     * (panel non conforme) est renvoyé tel quel plutôt que de planter. */
    suspend fun getShortEpg(streamId: String, limit: Int = 2): List<XtEpgListing> = buildList {
        val json = callObject("get_short_epg", mapOf("stream_id" to streamId, "limit" to limit.toString()))
        val arr = json.optJSONArray("epg_listings") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            add(
                XtEpgListing(
                    title = decodeMaybeBase64(o.optString("title")),
                    startTimestamp = o.optString("start_timestamp").toLongOrNull() ?: 0L,
                    stopTimestamp = o.optString("stop_timestamp").toLongOrNull() ?: 0L,
                    nowPlaying = o.optString("now_playing") == "1" || o.optInt("now_playing") == 1
                )
            )
        }
    }

    /** Détail d'un film (get_vod_info) — appelé à la demande à l'ouverture de la
     * fiche film uniquement, cf. XtVodInfo. `get_vod_streams` (liste en masse)
     * ne renvoie souvent aucun de ces champs sur les panels réels ; ici ils
     * vivent dans un sous-objet "info" (absent → objet vide, jamais d'erreur). */
    suspend fun getVodInfo(vodId: String): XtVodInfo {
        val json = callObject("get_vod_info", mapOf("vod_id" to vodId))
        val info = json.optJSONObject("info") ?: JSONObject()
        val backdrops = info.optJSONArray("backdrop_path")
        return XtVodInfo(
            plot = info.optString("plot"),
            genre = info.optString("genre"),
            rating = info.optString("rating").toFloatOrNull() ?: 0f,
            durationSecs = info.optInt("duration_secs", 0),
            backdropUrl = backdrops?.optString(0).orEmpty()
        )
    }

    private fun decodeMaybeBase64(value: String): String {
        if (value.isBlank()) return value
        return try {
            String(android.util.Base64.decode(value, android.util.Base64.DEFAULT), Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            value
        }
    }

    fun liveStreamUrl(streamId: String): String = "$base/live/${enc(username)}/${enc(password)}/$streamId.ts"
    fun vodStreamUrl(streamId: String, ext: String): String = "$base/movie/${enc(username)}/${enc(password)}/$streamId.$ext"
    fun seriesEpisodeUrl(episodeId: String, ext: String): String = "$base/series/${enc(username)}/${enc(password)}/$episodeId.$ext"

    /** Export M3U classique (get.php), supporté par la quasi-totalité des
     * panels Xtream même quand l'API JSON (player_api.php) est absente/
     * restreinte pour ce compte — repli utilisé par [PlaylistRepository.loadXtream]
     * quand cette dernière ne renvoie aucun flux malgré un login réussi. */
    fun playlistM3uUrl(): String = "$base/get.php?username=${enc(username)}&password=${enc(password)}&type=m3u_plus&output=mpegts"
}
