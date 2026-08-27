package com.nicotv.iptv2.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** Type de source d'un profil (cf. PlaylistProfileEntity.type). */
enum class SourceType { M3U_URL, M3U_FILE, XTREAM }

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

    companion object {
        private const val KEY_ACTIVE_ID = "active_profile_id"
    }
}
