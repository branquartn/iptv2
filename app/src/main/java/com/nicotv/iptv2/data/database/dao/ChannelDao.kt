package com.nicotv.iptv2.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.nicotv.iptv2.data.database.entity.ChannelEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChannelDao {
    @Query("SELECT * FROM channels ORDER BY sortOrder ASC, name ASC")
    fun getAllChannels(): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): ChannelEntity?

    @Query("SELECT DISTINCT category FROM channels WHERE category != '' ORDER BY category ASC")
    fun getCategories(): Flow<List<String>>

    /** Lecture ponctuelle des noms — cf. PlaylistRepository.
     * getAvailableContentLanguages (découverte des codes langue, une seule
     * fois à l'ouverture du sélecteur dans Réglages, pas un Flow). */
    @Query("SELECT name FROM channels")
    suspend fun getAllChannelNamesOnce(): List<String>

    /** Recherche (écran Search) — cf. MovieDao.searchByTitle, même raison. */
    @Query("SELECT * FROM channels WHERE name LIKE '%' || :query || '%' COLLATE NOCASE ORDER BY name ASC LIMIT :limit")
    suspend fun searchByName(query: String, limit: Int = 200): List<ChannelEntity>

    // ⚠️ Pagination (30/08/2026) — cf. MovieDao.getMoviesPage. Trois
    // spécificités propres à l'écran Chaînes, toutes reprises telles quelles de
    // l'ancien filtrage Kotlin (LiveViewModel.filteredChannels) :
    // 1. le filtre de langue porte sur le NOM (nameLanguageCode), pas sur la
    //    catégorie — deux conventions distinctes, cf. ChannelEntity ;
    // 2. le filtre "favoris uniquement" (:favOnly = 1) passe par une
    //    sous-requête sur la table favorites (pas de clé étrangère Room, cf.
    //    FavoriteEntity — on filtre toujours par itemType) ;
    // 3. le tri suit l'ordre du panel (sortOrder) — il DOIT être en SQL :
    //    appliqué page par page en Kotlin, l'ordre global serait incohérent.
    // [limit] négatif = aucune limite (catégorie précise sélectionnée).
    @Query("""
        SELECT * FROM channels
        WHERE (:lang IS NULL OR nameLanguageCode = '' OR nameLanguageCode = :lang)
          AND (:category IS NULL OR category = :category)
          AND (:favOnly = 0 OR id IN (SELECT itemId FROM favorites WHERE itemType = :favType))
        -- ⚠️ Ordre du PANEL (30/08/2026, "garde l'ordre comme les films") :
        -- `sortOrder` = index dans get_live_streams côté Xtream, ordre
        -- d'apparition côté M3U. Remplace le tri "ordre TNT" (tntRank) du
        -- 28/08. `id` en dernier pour un ordre TOTAL, sinon la pagination peut
        -- dupliquer ou perdre des lignes (cf. doublons du 30/08).
        ORDER BY sortOrder ASC, id ASC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getChannelsPage(
        lang: String?,
        category: String?,
        favOnly: Int,
        favType: String,
        limit: Int,
        offset: Int
    ): List<ChannelEntity>

    /** Cf. MovieDao.getDistinctCategoriesForLanguage — catégories brutes,
     * préfixe langue conservé (30/08/2026), triées dans l'ORDRE DE LA SOURCE
     * (cf. categoryOrder) — `GROUP BY` + `MIN(categoryOrder)` car une catégorie
     * couvre plusieurs lignes ; `category` en second critère départage un
     * catalogue chargé avant cette version (tous les rangs à 0). Filtré sur le préfixe de la
     * CATÉGORIE (categoryLanguageCode), pas celui du nom : la sidebar liste
     * des catégories, cf. ChannelEntity. */
    @Query("""
        SELECT category FROM channels
        WHERE category != '' AND (:lang IS NULL OR categoryLanguageCode = '' OR categoryLanguageCode = :lang)
        GROUP BY category
        ORDER BY MIN(categoryOrder) ASC, category ASC
    """)
    suspend fun getDistinctCategoriesForLanguage(lang: String?): List<String>

    /** Chaînes désignées par les favoris — cf. PlaylistRepository.
     * getFavoriteChannels. ⚠️ Liste à découper par l'appelant. */
    @Query("SELECT * FROM channels WHERE id IN (:ids) ORDER BY sortOrder ASC, name ASC")
    suspend fun getChannelsByIds(ids: List<Long>): List<ChannelEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(channels: List<ChannelEntity>)

    @Query("DELETE FROM channels")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM channels")
    suspend fun count(): Int
}
