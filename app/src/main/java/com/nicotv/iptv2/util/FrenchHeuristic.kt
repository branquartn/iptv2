package com.nicotv.iptv2.util

/** Heuristique "France" partagée (extraite de LiveViewModel.isFrench, 28/08/2026,
 * pour trier les catégories France en premier sur Chaînes/Films/Séries — cf.
 * CategorySidebarAdapter). Ni Xtream ni M3U n'exposent de champ pays exploitable
 * et les catégories des panels réels ne suivent aucune norme (`AFR| AFRICA VIP
 * HD/4K`, `4K| 24/7 UHD 3840P`…) → token exact `FR` (délimité, sinon `AFR`/
 * `OFFER` matcheraient) ou sous-chaîne `FRANCE`/`FRENCH`. */
fun isFrenchLabel(text: String): Boolean {
    val haystack = text.uppercase()
    if (haystack.contains("FRANCE") || haystack.contains("FRENCH")) return true
    return haystack.split(NON_ALNUM).any { it == "FR" }
}

private val NON_ALNUM = Regex("[^A-Z0-9]+")
