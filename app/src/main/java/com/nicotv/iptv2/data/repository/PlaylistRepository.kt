package com.nicotv.iptv2.data.repository

import android.content.Context
import android.net.Uri
import com.nicotv.iptv2.data.PlaylistSourcePrefs
import com.nicotv.iptv2.data.ProfileBackupPrefs
import com.nicotv.iptv2.data.SourceType
import androidx.room.withTransaction
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
import com.nicotv.iptv2.util.cleanTitleForMatch
import com.nicotv.iptv2.util.extractLeadingLanguageCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
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
    private val sourcePrefs: PlaylistSourcePrefs,
    // Survit à la destruction d'une Activity/ViewModel — pour les écritures qui
    // doivent aboutir même si l'écran qui les a déclenchées se ferme juste
    // après (sauvegarde de la position de lecture au retour du player).
    // ⚠️ Servait aussi de portée aux 3 StateFlow "chauds" du catalogue complet,
    // supprimés le 30/08/2026 (cf. plus bas) — ne pas en recréer.
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
        // Taille de page des écrans Films/Séries/Chaînes — assez pour remplir
        // l'écran + une marge de scroll sans viser l'exhaustivité immédiate
        // (tout l'intérêt de la pagination).
        // ⚠️ N'est utilisée QUE pour "Toutes" (30/08/2026, demande explicite) :
        // dès qu'une catégorie précise est sélectionnée, la page est chargée en
        // ENTIER (NO_LIMIT) — une catégorie donnée est toujours bien plus petite
        // que le catalogue complet, et l'utilisateur veut alors tout voir d'un
        // coup (compteur juste, scroll complet immédiat).
        const val MOVIES_PAGE_SIZE = 60
        /** `LIMIT -1` = aucune limite en SQLite — cf. commentaire ci-dessus. */
        const val NO_LIMIT = -1
        // ⚠️ SQLite plafonne le nombre de paramètres liés d'une requête
        // (SQLITE_MAX_VARIABLE_NUMBER, 999 sur les Android concernés). Toute
        // requête `WHERE x IN (:liste)` doit donc être découpée en lots sous
        // ce seuil — cf. watchPositionsFor et le crash du 30/08/2026
        // documenté dans CLAUDE.md. 900 = marge de sécurité.
        private const val SQLITE_MAX_VARIABLES = 900
        /** Nombre de jaquettes candidates pour le fond de l'accueil. */
        private const val HOME_BG_LIMIT = 12
        /** Taille des lots d'insertion — assez gros pour rester efficace, assez
         * petit pour que la progression affichée avance régulièrement. */
        private const val DB_INSERT_CHUNK = 2_000
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
     * pour réessayer).
     *
     * [onProgress] : pourcentage (0-100) + message d'étape, pour le dialogue
     * de chargement (29/08/2026, demande explicite "fenêtre de chargement...
     * avec un pourcentage") — best-effort, pas une mesure précise partout :
     * réel (compte fait/total) pendant l'enrichissement TMDb d'un M3U (seule
     * étape vraiment longue et dénombrable), par paliers fixes ailleurs
     * (connexion Xtream, récupération des flux — pas d'unité de progression
     * naturelle sur ces appels réseau groupés). Par défaut no-op : les
     * appelants qui n'affichent pas de dialogue (refreshActiveProfileIfStale)
     * n'ont rien à changer. */
    suspend fun loadProfile(profileId: Long, onProgress: (percent: Int, message: String) -> Unit = { _, _ -> }): Result<Int> {
        val profile = withContext(Dispatchers.IO) { db.playlistProfileDao().getById(profileId) }
            ?: return Result.failure(LoadException("Profil introuvable"))
        return try {
            val count = when (SourceType.valueOf(profile.type)) {
                SourceType.M3U_URL -> {
                    onProgress(5, "Téléchargement de la playlist…")
                    loadM3u(fetchUrl(profile.m3uUrl), onProgress)
                }
                SourceType.M3U_FILE -> {
                    onProgress(5, "Lecture du fichier…")
                    loadM3u(readLocalFile(profile.m3uFileUri), onProgress)
                }
                SourceType.XTREAM -> loadXtream(profile, onProgress)
            }
            onProgress(100, "Terminé")
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

    private suspend fun loadM3u(text: String, onProgress: (percent: Int, message: String) -> Unit = { _, _ -> }): Int = withContext(Dispatchers.Default) {
        onProgress(15, "Analyse de la playlist…")
        val entries = M3uParser.parse(text)
        if (entries.isEmpty()) throw LoadException("Playlist vide ou format M3U non reconnu")

        val channels = mutableListOf<ChannelEntity>()
        val movies = mutableListOf<MovieEntity>()
        // Titre de série → (entrée d'épisode + saison/n°/titre parsés)
        val seriesEpisodes = LinkedHashMap<String, MutableList<Triple<M3uEntry, M3uParser.ParsedEpisode, Int>>>()

        // ⚠️ Ordre des catégories tel que la PLAYLIST les présente (30/08/2026,
        // cf. MovieEntity.categoryOrder) : rang de première apparition du
        // group-title dans le fichier. `getOrPut` sur une LinkedHashMap suffit
        // — l'insertion garde l'ordre de parcours, donc le rang est croissant.
        val categoryOrder = LinkedHashMap<String, Int>()
        fun orderOf(groupTitle: String): Int = categoryOrder.getOrPut(groupTitle) { categoryOrder.size }

        var order = 0
        for (entry in entries) {
            when (M3uParser.classify(entry)) {
                M3uParser.Kind.LIVE -> channels.add(
                    ChannelEntity(
                        name = entry.name, streamUrl = entry.url, logoUrl = entry.logo, category = entry.groupTitle, sortOrder = order++,
                        categoryOrder = orderOf(entry.groupTitle),
                        nameLanguageCode = ChannelEntity.nameLanguageCodeFor(entry.name),
                        nameStripped = ChannelEntity.nameStrippedFor(entry.name),
                        categoryLanguageCode = ChannelEntity.categoryLanguageCodeFor(entry.groupTitle),
                        categoryStripped = ChannelEntity.categoryStrippedFor(entry.groupTitle),
                        tntRank = ChannelEntity.tntRankForName(entry.name)
                    )
                )
                M3uParser.Kind.MOVIE -> movies.add(
                    MovieEntity(
                        title = entry.name, streamUrl = entry.url, posterUrl = entry.logo, category = entry.groupTitle,
                        languageCode = MovieEntity.languageCodeFor(entry.groupTitle), categoryStripped = MovieEntity.categoryStrippedFor(entry.groupTitle),
                        categoryOrder = orderOf(entry.groupTitle),
                        // Rang dans le fichier : movies.size vaut le nombre de
                        // films déjà ajoutés, donc l'index du prochain.
                        sortOrder = movies.size
                    )
                )
                M3uParser.Kind.EPISODE -> {
                    val parsed = M3uParser.parseEpisodeTitle(entry.name)
                    if (parsed == null) {
                        // Motif SxxEyy absent malgré la classification par group-title :
                        // traité comme film plutôt que perdu.
                        movies.add(
                            MovieEntity(
                                title = entry.name, streamUrl = entry.url, posterUrl = entry.logo, category = entry.groupTitle,
                                languageCode = MovieEntity.languageCodeFor(entry.groupTitle), categoryStripped = MovieEntity.categoryStrippedFor(entry.groupTitle),
                                categoryOrder = orderOf(entry.groupTitle),
                                sortOrder = movies.size
                            )
                        )
                    } else {
                        seriesEpisodes.getOrPut(parsed.seriesTitle) { mutableListOf() }.add(Triple(entry, parsed, order++))
                    }
                }
            }
        }

        replaceCatalog(channels, movies, seriesEpisodes, categoryOrder, onProgress)
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
    private suspend fun enrichMovies(
        movies: List<MovieEntity>,
        onProgress: (percent: Int, message: String) -> Unit = { _, _ -> }
    ): List<MovieEntity> = coroutineScope {
        // Seule étape avec un compte réel fait/total (29/08/2026) : mappée sur
        // la plage 20-90% du chargement M3U global — le reste (téléchargement,
        // analyse, enregistrement) est trop bref/indivisible pour valoir la
        // peine d'un pourcentage précis.
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        val total = movies.size
        movies.map { m ->
            async {
                val hit = tmdbSemaphore.withPermit { tmdbClient.searchMovie(m.title) }
                val n = done.incrementAndGet()
                if (total > 0) {
                    val pct = 20 + (n * 70 / total)
                    onProgress(pct.coerceIn(20, 90), "Jaquettes… ($n/$total)")
                }
                if (hit == null) return@async m
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

    /** Insère [items] par lots en signalant l'avancement entre [from] et [to] %.
     *
     * ⚠️ Sans ça, l'enregistrement était un trou noir : le dialogue affichait
     * 90% puis rampait jusqu'à son plafond de 96% pendant TOUTE l'écriture
     * (~215 000 lignes), ce que l'utilisateur a signalé comme "long à partir de
     * 96%". Le découpage ne rend pas l'écriture plus rapide en soi — il la rend
     * mesurable, donc affichable. Les lots restent DANS la transaction
     * appelante : on ne perd ni l'atomicité ni le gain d'une transaction
     * unique. */
    private suspend fun <T> insertInChunks(
        items: List<T>,
        from: Int,
        to: Int,
        label: String,
        onProgress: (percent: Int, message: String) -> Unit,
        insert: suspend (List<T>) -> Unit
    ) {
        if (items.isEmpty()) return
        var done = 0
        items.chunked(DB_INSERT_CHUNK).forEach { part ->
            insert(part)
            done += part.size
            onProgress(from + (to - from) * done / items.size, "$label ($done/${items.size})")
        }
    }

    private suspend fun replaceCatalog(
        channels: List<ChannelEntity>,
        movies: List<MovieEntity>,
        seriesEpisodes: Map<String, List<Triple<M3uEntry, M3uParser.ParsedEpisode, Int>>>,
        // Rang de chaque group-title dans le fichier — cf. loadM3u/orderOf.
        categoryOrder: Map<String, Int>,
        onProgress: (percent: Int, message: String) -> Unit = { _, _ -> }
    ) = withContext(Dispatchers.IO) {
        val enrichedMovies = enrichMovies(movies, onProgress)

        onProgress(92, "Enregistrement…")
        val seriesLogos = seriesEpisodes.mapValues { (_, items) ->
            items.firstOrNull { it.first.logo.isNotBlank() }?.first?.logo.orEmpty()
        }
        val seriesArt = fetchSeriesArt(seriesLogos.filterValues { it.isBlank() }.keys)

        // Séries préparées EN MÉMOIRE puis insérées en un seul lot — cf. le
        // commentaire de performance dans loadXtream : une insertion par série
        // signifie une transaction (et une écriture disque) par série.
        // `seriesEpisodes` est une LinkedHashMap : l'itération suit l'ordre
        // d'apparition dans le fichier, qui devient donc `sortOrder`.
        val seriesEntities = seriesEpisodes.entries.mapIndexed { index, (seriesTitle, items) ->
            val category = items.first().first.groupTitle
            val logo = seriesLogos[seriesTitle].orEmpty()
            val art = seriesArt[seriesTitle]
            SeriesEntity(
                title = seriesTitle,
                posterUrl = logo.ifBlank { art?.posterUrl.orEmpty() },
                backdropUrl = art?.backdropUrl.orEmpty(),
                overview = art?.overview.orEmpty(),
                rating = art?.rating ?: 0f,
                releaseYear = art?.year.orEmpty(),
                category = category,
                languageCode = SeriesEntity.languageCodeFor(category),
                categoryStripped = SeriesEntity.categoryStrippedFor(category),
                categoryOrder = categoryOrder[category] ?: 0,
                sortOrder = index
            )
        }

        // ⚠️ TOUT le remplacement dans UNE transaction (30/08/2026) : sans
        // elle, chaque deleteAll/insertAll était sa propre transaction, donc
        // autant de synchronisations disque. Bénéfice secondaire mais réel :
        // le remplacement devient ATOMIQUE — une coupure en plein chargement
        // ne peut plus laisser un catalogue à moitié rempli.
        // ⚠️ Aucun appel réseau ici : TMDb (enrichMovies/fetchSeriesArt) est
        // volontairement fait AVANT. Ne jamais faire d'E/S réseau dans une
        // transaction, elle garderait le verrou d'écriture pendant tout ce
        // temps.
        db.withTransaction {
            db.channelDao().deleteAll()
            db.movieDao().deleteAll()
            db.seriesDao().deleteAll() // cascade → episodes déjà supprimés

            insertInChunks(channels, 92, 94, "Chaînes", onProgress) { db.channelDao().insertAll(it) }
            insertInChunks(enrichedMovies, 94, 97, "Films", onProgress) { db.movieDao().insertAll(it) }

            // Les id reviennent dans l'ordre de la liste fournie : on peut donc
            // rattacher les épisodes sans insérer les séries une par une.
            val seriesIds = db.seriesDao().insertAll(seriesEntities)

            val allEpisodes = mutableListOf<EpisodeEntity>()
            seriesEpisodes.entries.forEachIndexed { index, (seriesTitle, items) ->
                val seriesId = seriesIds[index]
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
            insertInChunks(allEpisodes, 97, 99, "Épisodes", onProgress) { db.episodeDao().insertAll(it) }
        }
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

    private suspend fun loadXtream(
        profile: PlaylistProfileEntity,
        onProgress: (percent: Int, message: String) -> Unit = { _, _ -> }
    ): Int = withContext(Dispatchers.IO) {
        // Paliers fixes, pas de compte réel (29/08/2026) : ces appels renvoient
        // chacun un bloc entier d'un coup, pas d'unité de progression naturelle
        // comme "n éléments traités sur un total" (contrairement à
        // enrichMovies, seule étape du chargement M3U qui a un vrai compteur).
        onProgress(10, "Connexion au serveur…")
        val client = xtreamClientFor(profile)
        client.login()

        onProgress(30, "Récupération des chaînes…")
        // ⚠️ On garde les LISTES autant que les Map : le panel renvoie ses
        // catégories dans SON ordre (celui affiché par les autres applis IPTV,
        // nouveautés en tête), et c'est cet ordre qu'on veut reproduire dans la
        // sidebar — cf. MovieEntity.categoryOrder. `associateBy` produit une
        // LinkedHashMap, mais on préfère un index explicite : plus lisible, et
        // insensible à un futur changement de type de collection.
        val liveCatList = client.getLiveCategories()
        val liveCats = liveCatList.associateBy { it.id }
        val liveCatOrder = liveCatList.withIndex().associate { (i, c) -> c.id to i }
        val liveStreams = client.getLiveStreams()
        val vodCatList = client.getVodCategories()
        val vodCats = vodCatList.associateBy { it.id }
        val vodCatOrder = vodCatList.withIndex().associate { (i, c) -> c.id to i }
        val seriesCatList = client.getSeriesCategories()
        val seriesCats = seriesCatList.associateBy { it.id }
        val seriesCatOrder = seriesCatList.withIndex().associate { (i, c) -> c.id to i }
        onProgress(60, "Récupération des films et séries…")
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
            return@withContext loadM3u(fetchUrl(client.playlistM3uUrl()), onProgress)
        }

        // ⚠️ `sortOrder` n'était PAS rempli côté Xtream jusqu'au 30/08/2026 (il ne
        // l'était que pour le M3U) : toutes les chaînes valaient 0, donc l'ordre
        // du panel était perdu. mapIndexed le corrige — même principe que les
        // films (cf. vodStreams).
        val channels = liveStreams.mapIndexed { index, stream ->
            val cat = liveCats[stream.categoryId]?.name.orEmpty()
            ChannelEntity(
                name = stream.name, streamUrl = client.liveStreamUrl(stream.streamId),
                logoUrl = stream.icon, category = cat,
                sortOrder = index,
                xtreamStreamId = stream.streamId,
                nameLanguageCode = ChannelEntity.nameLanguageCodeFor(stream.name),
                nameStripped = ChannelEntity.nameStrippedFor(stream.name),
                categoryLanguageCode = ChannelEntity.categoryLanguageCodeFor(cat),
                categoryStripped = ChannelEntity.categoryStrippedFor(cat),
                tntRank = ChannelEntity.tntRankForName(stream.name),
                categoryOrder = liveCatOrder[stream.categoryId] ?: 0
            )
        }

        // Pas d'appel TMDb pour Xtream (contrairement au M3U, dont le tvg-logo
        // est peu fiable) : le panel fournit déjà icône/plot/rating/cover pour
        // la quasi-totalité de son catalogue. Sur un panel à plusieurs dizaines
        // de milliers d'entrées (courant, cf. XtreamClient), enrichir même les
        // seules entrées sans jaquette restait des milliers d'appels TMDb —
        // lent et lourd (CPU/réseau) pour un gain marginal. Pas de secours TMDb
        // possible ici de toute façon.
        // ⚠️ mapIndexed : l'index dans get_vod_streams EST l'ordre voulu par le
        // panel (nouveautés en tête) — c'est celui qu'affiche IPTV Smarters.
        val movies = vodStreams.mapIndexed { index, stream ->
            val cat = vodCats[stream.categoryId]?.name.orEmpty()
            MovieEntity(
                title = stream.name, streamUrl = client.vodStreamUrl(stream.streamId, stream.containerExtension),
                posterUrl = stream.icon, overview = stream.plot, rating = stream.rating,
                category = cat,
                xtreamStreamId = stream.streamId,
                languageCode = MovieEntity.languageCodeFor(cat), categoryStripped = MovieEntity.categoryStrippedFor(cat),
                categoryOrder = vodCatOrder[stream.categoryId] ?: 0,
                sortOrder = index
            )
        }

        onProgress(90, "Enregistrement…")
        // ⚠️ CORRECTIF PERF MAJEUR (30/08/2026) : les séries étaient insérées
        // UNE PAR UNE dans une boucle (`seriesDao().insert(...)`). Room ouvre
        // une transaction par appel, donc ~31 000 séries = ~31 000
        // transactions, chacune avec sa synchronisation disque — le poste le
        // plus lourd de tout le chargement, très loin devant le réseau. Un
        // seul `insertAll` = une seule transaction.
        val seriesEntities = seriesList.mapIndexed { seriesIndex, item ->
            val cat = seriesCats[item.categoryId]?.name.orEmpty()
            SeriesEntity(
                title = item.name,
                posterUrl = item.cover,
                overview = item.plot,
                rating = item.rating,
                genres = item.genre, releaseYear = item.releaseDate.take(4),
                category = cat,
                xtreamSeriesId = item.seriesId,
                languageCode = SeriesEntity.languageCodeFor(cat),
                categoryStripped = SeriesEntity.categoryStrippedFor(cat),
                categoryOrder = seriesCatOrder[item.categoryId] ?: 0,
                sortOrder = seriesIndex
            )
        }

        // Une seule transaction pour tout le remplacement — cf. replaceCatalog.
        db.withTransaction {
            db.channelDao().deleteAll(); db.movieDao().deleteAll(); db.seriesDao().deleteAll()
            insertInChunks(channels, 90, 93, "Chaînes", onProgress) { db.channelDao().insertAll(it) }
            insertInChunks(movies, 93, 98, "Films", onProgress) { db.movieDao().insertAll(it) }
            insertInChunks(seriesEntities, 98, 99, "Séries", onProgress) { db.seriesDao().insertAll(it) }
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

    // ⚠️ SUPPRIMÉ le 30/08/2026 : les 3 StateFlow "chauds" du catalogue complet
    // (moviesFlow/seriesFlow/channelsFlow + getMovies()/getSeries()/
    // getChannels()) n'avaient plus AUCUN consommateur. Historique, parce que
    // la tentation de les réintroduire sera forte :
    //   - créés le 28/08 pour éviter de remapper le catalogue à chaque
    //     ouverture d'écran (`stateIn(Eagerly)`, préchauffés depuis l'accueil) ;
    //   - vidés de leur rôle par la pagination (29-30/08) : Films/Séries/
    //     Chaînes interrogent la base page par page ;
    //   - puis retirés un par un de leurs derniers appelants — historique de
    //     reprise, fond de l'accueil, et enfin l'écran Favoris — chacun
    //     réécrit pour ne lire QUE les lignes dont il a besoin.
    // Le principe qui a émergé de toute cette séquence : sur un panel de
    // plusieurs dizaines de milliers d'entrées, **aucun écran ne doit charger
    // le catalogue entier en mémoire** — pas même "une seule fois, en cache".
    // Un cache chaud de 47 000 objets coûte son mapping (CPU + GC) et fait
    // ramer tout le reste. Interroger la base avec un filtre + un LIMIT est
    // toujours plus rapide que de garder un gros cache en RAM.
    // Si un besoin de "tout le catalogue" réapparaît, se demander d'abord
    // quelle requête SQL bornée répondrait à la question posée.

    /** Fond aléatoire de l'accueil — cf. MovieDao/SeriesDao.getRecentWithArt :
     * bornés en SQL (12 lignes), là où MainActivity filtrait/triait tout le
     * catalogue en Kotlin à chaque affichage de l'accueil (corrigé 30/08/2026,
     * même cause que getUnifiedHistory ci-dessus). [lang] = contentLanguage. */
    fun getRecentMoviesWithArt(lang: String?, limit: Int = HOME_BG_LIMIT): Flow<List<MovieEntity>> =
        db.movieDao().getRecentWithArt(lang, limit)

    fun getRecentSeriesWithArt(limit: Int = HOME_BG_LIMIT): Flow<List<SeriesEntity>> =
        db.seriesDao().getRecentWithArt(limit)

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

    // ⚠️ Ne mappent PLUS tout le catalogue (corrigé 30/08/2026) : ces deux
    // Flow passaient par getMovies()/getSeries()/getChannels(), donc ils
    // mappaient les ~47 000 films + séries + chaînes en objets domaine pour
    // n'en garder que les quelques favoris. Tant que MainActivity préchauffait
    // ces StateFlow, le coût était payé d'avance et invisible ; ce
    // préchauffage ayant été retiré (devenu inutile pour les écrans
    // catalogue, cf. plus haut), l'écran Favoris se serait retrouvé à le payer
    // À SON OUVERTURE — soit une régression franche. Désormais : on lit la
    // table favorites (petite) et on ne va chercher QUE les lignes qu'elle
    // référence.
    fun getFavoriteMoviesAndSeries(): Flow<List<Movie>> =
        combine(
            db.favoriteDao().getFavoritesByType(FavoriteEntity.Type.MOVIE),
            db.favoriteDao().getFavoritesByType(FavoriteEntity.Type.SERIES)
        ) { movieFavs, seriesFavs -> movieFavs to seriesFavs }
            .map { (movieFavs, seriesFavs) ->
                val movieIds = movieFavs.map { it.itemId }
                val entities = moviesByIds(movieIds).sortedBy { it.title }
                // Barre de reprise conservée sur les affiches favorites (elle
                // venait "gratuitement" de getMovies() avant).
                val historyByKey = watchPositionsFor(entities.map { "m" + it.id })
                val movies = entities.map {
                    it.toDomain(isFavorite = true, watchProgress = historyByKey["m" + it.id]?.progressPercent ?: 0)
                }
                val series = seriesByIds(seriesFavs.map { it.itemId }).map { it.toDomain(isFavorite = true) }
                movies + series
            }

    fun getFavoriteChannels(): Flow<List<Channel>> =
        db.favoriteDao().getFavoritesByType(FavoriteEntity.Type.CHANNEL)
            .map { favs -> channelsByIds(favs.map { it.itemId }).map { it.toDomain(isFavorite = true) } }

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
        val owned = if (work.isTv) findSeriesByCleanTitle(work.title) != null
                    else findMovieByCleanTitle(work.title) != null
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
            findSeriesByCleanTitle(work.title)?.let { OpenTarget.SeriesTarget(it.id, it.title, it.posterUrl) }
        } else {
            findMovieByCleanTitle(work.title)?.let { OpenTarget.MovieTarget(it.id) }
        }
    }

    /** [title] est un titre TMDb nu (sans tags/année) — les candidats `LIKE`
     * ramenés par le dao sont vérifiés ici par égalité après nettoyage complet
     * de LEUR titre catalogue (tags+année), pas du titre TMDb qui n'en a pas.
     * Cf. commentaire de MovieDao.findCandidatesByTitle pour le pourquoi. */
    private suspend fun findMovieByCleanTitle(title: String) =
        db.movieDao().findCandidatesByTitle(title).firstOrNull { it.title.cleanTitleForMatch().equals(title, ignoreCase = true) }

    private suspend fun findSeriesByCleanTitle(title: String) =
        db.seriesDao().findCandidatesByTitle(title).firstOrNull { it.title.cleanTitleForMatch().equals(title, ignoreCase = true) }

    suspend fun isSeriesFavorite(id: Long): Boolean = withContext(Dispatchers.IO) { db.favoriteDao().isFavorite(id, FavoriteEntity.Type.SERIES) }

    /** watchKey d'épisode → état (reprise/rien). Pas de notion de "vu" permanente
     * en v1 (simplifié) : une entrée présente = en cours, absente = jamais commencé
     * ou terminé (l'entrée est supprimée à la fin, cf. saveWatchPosition). */
    suspend fun getEpisodeProgressMap(episodes: List<EpisodeEntity>): Map<Long, EpisodeProgress> = withContext(Dispatchers.IO) {
        val keys = episodes.map { "e:${it.fileKey}" }
        val positions = watchPositionsFor(keys)
        episodes.mapNotNull { ep ->
            val h = positions["e:${ep.fileKey}"] ?: return@mapNotNull null
            ep.watchKey to EpisodeProgress(seen = false, percent = h.progressPercent, positionMs = h.positionMs, durationMs = h.durationMs)
        }.toMap()
    }

    suspend fun getEpisodesForSeriesId(seriesId: Long): List<EpisodeEntity> = withContext(Dispatchers.IO) { db.episodeDao().getEpisodesForSeries(seriesId) }

    // ── Reprise de lecture ───────────────────────────────────────────────────

    /** Écran "Reprendre la lecture" : films et épisodes en cours, triés du plus
     * récent au plus ancien. */
    // ⚠️ Ne mappe PLUS tout le catalogue (corrigé 30/08/2026, signalé "ça
    // charge encore tous puis la catégorie") : la version précédente
    // combinait l'historique avec `getAllMovies()` ET `getAllEpisodesFlow()`,
    // donc elle chargeait et mappait les ~47 000 films + tous les épisodes en
    // mémoire — À CHAQUE émission, et depuis l'ACCUEIL (MainActivity l'observe
    // pour le bouton "Reprendre"). C'était le gros du travail de fond qui
    // saturait le CPU au lancement, avant même d'ouvrir Films. Désormais :
    // seul l'historique (quelques lignes) est un Flow, et on ne va chercher
    // en base QUE les films/épisodes qu'il référence.
    fun getUnifiedHistory(): Flow<List<Movie>> =
        db.watchHistoryDao().getRecentHistory().map { history ->
            if (history.isEmpty()) return@map emptyList<Movie>()
            val movieIds = history.filter { it.historyKey.startsWith("m") }
                .mapNotNull { it.historyKey.removePrefix("m").toLongOrNull() }
            val episodeKeys = history.filterNot { it.historyKey.startsWith("m") }.map { it.movieId }
            val moviesById = moviesByIds(movieIds).associateBy { it.id }
            val episodesByWatchKey = episodesByWatchKeys(episodeKeys).associateBy { it.watchKey }
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
        val historyByKey = watchPositionsFor(entities.map { "m${it.id}" })
        entities.map { m -> m.toDomain(isFavorite = m.id in favIds, watchProgress = historyByKey["m${m.id}"]?.progressPercent ?: 0) }
    }

    // ── Pagination écran Films ──────────────────────────────────────────────
    // ⚠️ 29/08/2026 (cf. CLAUDE.md) : remplace le chargement en mémoire de
    // TOUT le catalogue (moviesFlow) pour l'écran Films — celui-ci reste
    // utilisé tel quel par Favoris/Reprise/Recherche globale/l'accueil
    // (fond aléatoire), qui ont besoin du catalogue complet. Même principe
    // que searchMoviesByTitle (favoris/historique joints seulement sur le
    // sous-ensemble déjà réduit par SQL), mais filtré par langue/catégorie
    // au lieu d'un titre recherché.

    /** Positions de reprise pour un lot de clés, **découpé** pour ne jamais
     * dépasser [SQLITE_MAX_VARIABLES] paramètres liés dans le `IN (...)`.
     *
     * ⚠️ Crash vécu (30/08/2026, corrigé le jour même) : `SQLiteException:
     * too many SQL variables`, l'écran Films s'ouvrait puis se refermait
     * aussitôt. Introduit par le chargement d'une catégorie ENTIÈRE
     * (NO_LIMIT, cf. plus haut) : jusque-là chaque appel restait sous les 999
     * clés (page de 60, recherche bornée à 200), mais une catégorie complète
     * en compte des milliers — la requête devenait impossible à compiler.
     * Ne JAMAIS repasser un `getPositions(...)` brut sur une liste non bornée. */
    /** Cf. watchPositionsFor — même découpage obligatoire (`IN (...)` borné à
     * [SQLITE_MAX_VARIABLES] paramètres). */
    private suspend fun moviesByIds(ids: List<Long>): List<MovieEntity> {
        if (ids.isEmpty()) return emptyList()
        return ids.chunked(SQLITE_MAX_VARIABLES).flatMap { db.movieDao().getMoviesByIds(it) }
    }

    /** Cf. moviesByIds. */
    private suspend fun seriesByIds(ids: List<Long>): List<SeriesEntity> {
        if (ids.isEmpty()) return emptyList()
        return ids.chunked(SQLITE_MAX_VARIABLES).flatMap { db.seriesDao().getSeriesByIds(it) }
    }

    /** Cf. moviesByIds. */
    private suspend fun channelsByIds(ids: List<Long>): List<ChannelEntity> {
        if (ids.isEmpty()) return emptyList()
        return ids.chunked(SQLITE_MAX_VARIABLES).flatMap { db.channelDao().getChannelsByIds(it) }
    }

    /** Cf. moviesByIds. */
    private suspend fun episodesByWatchKeys(keys: List<Long>): List<EpisodeEntity> {
        if (keys.isEmpty()) return emptyList()
        return keys.chunked(SQLITE_MAX_VARIABLES).flatMap { db.episodeDao().getEpisodesByWatchKeys(it) }
    }

    private suspend fun watchPositionsFor(keys: List<String>): Map<String, WatchHistoryEntity> {
        if (keys.isEmpty()) return emptyMap()
        return keys.chunked(SQLITE_MAX_VARIABLES)
            .flatMap { db.watchHistoryDao().getPositions(it) }
            .associateBy { it.historyKey }
    }

    /** Une page de films filtrés en SQL (langue + catégorie déjà "nettoyée",
     * cf. MovieDao.getMoviesPage/MovieEntity.categoryStripped) — [lang] =
     * contentLanguage (null = "Toutes"), [category] = valeur choisie dans la
     * sidebar (null = "Toutes"). */
    suspend fun getMoviesPage(lang: String?, category: String?, offset: Int, limit: Int = MOVIES_PAGE_SIZE): List<Movie> = withContext(Dispatchers.IO) {
        val entities = db.movieDao().getMoviesPage(lang, category, limit, offset)
        if (entities.isEmpty()) return@withContext emptyList()
        val favIds = db.favoriteDao().getFavoriteIds(FavoriteEntity.Type.MOVIE).toSet()
        val historyByKey = watchPositionsFor(entities.map { "m${it.id}" })
        entities.map { m -> m.toDomain(isFavorite = m.id in favIds, watchProgress = historyByKey["m${m.id}"]?.progressPercent ?: 0) }
    }

    /** Catégories pour la sidebar Films, déjà filtrées par langue et déjà
     * "nettoyées" (categoryStripped) — cf. MovieDao.getDistinctCategoriesForLanguage.
     * Le tri (France en premier, cf. isFrenchLabel) reste fait côté ViewModel,
     * la liste de catégories est de toute façon petite (quelques dizaines). */
    suspend fun getMoviesCategories(lang: String?): List<String> = withContext(Dispatchers.IO) {
        db.movieDao().getDistinctCategoriesForLanguage(lang)
    }

    /** cf. MoviesActivity.onResume — rafraîchit l'état favori des films déjà
     * chargés (page(s) en mémoire côté ViewModel) après un aller-retour par
     * la fiche détail : la pagination n'est plus un Flow réactif à la table
     * favorites comme l'était moviesFlow, donc un favori togglé depuis
     * DetailActivity ne se répercute plus tout seul sur la grille déjà
     * affichée. La table favoris reste petite (jamais 47 000 lignes) : ce
     * rafraîchissement est bon marché même appelé à chaque retour sur l'écran. */
    suspend fun getFavoriteMovieIds(): Set<Long> = withContext(Dispatchers.IO) {
        db.favoriteDao().getFavoriteIds(FavoriteEntity.Type.MOVIE).toSet()
    }

    // ── Pagination écran Séries ─────────────────────────────────────────────
    // Cf. section Films juste au-dessus : même principe, mêmes conventions.

    suspend fun getSeriesPage(lang: String?, category: String?, offset: Int, limit: Int = MOVIES_PAGE_SIZE): List<Movie> = withContext(Dispatchers.IO) {
        val entities = db.seriesDao().getSeriesPage(lang, category, limit, offset)
        if (entities.isEmpty()) return@withContext emptyList()
        val favIds = db.favoriteDao().getFavoriteIds(FavoriteEntity.Type.SERIES).toSet()
        entities.map { it.toDomain(isFavorite = it.id in favIds) }
    }

    suspend fun getSeriesCategories(lang: String?): List<String> = withContext(Dispatchers.IO) {
        db.seriesDao().getDistinctCategoriesForLanguage(lang)
    }

    suspend fun getFavoriteSeriesIds(): Set<Long> = withContext(Dispatchers.IO) {
        db.favoriteDao().getFavoriteIds(FavoriteEntity.Type.SERIES).toSet()
    }

    // ── Pagination écran Chaînes ────────────────────────────────────────────
    // Cf. ChannelDao.getChannelsPage pour les 3 spécificités de cet écran
    // (filtre langue sur le NOM, filtre favoris en sous-requête, tri TNT en SQL).

    /** Chaînes d'une page, dans l'ordre du panel (cf.
     * ChannelDao.getChannelsPage). Noms affichés BRUTS depuis le 30/08/2026
     * ("ne renomme pas les chaînes"). */
    suspend fun getChannelsPage(
        lang: String?,
        category: String?,
        favoritesOnly: Boolean,
        offset: Int,
        limit: Int = MOVIES_PAGE_SIZE
    ): List<Channel> = withContext(Dispatchers.IO) {
        val entities = db.channelDao().getChannelsPage(
            lang = lang,
            category = category,
            favOnly = if (favoritesOnly) 1 else 0,
            favType = FavoriteEntity.Type.CHANNEL,
            limit = limit,
            offset = offset
        )
        if (entities.isEmpty()) return@withContext emptyList()
        val favIds = db.favoriteDao().getFavoriteIds(FavoriteEntity.Type.CHANNEL).toSet()
        entities.map { it.toDomain(isFavorite = it.id in favIds) }
    }

    suspend fun getChannelsCategories(lang: String?): List<String> = withContext(Dispatchers.IO) {
        db.channelDao().getDistinctCategoriesForLanguage(lang)
    }

    suspend fun getFavoriteChannelIds(): Set<Long> = withContext(Dispatchers.IO) {
        db.favoriteDao().getFavoriteIds(FavoriteEntity.Type.CHANNEL).toSet()
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
