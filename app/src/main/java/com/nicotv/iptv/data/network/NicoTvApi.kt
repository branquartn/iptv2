package com.nicotv.iptv.data.network

import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

/**
 * Demande d'ajout d'un titre repéré via la recherche TMDb.
 * Le login permet au serveur de savoir dans quel répertoire ranger le contenu.
 */
data class AddMediaRequest(
    val username: String,
    val tmdb_id: Int,
    val media_type: String,   // "movie" ou "tv"
    val title: String,
    val year: String
)

data class AddMediaResponse(val ok: Boolean, val message: String? = null, val error: String? = null)

/** API externe (api.nicotv.ovh) qui orchestre l'ajout des films/séries au catalogue. */
interface NicoTvApi {

    @POST("add")
    suspend fun addMedia(
        @Header("Authorization") bearer: String,
        @Body body: AddMediaRequest
    ): AddMediaResponse
}
