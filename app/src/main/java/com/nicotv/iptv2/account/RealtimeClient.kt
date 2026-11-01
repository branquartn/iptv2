package com.nicotv.iptv2.account

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bus temps réel (hub WebSocket ws.nicotv.ovh), comme l'app iptv. Connexion avec le jeton de session ; le serveur
 * abonne la connexion à l'utilisateur. Évènements gérés :
 * - « iptv:add » / « iptv:lib » / « state » : la bibliothèque du serveur a changé (titre ajouté, supprimé…) ;
 * - « xtream » : l'admin a modifié les codes Xtream de l'utilisateur ;
 * - « chat:* » : nouveau message / modification / réaction du tchat.
 * Reconnexion automatique avec attente croissante. Démarré au premier plan, arrêté en arrière-plan.
 */
class RealtimeClient(
    private val client: OkHttpClient,
    private val token: () -> String,
    private val scope: CoroutineScope,
    private val onLibraryChanged: () -> Unit,
    private val onXtreamChanged: () -> Unit,
    private val onChatEvent: () -> Unit
) {
    private val wantOpen = AtomicBoolean(false)
    @Volatile private var ws: WebSocket? = null
    private var backoffMs = 1_000L
    private var reconnectJob: Job? = null

    /** Ouvre (ou ré-ouvre) la connexion. Idempotent. */
    fun start() {
        val already = wantOpen.getAndSet(true)
        if (already && ws != null) return
        reconnectJob?.cancel()
        connect()
    }

    fun stop() {
        wantOpen.set(false)
        reconnectJob?.cancel()
        ws?.close(1000, "stop")
        ws = null
    }

    private fun connect() {
        if (!wantOpen.get() || ws != null) return
        val t = token()
        if (t.isBlank()) return
        val url = "wss://ws.nicotv.ovh/?token=" + URLEncoder.encode(t, "UTF-8")
        ws = client.newWebSocket(Request.Builder().url(url).build(), listener)
    }

    private fun scheduleReconnect() {
        if (!wantOpen.get()) return
        reconnectJob?.cancel()
        val wait = backoffMs
        backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
        reconnectJob = scope.launch { delay(wait); connect() }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            backoffMs = 1_000L
            // Rattrapage : les évènements émis pendant une coupure sont perdus → on resynchronise à chaque (re)connexion.
            runCatching { onLibraryChanged() }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val event = runCatching { JSONObject(text).optString("event") }.getOrNull() ?: return
            when {
                event == "iptv:add" || event == "iptv:lib" || event == "state" -> runCatching { onLibraryChanged() }
                event == "xtream" -> runCatching { onXtreamChanged() }
                event.startsWith("chat:") -> runCatching { onChatEvent() }
            }
        }

        // Callbacks d'une ancienne socket ignorés : seule la socket COURANTE agit.
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w("RealtimeClient", "WS échec : ${t.message}")
            if (ws === webSocket) { ws = null; scheduleReconnect() }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (ws === webSocket) { ws = null; if (wantOpen.get()) scheduleReconnect() }
        }
    }
}
