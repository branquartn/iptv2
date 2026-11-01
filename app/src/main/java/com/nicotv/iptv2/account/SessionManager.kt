package com.nicotv.iptv2.account

import android.content.Context
import androidx.core.content.edit

/** Session NicoTV (même compte que l'app iptv) : jeton + identité, en SharedPreferences. */
class SessionManager(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("iptv2_session", Context.MODE_PRIVATE)

    fun isLoggedIn(): Boolean = getToken().isNotBlank()
    fun getToken(): String = prefs.getString(KEY_TOKEN, "") ?: ""
    fun getUsername(): String = prefs.getString(KEY_USER, "") ?: ""
    fun isAdmin(): Boolean = prefs.getBoolean(KEY_ADMIN, false)
    fun bearer(): String = "Bearer " + getToken()

    fun save(username: String, token: String, isAdmin: Boolean) = prefs.edit {
        putString(KEY_USER, username); putString(KEY_TOKEN, token); putBoolean(KEY_ADMIN, isAdmin)
    }

    fun updateToken(token: String) = prefs.edit { putString(KEY_TOKEN, token) }

    // Correspondance « id du code côté serveur → id du profil local » (JSON) : permet de savoir quels
    // profils viennent du serveur (les autres, saisis à la main avant le login, sont ignorés/supprimés).
    fun getRemoteMap(): MutableMap<Long, Long> {
        val raw = prefs.getString(KEY_REMOTE_MAP, "{}") ?: "{}"
        val out = mutableMapOf<Long, Long>()
        runCatching {
            val o = org.json.JSONObject(raw)
            o.keys().forEach { k -> out[k.toLong()] = o.getLong(k) }
        }
        return out
    }

    fun saveRemoteMap(map: Map<Long, Long>) = prefs.edit {
        val o = org.json.JSONObject(); map.forEach { (k, v) -> o.put(k.toString(), v) }
        putString(KEY_REMOTE_MAP, o.toString())
    }

    fun getLastSyncAt(): Long = prefs.getLong(KEY_LAST_SYNC, 0L)
    fun setLastSyncAt(t: Long) = prefs.edit { putLong(KEY_LAST_SYNC, t) }

    fun clear() = prefs.edit { clear() }

    companion object {
        private const val KEY_TOKEN = "token"
        private const val KEY_USER = "username"
        private const val KEY_ADMIN = "is_admin"
        private const val KEY_REMOTE_MAP = "remote_map"
        private const val KEY_LAST_SYNC = "last_sync"
    }
}
