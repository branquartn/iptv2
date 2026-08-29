package com.nicotv.iptv2.data.database.dao

import androidx.room.*
import com.nicotv.iptv2.data.database.entity.MovieEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MovieDao {
    @Query("SELECT * FROM movies ORDER BY title ASC")
    fun getAllMovies(): Flow<List<MovieEntity>>

    @Query("SELECT * FROM movies WHERE id = :id LIMIT 1")
    suspend fun getMovieById(id: Long): MovieEntity?

    // "Déjà dans le catalogue ?" pour le badge ✓ des films similaires/filmographie
    // acteur (pas de tmdbId stocké — nos films viennent d'un M3U/Xtream, pas de
    // TMDb). ⚠️ Bug corrigé 29/08/2026 : une égalité exacte (`title = :title`)
    // ne matchait quasiment jamais — le titre catalogue garde ses tags qualité/
    // langue/codec et son année ("4K-EN - Avatar (2009)"), le titre TMDb est nu
    // ("Avatar") : le ✓ n'apparaissait donc presque jamais. `LIKE` ramène des
    // candidats (le titre catalogue CONTIENT le titre TMDb comme sous-chaîne),
    // PlaylistRepository vérifie ensuite l'égalité après nettoyage complet
    // (util.cleanTitleForMatch, tags+année) pour écarter les faux positifs
    // qu'un simple LIKE laisserait passer (ex. cible "Up" ne doit pas matcher
    // "Wake Up" après nettoyage). LIMIT généreux mais borné : un titre TMDb très
    // court/générique ne doit pas ramener un nombre de lignes déraisonnable sur
    // un gros catalogue.
    @Query("SELECT * FROM movies WHERE title LIKE '%' || :title || '%' COLLATE NOCASE LIMIT 20")
    suspend fun findCandidatesByTitle(title: String): List<MovieEntity>

    @Query("SELECT DISTINCT category FROM movies WHERE category != '' ORDER BY category ASC")
    fun getCategories(): Flow<List<String>>

    /** Lecture ponctuelle — cf. PlaylistRepository.getAvailableContentLanguages. */
    @Query("SELECT DISTINCT category FROM movies WHERE category != ''")
    suspend fun getCategoriesOnce(): List<String>

    /** Recherche (écran Search) — filtre en SQL, pas en Kotlin sur la liste
     * entière : sur un gros catalogue Xtream (des dizaines/centaines de
     * milliers de films), passer par getAllMovies().first().filter{} mappait
     * TOUT le catalogue (+ jointure favoris/historique) à chaque frappe avant
     * de filtrer — recherche perceptiblement lente. LIKE '%...%' ne profite
     * d'aucun index (SQLite ne peut pas indexer un préfixe joker), mais un
     * scan natif SQLite reste largement plus rapide qu'un mapping Kotlin de
     * l'intégralité de la table. [limit] : filet contre un résultat massif. */
    @Query("SELECT * FROM movies WHERE title LIKE '%' || :query || '%' COLLATE NOCASE ORDER BY title ASC LIMIT :limit")
    suspend fun searchByTitle(query: String, limit: Int = 200): List<MovieEntity>

    // ⚠️ Pagination (29/08/2026, cf. CLAUDE.md) — remplace le chargement en
    // mémoire de la TOTALITÉ du catalogue avant affichage (jusqu'à ~136 000
    // films, confirmé lent même hors thread principal). Filtre langue/
    // catégorie directement en SQL via languageCode/categoryStripped
    // (calculés une fois au chargement, cf. MovieEntity) : `:lang IS NULL`
    // court-circuite la comparaison quand aucun filtre de langue n'est actif
    // (réglage "Toutes"), idiome standard Room/SQLite pour un paramètre
    // optionnel — un film sans préfixe détecté (languageCode = '') passe
    // toujours, même principe que l'ancien filtre Kotlin (applyLanguageFilter).
    // `:category IS NULL` = "Toutes" catégories.
    // ⚠️ [limit] négatif = AUCUNE limite (comportement SQLite standard pour
    // `LIMIT -1`) — utilisé quand une catégorie précise est sélectionnée
    // (30/08/2026, demande explicite : pagination seulement sur "Toutes",
    // chargement complet dans une catégorie donnée, toujours bien plus petite
    // que le catalogue entier).
    @Query("""
        SELECT * FROM movies
        WHERE (:lang IS NULL OR languageCode = '' OR languageCode = :lang)
          AND (:category IS NULL OR category = :category)
        ORDER BY title ASC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getMoviesPage(lang: String?, category: String?, limit: Int, offset: Int): List<MovieEntity>

    /** Catégories BRUTES (préfixe langue conservé : "FR - Action" reste
     * "FR - Action") déjà filtrées par langue — alimente directement la
     * sidebar, sans mapper le catalogue complet en objets domaine.
     * ⚠️ 30/08/2026, demande explicite ("je ne veux plus de renommage des
     * catégories, laisse le FR") : on lit `category`, plus `categoryStripped`
     * — cf. CLAUDE.md. La colonne `categoryStripped` reste en base (aucun
     * changement de schéma, donc aucun rechargement de catalogue imposé) mais
     * n'est plus utilisée pour l'affichage ni le filtrage. */
    @Query("""
        SELECT DISTINCT category FROM movies
        WHERE category != '' AND (:lang IS NULL OR languageCode = '' OR languageCode = :lang)
    """)
    suspend fun getDistinctCategoriesForLanguage(lang: String?): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(movies: List<MovieEntity>)

    @Query("DELETE FROM movies")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM movies")
    suspend fun count(): Int
}
