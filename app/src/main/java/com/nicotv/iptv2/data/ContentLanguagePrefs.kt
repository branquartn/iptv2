package com.nicotv.iptv2.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** Filtre de langue du contenu (Films/Séries), réglable dans Réglages —
 * demande explicite 28/08/2026. Une seule valeur gérée pour l'instant :
 * "FR" (heuristique util.isFrenchLabel appliquée titre+catégorie, même
 * principe que le filtre FR déjà existant sur l'écran Chaînes) — null =
 * Toutes. Champ conçu extensible (String, pas Boolean) si d'autres langues
 * sont ajoutées plus tard, mais seul "FR" est câblé aujourd'hui. */
class ContentLanguagePrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("iptv2_content_language", Context.MODE_PRIVATE)

    fun getLanguage(): String? = prefs.getString(KEY_LANGUAGE, null)

    fun setLanguage(language: String?) {
        prefs.edit {
            if (language == null) remove(KEY_LANGUAGE) else putString(KEY_LANGUAGE, language)
        }
    }

    companion object {
        const val FRENCH = "FR"
        private const val KEY_LANGUAGE = "language"
    }
}
