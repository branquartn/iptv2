package com.nicotv.iptv2.util

/** Code de langue/bouquet en tête d'un nom de chaîne ou d'une catégorie —
 * demande explicite 28/08/2026 : filtrer les chaînes par langue (exact, pas
 * l'heuristique `isFrenchLabel`) et retirer le préfixe une fois filtré.
 * Conventions réellement observées sur un panel Xtream (pas de norme, deux
 * délimiteurs différents suivant l'endroit) :
 * - noms de chaîne : "FR: TF1 HD", "AF: TF1" (deux-points ou barre verticale)
 * - catégories/titres film-série : "FR - Ghost (1990)", "CA| CINEMA FR"
 * D'où deux regex plutôt qu'une seule : `:`/`|` collés au code, ou ` - `
 * espacé (jamais l'inverse dans ce qui a été constaté). */
private val LEADING_CODE_COLON_PIPE = Regex("""^\s*([A-Za-z]{2,4})\s*[:|]\s*""")
private val LEADING_CODE_DASH = Regex("""^\s*([A-Za-z]{2,4})\s*-\s+""")

/** Code en tête ("FR", "AF"...) ou null si le texte n'en a pas — toujours en
 * MAJUSCULES (une chaîne "fr: tf1" donnerait "FR", jamais rencontré en
 * pratique mais plus sûr). */
fun extractLeadingLanguageCode(text: String): String? {
    LEADING_CODE_COLON_PIPE.find(text)?.let { return it.groupValues[1].uppercase() }
    LEADING_CODE_DASH.find(text)?.let { return it.groupValues[1].uppercase() }
    return null
}

/** Retire le préfixe correspondant à [code] ("FR: TF1 HD" + "FR" → "TF1 HD")
 * — insensible à la casse et au délimiteur (`:`/`|`/`-`). Renvoie [text]
 * inchangé si le préfixe ne correspond pas (filet, ne doit normalement pas
 * arriver puisqu'on ne l'appelle qu'après avoir vérifié le code exact). */
fun stripLeadingLanguageCode(text: String, code: String): String {
    val pattern = Regex("""^\s*${Regex.escape(code)}\s*[:|\-]\s*""", RegexOption.IGNORE_CASE)
    return text.replaceFirst(pattern, "").trim()
}
