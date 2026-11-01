package com.nicotv.iptv2.account

import com.nicotv.iptv2.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Actions sur la bibliothèque du serveur NicoTV (mêmes routes que l'app iptv) : retirer un titre, demander un ajout. */
class LibraryApi(private val client: OkHttpClient, private val session: SessionManager) {

    private val endpoint = AppConfig.Account.BASE_URL + "index.php"
    private val json = "application/json; charset=utf-8".toMediaType()

    private fun post(action: String, body: JSONObject): JSONObject? = runCatching {
        val req = Request.Builder().url("$endpoint?action=$action").header("Authorization", session.bearer())
            .post(body.toString().toRequestBody(json)).build()
        client.newCall(req).execute().use { r -> r.body?.string().orEmpty().let { if (it.isBlank()) null else JSONObject(it) } }
    }.getOrNull()

    /** Retire un titre. Compte normal : accès du profil seulement. ADMIN : suppression définitive (catalogue + torrent). */
    suspend fun deleteMedia(type: String, releaseKey: String = "", strm: String = "", name: String = ""): JSONObject? =
        withContext(Dispatchers.IO) {
            post("delete_media", JSONObject().put("type", type).put("releaseKey", releaseKey).put("strm", strm).put("name", name))
        }

    /** Demande l'ajout d'un film/série (file de téléchargement du serveur). Renvoie le message du serveur. */
    suspend fun addTitle(tmdbId: Int, isTv: Boolean, title: String, year: String): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            val j = post("add", JSONObject().put("tmdb_id", tmdbId).put("media_type", if (isTv) "tv" else "movie")
                .put("title", title).put("year", year))
            if (j == null) false to "Pas de connexion au serveur"
            else j.optBoolean("ok") to (j.optString("message").ifBlank { j.optString("error").ifBlank { if (j.optBoolean("ok")) "Ajouté" else "Échec" } })
        }
}
