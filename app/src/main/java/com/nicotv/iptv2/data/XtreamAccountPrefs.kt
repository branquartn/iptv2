package com.nicotv.iptv2.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.nicotv.iptv2.data.xtream.XtAccountInfo

/** Dernières infos d'abonnement Xtream connues, par id de profil. En
 * SharedPreferences plutôt qu'en colonne Room : la base est en
 * fallbackToDestructiveMigration, un changement de schéma effacerait les
 * profils (cf. ProfileBackupPrefs). */
class XtreamAccountPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("iptv2_xtream_account", Context.MODE_PRIVATE)

    fun get(profileId: Long): XtAccountInfo? {
        if (!prefs.contains("checked_$profileId")) return null
        val exp = prefs.getLong("exp_$profileId", -1L)
        return XtAccountInfo(
            expDateMs = if (exp <= 0L) null else exp,
            status = prefs.getString("status_$profileId", "").orEmpty(),
            checkedAt = prefs.getLong("checked_$profileId", 0L)
        )
    }

    fun set(profileId: Long, info: XtAccountInfo) {
        prefs.edit {
            putLong("exp_$profileId", info.expDateMs ?: -1L)
            putString("status_$profileId", info.status)
            putLong("checked_$profileId", info.checkedAt)
        }
    }

    fun remove(profileId: Long) {
        prefs.edit {
            remove("exp_$profileId")
            remove("status_$profileId")
            remove("checked_$profileId")
        }
    }
}
