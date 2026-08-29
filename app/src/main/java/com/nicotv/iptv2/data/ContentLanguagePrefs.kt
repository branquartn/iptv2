package com.nicotv.iptv2.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** Filtre de langue du contenu (Films/Séries/Chaînes), réglable dans Réglages
 * — demande explicite 28/08/2026. Codes découverts dynamiquement dans le
 * catalogue (util.extractLeadingLanguageCode), pas une liste figée — cf.
 * SettingsActivity.showContentLanguageDialog. null = Toutes (aucun filtre).
 *
 * Défaut tant que l'utilisateur n'a JAMAIS touché ce réglage : toujours "FR"
 * (revenu en arrière le 29/08/2026, demande explicite — un essai précédent le
 * même jour calait ce défaut sur la langue système de l'appareil, mais
 * l'utilisateur veut FR systématiquement à l'installation, peu importe la
 * langue du téléphone). Une fois l'utilisateur passé par le dialogue au moins
 * une fois — y compris pour choisir explicitement "Toutes" — son choix est
 * respecté pour toujours, d'où le sentinel [ALL] stocké en dur au lieu d'un
 * simple `remove()` (sinon "Toutes" choisi explicitement redevenait
 * indiscernable de "jamais touché" et se refaisait écraser par le défaut FR à
 * la prochaine lecture). */
class ContentLanguagePrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("iptv2_content_language", Context.MODE_PRIVATE)

    fun getLanguage(): String? {
        val stored = prefs.getString(KEY_LANGUAGE, null) ?: return FRENCH
        return if (stored == ALL) null else stored
    }

    fun setLanguage(language: String?) {
        prefs.edit { putString(KEY_LANGUAGE, language ?: ALL) }
    }

    companion object {
        const val FRENCH = "FR"
        private const val ALL = "__ALL__"
        private const val KEY_LANGUAGE = "language"
    }
}
