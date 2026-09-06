package com.nicotv.iptv2.util

import android.content.Context
import java.util.UUID

/** Identifiant d'appareil persistant, généré une fois et gardé en SharedPreferences.
 *  Sert UNIQUEMENT à répartir le quota de transcodage public (`pub_remux`, serveur
 *  NicoTV) entre appareils — pas un compte, pas vérifiable côté serveur (auto-déclaré),
 *  juste évite qu'un seul appareil sature son propre quota lors d'un double lancement. */
object DeviceId {
    private const val PREFS = "device_id_prefs"
    private const val KEY = "device_id"

    fun get(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY, null)?.let { return it }
        val id = UUID.randomUUID().toString()
        prefs.edit().putString(KEY, id).apply()
        return id
    }
}
