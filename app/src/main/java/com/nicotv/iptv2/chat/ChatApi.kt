package com.nicotv.iptv2.chat

import android.content.Context
import com.nicotv.iptv2.AppConfig
import com.nicotv.iptv2.account.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** Message du tchat NicoTV ↔ Telegram (table serveur `iptv_chat_messages`) — même serveur que l'app iptv. */
data class ChatMessage(
    val id: Long,
    val direction: String,        // "out" = envoyé par l'appli, "in" = réponse de Nicolas
    val text: String,
    val type: String,             // text | voice | image | file | film
    val mediaName: String?,
    val replyPreview: String?,
    val replyWho: String?,
    val createdAt: Long,          // secondes epoch
    val replyToId: Long? = null,
    val reaction: String? = null,
    val fwdWho: String? = null,
    val tmdbId: Int? = null
) {
    val isOut: Boolean get() = direction == "out"
}

/** Appels du tchat vers api.nicotv.ovh (mêmes routes `chat_*` que l'app iptv, authentifiées par le jeton du login). */
class ChatApi(
    private val client: OkHttpClient,
    private val session: SessionManager,
    context: Context
) {
    private val endpoint = AppConfig.Account.BASE_URL + "index.php"
    private val prefs = context.applicationContext.getSharedPreferences("iptv2_chat", Context.MODE_PRIVATE)
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private fun url(action: String, vararg extra: Pair<String, String>): HttpUrl {
        val b = endpoint.toHttpUrl().newBuilder().addQueryParameter("action", action)
        extra.forEach { b.addQueryParameter(it.first, it.second) }
        return b.build()
    }

    private fun authed(url: HttpUrl) = Request.Builder().url(url).header("Authorization", session.bearer())

    private fun postJson(url: HttpUrl, json: JSONObject): Request =
        authed(url).post(json.toString().toRequestBody(jsonType)).build()

    private fun call(request: Request, c: OkHttpClient = client): JSONObject? = runCatching {
        c.newCall(request).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (body.isBlank()) null else JSONObject(body)
        }
    }.getOrNull()

    // org.json (Android) renvoie la chaîne "null" pour optString sur une valeur JSON null.
    private fun JSONObject.str(name: String): String? = if (isNull(name)) null else optString(name)

    private fun parseMessage(o: JSONObject) = ChatMessage(
        id = o.optLong("id"),
        direction = o.optString("direction", "in"),
        text = o.str("text").orEmpty(),
        type = o.str("type") ?: "text",
        mediaName = o.str("mediaName"),
        replyPreview = o.str("replyPreview"),
        replyWho = o.str("replyWho"),
        createdAt = o.optLong("createdAt"),
        replyToId = if (o.isNull("replyToId")) null else o.optLong("replyToId").takeIf { it > 0 },
        reaction = o.str("reaction"),
        fwdWho = o.str("fwdWho"),
        tmdbId = if (o.isNull("tmdbId")) null else o.optInt("tmdbId").takeIf { it > 0 }
    )

    /** Sans [since] : les 100 derniers messages. Avec : tout ce qui suit cet id. `null` = échec réseau. */
    suspend fun chatHistory(since: Long = 0): List<ChatMessage>? = withContext(Dispatchers.IO) {
        val u = if (since > 0) url("chat_history", "since" to since.toString()) else url("chat_history")
        val j = call(authed(u).get().build()) ?: return@withContext null
        if (!j.optBoolean("ok")) return@withContext null
        val arr = j.optJSONArray("messages") ?: return@withContext emptyList<ChatMessage>()
        (0 until arr.length()).map { parseMessage(arr.getJSONObject(it)) }
    }

    suspend fun chatSend(text: String, replyToId: Long = 0): Boolean = withContext(Dispatchers.IO) {
        val body = JSONObject().put("text", text)
        if (replyToId > 0) body.put("replyToId", replyToId)
        call(postJson(url("chat_send"), body))?.optBoolean("ok") == true
    }

    suspend fun chatEdit(id: Long, text: String): Boolean = withContext(Dispatchers.IO) {
        call(postJson(url("chat_edit"), JSONObject().put("id", id).put("text", text)))?.optBoolean("ok") == true
    }

    /** Une seule requête pour N ids : des suppressions concurrentes se disputent le verrou SQLite. */
    suspend fun chatDelete(ids: List<Long>): Boolean = withContext(Dispatchers.IO) {
        call(postJson(url("chat_delete"), JSONObject().put("ids", JSONArray(ids))))?.optBoolean("ok") == true
    }

    suspend fun chatClear(): Boolean = withContext(Dispatchers.IO) {
        call(postJson(url("chat_clear"), JSONObject()))?.optBoolean("ok") == true
    }

    suspend fun chatSendMedia(file: File, mime: String, name: String, voice: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            val type = runCatching { mime.toMediaType() }.getOrDefault("application/octet-stream".toMediaType())
            val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file", name, file.asRequestBody(type))
            if (voice) builder.addFormDataPart("kind", "voice")
            val slow = client.newBuilder()
                .callTimeout(90, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .writeTimeout(90, TimeUnit.SECONDS)
                .build()
            call(authed(url("chat_send_media")).post(builder.build()).build(), slow)?.optBoolean("ok") == true
        }

    /** URL du fichier joint (vocal / image / document). Jeton en query : ni <img> ni MediaPlayer ne posent d'en-tête. */
    fun chatMediaUrl(id: Long): String =
        url("chat_media_file", "id" to id.toString(), "token" to session.getToken()).toString()

    /** Réaction emoji (bascule : même emoji = retrait). */
    suspend fun chatReact(id: Long, emoji: String): Boolean = withContext(Dispatchers.IO) {
        call(postJson(url("chat_react"), JSONObject().put("id", id).put("emoji", emoji)))?.optBoolean("ok") == true
    }

    /** Transfère un message (copie « Transféré de … » en bas du fil). */
    suspend fun chatForward(id: Long): Boolean = withContext(Dispatchers.IO) {
        call(postJson(url("chat_forward"), JSONObject().put("id", id)))?.optBoolean("ok") == true
    }

    /** Partage un film TMDb dans le tchat (bulle + Telegram). */
    suspend fun chatSendFilm(tmdbId: Int, title: String, year: String, poster: String?): Boolean =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("tmdbId", tmdbId).put("title", title).put("year", year).put("poster", poster ?: "")
            call(postJson(url("chat_send_film"), body))?.optBoolean("ok") == true
        }

    private fun seenKey() = "seen_" + session.getUsername()

    /** Dernier id de message vu par ce compte sur cet appareil ; -1 = jamais initialisé. */
    fun chatLastSeen(): Long = prefs.getLong(seenKey(), -1L)

    fun chatMarkSeen(id: Long) {
        if (id > chatLastSeen()) prefs.edit().putLong(seenKey(), id).apply()
    }
}
