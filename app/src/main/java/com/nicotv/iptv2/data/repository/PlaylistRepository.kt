package com.nicotv.iptv2.data.repository

import android.content.Context
import android.net.Uri
import com.nicotv.iptv2.data.PlaylistSourcePrefs
import com.nicotv.iptv2.data.ProfileBackupPrefs
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
import com.nicotv.iptv2.util.extractLeadingLanguageCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
    private val sourcePrefs: PlaylistSourcePrefs,
    // Survit à la destruction d'une Activity/ViewModel — indispensable pour que
    // getMovies()/getSeries()/getChannels() restent des StateFlow "chauds" au
    // niveau du repository plutôt que recalculés à chaque écran (cf. leurs
    // commentaires plus bas).
    private val appScope: CoroutineScope
) {
    companion object {
        // Reprise affichée dès 5s de lecture (comme NicoTV) — sous ce seuil, pas
        // la peine de proposer une reprise pour quelques secondes de générique/pub.
        private const val MIN_RESUME_MS = 5_000L
        // Rafraîchissement auto du catalogue au démarrage (MainActivity.onStart) —
        // au-delà de cet âge, on retente un chargement réseau best-effort en fond
        // sans bloquer l'écran. Le tap sur un profil (SetupActivity) reste lui
        // toujours un rechargement réseau explicite, jamais servi depuis Room.
        private const val CATALOG_MAX_AGE_MS = 24 * 60 * 60 * 1000L
    }

    class LoadException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private val tmdbClient = TmdbClient(okHttpClient)
    // Borne le nombre de recherches TMDb simultanées pendant l'enrichissement
    // (un M3U avec des centaines de films sans jaquette ne doit pas matraquer
    // l'API d'un coup — ni bloquer le chargement en les faisant en série).
    private val tmdbSemaphore = Semaphore(6)
    // Même principe pour le repli par catégorie Xtream (fetchVodStreamsByCategory/
    // fetchSeriesByCategory) — un panel avec une centaine de catégories ne doit
    // pas partir en rafale de requêtes simultanées.
    private val xtreamSemaphore = Semaphore(6)
    // Copie de secours des profils hors Room — cf. ProfileBackupPrefs.
    private val profileBackup = ProfileBackupPrefs(context)

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

    // [existingId] : passé depuis le dialogue "Modifier" (ProfileAdapter.onEdit)
    // — insert() est en OnConflictStrategy.REPLACE, donc réutiliser l'id
    // existant écrase la même ligne (édition) au lieu d'en créer une nouvelle.
    // Laissé à 0 (défaut) pour un nouveau profil : Room lui assigne un id.
    suspend fun saveM3uUrlProfile(name: String, url: String, existingId: Long = 0): Long = withContext(Dispatchers.IO) {
        db.playlistProfileDao().insert(PlaylistProfileEntity(id = existingId, name = name, type = SourceType.M3U_URL.name, m3uUrl = url))
            .also { backupProfiles() }
    }

    suspend fun saveM3uFileProfile(name: String, uri: String, existingId: Long = 0): Long = withContext(Dispatchers.IO) {
        db.playlistProfileDao().insert(PlaylistProfileEntity(id = existingId, name = name, type = SourceType.M3U_FILE.name, m3uFileUri = uri))
            .also { backupProfiles() }
    }

    suspend fun saveXtreamProfile(name: String, host: String, username: String, password: String, existingId: Long = 0): Long = withContext(Dispatchers.IO) {
        db.playlistProfileDao().insert(
            PlaylistProfileEntity(id = existingId, name = name, type = SourceType.XTREAM.name, xtreamHost = host, xtreamUsername = username, xtreamPassword = password)
        ).also { backupProfiles() }
    }

    suspend fun deleteProfile(id: Long) = withContext(Dispatchers.IO) {
        db.playlistProfileDao().delete(id)
        if (sourcePrefs.getActiveProfileId() == id) sourcePrefs.setActiveProfileId(null)
        backupProfiles()
    }

    /** Réécrit la copie de secours SharedPreferences après chaque changement de
     * profil (cf. [ProfileBackupPrefs]) — Room peut être vidé (montée de schéma
     * destructive, incident), pas les prefs. */
    private suspend fun backupProfiles() = withContext(Dispatchers.IO) {
        profileBackup.save(db.playlistProfileDao().getAllOnce())
    }

    /** Appelé au démarrage : si Room n'a aucun profil mais que la copie de
     * secours en contient, on les réinsère (nouveaux id — les favoris/reprise
     * d'un ancien catalogue ne s'y rattachent pas, même limite qu'un rechargement
     * normal). Ne fait rien dans le cas nominal (Room non vide). */
    suspend fun restoreProfilesIfEmpty() = withContext(Dispatchers.IO) {
        if (db.playlistProfileDao().countProfiles() > 0) return@withContext
        val saved = profileBackup.load()
        if (saved.isEmpty()) return@withContext
        android.util.Log.i("PlaylistRepository", "Room vide : restauration de ${saved.size} profil(s) depuis la sauvegarde de secours")
        saved.forEach { db.playlistProfileDao().insert(it.copy(id = 0)) }
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

    suspend fun getActiveProfile(): PlaylistProfileEntity? = withContext(Dispatchers.IO) {
        sourcePrefs.getActiveProfileId()?.let { db.playlistProfileDao().getById(it) }
    }

    /** Appelé au démarrage de l'accueil (MainActivity.onStart) : recharge le
     * catalogue du profil actif en fond s'il date de plus de
     * [CATALOG_MAX_AGE_MS] — évite de servir indéfiniment un catalogue périmé
     * à un utilisateur qui laisse l'app ouverte/rouverte sans jamais retoucher
     * l'écran de démarrage. Best-effort : un échec réseau ne remonte nulle
     * part, le catalogue existant reste affiché tel quel. */
    suspend fun refreshActiveProfileIfStale(maxAgeMs: Long = CATALOG_MAX_AGE_MS): Boolean {
        val profile = getActiveProfile() ?: return false
        if (System.currentTimeMillis() - profile.lastUsedAt < maxAgeMs) return false
        return loadProfile(profile.id).isSuccess
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

    /** Repli pour les panels où get_vod_streams sans category_id renvoie une
     * liste vide (contrairement à get_live_streams) — appel un par un sur
     * chaque catégorie VOD connue, en parallèle borné, puis fusion. */
    private suspend fun fetchVodStreamsByCategory(client: XtreamClient, categoryIds: Collection<String>): List<com.nicotv.iptv2.data.xtream.XtStream> =
        coroutineScope {
            categoryIds.map { id -> async { xtreamSemaphore.withPermit { client.getVodStreams(id) } } }.awaitAll().flatten()
        }

    /** Même repli que [fetchVodStreamsByCategory] pour get_series. */
    private suspend fun fetchSeriesByCategory(client: XtreamClient, categoryIds: Collection<String>): List<com.nicotv.iptv2.data.xtream.XtSeriesItem> =
        coroutineScope {
            categoryIds.map { id -> async { xtreamSemaphore.withPermit { client.getSeriesList(id) } } }.awaitAll().flatten()
        }

    private suspend fun loadXtream(profile: PlaylistProfileEntity): Int = withContext(Dispatchers.IO) {
        val client = xtreamClientFor(profile)
        client.login()

        val liveCats = client.getLiveCategories().associateBy { it.id }
        val liveStreams = client.getLiveStreams()
        val vodCats = client.getVodCategories().associateBy { it.id }
        val seriesCats = client.getSeriesCategories().associateBy { it.id }
        val vodStreams = client.getVodStreams().ifEmpty { fetchVodStreamsByCategory(client, vodCats.keys) }
        val seriesList = client.getSeriesList().ifEmpty { fetchSeriesByCategory(client, seriesCats.keys) }

        // login() a réussi (identifiants acceptés) mais les 3 catégories sont
        // vides : panel avec API JSON (player_api.php) désactivée/restreinte
        // pour ce compte — répond au login mais pas aux get_*_streams (ou par
        // une erreur/un objet avalé silencieusement, cf. log XtreamClient).
        // Courant chez certains fournisseurs. Repli sur l'export M3U classique
        // (get.php), supporté par la quasi-totalité des panels Xtream — même
        // pipeline que le mode M3U (classification par chemin d'URL /live/,
        // /movie/, /series/ — particulièrement fiable sur un export Xtream).
        if (liveStreams.isEmpty() && vodStreams.isEmpty() && seriesList.isEmpty()) {
            return@withContext loadM3u(fetchUrl(client.playlistM3uUrl()))
        }

        val channels = liveStreams.map {
            ChannelEntity(
                name = it.name, streamUrl = client.liveStreamUrl(it.streamId),
                logoUrl = it.icon, category = liveCats[it.categoryId]?.name.orEmpty(),
                xtreamStreamId = it.streamId
            )
        }

        // Pas d'appel TMDb pour Xtream (contrairement au M3U, dont le tvg-logo
        // est peu fiable) : le panel fournit déjà icône/plot/rating/cover pour
        // la quasi-totalité de son catalogue. Sur un panel à plusieurs dizaines
        // de milliers d'entrées (courant, cf. XtreamClient), enrichir même les
        // seules entrées sans jaquette restait des milliers d'appels TMDb —
        // lent et lourd (CPU/réseau) pour un gain marginal. Pas de secours TMDb
        // possible ici de toute façon.
        val movies = vodStreams.map {
            MovieEntity(
                title = it.name, streamUrl = client.vodStreamUrl(it.streamId, it.containerExtension),
                posterUrl = it.icon, overview = it.plot, rating = it.rating,
                category = vodCats[it.categoryId]?.name.orEmpty(),
                xtreamStreamId = it.streamId
            )
        }

        db.channelDao().deleteAll(); db.movieDao().deleteAll(); db.seriesDao().deleteAll()
        db.channelDao().insertAll(channels)
        db.movieDao().insertAll(movies)
        for (s in seriesList) {
            db.seriesDao().insert(
                SeriesEntity(
                    title = s.name,
                    posterUrl = s.cover,
                    overview = s.plot,
                    rating = s.rating,
                    genres = s.genre, releaseYear = s.releaseDate.take(4),
                    category = seriesCats[s.categoryId]?.name.orEmpty(),
                    xtreamSeriesId = s.seriesId
                )
            )
        }
        // Épisodes chargés à la demande (loadEpisodesForSeries) : des milliers de
        // séries impliqueraient sinon des milliers d'appels get_series_info au
        // chargement initial — bien trop long.
        channels.size + movies.size + seriesList.size
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

    // ⚠️ StateFlow "chaud" au niveau du repository, pas un simple Flow recréé à
    // chaque appel (corrigé 28/08/2026) : un `combine()` classique relance sa
    // requête SQL + le mapping domaine complet à chaque NOUVEAU collecteur —
    // comme chaque écran (MoviesActivity/SeriesActivity/LiveActivity) crée un
    // nouveau ViewModel à chaque ouverture, revisiter Films remappait les
    // ~136 000 lignes à chaque fois ("toujours long à recharger"). `stateIn` +
    // `SharingStarted.Eagerly` calcule UNE FOIS (démarré dès la création du
    // repository, avant même que l'utilisateur touche Films) et garde le
    // résultat en mémoire pour tout le process — un retour sur l'écran lit la
    // valeur déjà prête, quasi instantané. Recalculé automatiquement si
    // movies/favorites/watch_history changent (Room réémet), pas seulement au
    // premier accès.
    private val moviesFlow: Flow<List<Movie>> by lazy {
        combine(db.movieDao().getAllMovies(), db.favoriteDao().getFavoritesByType(FavoriteEntity.Type.MOVIE), db.watchHistoryDao().getAllHistory()) { movies, favs, history ->
            val favIds = favs.map { it.itemId }.toSet()
            val historyByKey = history.associateBy { it.historyKey }
            movies.map { m ->
                val h = historyByKey["m${m.id}"]
                m.toDomain(isFavorite = m.id in favIds, watchProgress = h?.progressPercent ?: 0)
            }
        }.stateIn(appScope, SharingStarted.Eagerly, emptyList())
    }

    private val seriesFlow: Flow<List<Movie>> by lazy {
        combine(db.seriesDao().getAllSeries(), db.favoriteDao().getFavoritesByType(FavoriteEntity.Type.SERIES)) { series, favs ->
            val favIds = favs.map { it.itemId }.toSet()
            series.map { it.toDomain(isFavorite = it.id in favIds) }
        }.stateIn(appScope, SharingStarted.Eagerly, emptyList())
    }

    private val channelsFlow: Flow<List<Channel>> by lazy {
        combine(db.channelDao().getAllChannels(), db.favoriteDao().getFavoritesByType(FavoriteEntity.Type.CHANNEL)) { channels, favs ->
            val favIds = favs.map { it.itemId }.toSet()
            channels.map { it.toDomain(isFavorite = it.id in favIds) }
        }.stateIn(appScope, SharingStarted.Eagerly, emptyList())
    }

    fun getMovies(): Flow<List<Movie>> = moviesFlow
    fun getSeries(): Flow<List<Movie>> = seriesFlow
    fun getChannels(): Flow<List<Channel>> = channelsFlow

    /** Langues/bouquets réellement présents dans le catalogue chargé — pour
     * peupler dynamiquement le choix "Langue du contenu" (Réglages), pas de
     * liste figée : chaque panel a ses propres codes ("FR", "AF", "CA"...),
     * cf. util.LanguageCode. Scanne noms de chaîne (convention "FR: TF1") et
     * catégories films/séries (convention "FR - Ghost") — une seule fois,
     * lecture ponctuelle, pas un Flow (appelé seulement à l'ouverture du
     * sélecteur dans Réglages). */
    suspend fun getAvailableContentLanguages(): List<String> = withContext(Dispatchers.IO) {
        val fromChannels = db.channelDao().getAllChannelNamesOnce().mapNotNull { extractLeadingLanguageCode(it) }
        val fromCategories = (db.movieDao().getCategoriesOnce() + db.seriesDao().getCategoriesOnce())
            .mapNotNull { extractLeadingLanguageCode(it) }
        (fromChannels + fromCategories).distinct().sorted()
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

    /** Complète le synopsis/genre/note/durée d'un film Xtream via get_vod_info —
     * appelé UNIQUEMENT à l'ouverture de sa fiche (DetailViewModel.load), jamais
     * au chargement du catalogue (des dizaines/centaines de milliers d'appels
     * seraient hors de question, même raison que loadEpisodesForSeries). Ne fait
     * rien (renvoie null) si le film a déjà un synopsis, n'est pas issu d'Xtream,
     * ou si le profil actif n'est plus Xtream — l'appelant garde alors l'entrée
     * déjà chargée telle quelle. */
    suspend fun enrichMovieFromXtreamIfNeeded(movieId: Long): Movie? = withContext(Dispatchers.IO) {
        val entity = db.movieDao().getMovieById(movieId) ?: return@withContext null
        if (entity.overview.isNotBlank() || entity.xtreamStreamId.isBlank()) return@withContext null
        val profile = getActiveProfile()?.takeIf { it.type == SourceType.XTREAM.name } ?: return@withContext null
        val info = try {
            xtreamClientFor(profile).getVodInfo(entity.xtreamStreamId)
        } catch (e: Exception) {
            null
        } ?: return@withContext null
        if (info.plot.isBlank()) return@withContext null

        val updated = entity.copy(
            overview = info.plot,
            genres = info.genre.ifBlank { entity.genres },
            rating = if (info.rating > 0f) info.rating else entity.rating,
            // >= 300s (5 min) : filet contre une valeur aberrante constatée sur un
            // panel réel (get_vod_info renvoyant quelques dizaines de secondes
            // pour un long-métrage — affichait "0h 2min"), sans savoir si c'est
            // le panel ou un mauvais champ ; mieux vaut garder l'ancienne valeur
            // (souvent 0, donc rien affiché) qu'une durée absurde.
            runtime = if (info.durationSecs >= 300) info.durationSecs / 60 else entity.runtime,
            backdropUrl = entity.backdropUrl.ifBlank { info.backdropUrl }
        )
        db.movieDao().insertAll(listOf(updated))

        val isFav = db.favoriteDao().isFavorite(movieId, FavoriteEntity.Type.MOVIE)
        val history = db.watchHistoryDao().getPosition("m$movieId")
        updated.toDomain(isFavorite = isFav, watchProgress = history?.progressPercent ?: 0)
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

    /** Recherche perceptiblement lente sur un gros catalogue Xtream avant ce
     * correctif (28/08/2026) : passait par getMovies()/getSeries()/getChannels(),
     * qui mappent et joignent favoris/historique sur TOUT le catalogue — sur un
     * panel de plusieurs centaines de milliers d'entrées (cf. section Xtream de
     * CLAUDE.md), ça remappait l'intégralité de la base à chaque frappe avant de
     * filtrer. Filtre désormais en SQL (`*Dao.searchByTitle`/`searchByName`,
     * LIKE) — ne mappe/joint que les résultats déjà filtrés, pas tout le
     * catalogue. */
    suspend fun searchTitle(query: String): Triple<List<Movie>, List<Movie>, List<Channel>> {
        if (query.isBlank()) return Triple(emptyList(), emptyList(), emptyList())
        return Triple(searchMoviesByTitle(query), searchSeriesByTitle(query), searchChannelsByName(query))
    }

    /** Recherche SQL directe (cf. MovieDao.searchByTitle) — utilisée par l'écran
     * Recherche global ET par le champ recherche interne de l'écran Films
     * (corrigé 28/08/2026) : ce dernier filtrait auparavant `getMovies().value`
     * (catalogue déjà mappé) en Kotlin avec `foldAccents()` par titre à chaque
     * frappe — lent même une fois déplacé en coroutine (des dizaines/centaines
     * de milliers d'appels Normalizer). Repasser par la même requête SQL que la
     * recherche globale (déjà rapide, cf. section CLAUDE.md dédiée) unifie les
     * deux et règle le "pas immédiat" — au prix de l'insensibilité aux accents
     * (SQLite LIKE ne connaît pas `foldAccents()`), déjà acceptée côté
     * recherche globale sans regret signalé. */
    suspend fun searchMoviesByTitle(query: String): List<Movie> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isBlank()) return@withContext emptyList()
        val entities = db.movieDao().searchByTitle(q)
        val favIds = db.favoriteDao().getFavoriteIds(FavoriteEntity.Type.MOVIE).toSet()
        // Reprise de lecture (barre de progression) : seulement pour les films
        // trouvés, pas tout l'historique — cf. getMovies() pour le même principe
        // appliqué au catalogue complet.
        val historyByKey = db.watchHistoryDao().getPositions(entities.map { "m${it.id}" }).associateBy { it.historyKey }
        entities.map { m -> m.toDomain(isFavorite = m.id in favIds, watchProgress = historyByKey["m${m.id}"]?.progressPercent ?: 0) }
    }

    /** Cf. searchMoviesByTitle. */
    suspend fun searchSeriesByTitle(query: String): List<Movie> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isBlank()) return@withContext emptyList()
        val favIds = db.favoriteDao().getFavoriteIds(FavoriteEntity.Type.SERIES).toSet()
        db.seriesDao().searchByTitle(q).map { it.toDomain(isFavorite = it.id in favIds) }
    }

    /** Cf. searchMoviesByTitle. */
    suspend fun searchChannelsByName(query: String): List<Channel> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isBlank()) return@withContext emptyList()
        val favIds = db.favoriteDao().getFavoriteIds(FavoriteEntity.Type.CHANNEL).toSet()
        db.channelDao().searchByName(q).map { it.toDomain(isFavorite = it.id in favIds) }
    }

    /** Vide le catalogue chargé (garde les profils sauvegardés) et désactive le
     * profil actif — prochain démarrage : retour à l'écran de démarrage. */
    suspend fun clearAllData() = withContext(Dispatchers.IO) {
        db.channelDao().deleteAll(); db.movieDao().deleteAll(); db.seriesDao().deleteAll()
        db.favoriteDao().deleteAll(); db.watchHistoryDao().deleteAll()
        sourcePrefs.setActiveProfileId(null)
    }

}
