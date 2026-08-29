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

/**
 * ⚠️ LISTE BLANCHE OBLIGATOIRE (30/08/2026) — sans elle, la regex seule prend
 * **n'importe quel** mot de 2 à 4 lettres suivi de `:` ou `|` pour un code
 * langue. Bug vécu, signalé "pour les chaînes il m'en manque" : sur le panel
 * réel, des centaines de chaînes s'appellent `VIP: BEIN SPORTS 1`,
 * `VIP: CANAL+ FOOT`… — "VIP" était donc lu comme une langue, différente de
 * "FR", et **toutes ces chaînes disparaissaient** du filtre "Langue du
 * contenu". Même piège en puissance avec `RAW:`, `MAX:`, `NEW:`, `TOP:`…
 *
 * Le sens de l'erreur est choisi exprès : un code ABSENT de cette liste est
 * traité comme "pas de langue", donc l'élément est **gardé** (et son préfixe
 * reste affiché). Au pire on montre une chaîne de trop ; jamais on n'en perd —
 * l'inverse d'une liste noire, qui laisserait passer tout marqueur oublié et
 * continuerait à faire disparaître du contenu en silence.
 *
 * Ajouter un code ici seulement s'il désigne vraiment une langue/un pays.
 */
private val LANGUAGE_CODES = setOf(
    // Codes 2 lettres (ISO 639-1 / pays) réellement croisés sur ces panels
    "FR", "EN", "AR", "DE", "ES", "IT", "PT", "NL", "BE", "CH", "CA", "AF",
    "TR", "PL", "RU", "RO", "GR", "SE", "NO", "DK", "FI", "IS", "HU", "CZ",
    "SK", "BG", "HR", "RS", "SR", "AL", "MK", "UA", "IL", "IR", "IN", "PK",
    "TH", "VN", "CN", "JP", "KR", "BR", "MX", "US", "UK", "GB", "LU", "MA",
    "DZ", "TN", "EG", "QA", "AE", "SA", "KW", "EX", "LB", "SY", "JO", "YE",
    // Codes 3 lettres (ISO 639-2 / pays)
    "ENG", "FRA", "FRE", "GER", "DEU", "SPA", "ESP", "POR", "ITA", "NED",
    "DUT", "TUR", "POL", "RUS", "ARA", "SWE", "NOR", "DAN", "FIN", "GRE",
    "ROM", "HUN", "CZE", "SVK", "HRV", "SRP", "UKR", "HEB", "HIN", "THA",
    "VIE", "CHI", "JPN", "KOR", "USA", "GBR", "BEL", "SUI", "CAN", "BRA",
    "MEX", "ARG", "MAR", "ALG", "TUN", "EGY", "AFR", "SUO"
)

/** Code en tête ("FR", "AF"...) ou null si le texte n'en a pas — toujours en
 * MAJUSCULES (une chaîne "fr: tf1" donnerait "FR", jamais rencontré en
 * pratique mais plus sûr). Renvoie null si le préfixe trouvé n'est pas un vrai
 * code langue (cf. [LANGUAGE_CODES] : "VIP:", "RAW:"...). */
fun extractLeadingLanguageCode(text: String): String? {
    val candidate = LEADING_CODE_COLON_PIPE.find(text)?.groupValues?.get(1)
        ?: LEADING_CODE_DASH.find(text)?.groupValues?.get(1)
        ?: return null
    val code = candidate.uppercase()
    return if (code in LANGUAGE_CODES) code else null
}

/** Retire le préfixe correspondant à [code] ("FR: TF1 HD" + "FR" → "TF1 HD")
 * — insensible à la casse et au délimiteur (`:`/`|`/`-`). Renvoie [text]
 * inchangé si le préfixe ne correspond pas (filet, ne doit normalement pas
 * arriver puisqu'on ne l'appelle qu'après avoir vérifié le code exact). */
fun stripLeadingLanguageCode(text: String, code: String): String {
    val pattern = Regex("""^\s*${Regex.escape(code)}\s*[:|\-]\s*""", RegexOption.IGNORE_CASE)
    return text.replaceFirst(pattern, "").trim()
}

/** Variantes "colonne Room" des deux fonctions ci-dessus — utilisées pour
 * précalculer le code langue / le libellé nettoyé AU CHARGEMENT de la playlist
 * (cf. MovieEntity/SeriesEntity/ChannelEntity), plutôt que de les recalculer en
 * Kotlin sur chaque ligne à chaque ouverture d'écran (cf. CLAUDE.md, section
 * "Pagination"). Une colonne Room ne pouvant pas être `null` sans complexifier
 * les requêtes, l'absence de code est représentée par la chaîne vide.
 *
 * ⚠️ Invariant sur lequel repose tout le filtrage SQL : quand
 * [leadingLanguageCodeOrEmpty] renvoie "" (aucun préfixe détecté),
 * [withoutLeadingLanguageCode] renvoie le texte INCHANGÉ — donc, une fois le
 * filtre langue appliqué (code vide OU code == langue choisie), la version
 * "nettoyée" est toujours la bonne valeur à afficher/comparer, sans avoir à
 * refaire le test au runtime. */
fun leadingLanguageCodeOrEmpty(text: String): String = extractLeadingLanguageCode(text) ?: ""

fun withoutLeadingLanguageCode(text: String): String {
    val code = extractLeadingLanguageCode(text) ?: return text
    return stripLeadingLanguageCode(text, code)
}
