package com.nicotv.iptv2.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** Type de source d'un profil (cf. PlaylistProfileEntity.type). */
enum class SourceType { M3U_URL, M3U_FILE, XTREAM, SERVER }

/** Ne retient que l'id du profil actif — les profils eux-mêmes (nom,
 * identifiants) vivent dans Room (PlaylistProfileEntity), pas ici : plusieurs
 * profils peuvent être sauvegardés, un seul est chargé à la fois. */
class PlaylistSourcePrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("iptv2_source", Context.MODE_PRIVATE)

    fun getActiveProfileId(): Long? {
        val id = prefs.getLong(KEY_ACTIVE_ID, -1L)
        return if (id == -1L) null else id
    }

    fun setActiveProfileId(id: Long?) {
        prefs.edit {
            if (id == null) remove(KEY_ACTIVE_ID) else putLong(KEY_ACTIVE_ID, id)
        }
    }

    /** Date (ms) du dernier chargement COMPLET réussi du catalogue de ce profil ; 0 = jamais chargé. */
    fun getLoadedAt(profileId: Long): Long = prefs.getLong("loaded_at_$profileId", 0L)
    fun setLoadedAt(profileId: Long, t: Long = System.currentTimeMillis()) = prefs.edit { putLong("loaded_at_$profileId", t) }

    /** Ancien catalogue unique (base principale) déjà vidé ? cf. PlaylistRepository.init. */
    fun isCatalogMigrated(): Boolean = prefs.getBoolean("catalog_per_profile_v1", false)
    fun setCatalogMigrated() = prefs.edit { putBoolean("catalog_per_profile_v1", true) }

    companion object {
        private const val KEY_ACTIVE_ID = "active_profile_id"
    }
}
