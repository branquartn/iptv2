package com.nicotv.iptv2.account

import android.content.Context
import android.os.Build
import com.nicotv.iptv2.AppConfig
import com.nicotv.iptv2.util.DeviceId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Présence de l'utilisateur pour l'admin (« qui est connecté, qui regarde quoi ») : mêmes routes `heartbeat` /
 * `presence_stop` que l'app iptv. Deux niveaux : « en ligne » (écran courant) et « regarde » (titre, position, état).
 * Tirer-et-oublier : une erreur réseau ne gêne jamais l'appli ni la lecture.
 */
class PresenceReporter(
    private val context: Context,
    private val client: OkHttpClient,
    private val session: SessionManager,
    private val scope: CoroutineScope
) {
    private val endpoint = AppConfig.Account.BASE_URL + "index.php"
    private val deviceId = DeviceId.get(context)
    private val deviceLabel = "iptv2 · ${Build.MANUFACTURER} ${Build.MODEL}".trim()

    private fun send(action: String, params: Map<String, String>) {
        if (!session.isLoggedIn()) return
        scope.launch(Dispatchers.IO) {
            runCatching {
                val b = endpoint.toHttpUrl().newBuilder().addQueryParameter("action", action).addQueryParameter("app", "iptv2")
                params.forEach { (k, v) -> b.addQueryParameter(k, v) }
                val req = Request.Builder().url(b.build())
                    .header("Authorization", session.bearer())
                    .header("X-Device-Id", deviceId)
                    .header("X-Device", deviceLabel)
                    .get().build()
                client.newCall(req).execute().close()
            }
        }
    }

    /** « En ligne » : l'utilisateur est dans l'appli, sur cet écran. */
    fun online(screen: String) = send("heartbeat", mapOf("screen" to screen))

    /** « Regarde » : titre, source (« Chaîne · Xtream Salon », « Film · Mon serveur »), position et état de lecture. */
    fun watching(title: String, source: String, posMs: Long, durMs: Long, playing: Boolean) = send(
        "heartbeat", mapOf(
            "watching" to "1", "title" to title, "source" to source,
            "pos" to (posMs / 1000).coerceAtLeast(0).toString(), "dur" to (durMs / 1000).coerceAtLeast(0).toString(),
            "playing" to if (playing) "1" else "0", "fs" to "1"
        )
    )

    /** L'appli passe en arrière-plan / se ferme : l'utilisateur n'est plus connecté. */
    fun stop() = send("presence_stop", emptyMap())
}
