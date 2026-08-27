package com.nicotv.iptv2.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** Type de source playlist choisi à l'écran de configuration (pas de compte,
 * pas de login — juste une source à charger). */
enum class SourceType { NONE, M3U_URL, M3U_FILE, XTREAM }

/** Config de la source active, lue par PlaylistRepository pour (re)charger le
 * catalogue. Persistée en SharedPreferences (quelques champs texte, pas besoin
 * de DataStore ici). */
data class PlaylistSource(
    val type: SourceType,
    val m3uUrl: String = "",
    // URI SAF (content://...) du fichier M3U local choisi via ACTION_OPEN_DOCUMENT.
    // La permission de lecture est prise en persistante (takePersistableUriPermission)
    // à la sélection — nécessaire pour pouvoir relire ce fichier plus tard (relance de
    // l'app, refresh manuel) sans redemander le sélecteur de fichiers.
    val m3uFileUri: String = "",
    val xtreamHost: String = "",
    val xtreamUsername: String = "",
    val xtreamPassword: String = ""
) {
    val isConfigured: Boolean get() = type != SourceType.NONE
}

class PlaylistSourcePrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("iptv2_source", Context.MODE_PRIVATE)

    fun get(): PlaylistSource {
        val type = runCatching { SourceType.valueOf(prefs.getString(KEY_TYPE, null) ?: "") }
            .getOrDefault(SourceType.NONE)
        return PlaylistSource(
            type = type,
            m3uUrl = prefs.getString(KEY_M3U_URL, "") ?: "",
            m3uFileUri = prefs.getString(KEY_M3U_FILE_URI, "") ?: "",
            xtreamHost = prefs.getString(KEY_XTREAM_HOST, "") ?: "",
            xtreamUsername = prefs.getString(KEY_XTREAM_USER, "") ?: "",
            xtreamPassword = prefs.getString(KEY_XTREAM_PASS, "") ?: ""
        )
    }

    fun saveM3uUrl(url: String) {
        prefs.edit {
            putString(KEY_TYPE, SourceType.M3U_URL.name)
            putString(KEY_M3U_URL, url)
        }
    }

    fun saveM3uFile(uri: String) {
        prefs.edit {
            putString(KEY_TYPE, SourceType.M3U_FILE.name)
            putString(KEY_M3U_FILE_URI, uri)
        }
    }

    fun saveXtream(host: String, username: String, password: String) {
        prefs.edit {
            putString(KEY_TYPE, SourceType.XTREAM.name)
            putString(KEY_XTREAM_HOST, host)
            putString(KEY_XTREAM_USER, username)
            putString(KEY_XTREAM_PASS, password)
        }
    }

    fun clear() {
        prefs.edit { clear() }
    }

    companion object {
        private const val KEY_TYPE = "type"
        private const val KEY_M3U_URL = "m3u_url"
        private const val KEY_M3U_FILE_URI = "m3u_file_uri"
        private const val KEY_XTREAM_HOST = "xtream_host"
        private const val KEY_XTREAM_USER = "xtream_user"
        private const val KEY_XTREAM_PASS = "xtream_pass"
    }
}
