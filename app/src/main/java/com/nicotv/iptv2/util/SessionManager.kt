package com.nicotv.iptv2.util

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

class SessionManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("iptv_session", Context.MODE_PRIVATE)

    fun saveSession(username: String, token: String, isAdmin: Boolean) {
        prefs.edit {
            putString(KEY_USERNAME, username)
            putString(KEY_TOKEN, token)
            putBoolean(KEY_IS_ADMIN, isAdmin)
            putBoolean(KEY_LOGGED_IN, true)
        }
    }

    fun isLoggedIn(): Boolean = prefs.getBoolean(KEY_LOGGED_IN, false)

    fun getUsername(): String = prefs.getString(KEY_USERNAME, "") ?: ""

    fun getToken(): String = prefs.getString(KEY_TOKEN, "") ?: ""

    fun isAdmin(): Boolean = prefs.getBoolean(KEY_IS_ADMIN, false)

    fun bearer(): String = "Bearer ${getToken()}"

    /** Identifiant stable de CET appareil (indépendant du compte connecté, survit
     *  logout/re-login) — distingue plusieurs sessions du même compte dans le
     *  panel admin.nicotv.ovh « Qui regarde quoi » (table iptv_presence, clé
     *  composite uid+device_id). Généré une fois, jamais effacé par clearSession(). */
    fun getOrCreateDeviceId(): String {
        prefs.getString(KEY_DEVICE_ID, null)?.let { return it }
        val id = java.util.UUID.randomUUID().toString()
        prefs.edit { putString(KEY_DEVICE_ID, id) }
        return id
    }

    fun clearSession() {
        prefs.edit {
            remove(KEY_USERNAME); remove(KEY_TOKEN); remove(KEY_IS_ADMIN); remove(KEY_LOGGED_IN)
        }
    }

    companion object {
        private const val KEY_USERNAME = "username"
        private const val KEY_TOKEN = "token"
        private const val KEY_IS_ADMIN = "is_admin"
        private const val KEY_LOGGED_IN = "logged_in"
        private const val KEY_DEVICE_ID = "device_id"
    }
}
