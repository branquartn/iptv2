package com.nicotv.iptv2.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.nicotv.iptv2.data.database.entity.PlaylistProfileEntity
import org.json.JSONArray
import org.json.JSONObject

/**
 * Copie de secours des profils enregistrés, en SharedPreferences (JSON).
 *
 * Les profils vivent normalement dans Room (`playlist_profiles`), mais Room est
 * recréé de zéro à chaque montée de schéma (`fallbackToDestructiveMigration`,
 * choix assumé du projet — cf. AppDatabase) et une base peut aussi être vidée
 * par un incident (crash pendant l'écriture, nettoyage système, réinstallation).
 * Des profils perdus obligent à retaper hôte/utilisateur/mot de passe Xtream :
 * c'est justement ce qu'on ne veut jamais.
 *
 * Cette copie est réécrite à chaque enregistrement/suppression de profil, et
 * relue au démarrage : si Room est vide alors que la sauvegarde ne l'est pas,
 * les profils y sont réinsérés (cf. PlaylistRepository.restoreProfilesIfEmpty).
 *
 * SharedPreferences survit à tout ça — seule une désinstallation ou un effacement
 * manuel des données de l'app le vide.
 */
class ProfileBackupPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("iptv2_profiles_backup", Context.MODE_PRIVATE)

    fun save(profiles: List<PlaylistProfileEntity>) {
        try {
            val arr = JSONArray()
            profiles.forEach { p ->
                arr.put(
                    JSONObject().apply {
                        put("name", p.name)
                        put("type", p.type)
                        put("m3uUrl", p.m3uUrl)
                        put("m3uFileUri", p.m3uFileUri)
                        put("xtreamHost", p.xtreamHost)
                        put("xtreamUsername", p.xtreamUsername)
                        put("xtreamPassword", p.xtreamPassword)
                        put("createdAt", p.createdAt)
                        put("lastUsedAt", p.lastUsedAt)
                    }
                )
            }
            prefs.edit { putString(KEY_PROFILES, arr.toString()) }
            Log.i(TAG, "Sauvegarde de secours écrite : ${profiles.size} profil(s)")
        } catch (e: Exception) {
            // Best-effort : ne doit jamais faire échouer un enregistrement de profil.
            Log.w(TAG, "Échec de la sauvegarde de secours : ${e.message}")
        }
    }

    /** L'id n'est pas conservé : la restauration réinsère (nouveaux id Room). */
    fun load(): List<PlaylistProfileEntity> {
        val raw = prefs.getString(KEY_PROFILES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    add(
                        PlaylistProfileEntity(
                            name = o.optString("name"),
                            type = o.optString("type"),
                            m3uUrl = o.optString("m3uUrl"),
                            m3uFileUri = o.optString("m3uFileUri"),
                            xtreamHost = o.optString("xtreamHost"),
                            xtreamUsername = o.optString("xtreamUsername"),
                            xtreamPassword = o.optString("xtreamPassword"),
                            createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                            lastUsedAt = o.optLong("lastUsedAt", System.currentTimeMillis())
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Sauvegarde de secours illisible : ${e.message}")
            emptyList()
        }
    }

    companion object {
        private const val TAG = "ProfileBackup"
        private const val KEY_PROFILES = "profiles_json"
    }
}
