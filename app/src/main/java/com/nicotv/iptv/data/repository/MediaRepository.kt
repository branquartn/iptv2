package com.nicotv.iptv.data.repository

import android.util.Log
import com.nicotv.iptv.AppConfig
import com.nicotv.iptv.data.database.dao.EpisodeDao
import com.nicotv.iptv.data.database.dao.FavoriteDao
import com.nicotv.iptv.data.database.dao.MovieDao
import com.nicotv.iptv.data.database.dao.NewDetectEpisodeDao
import com.nicotv.iptv.data.database.dao.SeenEpisodeDao
import com.nicotv.iptv.data.database.dao.SeenMovieDao
import com.nicotv.iptv.data.database.dao.SeenSeriesDao
import com.nicotv.iptv.data.database.dao.SeriesDao
import com.nicotv.iptv.data.database.dao.SeriesFavoriteDao
import com.nicotv.iptv.data.database.dao.WatchHistoryDao
import com.nicotv.iptv.data.database.entity.EpisodeEntity
import com.nicotv.iptv.data.database.entity.FavoriteEntity
import com.nicotv.iptv.data.database.entity.MovieEntity
import com.nicotv.iptv.data.database.entity.NewDetectEpisodeEntity
import com.nicotv.iptv.data.database.entity.SeenEpisodeEntity
import com.nicotv.iptv.data.database.entity.SeenMovieEntity
import com.nicotv.iptv.data.database.entity.SeenSeriesEntity
import com.nicotv.iptv.data.database.entity.SeriesEntity
import com.nicotv.iptv.data.database.entity.SeriesFavoriteEntity
import com.nicotv.iptv.data.database.entity.WatchHistoryEntity
import com.nicotv.iptv.data.network.AddMediaRequest
import com.nicotv.iptv.data.network.CatalogApi
import com.nicotv.iptv.data.network.LibraryMovie
import com.nicotv.iptv.data.network.LibrarySeries
import com.nicotv.iptv.data.network.NicoTvApi
import com.nicotv.iptv.data.network.PlaybackProgress
import com.nicotv.iptv.data.network.SeenState
import com.nicotv.iptv.data.network.StateUpdateRequest
import com.nicotv.iptv.data.network.TmdbApi
import com.nicotv.iptv.data.network.model.TmdbCredits
import com.nicotv.iptv.data.network.model.TmdbMovieResult
import com.nicotv.iptv.data.network.model.TmdbMultiResult
import com.nicotv.iptv.data.network.model.TmdbPerson
import com.nicotv.iptv.domain.model.EpisodeProgress
import com.nicotv.iptv.domain.model.Movie
import com.nicotv.iptv.domain.model.OpenTarget
import com.nicotv.iptv.domain.model.SimilarWork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.IOException
import java.net.URLEncoder

class MediaRepository(
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val episodeDao: EpisodeDao,
    private val favoriteDao: FavoriteDao,
    private val seriesFavoriteDao: SeriesFavoriteDao,
    private val watchHistoryDao: WatchHistoryDao,
    private val seenEpisodeDao: SeenEpisodeDao,
    private val seenMovieDao: SeenMovieDao,
    private val seenSeriesDao: SeenSeriesDao,
    private val newDetectEpisodeDao: NewDetectEpisodeDao,
    private val tmdbApi: TmdbApi,
    private val catalogApi: CatalogApi,
    private val nicoTvApi: NicoTvApi,
    private val finishedMoviesCache: com.nicotv.iptv.util.FinishedMoviesCache
) {

    fun getMoviesWithFavorites(username: String): Flow<List<Movie>> =
        combine(
            movieDao.getAllMovies(username),
            favoriteDao.getAllFavorites(),
            watchHistoryDao.getAllHistory(),
            seenMovieDao.getAllSeenKeysFlow(),
            finishedMoviesCache.keysFlow
        ) { movies, favs, history, seenKeys, finishedKeys ->
            val favIds = favs.map { it.movieId }.toSet()
            val progressById = history.associate { it.movieId to it.progressPercent }
            val progressByKey = history.associate { it.historyKey to it.progressPercent }
            val seenSet = seenKeys.toSet()
            // Comme isNewItem() côté PWA (garde app.js:478) : si AUCUN état « vu »
            // n'est connu (profil neuf, réinstall avant la 1re synchro), rien n'est
            // NOUVEAU — sinon toute la médiathèque s'allume d'un coup (+N pastille).
            val hasSeenState = seenSet.isNotEmpty()
            movies.map {
                val stableKey = if (it.tmdbId > 0) movieKey(it.tmdbId) else ""
                // Présence dans l'historique = commencé non terminé (l'item est retiré à la fin).
                // → reprise affichée quel que soit le %, à partir de 5s (MIN_RESUME_MS) et jusqu'à la dernière.
                val pct = progressById[it.id] ?: progressByKey[stableKey]
                val inProgress = pct != null
                val isSeen = stableKey in seenSet || "movie:${it.id}" in seenSet
                it.toDomain(
                    isFavorite = it.id in favIds,
                    // Comme isNewItem() côté PWA : NOUVEAU tant que non ouvert, sans
                    // expiration dans le temps.
                    isNew = hasSeenState && !inProgress && !isSeen,
                    watchProgress = if (inProgress) pct!!.coerceIn(1, 99) else 0,
                    isSeen = isSeen,
                    isFinished = stableKey.ifBlank { "movie:${it.id}" } in finishedKeys
                )
            }
        }

    /** Nombre de films « nouveaux » (badge accueil, comme la pastille de la PWA).
     * Réutilise EXACTEMENT le calcul du ruban NOUVEAU (getMoviesWithFavorites) →
     * toujours cohérent avec ce qui s'affiche dans la grille Films. */
    fun getNewMoviesCount(username: String): Flow<Int> =
        getMoviesWithFavorites(username).map { movies -> movies.count { it.isNew } }

    /** Même chose pour les séries (getSeries() calcule désormais isNew aussi). */
    fun getNewSeriesCount(username: String): Flow<Int> =
        getSeries(username).map { series -> series.count { it.isNew } }

    fun getFavoriteMovies(username: String): Flow<List<Movie>> =
        combine(movieDao.getAllMovies(username), favoriteDao.getAllFavorites()) { movies, favs ->
            val favIds = favs.map { it.movieId }.toSet()
            movies.filter { it.id in favIds }.map { it.toDomain(isFavorite = true) }
        }

    /** Favoris unifiés (films + séries) pour l'écran Favoris. */
    fun getFavorites(username: String): Flow<List<Movie>> =
        combine(getFavoriteMovies(username), getSeries(username)) { movies, series ->
            movies + series.filter { it.isFavorite }
        }

    /** Nombre total de favoris (films + séries) pour le badge d'accueil. */
    fun getFavoritesCount(): Flow<Int> =
        combine(favoriteDao.getAllFavorites(), seriesFavoriteDao.getAllFavoritesFlow()) { m, s ->
            m.size + s.size
        }

    fun getWatchHistory(): Flow<List<WatchHistoryEntity>> = watchHistoryDao.getRecentHistory()

    /** Retourne l'historique unifié (films + épisodes) sous forme d'objets Movie pour l'UI. */
    fun getUnifiedHistory(username: String): Flow<List<Movie>> =
        combine(
            getMoviesWithFavorites(username),
            seriesDao.getAllSeries(username),
            episodeDao.getAllEpisodesFlow(),
            watchHistoryDao.getRecentHistory()
        ) { movies, series, episodes, history ->
            val moviesById = movies.associateBy { it.id }
            val seriesById = series.associateBy { it.id }
            val episodesByWatchKey = episodes.associateBy { it.watchKey }

            history.mapNotNull { h ->
                if (h.historyKey.startsWith("m")) {
                    moviesById[h.movieId]?.copy(watchProgress = h.progressPercent.coerceIn(1, 99))
                } else if (h.historyKey.startsWith("e:")) {
                    val ep = episodesByWatchKey[h.movieId] ?: return@mapNotNull null
                    val s = seriesById[ep.seriesId] ?: return@mapNotNull null
                    Movie(
                        id = ep.watchKey,
                        title = "${s.title} — ${ep.episodeTitle}",
                        streamUrl = ep.streamUrl,
                        posterUrl = s.posterUrl,
                        backdropUrl = s.backdropUrl,
                        releaseYear = s.releaseYear,
                        rating = s.rating,
                        watchProgress = h.progressPercent.coerceIn(1, 99),
                        type = Movie.Type.EPISODE,
                        episodeKey = h.historyKey,
                        seriesId = ep.seriesId,
                        seriesTitle = s.title
                    )
                } else null
            }
        }

    suspend fun searchMovies(query: String, username: String): List<Movie> = withContext(Dispatchers.IO) {
        val favIds = favoriteDao.getFavoriteIds().toSet()
        movieDao.searchMovies(query, username).map { it.toDomain(isFavorite = it.id in favIds) }
    }

    suspend fun getMovieById(id: Long): Movie? = withContext(Dispatchers.IO) {
        val favIds = favoriteDao.getFavoriteIds().toSet()
        movieDao.getMovieById(id)?.toDomain(isFavorite = id in favIds)
    }

    /** Marque un film comme « vu » (fiche ouverte) : retire le badge NOUVEAU —
     * comme markItemSeen() côté PWA (déclenché à l'ouverture, pas seulement en
     * fin de lecture). Avant ce fix, movieDao.markSeen() ne mettait à jour QUE
     * addedAt (sentinelle locale, zérotée) : jamais poussé au serveur, jamais lu
     * par isNew (qui dépend de seenMovieDao) → ouvrir la fiche ne retirait rien
     * de synchronisé avec la PWA. Même chemin que la fin de lecture
     * (saveWatchPosition) : seenMovieDao (la table lue par isNew) + push serveur
     * immédiat (seen.mkeys, canal partagé avec la PWA). */
    suspend fun markMovieSeen(id: Long, username: String, bearer: String) = withContext(Dispatchers.IO) {
        movieDao.markSeen(id)
        val movie = movieDao.getMovieById(id) ?: return@withContext
        val seenKey = if (movie.tmdbId > 0) movieKey(movie.tmdbId) else "movie:${movie.id}"
        seenMovieDao.markSeen(SeenMovieEntity(seenKey))
        runCatching { pushSeenState(username, bearer) }
    }

    /** Même chose pour une série (fiche ouverte) : retire le badge NOUVEAU —
     * comme markItemSeen() côté PWA. Marque le nom (snames) ET tous les
     * épisodes CONNUS à cet instant (seen.episodes) : si de nouveaux épisodes
     * arrivent ensuite, ils ne seront pas dans cette liste → la série redevient
     * NOUVEAU (exactement le comportement isNewItem() côté PWA). */
    suspend fun markSeriesSeen(seriesId: Long, username: String, bearer: String) = withContext(Dispatchers.IO) {
        val series = seriesDao.getById(seriesId) ?: return@withContext
        seenSeriesDao.markSeen(SeenSeriesEntity(series.title))
        val fileKeys = episodeDao.getEpisodesForSeries(seriesId).map { it.fileKey }
        if (fileKeys.isNotEmpty()) {
            newDetectEpisodeDao.markSeen(fileKeys.map { NewDetectEpisodeEntity(it) })
        }
        runCatching { pushSeenState(username, bearer) }
    }

    // ---- Séries ----

    fun getSeries(username: String): Flow<List<Movie>> {
        // Fusionné à part : combine() n'a pas de surcharge à 6 flux nommés.
        val seenSeriesInfo = combine(
            seenSeriesDao.getAllNamesFlow(),
            newDetectEpisodeDao.getAllKeysFlow()
        ) { names, keys -> names.toSet() to keys.toSet() }

        return combine(
            seriesDao.getAllSeries(username),
            seriesFavoriteDao.getAllFavoritesFlow(),
            episodeDao.getAllEpisodesFlow(),
            watchHistoryDao.getAllHistory(),
            seenSeriesInfo
        ) { series, favs, episodes, history, (seenNames, seenEpKeys) ->
            val favIds = favs.map { it.seriesId }.toSet()
            // Progression par épisode (clé e:…) : watchKey → %.
            val epProgress = history
                .filter { it.historyKey.startsWith("e:") }
                .associate { it.movieId to it.progressPercent }
            val episodesBySeries = episodes.groupBy { it.seriesId }
            series.map { s ->
                val seriesEpisodes = episodesBySeries[s.id].orEmpty()
                // Série « en cours » = au moins un épisode commencé (présent dans l'historique,
                // quel que soit le %) ; barre de l'affiche = progression la plus avancée.
                val prog = seriesEpisodes
                    .mapNotNull { ep -> epProgress[ep.watchKey] }
                    .maxOrNull()?.coerceIn(1, 99) ?: 0
                // Comme isNewItem() côté PWA : NOUVEAU si le nom n'a jamais été
                // ouvert, OU si de nouveaux épisodes sont arrivés depuis la
                // dernière ouverture (pas dans seen.episodes). Garde profil neuf
                // (aucun état « vu » connu → rien de nouveau), comme les films.
                val isNew = (seenNames.isNotEmpty() || seenEpKeys.isNotEmpty()) &&
                    (s.title !in seenNames || seriesEpisodes.any { it.fileKey !in seenEpKeys })
                s.toDomain().copy(
                    isFavorite = s.id in favIds,
                    watchProgress = prog,
                    isNew = isNew,
                    type = Movie.Type.SERIES
                )
            }
        }
    }

    suspend fun getEpisodesForSeries(seriesId: Long) =
        episodeDao.getEpisodesForSeries(seriesId)

    suspend fun getEpisodeByWatchKey(watchKey: Long) =
        episodeDao.getEpisodeByWatchKey(watchKey)

    suspend fun getSeriesById(id: Long) = seriesDao.getById(id)

    suspend fun isSeriesFavorite(seriesId: Long): Boolean =
        withContext(Dispatchers.IO) { seriesFavoriteDao.isFavorite(seriesId) }

    /** Bascule le favori série et retourne le nouvel état (true = ajouté). */
    suspend fun toggleSeriesFavorite(seriesId: Long, username: String, bearer: String): Boolean = withContext(Dispatchers.IO) {
        val result = if (seriesFavoriteDao.isFavorite(seriesId)) {
            seriesFavoriteDao.remove(seriesId)
            false
        } else {
            seriesFavoriteDao.add(SeriesFavoriteEntity(seriesId))
            true
        }
        runCatching { pushFavoriteState(username, bearer) }
        result
    }

    /** Relie une série existante à une nouvelle fiche TMDb. */
    suspend fun relinkSeriesToTmdb(seriesId: Long, result: TmdbMultiResult, username: String, bearer: String) = withContext(Dispatchers.IO) {
        val entity = seriesDao.getById(seriesId) ?: return@withContext
        val updated = if (result.isMovie) {
            val detail = runCatching { tmdbApi.getMovieDetail(result.id) }.getOrNull()
            entity.copy(
                posterUrl = result.posterPath?.let { AppConfig.Tmdb.IMAGE_BASE_W500 + it } ?: entity.posterUrl,
                backdropUrl = detail?.backdropPath?.let { AppConfig.Tmdb.IMAGE_BASE_ORIGINAL + it } ?: entity.backdropUrl,
                overview = detail?.overview?.ifBlank { entity.overview } ?: entity.overview,
                releaseYear = (detail?.releaseDate ?: result.releaseDate ?: "").take(4),
                rating = if ((detail?.voteAverage ?: 0f) > 0f) detail!!.voteAverage else result.voteAverage,
                genres = detail?.genres?.joinToString(",") { g -> g.name }?.ifBlank { entity.genres } ?: entity.genres,
                tmdbId = result.id,
                updatedAt = System.currentTimeMillis()
            )
        } else {
            val detail = runCatching { tmdbApi.getTvDetail(result.id) }.getOrNull()
            entity.copy(
                posterUrl = result.posterPath?.let { AppConfig.Tmdb.IMAGE_BASE_W500 + it } ?: entity.posterUrl,
                backdropUrl = detail?.backdropPath?.let { AppConfig.Tmdb.IMAGE_BASE_ORIGINAL + it } ?: entity.backdropUrl,
                overview = detail?.overview?.ifBlank { entity.overview } ?: entity.overview,
                releaseYear = (detail?.firstAirDate ?: result.firstAirDate ?: "").take(4),
                rating = if ((detail?.voteAverage ?: 0f) > 0f) detail!!.voteAverage else result.voteAverage,
                genres = detail?.genres?.joinToString(",") { g -> g.name }?.ifBlank { entity.genres } ?: entity.genres,
                tmdbId = result.id,
                updatedAt = System.currentTimeMillis()
            )
        }
        seriesDao.insert(updated)
        runCatching { pushTvIdsState(username, bearer) }
    }

    suspend fun firstMovieArtwork(username: String): MovieEntity? = withContext(Dispatchers.IO) {
        movieDao.firstArtwork(username)
    }

    suspend fun firstSeriesArtwork(username: String): SeriesEntity? = withContext(Dispatchers.IO) {
        seriesDao.firstArtwork(username)
    }

    suspend fun movieArtworks(username: String, limit: Int = 12): List<MovieEntity> = withContext(Dispatchers.IO) {
        movieDao.artworks(username, limit)
    }

    suspend fun seriesArtworks(username: String, limit: Int = 12): List<SeriesEntity> = withContext(Dispatchers.IO) {
        seriesDao.artworks(username, limit)
    }

    /** État des épisodes pour la fiche série : watchKey → [EpisodeProgress].
     *  - présent dans l'historique → reprise (« ▶ Reprendre », à partir de 5s, % 1..99) ;
     *  - sinon présent dans seen_episodes → « ✓ Vu ».
     *  Plus de seuil en % : la reprise prime sur le « vu » tant qu'une position est mémorisée. */
    suspend fun getEpisodesProgress(episodes: List<EpisodeEntity>): Map<Long, EpisodeProgress> =
        withContext(Dispatchers.IO) {
            val hKeys = episodes.map { "e:" + it.fileKey }
            val positions = watchHistoryDao.getPositions(hKeys).associateBy { it.movieId }
            val seenFileKeys = seenEpisodeDao.getAllSeenFileKeys().toSet()

            val result = mutableMapOf<Long, EpisodeProgress>()
            for (ep in episodes) {
                val pos = positions[ep.watchKey]
                // .in-lib PRIORITAIRE sur la position : saveWatchPosition() (fin réelle)
                // fait removeHistory() PUIS seenEpisodeDao.markSeen() dans la même
                // coroutine appScope, lancée en fire-and-forget par PlayerActivity
                // juste avant finish() — pas attendue. SeriesDetailActivity.onResume()
                // (retour du lecteur) peut donc rafraîchir AVANT que removeHistory()
                // ait fini d'effacer l'ancienne position "en cours" : avec pos en
                // premier ici, cette position pas-encore-effacée masquait le "vu" tout
                // juste écrit (retour à moins d'1 min de la fin → jamais marqué vu tant
                // qu'aucun autre refresh ne repassait après la fin de l'écriture async).
                when {
                    ep.fileKey in seenFileKeys -> result[ep.watchKey] =
                        EpisodeProgress(seen = true, percent = 100)
                    pos != null -> result[ep.watchKey] = EpisodeProgress(
                        seen = false, percent = pos.progressPercent.coerceIn(1, 99), watchedAt = pos.watchedAt,
                        positionMs = pos.positionMs, durationMs = pos.durationMs
                    )
                }
            }
            result
        }

    // Sérialise les synchros : celle du démarrage (MainActivity) et celles du bus
    // temps réel peuvent se chevaucher, ce qui créerait des doublons à l'insertion.
    private val syncMutex = Mutex()

    /** Synchronise le catalogue serveur DB vers Room. */
    suspend fun syncCatalog(username: String, bearer: String): SyncResult = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            try {
                val response = catalogApi.library(bearer)
                if (!response.ok) {
                    return@withContext SyncResult.Error(
                        response.error ?: "Catalogue DB indisponible",
                        AppConfig.Catalog.BASE_URL,
                        false,
                        0
                    )
                }

                val movieResult = syncMoviesFromCatalog(username, response.movies)
                val seriesResult = syncSeriesFromCatalog(username, response.series, bearer.removePrefix("Bearer ").trim())
                syncRemoteStateWithRetry(username, bearer)
                SyncResult.Success(
                    added = movieResult.first + seriesResult.first,
                    updated = movieResult.second + seriesResult.second,
                    total = response.movies.size + seriesResult.third,
                    path = "Catalogue DB NicoTV",
                    hasCacheJson = true
                )
            } catch (e: HttpException) {
                val msg = if (e.code() == 401) "Session expirée" else "Erreur serveur (${e.code()})"
                Log.w("MediaRepository", "Catalog sync failed: HTTP ${e.code()}")
                SyncResult.Error(msg, AppConfig.Catalog.BASE_URL, false, 0)
            } catch (e: IOException) {
                Log.w("MediaRepository", "Catalog sync failed: réseau indisponible", e)
                SyncResult.Error("Connexion réseau indisponible", AppConfig.Catalog.BASE_URL, false, 0)
            } catch (e: Exception) {
                Log.w("MediaRepository", "Catalog sync failed: ${e.message}", e)
                SyncResult.Error(e.message ?: "Erreur de synchronisation catalogue", AppConfig.Catalog.BASE_URL, false, 0)
            }
        }
    }

    private suspend fun syncMoviesFromCatalog(username: String, movies: List<LibraryMovie>): Pair<Int, Int> {
        if (movies.isEmpty()) return 0 to 0
        val activeTitles = movies.mapNotNull { it.title.takeIf(String::isNotBlank) }
        if (activeTitles.isNotEmpty()) movieDao.deleteObsoleteMovies(activeTitles, username)

        // Première synchro (base vide, ex. installation fraîche) : le catalogue
        // existant sert de référence → addedAt = 0, rien n'est « NOUVEAU ».
        // Seuls les titres ajoutés ENSUITE porteront le badge (même philosophie
        // que la migration v5→v6 de la base).
        val firstSync = movieDao.countForUser(username) == 0

        var added = 0
        var updated = 0
        movies.forEach { item ->
            if (item.title.isBlank() || item.url.isBlank()) return@forEach
            val existing = movieDao.searchMovies(item.title, username).firstOrNull { it.title == item.title }
            val refreshed = buildMovieEntity(item, username, existing)
            when {
                existing == null -> {
                    movieDao.insert(if (firstSync) refreshed.copy(addedAt = 0) else refreshed)
                    added++
                }
                existing.streamUrl != refreshed.streamUrl || existing.posterUrl != refreshed.posterUrl || existing.tmdbId != refreshed.tmdbId -> {
                    movieDao.insert(refreshed.copy(id = existing.id, addedAt = existing.addedAt))
                    updated++
                }
            }
        }
        return added to updated
    }

    private suspend fun syncSeriesFromCatalog(username: String, series: List<LibrarySeries>, token: String): Triple<Int, Int, Int> {
        if (series.isEmpty()) return Triple(0, 0, 0)
        val activeTitles = series.mapNotNull { it.name.takeIf(String::isNotBlank) }
        if (activeTitles.isNotEmpty()) seriesDao.deleteObsoleteSeries(activeTitles, username)

        var added = 0
        var updated = 0
        var totalEpisodes = 0

        series.forEach { item ->
            if (item.name.isBlank()) return@forEach
            val existingId = seriesDao.findId(username, item.name) ?: 0L
            val entity = buildSeriesEntity(item, username, existingId)
            val seriesId = seriesDao.insert(entity)
            if (existingId == 0L) added++ else updated++

            episodeDao.deleteForSeries(seriesId)
            val episodes = buildEpisodeEntities(seriesId, item, entity.tmdbId, token)
            totalEpisodes += episodes.size
            episodeDao.insertAll(episodes)
        }
        return Triple(added, updated, totalEpisodes)
    }

    private suspend fun buildMovieEntity(item: LibraryMovie, username: String, existing: MovieEntity?): MovieEntity {
        // N'interroge TMDb que si nécessaire (nouveau titre, tmdbId changé ou poster
        // manquant) : sans ce garde-fou, chaque synchro relançait un appel TMDb par
        // film du catalogue. Quand detail est null, tous les champs retombent sur
        // les valeurs existantes — comportement inchangé pour les titres à jour.
        val needsTmdb = item.tmdbId > 0 &&
            (existing == null || existing.tmdbId != item.tmdbId || existing.posterUrl.isBlank())
        val detail = if (needsTmdb) runCatching { tmdbApi.getMovieDetail(item.tmdbId) }.getOrNull() else null
        return MovieEntity(
            id = existing?.id ?: 0,
            username = username,
            title = item.title,
            streamUrl = item.url,
            posterUrl = detail?.posterPath?.let { AppConfig.Tmdb.IMAGE_BASE_W500 + it } ?: existing?.posterUrl ?: "",
            backdropUrl = detail?.backdropPath?.let { AppConfig.Tmdb.IMAGE_BASE_ORIGINAL + it } ?: existing?.backdropUrl ?: "",
            overview = detail?.overview?.ifBlank { existing?.overview ?: "" } ?: existing?.overview ?: "",
            releaseYear = (detail?.releaseDate ?: item.year).take(4),
            runtime = detail?.runtime ?: existing?.runtime ?: 0,
            rating = detail?.voteAverage ?: existing?.rating ?: 0f,
            genres = detail?.genres?.joinToString(",") { it.name } ?: existing?.genres ?: "",
            tmdbId = item.tmdbId,
            addedAt = existing?.addedAt ?: System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
    }

    private suspend fun buildSeriesEntity(item: LibrarySeries, username: String, existingId: Long): SeriesEntity {
        val tv = runCatching { tmdbApi.searchTv(cleanTitle(item.name)).results.firstOrNull() }.getOrNull()
        val detail = tv?.let { runCatching { tmdbApi.getTvDetail(it.id) }.getOrNull() }
        return SeriesEntity(
            id = existingId,
            username = username,
            title = item.name,
            streamUrl = "",
            posterUrl = tv?.posterPath?.let { AppConfig.Tmdb.IMAGE_BASE_W500 + it } ?: "",
            backdropUrl = (detail?.backdropPath ?: tv?.backdropPath)?.let { AppConfig.Tmdb.IMAGE_BASE_ORIGINAL + it } ?: "",
            overview = detail?.overview?.ifBlank { tv?.overview ?: "" } ?: (tv?.overview ?: ""),
            releaseYear = (detail?.firstAirDate ?: tv?.firstAirDate ?: "").take(4),
            rating = detail?.voteAverage ?: tv?.voteAverage ?: 0f,
            genres = detail?.genres?.joinToString(",") { it.name } ?: "",
            tmdbId = tv?.id ?: 0,
            updatedAt = System.currentTimeMillis()
        )
    }

    private suspend fun buildEpisodeEntities(
        seriesId: Long,
        item: LibrarySeries,
        tmdbId: Int,
        token: String
    ): List<EpisodeEntity> {
        return item.seasons.flatMap { season ->
            val tmdbEpisodes = if (tmdbId > 0 && season.season > 0) {
                runCatching { tmdbApi.getTvSeason(tmdbId, season.season).episodes }
                    .getOrNull()?.associateBy { it.episodeNumber } ?: emptyMap()
            } else emptyMap()

            season.episodes.mapNotNull { ep ->
                val file = ep.file.takeIf(String::isNotBlank) ?: return@mapNotNull null
                val playKey = item.name + "/" + file
                val meta = tmdbEpisodes[ep.episode]
                val watchKey = EpisodeEntity.computeWatchKey(playKey)
                EpisodeEntity(
                    seriesId = seriesId,
                    seasonNumber = season.season,
                    seasonName = "Saison %02d".format(season.season),
                    episodeNumber = ep.episode,
                    episodeTitle = meta?.name?.takeIf(String::isNotBlank)?.let { "${ep.episode}. $it" }
                        ?: "Épisode ${ep.episode}",
                    overview = meta?.overview ?: "",
                    streamUrl = streamUrlForSeries(playKey, token),
                    fileKey = playKey,
                    watchKey = watchKey
                )
            }
        }
    }

    private fun streamUrlForSeries(playKey: String, token: String): String {
        val f = URLEncoder.encode(playKey, "UTF-8")
        val t = URLEncoder.encode(token, "UTF-8")
        // Retour au type=series car type=movie semble casser l'accès aux fichiers séries sur le serveur.
        return "${AppConfig.Catalog.BASE_URL}api.php?action=stream&type=series&f=$f&token=$t"
    }

    private fun cleanTitle(raw: String): String {
        var t = raw
        t = t.replace(Regex("""\(\d{4}\)|\b(19|20)\d{2}\b"""), " ")
        t = t.replace(Regex("""[._]"""), " ")
        t = t.replace(Regex("""(?i)\b(\d{1,2}x\d{2}|s\d{1,2}e\d{2}|1080p|720p|480p|2160p|4k|x264|x265|hevc|web-?dl|bluray|brrip|hdrip|dvdrip|vostfr|multi|truefrench|french|fr|en|st)\b"""), " ")
        return t.replace(Regex("""\s+"""), " ").trim().ifBlank { raw }
    }

    /** Recherche TMDb multi. */
    suspend fun searchTmdb(query: String): List<TmdbMultiResult> = withContext(Dispatchers.IO) {
        runCatching {
            tmdbApi.searchMulti(query).results.filter { it.isMovie || it.isTv }
        }.getOrDefault(emptyList())
    }

    // ── Casting / réalisateur / films similaires / bande-annonce / acteur ──────
    // (portage des fonctionnalités ajoutées côté PWA, réutilisées par DetailActivity
    // ET SearchActivity — ✓ ouvre la fiche déjà possédée, + ajoute via le même
    // endpoint POST `add` que l'écran Recherche, cf. resolveOrAddWork ci-dessous.)

    suspend fun getMovieCredits(tmdbId: Int) = withContext(Dispatchers.IO) {
        runCatching { tmdbApi.getMovieCredits(tmdbId) }.getOrDefault(TmdbCredits())
    }

    suspend fun getMovieRecommendations(tmdbId: Int): List<TmdbMovieResult> = withContext(Dispatchers.IO) {
        runCatching { tmdbApi.getMovieRecommendations(tmdbId).results }.getOrDefault(emptyList())
    }

    /** Bande-annonce YouTube (fr d'abord, sinon toute langue) — même logique que la PWA. */
    suspend fun getMovieTrailerKey(tmdbId: Int): String? = withContext(Dispatchers.IO) {
        val vids = runCatching { tmdbApi.getMovieVideos(tmdbId).results }.getOrDefault(emptyList())
        val yt = vids.filter { it.site == "YouTube" && (it.type == "Trailer" || it.type == "Teaser") }
        (yt.firstOrNull { it.lang == "fr" && it.type == "Trailer" }
            ?: yt.firstOrNull { it.type == "Trailer" }
            ?: yt.firstOrNull())?.key
    }

    suspend fun getPersonDetail(personId: Int): TmdbPerson? = withContext(Dispatchers.IO) {
        runCatching { tmdbApi.getPersonDetail(personId) }.getOrNull()
    }

    /** Genres (noms, comme la fiche film normale) + durée (minutes, film uniquement —
     * TMDb tv/{id} n'a pas de "runtime" simple) pour l'aperçu films similaires/
     * filmographie, afin d'y montrer les mêmes infos que la fiche film normale. */
    suspend fun getWorkGenresAndRuntime(tmdbId: Int, isTv: Boolean): Pair<String, Int> = withContext(Dispatchers.IO) {
        if (isTv) {
            val d = runCatching { tmdbApi.getTvDetail(tmdbId) }.getOrNull()
            (d?.genres?.joinToString(" • ") { it.name } ?: "") to 0
        } else {
            val d = runCatching { tmdbApi.getMovieDetail(tmdbId) }.getOrNull()
            (d?.genres?.joinToString(" • ") { it.name } ?: "") to (d?.runtime ?: 0)
        }
    }

    /** Filmographie (films + séries), plus récent d'abord, dédupliquée. Documentaires/
     * talk-shows/actualités/télé-réalité exclus (genres TMDb non-fiction) — sur la
     * fiche d'un acteur, on veut ses rôles de fiction, pas les interviews/plateaux/
     * making-of/hommages où il apparaît. */
    suspend fun getPersonFilmography(personId: Int): List<TmdbMultiResult> = withContext(Dispatchers.IO) {
        runCatching { tmdbApi.getPersonCombinedCredits(personId).cast }.getOrDefault(emptyList())
            .filter { (it.isMovie || it.isTv) && !it.posterPath.isNullOrBlank() && TMDB_NON_FICTION_GENRE_IDS.none { g -> g in it.genreIds } }
            .distinctBy { it.mediaType + it.id }
            .sortedByDescending { it.releaseDate ?: it.firstAirDate ?: "" }
    }

    /** Films (et séries) RÉALISÉS par une personne : tableau crew de combined_credits
     * filtré sur job == "Director" — pas sa filmographie d'acteur. Même nettoyage que
     * getPersonFilmography (fiction avec affiche, dédupliqué, plus récent d'abord). */
    suspend fun getPersonDirected(personId: Int): List<TmdbMultiResult> = withContext(Dispatchers.IO) {
        runCatching { tmdbApi.getPersonCombinedCredits(personId).crew }.getOrDefault(emptyList())
            .filter { it.job == "Director" }
            .filter { (it.isMovie || it.isTv) && !it.posterPath.isNullOrBlank() && TMDB_NON_FICTION_GENRE_IDS.none { g -> g in it.genreIds } }
            .distinctBy { it.mediaType + it.id }
            .sortedByDescending { it.releaseDate ?: it.firstAirDate ?: "" }
    }

    suspend fun getPersonDirectedAsWork(personId: Int, username: String): List<SimilarWork> =
        getPersonDirected(personId).map {
            toSimilarWork(
                it.id, it.isTv, it.displayTitle, it.displayYear, it.posterPath, username,
                overview = it.overview, backdropPath = it.backdropPath, rating = it.voteAverage
            )
        }

    /** Film déjà présent dans la médiathèque de l'utilisateur (par tmdbId), sinon null. */
    suspend fun findOwnedMovie(username: String, tmdbId: Int) = withContext(Dispatchers.IO) {
        movieDao.getMovieByTmdbId(username, tmdbId)
    }

    /** Série déjà présente dans la médiathèque de l'utilisateur (par tmdbId), sinon null. */
    suspend fun findOwnedSeries(username: String, tmdbId: Int) = withContext(Dispatchers.IO) {
        seriesDao.getSeriesByTmdbId(username, tmdbId)
    }

    /** Construit une carte affiche (avec badge ✓/+ déjà résolu) à partir d'un film ou
     * d'une série TMDb — partagé par la fiche détail (similaires) et la fiche acteur
     * (filmographie, utilisée aussi depuis l'écran Recherche). overview/backdrop/
     * rating servent à l'aperçu (synopsis + bande-annonce) au clic sur la carte —
     * déjà en main via /recommendations ou /combined_credits, pas de fetch de plus. */
    suspend fun toSimilarWork(
        tmdbId: Int, isTv: Boolean, title: String, year: String, posterPath: String?, username: String,
        overview: String = "", backdropPath: String? = null, rating: Float = 0f
    ): SimilarWork {
        val owned = if (isTv) findOwnedSeries(username, tmdbId) != null else findOwnedMovie(username, tmdbId) != null
        return SimilarWork(
            tmdbId = tmdbId, isTv = isTv, title = title, year = year,
            posterUrl = posterPath?.let { AppConfig.Tmdb.IMAGE_BASE_W500 + it } ?: "",
            owned = owned, overview = overview,
            backdropUrl = backdropPath?.let { AppConfig.Tmdb.IMAGE_BASE_W780 + it } ?: "",
            rating = rating
        )
    }

    suspend fun getPersonFilmographyAsWork(personId: Int, username: String): List<SimilarWork> =
        getPersonFilmography(personId).map {
            toSimilarWork(
                it.id, it.isTv, it.displayTitle, it.displayYear, it.posterPath, username,
                overview = it.overview, backdropPath = it.backdropPath, rating = it.voteAverage
            )
        }

    /** Bande-annonce YouTube, film OU série (fr d'abord, sinon toute langue). */
    suspend fun getTrailerKey(tmdbId: Int, isTv: Boolean): String? = withContext(Dispatchers.IO) {
        val vids = runCatching {
            if (isTv) tmdbApi.getTvVideos(tmdbId).results else tmdbApi.getMovieVideos(tmdbId).results
        }.getOrDefault(emptyList())
        val yt = vids.filter { it.site == "YouTube" && (it.type == "Trailer" || it.type == "Teaser") }
        (yt.firstOrNull { it.lang == "fr" && it.type == "Trailer" }
            ?: yt.firstOrNull { it.type == "Trailer" }
            ?: yt.firstOrNull())?.key
    }

    /** Déjà possédé → cible de navigation ; sinon ajoute à la file de téléchargement
     * (POST `add`, identique à l'écran Recherche) et renvoie null. [onAddResult] reçoit
     * le message à afficher (Toast) uniquement dans le cas de l'ajout. */
    suspend fun resolveOrAddWork(work: SimilarWork, username: String, bearer: String, onAddResult: (String) -> Unit): OpenTarget? {
        if (work.isTv) {
            findOwnedSeries(username, work.tmdbId)?.let { return OpenTarget.SeriesTarget(it.id, it.title) }
        } else {
            findOwnedMovie(username, work.tmdbId)?.let { return OpenTarget.MovieTarget(it.id) }
        }
        runCatching {
            val res = nicoTvApi.addMedia(
                bearer = bearer,
                body = AddMediaRequest(
                    username = username,
                    tmdb_id = work.tmdbId,
                    media_type = if (work.isTv) "tv" else "movie",
                    title = work.title,
                    year = work.year
                )
            )
            onAddResult(if (res.ok) res.message ?: "« ${work.title} » ajouté" else res.error ?: "Échec de l'ajout")
        }.onFailure { onAddResult("Erreur réseau : ${it.localizedMessage}") }
        return null
    }

    /** Relie un film existant à une nouvelle fiche TMDb. */
    suspend fun relinkMovieToTmdb(movieId: Long, result: TmdbMultiResult) = withContext(Dispatchers.IO) {
        val entity = movieDao.getMovieById(movieId) ?: return@withContext
        val updated = if (result.isMovie) {
            val detail = runCatching { tmdbApi.getMovieDetail(result.id) }.getOrNull()
            entity.copy(
                title = detail?.title?.ifBlank { result.displayTitle } ?: result.displayTitle,
                posterUrl = result.posterPath?.let { AppConfig.Tmdb.IMAGE_BASE_W500 + it } ?: entity.posterUrl,
                backdropUrl = detail?.backdropPath?.let { AppConfig.Tmdb.IMAGE_BASE_ORIGINAL + it } ?: entity.backdropUrl,
                overview = detail?.overview?.ifBlank { entity.overview } ?: entity.overview,
                releaseYear = (detail?.releaseDate ?: result.releaseDate ?: "").take(4),
                runtime = if ((detail?.runtime ?: 0) > 0) detail!!.runtime else entity.runtime,
                rating = if ((detail?.voteAverage ?: 0f) > 0f) detail!!.voteAverage else result.voteAverage,
                genres = detail?.genres?.joinToString(",") { genre -> genre.name }?.ifBlank { entity.genres } ?: entity.genres,
                tmdbId = result.id,
                updatedAt = System.currentTimeMillis()
            )
        } else {
            val detail = runCatching { tmdbApi.getTvDetail(result.id) }.getOrNull()
            entity.copy(
                title = detail?.name?.ifBlank { result.displayTitle } ?: result.displayTitle,
                posterUrl = result.posterPath?.let { AppConfig.Tmdb.IMAGE_BASE_W500 + it } ?: entity.posterUrl,
                backdropUrl = detail?.backdropPath?.let { AppConfig.Tmdb.IMAGE_BASE_ORIGINAL + it } ?: entity.backdropUrl,
                overview = detail?.overview?.ifBlank { entity.overview } ?: entity.overview,
                releaseYear = (detail?.firstAirDate ?: result.firstAirDate ?: "").take(4),
                runtime = entity.runtime,
                rating = if ((detail?.voteAverage ?: 0f) > 0f) detail!!.voteAverage else result.voteAverage,
                genres = detail?.genres?.joinToString(",") { genre -> genre.name }?.ifBlank { entity.genres } ?: entity.genres,
                tmdbId = result.id,
                updatedAt = System.currentTimeMillis()
            )
        }
        movieDao.insert(updated)
    }

    // syncRemoteState() était appelé via un runCatching muet dans syncCatalog() : tout
    // échec (token pas encore prêt juste après un vidage de données/relogin, réseau
    // lent au boot Shield) disparaissait sans log ni retry. Le catalogue remontait
    // « Success » mais l'état vu/favoris/progression restait celui d'avant — sans
    // refresh périodique automatique (par design), le badge NOUVEAU restait faux
    // jusqu'au prochain évènement WS ou à la prochaine synchro manuelle. 3 tentatives
    // avec backoff court avant d'abandonner et de logguer.
    private suspend fun syncRemoteStateWithRetry(username: String, bearer: String) {
        var lastError: Throwable? = null
        repeat(3) { attempt ->
            runCatching { syncRemoteState(username, bearer) }
                .onSuccess { return }
                .onFailure { lastError = it }
            if (attempt < 2) delay(1000L * (attempt + 1))
        }
        Log.w("MediaRepository", "syncRemoteState a échoué après 3 tentatives", lastError)
    }

    suspend fun syncRemoteState(username: String, bearer: String) = withContext(Dispatchers.IO) {
        val state = catalogApi.state(bearer)
        if (!state.ok) return@withContext

        // Favoris : liste construite d'abord, remplacement en UNE transaction
        // (sinon Room émet un état « vide » transitoire → étoile/badges qui clignotent).
        val movies = movieDao.getAllMoviesSnapshot(username)
        val byTmdb = movies.filter { it.tmdbId > 0 }.associateBy { it.tmdbId }
        val favorites = (state.favorites ?: emptyList())
            .mapNotNull { it.removePrefix("m").toIntOrNull() }
            .mapNotNull { byTmdb[it] }
            .map { FavoriteEntity(it.id) }
        favoriteDao.replaceAll(favorites)

        // Favoris séries : s<titre>
        val allSeries = seriesDao.getAllSeries(username).first()
        val seriesByTitle = allSeries.associateBy { it.title }
        val serverSeriesFavTitles = (state.favorites ?: emptyList())
            .filter { it.matches(SERIES_FAVORITE_KEY) }
            .map { it.removePrefix("s") }
            .toSet()
        val seriesFavEntities = seriesByTitle.values
            .filter { it.title in serverSeriesFavTitles }
            .map { SeriesFavoriteEntity(it.id) }
        seriesFavoriteDao.replaceAll(seriesFavEntities)

        // Seen movies : mkeys format (m<tmdbId>) — badge NOUVEAU (isNew) dépend de
        // seenMovieDao, PAS de movieDao.markSeen (sentinelle addedAt=0, plus lue
        // par isNew). Sans cette ligne, un film marqué « vu » par un autre appareil
        // (ou la PWA) ne retirait jamais le badge NOUVEAU ICI — d'où la désync
        // smartphone/TV constatée : chaque appareil ne recevait que la moitié du
        // mécanisme (movieDao.markSeen), jamais seenMovieDao (qu'isNew lit vraiment).
        val seenMkeys = (state.seen?.mkeys ?: emptyList())
            .mapNotNull { it.removePrefix("m").toIntOrNull() }
            .toSet()
        if (seenMkeys.isNotEmpty()) {
            movies.filter { it.tmdbId in seenMkeys }.forEach {
                movieDao.markSeen(it.id)
                seenMovieDao.markSeen(SeenMovieEntity(movieKey(it.tmdbId)))
            }
        }

        // Seen series : snames + seen.episodes (détection NOUVEAU des séries,
        // même principe que seenMkeys ci-dessus — sans ça, une série marquée vue
        // par un autre appareil garde son badge NOUVEAU ici indéfiniment).
        val seenSnames = (state.seen?.snames ?: emptyList()).toSet()
        if (seenSnames.isNotEmpty()) {
            allSeries.filter { it.title in seenSnames }.forEach {
                seenSeriesDao.markSeen(SeenSeriesEntity(it.title))
            }
        }
        // tvids : corrections TMDB séries (nom → tmdbId)
        state.tvids?.forEach { (name, id) ->
            if (id > 0) seriesDao.updateTmdbId(username, name, id)
        }

        // Historique / Progression : même principe.
        val allEpisodes = episodeDao.getAllEpisodes()
        val epByFileKey = allEpisodes.associateBy { it.fileKey }

        val seenEpisodesForNew = (state.seen?.episodes ?: emptyList()).toSet()
        if (seenEpisodesForNew.isNotEmpty()) {
            val knownKeys = allEpisodes.map { it.fileKey }.filter { it in seenEpisodesForNew }
            if (knownKeys.isNotEmpty()) {
                newDetectEpisodeDao.markSeen(knownKeys.map { NewDetectEpisodeEntity(it) })
            }
        }

        // Épisodes vus (canal dédié « epseen ») : marque localement ceux du catalogue.
        val seenEpKeys = (state.epseen ?: emptyList()).filter { it in epByFileKey }
        if (seenEpKeys.isNotEmpty()) {
            seenEpisodeDao.markSeenAll(seenEpKeys.map { SeenEpisodeEntity(it) })
            // Purge la reprise locale de ces épisodes : getEpisodesProgress() fait
            // primer la reprise sur « vu » tant qu'une ligne watch_history existe
            // (permet de reprendre un épisode revu avant la fin) — sans ce nettoyage,
            // un épisode fini sur un AUTRE appareil (ex. PWA) gardait sa vieille
            // position locale et l'APK réaffichait « Reprendre » à l'ancien point au
            // lieu du badge « ✓ Vu » (la finition, elle, exclut déjà la clé de
            // serverEntries plus bas — isFinished — donc rien ne la rafraîchissait).
            watchHistoryDao.removeHistories(seenEpKeys.map { "e:$it" })
        }

        // Films regardés jusqu'au bout (canal « mfinished ») : badge « ✓ Vu »
        // synchronisé entre appareils — même principe que epseen. Purge la reprise
        // locale de ces films pour la même raison que les épisodes plus haut :
        // getMoviesWithFavorites calcule isFinished/inProgress indépendamment
        // (PosterAdapter peut afficher les deux badges à la fois), sinon un film
        // fini sur un autre appareil gardait sa vieille reprise ici.
        // Réconciliation AUTORITAIRE (pas seulement ajout) : le serveur est la
        // source de vérité (pushFinishedMovies fusionne avant d'écrire, une remise
        // à zéro retire explicitement la clé). replaceAll retire donc localement les
        // films qui ne sont plus « finis » côté serveur → le badge « ✓ Vu » disparaît
        // ici aussi après un reset fait sur kodi/PWA (bug : ne partait jamais avant).
        val serverFinished = (state.mfinished ?: emptyList()).toSet()
        val newlyFinishedMovieKeys = serverFinished.filter { it !in finishedMoviesCache.allKeys() }
        finishedMoviesCache.replaceAll(serverFinished)
        if (newlyFinishedMovieKeys.isNotEmpty()) {
            watchHistoryDao.removeHistories(newlyFinishedMovieKeys)
        }

        val serverEntries = (state.progress ?: emptyMap()).mapNotNull { (key, value) ->
            val positionMs = value.positionSeconds * 1000L
            val durationMs = value.durationSeconds * 1000L
            // Marge distincte film/épisode : alignée sur le seuil PWA respectif
            // (app.js — séries : remain<=60 dans timeupdate ; films : seulement
            // t>=dur-2, pas de détection anticipée). Une marge unique de 60s pour
            // les deux ferait marquer un FILM « vu » côté APK une minute avant la
            // fin, désync avec le badge PWA qui n'apparaît que dans les 2 dernières
            // secondes.
            val margin = if (key.startsWith("e:")) EPISODE_FINISHED_MARGIN_MS else MOVIE_FINISHED_MARGIN_MS
            val isFinished = durationMs > 0 && positionMs >= durationMs - margin
            if (positionMs <= MIN_RESUME_MS || isFinished) return@mapNotNull null

            when {
                key.startsWith("m") -> {
                    val tmdbId = key.removePrefix("m").toIntOrNull() ?: return@mapNotNull null
                    val movie = byTmdb[tmdbId] ?: return@mapNotNull null
                    WatchHistoryEntity(
                        historyKey = key,
                        movieId = movie.id,
                        title = movie.title,
                        positionMs = positionMs,
                        durationMs = durationMs,
                        watchedAt = value.updatedAt.takeIf { it > 0 } ?: System.currentTimeMillis()
                    )
                }
                key.startsWith("e:") -> {
                    val fileKey = key.removePrefix("e:")
                    val ep = epByFileKey[fileKey] ?: return@mapNotNull null
                    WatchHistoryEntity(
                        historyKey = key,
                        movieId = ep.watchKey,
                        title = ep.episodeTitle,
                        positionMs = positionMs,
                        durationMs = durationMs,
                        watchedAt = value.updatedAt.takeIf { it > 0 } ?: System.currentTimeMillis()
                    )
                }
                else -> null
            }
        }
        // Fusion locale/serveur par date (le plus récent gagne), PAS un remplacement
        // aveugle : si pushProgress() a épuisé ses retries (PUSH_PROGRESS_RETRY_DELAYS_MS)
        // pour une position sauvegardée localement, un replaceAll basé uniquement sur le
        // serveur effacerait cette progression au prochain sync (démarrage app ou
        // évènement WS « state ») — même classe de bug que celui corrigé côté PWA
        // (iptv/app.js loadState()/mergeProgress), mécanisme différent.
        val merged = watchHistoryDao.getAllHistorySnapshot().associateBy { it.historyKey }.toMutableMap()
        for (server in serverEntries) {
            val local = merged[server.historyKey]
            if (local == null || server.watchedAt >= local.watchedAt) merged[server.historyKey] = server
        }
        // Purge les entrées locales absentes du serveur (film/épisode fini ou remis à
        // zéro sur un AUTRE appareil, qui a confirmé la suppression avant que ce sync
        // ne se déclenche) — sinon la barre/badge « commencé » restait affiché
        // indéfiniment ici après une remise à zéro ailleurs (jamais nettoyé, contrairement
        // au cas « fini » qui a son propre canal epseen/mfinished déjà purgé plus haut).
        // Marge de sécurité (RESET_PURGE_GRACE_MS) sur les entrées très récentes ICI :
        // protège un push local qui vient d'échouer silencieusement (cf. commentaire
        // au-dessus) contre une purge par CE MÊME sync avant d'avoir pu réessayer.
        val now = System.currentTimeMillis()
        val serverKeys = serverEntries.map { it.historyKey }.toSet()
        merged.entries.removeAll { (key, entry) ->
            key !in serverKeys && now - entry.watchedAt > RESET_PURGE_GRACE_MS
        }
        watchHistoryDao.replaceAll(merged.values.toList())
    }

    suspend fun toggleFavorite(movieId: Long, username: String, bearer: String) = withContext(Dispatchers.IO) {
        if (favoriteDao.isFavorite(movieId)) {
            favoriteDao.removeFavorite(movieId)
        } else {
            favoriteDao.addFavorite(FavoriteEntity(movieId))
        }
        runCatching { pushFavoriteState(username, bearer) }
    }

    suspend fun saveWatchPosition(
        movieId: Long,
        title: String,
        positionMs: Long,
        durationMs: Long,
        username: String,
        bearer: String,
        forceFinished: Boolean = false
    ) = withContext(Dispatchers.IO) {
        // Marge distincte film/épisode — cf. commentaire de syncRemoteState (même
        // constante partagée avant, désync avec le seuil PWA côté films).
        val margin = if (movieId >= EpisodeEntity.WATCH_OFFSET) EPISODE_FINISHED_MARGIN_MS else MOVIE_FINISHED_MARGIN_MS
        val finished = forceFinished || (durationMs > 0 && positionMs >= durationMs - margin)

        // On détermine s'il s'agit d'un film ou d'un épisode
        val movie = if (movieId < EpisodeEntity.WATCH_OFFSET) movieDao.getMovieById(movieId) else null
        val episode = if (movieId >= EpisodeEntity.WATCH_OFFSET) {
            episodeDao.getEpisodeByWatchKey(movieId)
        } else null

        val historyKey = when {
            movie != null && movie.tmdbId > 0 -> movieKey(movie.tmdbId)
            episode != null -> "e:" + episode.fileKey
            else -> null
        }

        // Seuil avant lequel une reprise n'est pas créée/affichée : 5s, film comme
        // épisode (évite un badge sur un lancement accidentel). En dessous, on efface
        // une reprise existante au lieu de la laisser en l'état (ex. « recommencer à
        // zéro » suivi d'une sortie rapide).
        if (positionMs > MIN_RESUME_MS && !finished) {
            if (historyKey != null) {
                watchHistoryDao.savePosition(
                    WatchHistoryEntity(historyKey, movieId, title, positionMs, durationMs)
                )
                runCatching { pushProgress(bearer, historyKey, positionMs, durationMs) }
            }
        } else if (finished) {
            if (historyKey != null) {
                watchHistoryDao.removeHistory(historyKey)
                runCatching { pushProgress(bearer, historyKey, null, null) }
                if (movie != null) {
                    movieDao.markSeen(movie.id)
                    val seenKey = if (movie.tmdbId > 0) movieKey(movie.tmdbId) else "movie:${movie.id}"
                    seenMovieDao.markSeen(SeenMovieEntity(seenKey))
                    runCatching { pushSeenState(username, bearer) }
                    // Badge « ✓ Vu » : ICI seulement (fin de lecture réelle), pas à
                    // l'ouverture de la fiche (cf. markMovieSeen, qui ne touche que
                    // seenMovieDao/NOUVEAU, jamais ce cache). Même clé stable que
                    // seen_movies (pas l'id Room, instable après migration/ré-ajout).
                    // Synchronisé entre appareils via le canal serveur mfinished.
                    finishedMoviesCache.markFinished(seenKey)
                    runCatching { pushFinishedMovies(bearer) }
                } else if (episode != null) {
                    seenEpisodeDao.markSeen(SeenEpisodeEntity(episode.fileKey))
                    runCatching { pushSeenEpisodes(bearer) }
                }
            }
        } else if (historyKey != null) {
            watchHistoryDao.removeHistory(historyKey)
            runCatching { pushProgress(bearer, historyKey, null, null) }
        }
    }

    /** Présence temps réel (admin.nicotv.ovh « qui regarde quoi ») : même identification
     * film/épisode que saveWatchPosition (movieId < WATCH_OFFSET = film, sinon épisode).
     * Best-effort, jamais bloquant pour la lecture. */
    suspend fun sendHeartbeat(movieId: Long, positionMs: Long, durationMs: Long, playing: Boolean, bearer: String) = withContext(Dispatchers.IO) {
        val posS = positionMs / 1000
        val durS = durationMs / 1000
        val playInt = if (playing) 1 else 0
        if (movieId < EpisodeEntity.WATCH_OFFSET) {
            val movie = movieDao.getMovieById(movieId) ?: return@withContext
            if (movie.tmdbId <= 0) return@withContext
            runCatching { catalogApi.heartbeat(bearer, id = movie.tmdbId, positionSeconds = posS, durationSeconds = durS, playing = playInt) }
        } else {
            val ep = episodeDao.getEpisodeByWatchKey(movieId) ?: return@withContext
            runCatching { catalogApi.heartbeat(bearer, type = "series", f = ep.fileKey, positionSeconds = posS, durationSeconds = durS, playing = playInt) }
        }
    }

    /** Présence « en ligne » hors lecture (accueil, listes, fiche détail…) — pendant
     * de sendHeartbeat() ci-dessus mais sans contexte film/série (cf. sendAppHeartbeat()
     * dans app.js côté PWA). Best-effort, jamais bloquant. */
    suspend fun sendAppHeartbeat(screen: String, bearer: String) = withContext(Dispatchers.IO) {
        runCatching { catalogApi.heartbeat(bearer, positionSeconds = 0, durationSeconds = 0, playing = 0, screen = screen) }
    }

    /** Sessions actives DU MÊME COMPTE sur d'autres appareils (« en cours sur… »,
     *  écran d'accueil mobile). Liste vide (jamais d'exception) en cas d'échec réseau. */
    suspend fun otherDevicesPresence(bearer: String): List<com.nicotv.iptv.data.network.PresenceItem> = withContext(Dispatchers.IO) {
        runCatching { catalogApi.presenceList(bearer).presence }.getOrDefault(emptyList())
    }

    suspend fun remotePauseOther(deviceId: String, bearer: String) = withContext(Dispatchers.IO) {
        runCatching { catalogApi.remotePause(bearer, deviceId = deviceId) }
    }

    suspend fun remoteResumeOther(deviceId: String, bearer: String) = withContext(Dispatchers.IO) {
        runCatching { catalogApi.remoteResumeOther(bearer, deviceId = deviceId) }
    }

    /** Télécommande complète (D-pad + lecteur) sur une autre session du même compte,
     *  cf. RemoteControlActivity. */
    suspend fun remoteCmd(deviceId: String, cmd: String, value: String? = null, bearer: String) = withContext(Dispatchers.IO) {
        runCatching { catalogApi.remoteCmd(bearer, deviceId = deviceId, cmd = cmd, value = value) }
    }

    /** Répond à une demande get_tracks de la télécommande (menu audio/sous-titres),
     *  cf. PlayerActivity.remoteReportTracks(). */
    suspend fun remoteReportTracks(report: com.nicotv.iptv.data.network.RemoteTracksReport, bearer: String) = withContext(Dispatchers.IO) {
        runCatching { catalogApi.remoteReportTracks(bearer, body = report) }
    }

    suspend fun sendPresenceStop(bearer: String) = withContext(Dispatchers.IO) {
        runCatching { catalogApi.presenceStop(bearer) }
    }

    private suspend fun pushSeenState(username: String, bearer: String) {
        val serverSeen = runCatching { catalogApi.state(bearer).seen }.getOrNull()
        val localMkeys = movieDao.getSeenMovies(username).map { "m${it.tmdbId}" }
        val mergedMkeys = ((serverSeen?.mkeys ?: emptyList()) + localMkeys).distinct()
        // snames/episodes : détection NOUVEAU des séries (comme getSeenLib() côté
        // PWA) — fusion avec le serveur, jamais d'écrasement (autres appareils/PWA).
        val mergedSnames = ((serverSeen?.snames ?: emptyList()) + seenSeriesDao.getAllNames()).distinct()
        val mergedEpisodes = ((serverSeen?.episodes ?: emptyList()) + newDetectEpisodeDao.getAllKeys()).distinct()
        catalogApi.updateState(
            bearer = bearer,
            body = StateUpdateRequest(seen = SeenState(
                mkeys    = mergedMkeys,
                mids     = serverSeen?.mids,
                snames   = mergedSnames,
                episodes = mergedEpisodes
            ))
        )
    }

    /** Pousse les films regardés jusqu'au bout (canal « mfinished », badge « ✓ Vu »,
     *  clés "m<tmdbId>"). Fusion avec le serveur, jamais d'écrasement. */
    private suspend fun pushFinishedMovies(bearer: String) {
        val serverKeys = runCatching { catalogApi.state(bearer).mfinished ?: emptyList() }.getOrDefault(emptyList())
        // Filtre "m…" : écarte les vieilles clés numériques (ids Room bruts) d'avant
        // le passage aux clés stables — poids mort local, à ne pas propager.
        val localKeys = finishedMoviesCache.allKeys().filter { it.startsWith("m") }
        val merged = (serverKeys + localKeys).distinct()
        if (merged.isNotEmpty()) {
            catalogApi.updateState(bearer = bearer, body = StateUpdateRequest(mfinished = merged))
        }
    }

    /** Pousse les épisodes vus (canal dédié « epseen », clé fileKey "Série/Fichier.mkv").
     *  Fusionne avec l'état serveur pour ne jamais perdre les vus des autres appareils. */
    private suspend fun pushSeenEpisodes(bearer: String) {
        val serverEps = runCatching { catalogApi.state(bearer).epseen ?: emptyList() }.getOrDefault(emptyList())
        val localEps = seenEpisodeDao.getAllSeenFileKeys()
        val merged = (serverEps + localEps).distinct()
        if (merged.isNotEmpty()) {
            catalogApi.updateState(bearer = bearer, body = StateUpdateRequest(epseen = merged))
        }
    }

    private suspend fun pushTvIdsState(username: String, bearer: String) {
        val serverTvIds = runCatching { catalogApi.state(bearer).tvids ?: emptyMap() }.getOrDefault(emptyMap())
        val localTvIds = seriesDao.getAllSeriesSnapshot(username)
            .filter { it.tmdbId > 0 }
            .associate { it.title to it.tmdbId }
        val merged = serverTvIds + localTvIds
        if (merged.isNotEmpty()) {
            catalogApi.updateState(
                bearer = bearer,
                body = StateUpdateRequest(tvids = merged)
            )
        }
    }

    private suspend fun pushFavoriteState(username: String, bearer: String) {
        val remoteFavorites = runCatching { catalogApi.state(bearer).favorites ?: emptyList() }.getOrDefault(emptyList())
        val preserved = remoteFavorites.filterNot { it.matches(MOVIE_FAVORITE_KEY) || it.matches(SERIES_FAVORITE_KEY) }

        val favoriteIds = favoriteDao.getFavoriteIds()
        val localMovies = if (favoriteIds.isEmpty()) emptyList() else movieDao.getMoviesByIds(username, favoriteIds)
        val movieFavorites = localMovies
            .filter { it.tmdbId > 0 }
            .map { movieKey(it.tmdbId) }

        val seriesFavIds = seriesFavoriteDao.getAllFavoriteIds().toSet()
        val seriesFavorites = if (seriesFavIds.isEmpty()) emptyList()
        else seriesDao.getAllSeries(username).first()
            .filter { it.id in seriesFavIds }
            .map { seriesKey(it.title) }

        catalogApi.updateState(
            bearer = bearer,
            body = StateUpdateRequest(favorites = (preserved + movieFavorites + seriesFavorites).distinct())
        )
    }

    private suspend fun pushProgress(
        bearer: String,
        historyKey: String,
        positionMs: Long?,
        durationMs: Long?
    ) {
        var lastError: Throwable? = null
        for (attempt in 0..PUSH_PROGRESS_RETRY_DELAYS_MS.size) {
            if (attempt > 0) delay(PUSH_PROGRESS_RETRY_DELAYS_MS[attempt - 1])
            val result = runCatching {
                val state = catalogApi.state(bearer)
                val progress = (state.progress ?: emptyMap()).toMutableMap()
                if (positionMs != null && durationMs != null) {
                    progress[historyKey] = PlaybackProgress(
                        positionSeconds = positionMs / 1000L,
                        durationSeconds = durationMs.coerceAtLeast(0L) / 1000L,
                        updatedAt = System.currentTimeMillis()
                    )
                } else {
                    progress.remove(historyKey)
                }
                catalogApi.updateState(bearer = bearer, body = StateUpdateRequest(progress = progress))
            }
            if (result.isSuccess) return
            lastError = result.exceptionOrNull()
        }
        lastError?.let { throw it }   // épuisé → l'appelant (runCatching) l'avale, le merge prend le relais
    }

    private fun movieKey(tmdbId: Int): String = "m$tmdbId"

    suspend fun getWatchPosition(movieId: Long): Long {
        // Try direct movieId match (works for both movies and episodes if watchKey is used)
        val history = watchHistoryDao.getAllHistory().first().find { it.movieId == movieId }
        if (history != null) return history.positionMs

        // If not found, try stable key for movies
        val movie = if (movieId < EpisodeEntity.WATCH_OFFSET) movieDao.getMovieById(movieId) else null
        if (movie != null && movie.tmdbId > 0) {
            val stableKey = movieKey(movie.tmdbId)
            return watchHistoryDao.getPosition(stableKey)?.positionMs ?: 0L
        }

        return 0L
    }

    sealed class SyncResult {
        data class Success(
            val added: Int,
            val updated: Int,
            val total: Int,
            val path: String,
            val hasCacheJson: Boolean
        ) : SyncResult()
        data class Error(
            val message: String,
            val path: String,
            val hasCacheJson: Boolean,
            val total: Int
        ) : SyncResult()
    }

    companion object {
        // Reprise (film ou épisode) : badge affiché seulement à partir de 5s
        // (évite un lancement accidentel).
        private const val MIN_RESUME_MS = 5_000L
        // Séries : alignée sur le seuil PWA (app.js: remain <= 60 dans timeupdate)
        // — badge « ✓ Vu » dès la dernière minute, canal epseen partagé.
        private const val EPISODE_FINISHED_MARGIN_MS = 60_000L
        // Films : « ✓ Vu » dès la dernière minute (comme les épisodes) — un film
        // arrêté à ~1 min de la fin compte comme regardé. La PWA ne marque fini
        // que tout à la fin (t>=dur-2), mais le canal mfinished est fusionné (jamais
        // écrasé) → le badge posé par l'APK reste cohérent sur tous les appareils.
        private const val MOVIE_FINISHED_MARGIN_MS = 60_000L
        // Marge de sécurité avant de purger une entrée locale absente du serveur
        // (syncRemoteState) : au-delà, on considère qu'elle a eu le temps d'être
        // poussée si elle devait l'être — protège contre une purge par le sync qui
        // suit immédiatement un pushProgress() local pas encore confirmé.
        private const val RESET_PURGE_GRACE_MS = 15_000L
        // Retries pushProgress() sur échec réseau/timeout transitoire (avant : un seul
        // essai, échec avalé par le runCatching de l'appelant — cf. commentaire sur
        // RESET_PURGE_GRACE_MS ci-dessus, même parade côté PWA : app.js/pushStateNow).
        // Réduit la fenêtre où une reprise locale non confirmée peut se faire purger
        // par un sync avant d'avoir pu être réessayée.
        private val PUSH_PROGRESS_RETRY_DELAYS_MS = longArrayOf(2_000L, 6_000L)
        // Genres TMDb non-fiction exclus de la filmographie acteur : Documentaire (99,
        // même id films/séries), Talk-show (10767), Actualités (10763), Télé-réalité
        // (10764) — ces 3 derniers n'existent que côté séries TV.
        private val TMDB_NON_FICTION_GENRE_IDS = setOf(99, 10767, 10763, 10764)
        private val MOVIE_FAVORITE_KEY  = Regex("^m\\d+$")
        private val SERIES_FAVORITE_KEY = Regex("^s.+")
        private fun seriesKey(title: String): String = "s$title"
    }
}
