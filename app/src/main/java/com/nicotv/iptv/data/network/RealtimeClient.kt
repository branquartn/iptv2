package com.nicotv.iptv.data.network

import com.nicotv.iptv.AppConfig
import com.nicotv.iptv.util.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import android.util.Log
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Client du bus temps réel (hub WebSocket ws.nicotv.ovh).
 *
 * Se connecte avec le jeton HMAC de session (même jeton que l'API) ; le serveur abonne
 * automatiquement la connexion à l'utilisateur. À la réception d'un évènement de catalogue
 * (« iptv:add » = titre rangé, « iptv:lib » = suppression / transfert / correction TMDb),
 * il déclenche [onCatalogChanged] — typiquement une re-synchro Catalogue → Room qui rafraîchit
 * l'UI via la LiveData de Room, sans intervention de l'utilisateur.
 *
 * Reconnexion automatique avec backoff ; les pings/pongs WebSocket sont gérés par OkHttp
 * (pingInterval réglé sur le client fourni). Démarré/arrêté selon le premier plan de l'app.
 */
class RealtimeClient(
    private val client: OkHttpClient,
    private val session: SessionManager,
    private val scope: CoroutineScope,
    private val onRemoteCommand: (JSONObject) -> Unit = {},
    private val onCatalogChanged: () -> Unit,
) {
    private val wantOpen = AtomicBoolean(false)
    @Volatile private var ws: WebSocket? = null
    private var backoffMs = 1_000L
    private var reconnectJob: Job? = null

    /** Ouvre (ou ré-ouvre) la connexion. Idempotent. */
    fun start() {
        val alreadyWanted = wantOpen.getAndSet(true)
        if (alreadyWanted && ws != null) return // déjà connectée (ou en cours)
        reconnectJob?.cancel()
        connect()
    }

    /** Ferme la connexion et stoppe les reconnexions. */
    fun stop() {
        wantOpen.set(false)
        reconnectJob?.cancel()
        ws?.close(1000, "stop")
        ws = null
    }

    private fun connect() {
        if (!wantOpen.get() || ws != null) return
        val token = session.getToken()
        if (token.isBlank()) {
            Log.w("RealtimeClient", "connect() annulé : jeton vide")
            return
        }
        val url = AppConfig.Realtime.WS_URL + "?token=" + URLEncoder.encode(token, "UTF-8")
        ws = client.newWebSocket(Request.Builder().url(url).build(), listener)
    }

    private fun scheduleReconnect() {
        if (!wantOpen.get()) return
        reconnectJob?.cancel()
        val wait = backoffMs
        backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
        reconnectJob = scope.launch {
            delay(wait)
            connect()
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d("RealtimeClient", "WS connectée")
            backoffMs = 1_000L
            // Rattrapage : les évènements émis pendant la déconnexion (app en
            // arrière-plan, coupure réseau) sont perdus → on relance une synchro
            // (débouncée côté application) à chaque (re)connexion.
            runCatching { onCatalogChanged() }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val event = runCatching { JSONObject(text).optString("event") }.getOrNull()
            if (event == null) {
                Log.w("RealtimeClient", "Message WS illisible, ignoré")
                return
            }
            Log.d("RealtimeClient", "Évènement WS reçu: $event")
            when (event) {
                "iptv:add", "iptv:lib", "state" -> runCatching { onCatalogChanged() }
                "remote" -> runCatching {
                    val data = JSONObject(text).optJSONObject("data") ?: JSONObject()
                    onRemoteCommand(data)
                }
            }
        }

        // Les callbacks ne touchent l'état que s'ils concernent la socket COURANTE :
        // ceux d'une ancienne socket (stop/start rapprochés) ne doivent ni écraser
        // la référence, ni déclencher une reconnexion en doublon.
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w("RealtimeClient", "WS échec (code ${response?.code}) : ${t.message}")
            if (ws === webSocket) {
                ws = null
                scheduleReconnect()
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d("RealtimeClient", "WS fermée : $code $reason")
            if (ws === webSocket) {
                ws = null
                if (wantOpen.get()) scheduleReconnect()
            }
        }
    }
}
