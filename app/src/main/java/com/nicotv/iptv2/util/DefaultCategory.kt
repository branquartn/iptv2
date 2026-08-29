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

/*
 * ⚠️ Une liste d'ordre CODÉE EN DUR (MOVIES_CATEGORY_ORDER +
 * sortCategoriesByPreferredOrder) a existé ici quelques heures le 30/08/2026,
 * puis a été SUPPRIMÉE le jour même : l'utilisateur a proposé mieux ("peut-être
 * trier par id au lieu que par ordre alphabétique ? sinon récupère les
 * catégories dans la playlist téléchargée"). L'ordre vient désormais de la
 * SOURCE elle-même (colonne MovieEntity.categoryOrder, tri fait en SQL par les
 * DAO) — c'est l'ordre voulu par le fournisseur, celui des autres applis IPTV,
 * et il n'a pas besoin d'être maintenu à la main ni de deviner les libellés.
 * Ne pas réintroduire de liste en dur : elle redeviendrait fausse au premier
 * renommage de catégorie côté panel.
 */
