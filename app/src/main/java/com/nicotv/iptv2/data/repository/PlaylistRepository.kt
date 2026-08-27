package com.nicotv.iptv2.data.repository

import android.content.Context
import android.net.Uri
import com.nicotv.iptv2.data.PlaylistSourcePrefs
import com.nicotv.iptv2.data.SourceType
import com.nicotv.iptv2.data.database.AppDatabase
import com.nicotv.iptv2.data.database.entity.ChannelEntity
import com.nicotv.iptv2.data.database.entity.EpisodeEntity
import com.nicotv.iptv2.data.database.entity.FavoriteEntity
import com.nicotv.iptv2.data.database.entity.MovieEntity
import com.nicotv.iptv2.data.database.entity.PlaylistProfileEntity
import com.nicotv.iptv2.data.database.entity.SeriesEntity
import com.nicotv.iptv2.data.database.entity.WatchHistoryEntity
import com.nicotv.iptv2.data.m3u.M3uEntry
import com.nicotv.iptv2.data.m3u.M3uParser
import com.nicotv.iptv2.AppConfig
import com.nicotv.iptv2.data.tmdb.TmdbClient
import com.nicotv.iptv2.data.xtream.XtreamClient
import com.nicotv.iptv2.domain.model.Channel
import com.nicotv.iptv2.domain.model.EpisodeProgress
import com.nicotv.iptv2.domain.model.Movie
import com.nicotv.iptv2.domain.model.OpenTarget
import com.nicotv.iptv2.domain.model.SimilarWork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Point central : charge la playlist (M3U url/fichier ou Xtream Codes) dans le
 * cache Room, puis expose films/séries/chaînes joints aux favoris et à la
 * reprise de lecture. Remplace MediaRepository (NicoTV) — pas de backend, pas
 * de compte. Plusieurs profils peuvent être sauvegardés (PlaylistProfileEntity,
 * nommés par l'utilisateur), un seul chargé à la fois (cf. PlaylistSourcePrefs
 * pour l'id du profil actif).
 *
 * Limite connue (héritée du même choix que NicoTV, cf. MovieEntity/SeriesEntity) :
 * charger/recharger un profil redistribue de nouveaux id Room (autoIncrement) —
 * les favoris/la reprise d'un titre disparu-puis-revenu ne sont pas rattachés
 * automatiquement. Acceptable pour une v1 : la source ne change pas souvent.
 */
class PlaylistRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val okHttpClient: OkHttpClient,
    private val sourcePrefs: PlaylistSourcePrefs
) {
    companion object {
        // Reprise affichée dès 5s de lecture (comme NicoTV) — sous ce seuil, pas
        // la peine de proposer une reprise pour quelques secondes de générique/pub.
        private const val MIN_RESUME_MS = 5_000L
    }

    class LoadException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private val tmdbClient = TmdbClient(okHttpClient)
    // Borne le nombre de recherches TMDb simultanées pendant l'enrichissement
    // (un M3U avec des centaines de films sans jaquette ne doit pas matraquer
    // l'API d'un coup — ni bloquer le chargement en les faisant en série).
    private val tmdbSemaphore = Semaphore(6)

    // ── Profils sauvegardés ──────────────────────────────────────────────────

    fun getProfiles(): Flow<List<PlaylistProfileEntity>> = db.playlistProfileDao().getAll()

    suspend fun getProfile(id: Long): PlaylistProfileEntity? =
        withContext(Dispatchers.IO) { db.playlistProfileDao().getById(id) }

    /** Vrai seulement si le profil actif existe ENCORE en base : l'id vit dans
     * SharedPreferences (jamais effacé) alors que les profils vivent dans Room,
     * que fallbackToDestructiveMigration vide à chaque montée de schéma — sans
     * cette vérification, l'app croyait avoir une source active pointant vers un
     * profil disparu (catalogue vide, impossible de recharger). */
    suspend fun hasValidActiveProfile(): Boolean = withContext(Dispatchers.IO) {
        val id = sourcePrefs.getActiveProfileId() ?: return@withContext false
        val exists = db.playlistProfileDao().getById(id) != null
        if (!exists) sourcePrefs.setActiveProfileId(null)
        exists
    }

    suspend fun saveM3uUrlProfile(name: String, url: String): Long = withContext(Dispatchers.IO) {
        db.playlistProfileDao().insert(PlaylistProfileEntity(name = name, type = SourceType.M3U_URL.name, m3uUrl = url))
    }

    suspend fun saveM3uFileProfile(name: String, uri: String): Long = withContext(Dispatchers.IO) {
        db.playlistProfileDao().insert(PlaylistProfileEntity(name = name, type = SourceType.M3U_FILE.name, m3uFileUri = uri))
    }

    suspend fun saveXtreamProfile(name: String, host: String, username: String, password: String): Long = withContext(Dispatchers.IO) {
        db.playlistProfileDao().insert(
            PlaylistProfileEntity(name = name, type = SourceType.XTREAM.name, xtreamHost = host, xtreamUsername = username, xtreamPassword = password)
        )
    }

    suspend fun deleteProfile(id: Long) = withContext(Dispatchers.IO) {
        db.playlistProfileDao().delete(id)
        if (sourcePrefs.getActiveProfileId() == id) sourcePrefs.setActiveProfileId(null)
    }

    /** Charge (ou recharge) un profil sauvegardé : dispatch selon son type,
     * remplace le catalogue Room, le marque actif si le chargement réussit —
     * en cas d'échec le profil reste sauvegardé (pas de retype des identifiants
     * pour réessayer). */
    suspend fun loadProfile(profileId: Long): Result<Int> {
        val profile = withContext(Dispatchers.IO) { db.playlistProfileDao().getById(profileId) }
            ?: return Result.failure(LoadException("Profil introuvable"))
        return try {
            val count = when (SourceType.valueOf(profile.type)) {
                SourceType.M3U_URL -> loadM3u(fetchUrl(profile.m3uUrl))
                SourceType.M3U_FILE -> loadM3u(readLocalFile(profile.m3uFileUri))
                SourceType.XTREAM -> loadXtream(profile)
            }
            sourcePrefs.setActiveProfileId(profileId)
            db.playlistProfileDao().touchLastUsed(profileId)
            Result.success(count)
        } catch (e: Exception) {
            Result.failure(if (e is LoadException) e else LoadException(e.message ?: "Erreur de chargement", e))
        }
    }

    private suspend fun fetchUrl(url: String): String = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw LoadException("Le serveur a répondu HTTP ${response.code}")
                response.body?.string() ?: throw LoadException("Réponse vide")
            }
        } catch (e: IOException) {
            throw LoadException("Impossible de joindre l'URL (réseau ?)", e)
        }
    }

    private suspend fun readLocalFile(uriString: String): String = withContext(Dispatchers.IO) {
        try {
            val uri = Uri.parse(uriString)
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: throw LoadException("Fichier introuvable — sélectionne-le à nouveau")
        } catch (e: SecurityException) {
            throw LoadException("Permission perdue sur le fichier — sélectionne-le à nouveau", e)
        }
    }

    // ── M3U (URL ou fichier local, même parsing) ────────────────────────────

    private suspend fun loadM3u(text: String): Int = withContext(Dispatchers.Default) {
        val entries = M3uParser.parse(text)
        if (entries.isEmpty()) throw LoadException("Playlist vide ou format M3U non reconnu")

        val channels = mutableListOf<ChannelEntity>()
        val movies = mutableListOf<MovieEntity>()
        // Titre de série → (entrée d'épisode + saison/n°/titre parsés)
        val seriesEpisodes = LinkedHashMap<String, MutableList<Triple<M3uEntry, M3uParser.ParsedEpisode, Int>>>()

        var order = 0
        for (entry in entries) {
            when (M3uParser.classify(entry)) {
                M3uParser.Kind.LIVE -> channels.add(
                    ChannelEntity(name = entry.name, streamUrl = entry.url, logoUrl = entry.logo, category = entry.groupTitle, sortOrder = order++)
                )
                M3uParser.Kind.MOVIE -> movies.add(
                    MovieEntity(title = entry.name, streamUrl = entry.url, posterUrl = entry.logo, category = entry.groupTitle)
                )
                M3uParser.Kind.EPISODE -> {
                    val parsed = M3uParser.parseEpisodeTitle(entry.name)
                    if (parsed == null) {
                        // Motif SxxEyy absent malgré la classification par group-title :
                        // traité comme film plutôt que perdu.
                        movies.add(MovieEntity(title = entry.name, streamUrl = entry.url, posterUrl = entry.logo, category = entry.groupTitle))
                    } else {
                        seriesEpisodes.getOrPut(parsed.seriesTitle) { mutableListOf() }.add(Triple(entry, parsed, order++))
                    }
                }
            }
        }

        replaceCatalog(channels, movies, seriesEpisodes)
        channels.size + movies.size + seriesEpisodes.size
    }

    /** Recherche TMDb par titre pour CHAQUE film (en parallèle, borné par
     * [tmdbSemaphore]) — pas seulement ceux sans jaquette : un tvg-logo/
     * stream_icon fourni par la playlist est souvent un lien mort ou une icône
     * générique (fréquent sur les M3U publics), donc pas digne de confiance.
     * La jaquette/synopsis/note TMDb sont **prioritaires** quand trouvés — c'est
     * la même présentation que NicoTV, dont le catalogue était systématiquement
     * lié à TMDb. Résultat aussi utilisé pour stocker tmdbId (MovieEntity),
     * réutilisé par la fiche détail (casting/similaires/bande-annonce) sans
     * repasser par une recherche. Best-effort : un échec individuel (réseau,
     * aucune correspondance) laisse simplement l'entrée d'origine intacte. */
    private suspend fun enrichMovies(movies: List<MovieEntity>): List<MovieEntity> = coroutineScope {
        movies.map { m ->
            async {
                val hit = tmdbSemaphore.withPermit { tmdbClient.searchMovie(m.title) } ?: return@async m
                m.copy(
                    tmdbId = hit.id,
                    posterUrl = hit.posterUrl.ifBlank { m.posterUrl },
                    backdropUrl = hit.backdropUrl.ifBlank { m.backdropUrl },
                    overview = hit.overview.ifBlank { m.overview },
                    releaseYear = hit.year.ifBlank { m.releaseYear },
                    rating = if (hit.rating > 0f) hit.rating else m.rating
                )
            }
        }.awaitAll()
    }

    /** Variante Xtream de [enrichMovies] : ne cherche sur TMDb que les entrées
     * SANS jaquette, contrairement au M3U où l'enrichissement est systématique
     * (tvg-logo souvent mort/générique — cf. [enrichMovies]). Un panel Xtream
     * fournit déjà un stream_icon/plot/rating fiables dans l'immense majorité
     * des cas : sur un catalogue de plusieurs milliers de VOD (courant chez les
     * fournisseurs Xtream), interroger TMDb pour CHAQUE film faisait tourner le
     * chargement pendant de longues minutes sans jamais échouer (donc sans
     * message d'erreur) — l'app semblait figée indéfiniment sur l'écran de
     * démarrage. */
    private suspend fun enrichMoviesIfMissingArt(movies: List<MovieEntity>): List<MovieEntity> = coroutineScope {
        movies.map { m ->
            if (m.posterUrl.isNotBlank()) return@map async { m }
            async {
                val hit = tmdbSemaphore.withPermit { tmdbClient.searchMovie(m.title) } ?: return@async m
                m.copy(
                    tmdbId = hit.id,
                    posterUrl = hit.posterUrl.ifBlank { m.posterUrl },
                    backdropUrl = hit.backdropUrl.ifBlank { m.backdropUrl },
                    overview = hit.overview.ifBlank { m.overview },
                    releaseYear = hit.year.ifBlank { m.releaseYear },
                    rating = if (hit.rating > 0f) hit.rating else m.rating
                )
            }
        }.awaitAll()
    }

    /** Même principe pour les séries dont le titre n'a pas encore de jaquette —
     * appelé avec seulement les titres qui en ont besoin (évite de chercher les
     * séries déjà pourvues d'une cover par le M3U/Xtream). */
    private suspend fun fetchSeriesArt(titles: Collection<String>): Map<String, TmdbClient.Hit?> = coroutineScope {
        titles.associateWith { title -> async { tmdbSemaphore.withPermit { tmdbClient.searchTv(title) } } }
            .mapValues { it.value.await() }
    }

    private suspend fun replaceCatalog(
        channels: List<ChannelEntity>,
        movies: List<MovieEntity>,
        seriesEpisodes: Map<String, List<Triple<M3uEntry, M3uParser.ParsedEpisode, Int>>>
    ) = withContext(Dispatchers.IO) {
        val enrichedMovies = enrichMovies(movies)

        val seriesLogos = seriesEpisodes.mapValues { (_, items) ->
            items.firstOrNull { it.first.logo.isNotBlank() }?.first?.logo.orEmpty()
        }
        val seriesArt = fetchSeriesArt(seriesLogos.filterValues { it.isBlank() }.keys)

        db.channelDao().deleteAll()
        db.movieDao().deleteAll()
        db.seriesDao().deleteAll() // cascade → episodes déjà supprimés

        db.channelDao().insertAll(channels)
        db.movieDao().insertAll(enrichedMovies)

        val allEpisodes = mutableListOf<EpisodeEntity>()
        for ((seriesTitle, items) in seriesEpisodes) {
            val category = items.first().first.groupTitle
            val logo = seriesLogos[seriesTitle].orEmpty()
            val art = seriesArt[seriesTitle]
            val seriesId = db.seriesDao().insert(
                SeriesEntity(
                    title = seriesTitle,
                    posterUrl = logo.ifBlank { art?.posterUrl.orEmpty() },
                    backdropUrl = art?.backdropUrl.orEmpty(),
                    overview = art?.overview.orEmpty(),
                    rating = art?.rating ?: 0f,
                    releaseYear = art?.year.orEmpty(),
                    category = category
                )
            )
            items.forEach { (entry, parsed, _) ->
                val fileKey = "$seriesTitle/${parsed.season}x${parsed.episode}"
                allEpisodes.add(
                    EpisodeEntity(
                        seriesId = seriesId,
                        seasonNumber = parsed.season,
                        seasonName = "Saison ${parsed.season}",
                        episodeNumber = parsed.episode,
                        episodeTitle = parsed.episodeTitle,
                        streamUrl = entry.url,
                        fileKey = fileKey,
                        watchKey = EpisodeEntity.computeWatchKey(fileKey)
                    )
                )
            }
        }
        db.episodeDao().insertAll(allEpisodes)
    }

    // ── Xtream Codes ─────────────────────────────────────────────────────────

    private fun xtreamClientFor(profile: PlaylistProfileEntity): XtreamClient =
        XtreamClient(okHttpClient, profile.xtreamHost, profile.xtreamUsername, profile.xtreamPassword)

    private suspend fun loadXtream(profile: PlaylistProfileEntity): Int = withContext(Dispatchers.IO) {
        val client = xtreamClientFor(profile)
        client.login()

        val liveCats = client.getLiveCategories().associateBy { it.id }
        val liveStreams = client.getLiveStreams()
        val channels = liveStreams.map {
            ChannelEntity(
                name = it.name, streamUrl = client.liveStreamUrl(it.streamId),
                logoUrl = it.icon, category = liveCats[it.categoryId]?.name.orEmpty()
            )
        }

        val vodCats = client.getVodCategories().associateBy { it.id }
        val vodStreams = client.getVodStreams()
        val movies = enrichMoviesIfMissingArt(
            vodStreams.map {
                MovieEntity(
                    title = it.name, streamUrl = client.vodStreamUrl(it.streamId, it.containerExtension),
                    posterUrl = it.icon, overview = it.plot, rating = it.rating,
                    category = vodCats[it.categoryId]?.name.orEmpty()
                )
            }
        )

        val seriesCats = client.getSeriesCategories().associateBy { it.id }
        val seriesList = client.getSeriesList()
        // La plupart des panels Xtream fournissent déjà une cover ; recherche
        // TMDb seulement pour celles qui n'en ont pas.
        val seriesArt = fetchSeriesArt(seriesList.filter { it.cover.isBlank() }.map { it.name })

        db.channelDao().deleteAll(); db.movieDao().deleteAll(); db.seriesDao().deleteAll()
        db.channelDao().insertAll(channels)
        db.movieDao().insertAll(movies)
        for (s in seriesList) {
            val art = seriesArt[s.name]
            db.seriesDao().insert(
                SeriesEntity(
                    title = s.name,
                    posterUrl = s.cover.ifBlank { art?.posterUrl.orEmpty() },
                    backdropUrl = art?.backdropUrl.orEmpty(),
                    overview = s.plot.ifBlank { art?.overview.orEmpty() },
                    rating = if (s.rating > 0f) s.rating else (art?.rating ?: 0f),
                    genres = s.genre, releaseYear = s.releaseDate.take(4).ifBlank { art?.year.orEmpty() },
                    category = seriesCats[s.categoryId]?.name.orEmpty(),
                    xtreamSeriesId = s.seriesId
                )
            )
        }
        // Épisodes chargés à la demande (loadEpisodesForSeries) : des milliers de
        // séries impliqueraient sinon des milliers d'appels get_series_info au
        // chargement initial — bien trop long.
        val total = channels.size + movies.size + seriesList.size
        // login() a réussi (identifiants acceptés) mais les 3 catégories sont
        // vides : presque toujours un panel qui répond par une erreur/un objet
        // au lieu d'un tableau sur get_*_streams (avalé silencieusement par
        // XtreamClient.callArray, cf. son log) plutôt qu'un compte réellement
        // sans aucun contenu. Sans ce contrôle : succès silencieux, retour à un
        // accueil vide sans aucun message pour comprendre pourquoi.
        if (total == 0) throw LoadException("Connexion réussie mais aucun contenu reçu du panel (abonnement expiré, ou panel non standard — voir logcat XtreamClient)")
        total
    }

    /** Récupère les épisodes d'une série. Pour une série d'origine Xtream
     * (xtreamSeriesId non vide) sans épisode encore en cache, va les chercher
     * via get_series_info et les met en cache. Pour une série détectée depuis un
     * M3U, les épisodes sont déjà en base depuis le chargement initial. */
    suspend fun loadEpisodesForSeries(series: SeriesEntity): List<EpisodeEntity> = withContext(Dispatchers.IO) {
        val cached = db.episodeDao().getEpisodesForSeries(series.id)
        if (cached.isNotEmpty() || series.xtreamSeriesId.isBlank()) return@withContext cached

        val activeId = sourcePrefs.getActiveProfileId() ?: return@withContext cached
        val profile = db.playlistProfileDao().getById(activeId) ?: return@withContext cached
        if (profile.type != SourceType.XTREAM.name) return@withContext cached
        val client = xtreamClientFor(profile)
        val info = client.getSeriesInfo(series.xtreamSeriesId)
        val episodes = info.episodesBySeason.values.flatten().map { ep ->
            val fileKey = "${series.title}/${ep.seasonNumber}x${ep.episodeNumber}"
            EpisodeEntity(
                seriesId = series.id,
                seasonNumber = ep.seasonNumber,
                seasonName = info.seasons.firstOrNull { it.number == ep.seasonNumber }?.name ?: "Saison ${ep.seasonNumber}",
                episodeNumber = ep.episodeNumber,
                episodeTitle = ep.title,
                overview = ep.overview,
                streamUrl = client.seriesEpisodeUrl(ep.id, ep.containerExtension),
                fileKey = fileKey,
                watchKey = EpisodeEntity.computeWatchKey(fileKey)
            )
        }
        db.episodeDao().insertAll(episodes)
        episodes
    }

    // ── Lecture (films / séries / chaînes + favoris + reprise) ─────────────

    fun getMovies(): Flow<List<Movie>> =
        combine(db.movieDao().getAllMovies(), db.favoriteDao().getFavoritesByType(FavoriteEntity.Type.MOVIE), db.watchHistoryDao().getAllHistory()) { movies, favs, history ->
            val favIds = favs.map { it.itemId }.toSet()
            val historyByKey = history.associateBy { it.historyKey }
            movies.map { m ->
                val h = historyByKey["m${m.id}"]
                m.toDomain(isFavorite = m.id in favIds, watchProgress = h?.progressPercent ?: 0)
            }
        }

    fun getSeries(): Flow<List<Movie>> =
        combine(db.seriesDao().getAllSeries(), db.favoriteDao().getFavoritesByType(FavoriteEntity.Type.SERIES)) { series, favs ->
            val favIds = favs.map { it.itemId }.toSet()
            series.map { it.toDomain(isFavorite = it.id in favIds) }
        }

    fun getChannels(): Flow<List<Channel>> =
        combine(db.channelDao().getAllChannels(), db.favoriteDao().getFavoritesByType(FavoriteEntity.Type.CHANNEL)) { channels, favs ->
            val favIds = favs.map { it.itemId }.toSet()
            channels.map { it.toDomain(isFavorite = it.id in favIds) }
        }

    fun getFavoriteMoviesAndSeries(): Flow<List<Movie>> =
        combine(getMovies(), getSeries()) { movies, series -> (movies + series).filter { it.isFavorite } }

    fun getFavoriteChannels(): Flow<List<Channel>> =
        getChannels().map { list -> list.filter { it.isFavorite } }

    fun getFavoritesCount(): Flow<Int> = db.favoriteDao().getCount()

    suspend fun toggleFavorite(itemId: Long, type: String, currentlyFavorite: Boolean) = withContext(Dispatchers.IO) {
        if (currentlyFavorite) db.favoriteDao().removeFavorite(itemId, type)
        else db.favoriteDao().addFavorite(FavoriteEntity(itemId, type))
    }

    suspend fun getMovieById(id: Long): Movie? = withContext(Dispatchers.IO) {
        val entity = db.movieDao().getMovieById(id) ?: return@withContext null
        val isFav = db.favoriteDao().isFavorite(id, FavoriteEntity.Type.MOVIE)
        val history = db.watchHistoryDao().getPosition("m$id")
        entity.toDomain(isFavorite = isFav, watchProgress = history?.progressPercent ?: 0)
    }

    suspend fun getSeriesEntityById(id: Long): SeriesEntity? = withContext(Dispatchers.IO) { db.seriesDao().getById(id) }

    // ── Fiche film enrichie (casting, réalisateur, similaires, bande-annonce) ──
    // Même présentation que NicoTV, appelée directement à l'ouverture de la fiche
    // (pas au chargement de la playlist — un catalogue de plusieurs milliers de
    // titres ne peut pas tous être résolus contre TMDb d'un coup).

    /** Résout l'id TMDb d'un titre (pour les appels credits/recommendations/videos
     * qui suivent) — recherche par titre, pas de tmdbId stocké en base. */
    suspend fun resolveTmdbMovieId(title: String): Int? =
        tmdbClient.searchMovie(title)?.id?.takeIf { it > 0 }

    suspend fun getMovieCredits(tmdbId: Int) = tmdbClient.getMovieCredits(tmdbId)

    suspend fun getMovieTrailerKey(tmdbId: Int) = tmdbClient.getTrailerKey(tmdbId, isTv = false)

    suspend fun getWorkTrailerKey(tmdbId: Int, isTv: Boolean) = tmdbClient.getTrailerKey(tmdbId, isTv)

    suspend fun getWorkGenresAndRuntime(tmdbId: Int, isTv: Boolean) = tmdbClient.getGenresAndRuntime(tmdbId, isTv)

    suspend fun getMovieRecommendations(tmdbId: Int): List<SimilarWork> = withContext(Dispatchers.IO) {
        tmdbClient.getMovieRecommendations(tmdbId).map { toSimilarWork(it) }
    }

    suspend fun getPerson(personId: Int) = tmdbClient.getPerson(personId)

    suspend fun getPersonFilmography(personId: Int): List<SimilarWork> = withContext(Dispatchers.IO) {
        val (cast, _) = tmdbClient.getPersonCombinedCredits(personId)
        cast.filter { it.posterPath != null }.map { toSimilarWork(it) }
    }

    /** Films/séries réalisés (crew, job=Director) — pas la filmographie d'acteur. */
    suspend fun getPersonDirected(personId: Int): List<SimilarWork> = withContext(Dispatchers.IO) {
        val (_, crew) = tmdbClient.getPersonCombinedCredits(personId)
        crew.filter { it.job == "Director" && it.posterPath != null }.map { toSimilarWork(it) }
    }

    private suspend fun toSimilarWork(work: com.nicotv.iptv2.data.tmdb.TmdbWork): SimilarWork {
        val owned = if (work.isTv) db.seriesDao().findByTitle(work.title) != null
                    else db.movieDao().findByTitle(work.title) != null
        return SimilarWork(
            tmdbId = work.id, isTv = work.isTv, title = work.title, year = work.year,
            posterUrl = work.posterPath?.let { AppConfig.Tmdb.IMAGE_BASE_W500 + it }.orEmpty(),
            owned = owned, overview = work.overview,
            backdropUrl = work.backdropPath?.let { AppConfig.Tmdb.IMAGE_BASE_W780 + it }.orEmpty(),
            rating = work.rating
        )
    }

    /** Déjà dans le catalogue → cible de navigation ; sinon null (pas de backend
     * pour l'ajouter — contrairement à NicoTV, cf. domain.SimilarWork). */
    suspend fun resolveOpenTarget(work: SimilarWork): OpenTarget? = withContext(Dispatchers.IO) {
        if (work.isTv) {
            db.seriesDao().findByTitle(work.title)?.let { OpenTarget.SeriesTarget(it.id, it.title, it.posterUrl) }
        } else {
            db.movieDao().findByTitle(work.title)?.let { OpenTarget.MovieTarget(it.id) }
        }
    }

    suspend fun isSeriesFavorite(id: Long): Boolean = withContext(Dispatchers.IO) { db.favoriteDao().isFavorite(id, FavoriteEntity.Type.SERIES) }

    /** watchKey d'épisode → état (reprise/rien). Pas de notion de "vu" permanente
     * en v1 (simplifié) : une entrée présente = en cours, absente = jamais commencé
     * ou terminé (l'entrée est supprimée à la fin, cf. saveWatchPosition). */
    suspend fun getEpisodeProgressMap(episodes: List<EpisodeEntity>): Map<Long, EpisodeProgress> = withContext(Dispatchers.IO) {
        val keys = episodes.map { "e:${it.fileKey}" }
        val positions = db.watchHistoryDao().getPositions(keys).associateBy { it.historyKey }
        episodes.mapNotNull { ep ->
            val h = positions["e:${ep.fileKey}"] ?: return@mapNotNull null
            ep.watchKey to EpisodeProgress(seen = false, percent = h.progressPercent, positionMs = h.positionMs, durationMs = h.durationMs)
        }.toMap()
    }

    suspend fun getEpisodesForSeriesId(seriesId: Long): List<EpisodeEntity> = withContext(Dispatchers.IO) { db.episodeDao().getEpisodesForSeries(seriesId) }

    // ── Reprise de lecture ───────────────────────────────────────────────────

    /** Écran "Reprendre la lecture" : films et épisodes en cours, triés du plus
     * récent au plus ancien. */
    fun getUnifiedHistory(): Flow<List<Movie>> =
        combine(db.watchHistoryDao().getRecentHistory(), db.movieDao().getAllMovies(), db.episodeDao().getAllEpisodesFlow()) { history, movies, episodes ->
            val moviesById = movies.associateBy { it.id }
            val episodesByWatchKey = episodes.associateBy { it.watchKey }
            history.mapNotNull { h ->
                if (h.historyKey.startsWith("m")) {
                    val movieId = h.historyKey.removePrefix("m").toLongOrNull() ?: return@mapNotNull null
                    moviesById[movieId]?.toDomain(watchProgress = h.progressPercent)
                } else {
                    val ep = episodesByWatchKey[h.movieId] ?: return@mapNotNull null
                    Movie(
                        id = ep.watchKey, title = ep.episodeTitle, streamUrl = ep.streamUrl,
                        watchProgress = h.progressPercent, type = Movie.Type.EPISODE,
                        episodeKey = ep.fileKey, seriesId = ep.seriesId, seriesTitle = h.title
                    )
                }
            }
        }

    suspend fun getWatchPosition(historyKey: String): Long = withContext(Dispatchers.IO) { db.watchHistoryDao().getPosition(historyKey)?.positionMs ?: 0L }

    /** [historyKey] = "m<id>" pour un film, "e:<fileKey>" pour un épisode.
     * [progressMovieId] = id local utilisé pour le tri/lookup côté ResumeViewModel
     * (movie.id pour un film, episode.watchKey pour un épisode). */
    suspend fun saveWatchPosition(historyKey: String, progressMovieId: Long, title: String, positionMs: Long, durationMs: Long, finished: Boolean) =
        withContext(Dispatchers.IO) {
            if (finished || positionMs < MIN_RESUME_MS) {
                db.watchHistoryDao().removeHistory(historyKey)
            } else {
                db.watchHistoryDao().savePosition(WatchHistoryEntity(historyKey, progressMovieId, title, positionMs, durationMs))
            }
        }

    /** Épisode suivant dans la série (par watchKey courant), ou null si dernier. */
    suspend fun getNextEpisode(currentWatchKey: Long, seriesId: Long): EpisodeEntity? = withContext(Dispatchers.IO) {
        val episodes = db.episodeDao().getEpisodesForSeries(seriesId)
        val idx = episodes.indexOfFirst { it.watchKey == currentWatchKey }
        if (idx >= 0 && idx + 1 < episodes.size) episodes[idx + 1] else null
    }

    // ── Recherche locale (par titre, insensible aux accents) ───────────────

    suspend fun searchTitle(query: String): Triple<List<Movie>, List<Movie>, List<Channel>> {
        val q = query.trim()
        if (q.isBlank()) return Triple(emptyList(), emptyList(), emptyList())
        val movies = getMovies().first().filter { it.title.contains(q, ignoreCase = true) }
        val series = getSeries().first().filter { it.title.contains(q, ignoreCase = true) }
        val channels = getChannels().first().filter { it.name.contains(q, ignoreCase = true) }
        return Triple(movies, series, channels)
    }

    /** Vide le catalogue chargé (garde les profils sauvegardés) et désactive le
     * profil actif — prochain démarrage : retour à l'écran de démarrage. */
    suspend fun clearAllData() = withContext(Dispatchers.IO) {
        db.channelDao().deleteAll(); db.movieDao().deleteAll(); db.seriesDao().deleteAll()
        db.favoriteDao().deleteAll(); db.watchHistoryDao().deleteAll()
        sourcePrefs.setActiveProfileId(null)
    }
}
