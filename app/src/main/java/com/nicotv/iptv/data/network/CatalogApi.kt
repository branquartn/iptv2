package com.nicotv.iptv.data.network

import com.google.gson.annotations.SerializedName
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

data class LibraryResponse(
    val ok: Boolean = false,
    val movies: List<LibraryMovie> = emptyList(),
    val series: List<LibrarySeries> = emptyList(),
    val error: String? = null
)

data class LibraryMovie(
    @SerializedName("id") val tmdbId: Int = 0,
    @SerializedName("catalog_key") val catalogKey: String = "",
    @SerializedName("release_key") val releaseKey: String = "",
    val title: String = "",
    val year: String = "",
    val strm: String = "",
    val url: String = ""
)

data class LibrarySeries(
    val name: String = "",
    val seasons: List<LibrarySeason> = emptyList()
)

data class LibrarySeason(
    val season: Int = 0,
    val episodes: List<LibraryEpisode> = emptyList()
)

data class LibraryEpisode(
    val episode: Int = 0,
    val season: Int = 0,
    val file: String = ""
)

data class PlaybackProgress(
    @SerializedName("t") val positionSeconds: Long = 0,
    @SerializedName("d") val durationSeconds: Long = 0,
    @SerializedName("at") val updatedAt: Long = 0
)

data class SeenState(
    val mkeys: List<String>? = null,
    val mids: List<Long>? = null,
    val snames: List<String>? = null,
    val episodes: List<String>? = null
)

data class StateResponse(
    val ok: Boolean = false,
    val favorites: List<String>? = null,
    val progress: Map<String, PlaybackProgress>? = null,
    val seen: SeenState? = null,
    // Épisodes regardés jusqu'au bout (clé fileKey "Série/Fichier.mkv").
    // Canal dédié, distinct de seen.episodes (réservé à la détection « NOUVEAU » côté PWA).
    val epseen: List<String>? = null,
    val tvids: Map<String, Int>? = null,
    // Films regardés jusqu'au bout (badge « ✓ Vu », clés "m<tmdbId>").
    // Distinct de seen.mkeys (« ouvert au moins une fois », détection NOUVEAU).
    val mfinished: List<String>? = null,
    val error: String? = null
)

data class StateUpdateRequest(
    val favorites: List<String>? = null,
    val progress: Map<String, PlaybackProgress>? = null,
    val seen: SeenState? = null,
    val epseen: List<String>? = null,
    val tvids: Map<String, Int>? = null,
    val mfinished: List<String>? = null
)

data class OkResponse(val ok: Boolean = false)

// Sessions actives DU MÊME COMPTE sur d'autres appareils (« en cours sur <device> »,
// écran d'accueil mobile) — cf. api/iptv.php case 'presence_list' (scopé au uid du
// jeton, exclut l'appareil appelant). Pendant mobile de admin.nicotv.ovh « Qui
// regarde quoi », version restreinte à ses propres appareils.
data class PresenceListResponse(
    val ok: Boolean = false,
    val presence: List<PresenceItem> = emptyList()
)

data class PresenceItem(
    val deviceId: String = "",
    val title: String = "",
    val kind: String = "",
    val startedAt: Long = 0,
    val position: Long = 0,
    val duration: Long = 0,
    val playing: Boolean = false,
    val device: String = ""
)

/** Catalogue DB NicoTV via api.nicotv.ovh. */
interface CatalogApi {
    @GET("index.php")
    suspend fun library(
        @Header("Authorization") bearer: String,
        @Query("action") action: String = "library"
    ): LibraryResponse

    @GET("index.php")
    suspend fun state(
        @Header("Authorization") bearer: String,
        @Query("action") action: String = "state"
    ): StateResponse

    @POST("index.php")
    suspend fun updateState(
        @Header("Authorization") bearer: String,
        @Query("action") action: String = "state",
        @Body body: StateUpdateRequest
    ): StateResponse

    // Présence temps réel (admin.nicotv.ovh « qui regarde quoi »), cf. api/iptv.php
    // record_presence() : mêmes paramètres type/id/f que le film/épisode joué.
    @GET("index.php")
    suspend fun heartbeat(
        @Header("Authorization") bearer: String,
        @Query("action") action: String = "heartbeat",
        @Query("type") type: String? = null,
        @Query("id") id: Int? = null,
        @Query("f") f: String? = null,
        @Query("pos") positionSeconds: Long? = null,
        @Query("dur") durationSeconds: Long? = null,
        @Query("playing") playing: Int? = null,
        // Heartbeat d'app (pas de lecteur ouvert) : ni type/id/f, juste l'écran courant
        // (« Accueil », « Films », « Fiche : <titre> »…) — cf. presence_title_from_request()
        // côté serveur et son pendant PWA (sendAppHeartbeat() dans app.js).
        @Query("screen") screen: String? = null
    ): OkResponse

    @GET("index.php")
    suspend fun presenceStop(
        @Header("Authorization") bearer: String,
        @Query("action") action: String = "presence_stop"
    ): OkResponse

    @GET("index.php")
    suspend fun presenceList(
        @Header("Authorization") bearer: String,
        @Query("action") action: String = "presence_list"
    ): PresenceListResponse

    @GET("index.php")
    suspend fun remotePause(
        @Header("Authorization") bearer: String,
        @Query("action") action: String = "remote_pause",
        @Query("device_id") deviceId: String
    ): OkResponse

    @GET("index.php")
    suspend fun remoteResumeOther(
        @Header("Authorization") bearer: String,
        @Query("action") action: String = "remote_resume",
        @Query("device_id") deviceId: String
    ): OkResponse

    // Télécommande complète (icône à côté du pseudo, RemoteControlActivity) : navigation
    // menus (nav_up/down/left/right/select/back, D-pad) + contrôles lecteur (seek/volume/
    // mute/unmute/audio/subtitle) sur une autre session du même compte, cf. api/iptv.php
    // case 'remote_cmd' et son pendant PWA (sendRemoteCmd() dans app.js).
    @GET("index.php")
    suspend fun remoteCmd(
        @Header("Authorization") bearer: String,
        @Query("action") action: String = "remote_cmd",
        @Query("device_id") deviceId: String,
        @Query("cmd") cmd: String,
        @Query("value") value: String? = null
    ): OkResponse

    // Répond à remote_cmd cmd=get_tracks : publie les pistes audio/sous-titres
    // courantes pour que la télécommande construise un vrai menu (pas un cycle à
    // l'aveugle), cf. api/iptv.php case 'remote_report_tracks' et son pendant PWA
    // (reportTracks() dans app.js). Corps JSON (read_json_body() côté serveur).
    @POST("index.php")
    suspend fun remoteReportTracks(
        @Header("Authorization") bearer: String,
        @Query("action") action: String = "remote_report_tracks",
        @Body body: RemoteTracksReport
    ): OkResponse
}

data class RemoteTrackInfo(val i: Int, val label: String)
data class RemoteTracksReport(
    @SerializedName("device_id") val deviceId: String,
    val audio: List<RemoteTrackInfo>,
    val subtitle: List<RemoteTrackInfo>,
    @SerializedName("curAudio") val curAudio: Int?,
    @SerializedName("curSubtitle") val curSubtitle: Int?
)
