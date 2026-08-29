package com.nicotv.iptv2.util

/**
 * Catégorie ouverte PAR DÉFAUT et ORDRE d'affichage de la sidebar (écrans
 * Films et Chaînes) — demandes explicites des 30/08/2026.
 *
 * ⚠️ Les libellés ci-dessous ont été relevés sur le panel RÉEL de
 * l'utilisateur (dump `uiautomator` de la sidebar, 101 catégories films dont
 * 28 françaises), pas devinés. Point important : le panel écrit
 * **"FR - LE DRENIER AJOUTEE"** — avec la faute de frappe ("DRENIER", et
 * "AJOUTEE") — c'est exactement pour ça que la première version, qui
 * cherchait "LE DERNIER AJOUTE", ne matchait jamais et retombait sur la
 * catégorie suivante par ordre alphabétique. **Ne pas "corriger"
 * l'orthographe de ces fragments** : ils doivent coller au panel, pas au
 * français. Les variantes correctes restent listées derrière, au cas où le
 * fournisseur corrigerait un jour.
 *
 * Toute la correspondance est TOLÉRANTE (accents/casse ignorés, comparaison
 * par sous-chaîne) : les libellés portent des suffixes exotiques
 * ("ᴰᴼᴸᴮʸ ᴬᵁᴰᴵᴼ", "⁴ᴷ ³⁸⁴⁰ᴾ") qu'on ne veut pas avoir à écrire.
 */
private fun normalize(text: String): String = text.foldAccents().uppercase()

/**
 * @param categories liste déjà triée telle qu'affichée dans la sidebar —
 *   SANS l'entrée "Toutes", que l'adapter ajoute lui-même.
 * @param preferred fragments recherchés par ordre de préférence décroissante.
 * @return la catégorie à sélectionner, ou null seulement si [categories] est
 *   vide (l'écran reste alors sur "Toutes", faute de mieux).
 */
fun pickDefaultCategory(categories: List<String>, preferred: List<String>): String? {
    if (categories.isEmpty()) return null
    val normalized = categories.map { it to normalize(it) }
    for (fragment in preferred) {
        val target = normalize(fragment)
        normalized.firstOrNull { (_, n) -> n.contains(target) }?.let { return it.first }
    }
    return categories.first()
}

/** Écran Films — nouveautés en priorité. */
val MOVIES_PREFERRED_CATEGORIES = listOf(
    // Orthographe RÉELLE du panel (faute incluse) — cf. en-tête du fichier.
    "LE DRENIER AJOUTEE",
    "DRENIER AJOUTEE",
    // Variantes correctes, si le fournisseur corrige un jour.
    "LE DERNIER AJOUTE",
    "DERNIER AJOUTE",
    "DERNIERS AJOUTS",
    "NOUVEAUTE"
)

/** Écran Chaînes — "GENERAL FR" en priorité, puis un "GENERAL" quelconque. */
val CHANNELS_PREFERRED_CATEGORIES = listOf("GENERAL FR", "FR GENERAL", "GENERAL")

/**
 * Ordre d'affichage voulu pour la sidebar Films (30/08/2026, demande
 * explicite : "en première catégorie c'est FR - LE DRENIER AJOUTEE puis
 * FR - ACTION, FR - HORREUR, trouve l'ordre qui correspond").
 *
 * Logique retenue, du plus au moins consulté : **nouveautés** → **genres**
 * → **collections/qualité** → **plateformes** → **sport** → le reste. Une
 * catégorie non listée ici n'est pas perdue : elle passe simplement APRÈS
 * celles-ci, en gardant l'ordre habituel (françaises d'abord, cf.
 * `isFrenchLabel`, puis alphabétique) — c'est le cas de toutes les
 * catégories non francophones du panel (NETFLIX, NORDIC, PT/BR...).
 *
 * Les deux "LE DRENIER AJOUTEE" (normal et ᴰᴼᴸᴮʸ ᴬᵁᴰᴵᴼ) matchent le même
 * fragment : elles se départagent ensuite alphabétiquement, donc la version
 * simple passe devant la variante Dolby (préfixe plus court). Voulu.
 */
val MOVIES_CATEGORY_ORDER = listOf(
    // Nouveautés
    "LE DRENIER AJOUTEE",
    "FILM 2024",
    "NETFLIX 2025",
    // Genres principaux
    "ACTION",
    "HORREUR",
    "GUERRE",
    "HISTORIQUE BIOPIC",
    "ART MARTIAUX",
    "ASIATIQUE",
    "ANIMEE",
    "ANIMATION",
    "MANGA",
    "ACTEURS FRANCAIS",
    "2021 ANCIEN FILM",
    // Collections / qualité
    "FILMS EN SAGA",
    "FILM ⁴ᴷ",
    "FILM ᴰᴼᴸᴮʸ",
    "FILM CAM",
    "AUDIO-DESCRIPTION",
    "MUSIQUE",
    // Plateformes
    "NETFLIX FILMS",
    "AMAZONE",
    "PARAMOUNT+",
    // Sport
    "FOOTBALL",
    "GOLF",
    "HANDBALL",
    "MOTO"
)

/**
 * Trie [categories] selon [order] (fragments, correspondance par sous-chaîne
 * tolérante) : les catégories qui matchent viennent en premier, dans l'ordre
 * de [order] ; les autres suivent, triées comme avant (françaises d'abord
 * puis alphabétique — d'où [frenchFirst] passé par l'appelant plutôt que
 * réimplémenté ici).
 */
fun sortCategoriesByPreferredOrder(
    categories: List<String>,
    order: List<String>,
    frenchFirst: (String) -> Boolean
): List<String> {
    val normalizedOrder = order.map { normalize(it) }
    fun rank(category: String): Int {
        val n = normalize(category)
        val idx = normalizedOrder.indexOfFirst { n.contains(it) }
        return if (idx >= 0) idx else Int.MAX_VALUE
    }
    return categories.sortedWith(
        compareBy<String> { rank(it) }
            .thenByDescending { frenchFirst(it) }
            .thenBy { it }
    )
}
