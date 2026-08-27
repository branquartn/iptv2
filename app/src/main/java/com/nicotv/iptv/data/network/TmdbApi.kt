package com.nicotv.iptv.data.network

import com.nicotv.iptv.AppConfig
import com.nicotv.iptv.data.network.model.TmdbCombinedCredits
import com.nicotv.iptv.data.network.model.TmdbCredits
import com.nicotv.iptv.data.network.model.TmdbMovieDetail
import com.nicotv.iptv.data.network.model.TmdbMultiSearchResponse
import com.nicotv.iptv.data.network.model.TmdbPerson
import com.nicotv.iptv.data.network.model.TmdbSearchResponse
import com.nicotv.iptv.data.network.model.TmdbSeasonDetail
import com.nicotv.iptv.data.network.model.TmdbTvDetail
import com.nicotv.iptv.data.network.model.TmdbTvSearchResponse
import com.nicotv.iptv.data.network.model.TmdbVideosResponse
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

// api_key et language sont injectés par TmdbAuthInterceptor (voir IptvApplication),
// pas besoin de les déclarer sur chaque endpoint.
interface TmdbApi {

    @GET("search/movie")
    suspend fun searchMovie(@Query("query") query: String): TmdbSearchResponse

    @GET("search/multi")
    suspend fun searchMulti(@Query("query") query: String): TmdbMultiSearchResponse

    @GET("movie/{id}")
    suspend fun getMovieDetail(@Path("id") id: Int): TmdbMovieDetail

    @GET("search/tv")
    suspend fun searchTv(@Query("query") query: String): TmdbTvSearchResponse

    @GET("tv/{id}")
    suspend fun getTvDetail(@Path("id") id: Int): TmdbTvDetail

    @GET("tv/{id}/season/{season}")
    suspend fun getTvSeason(
        @Path("id") id: Int,
        @Path("season") season: Int
    ): TmdbSeasonDetail

    // Casting, réalisateur, films similaires, bande-annonce, fiche/filmographie acteur
    // (portage des fonctionnalités ajoutées côté PWA).
    @GET("movie/{id}/credits")
    suspend fun getMovieCredits(@Path("id") id: Int): TmdbCredits

    @GET("movie/{id}/recommendations")
    suspend fun getMovieRecommendations(@Path("id") id: Int): TmdbSearchResponse

    @GET("movie/{id}/videos")
    suspend fun getMovieVideos(@Path("id") id: Int): TmdbVideosResponse

    // Filmographie acteur : peut inclure des séries → bande-annonce série aussi.
    @GET("tv/{id}/videos")
    suspend fun getTvVideos(@Path("id") id: Int): TmdbVideosResponse

    @GET("person/{id}")
    suspend fun getPersonDetail(@Path("id") id: Int): TmdbPerson

    @GET("person/{id}/combined_credits")
    suspend fun getPersonCombinedCredits(@Path("id") id: Int): TmdbCombinedCredits
}
