package com.nicotv.iptv2.data.database.dao

import androidx.room.*
import com.nicotv.iptv2.data.database.entity.SeriesEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SeriesDao {
    @Query("SELECT * FROM series ORDER BY title ASC")
    fun getAllSeries(): Flow<List<SeriesEntity>>

    @Query("SELECT * FROM series WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): SeriesEntity?

    // Cf. MovieDao.findCandidatesByTitle : même correctif (29/08/2026), même
    // raison (titre catalogue avec tags/année vs titre TMDb nu).
    @Query("SELECT * FROM series WHERE title LIKE '%' || :title || '%' COLLATE NOCASE LIMIT 20")
    suspend fun findCandidatesByTitle(title: String): List<SeriesEntity>

    @Query("SELECT DISTINCT category FROM series WHERE category != '' ORDER BY category ASC")
    fun getCategories(): Flow<List<String>>

    /** Lecture ponctuelle — cf. PlaylistRepository.getAvailableContentLanguages. */
    @Query("SELECT DISTINCT category FROM series WHERE category != ''")
    suspend fun getCategoriesOnce(): List<String>

    /** Recherche (écran Search) — cf. MovieDao.searchByTitle, même raison. */
    @Query("SELECT * FROM series WHERE title LIKE '%' || :query || '%' COLLATE NOCASE ORDER BY title ASC LIMIT :limit")
    suspend fun searchByTitle(query: String, limit: Int = 200): List<SeriesEntity>

    // ⚠️ Pagination (30/08/2026) — cf. MovieDao.getMoviesPage, même principe,
    // mêmes colonnes précalculées (languageCode/categoryStripped) et même
    // convention : `:lang IS NULL` = aucun filtre de langue, `:category IS
    // NULL` = "Toutes", [limit] négatif = aucune limite (catégorie précise
    // sélectionnée, chargée en entier).
    @Query("""
        SELECT * FROM series
        WHERE (:lang IS NULL OR languageCode = '' OR languageCode = :lang)
          AND (:category IS NULL OR category = :category)
        ORDER BY title ASC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getSeriesPage(lang: String?, category: String?, limit: Int, offset: Int): List<SeriesEntity>

    /** Cf. MovieDao.getDistinctCategoriesForLanguage — catégories brutes,
     * préfixe langue conservé (30/08/2026). */
    @Query("""
        SELECT DISTINCT category FROM series
        WHERE category != '' AND (:lang IS NULL OR languageCode = '' OR languageCode = :lang)
    """)
    suspend fun getDistinctCategoriesForLanguage(lang: String?): List<String>

    /** Cf. MovieDao.getRecentWithArt — même rôle pour le fond de l'accueil. */
    @Query("""
        SELECT * FROM series
        WHERE backdropUrl != '' OR posterUrl != ''
        ORDER BY updatedAt DESC
        LIMIT :limit
    """)
    fun getRecentWithArt(limit: Int): Flow<List<SeriesEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(series: SeriesEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(series: List<SeriesEntity>)

    @Query("DELETE FROM series")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM series")
    suspend fun count(): Int
}
