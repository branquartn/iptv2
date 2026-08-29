package com.nicotv.iptv2.util

/**
 * Choix de la catégorie ouverte PAR DÉFAUT sur les écrans Films et Chaînes —
 * demande explicite 30/08/2026 : "dans films va directement dans FR - LE
 * DERNIER AJOUTE si il existe sinon vas dans un autre mais pas Toutes, et
 * pareil pour les chaînes va dans Général FR".
 *
 * ⚠️ Jamais "Toutes" (null) tant qu'au moins une catégorie existe : c'est tout
 * l'intérêt de la demande — "Toutes" ouvre le catalogue entier, justement le
 * cas le plus lourd (cf. CLAUDE.md, sections pagination), alors qu'une
 * catégorie précise se charge en entier et instantanément.
 *
 * La correspondance est volontairement TOLÉRANTE (accents/casse ignorés via
 * foldAccents, comparaison par sous-chaîne) : les libellés réels varient d'un
 * panel à l'autre et gardent désormais leur préfixe de langue ("FR - LE
 * DERNIER AJOUTE", "FR | LE DERNIER AJOUTÉ"...). Même limite heuristique
 * qu'`isFrenchLabel`/`tntRankFor` : un panel hors conventions ne matchera pas,
 * et on retombe alors sur la première catégorie de la liste — jamais sur
 * "Toutes".
 */
private fun normalize(text: String): String = text.foldAccents().uppercase()

/**
 * @param categories liste déjà triée telle qu'affichée dans la sidebar (France
 *   en premier, cf. isFrenchLabel) — SANS l'entrée "Toutes", que l'adapter
 *   ajoute lui-même.
 * @param preferred fragments recherchés par ordre de préférence décroissante ;
 *   le premier qui matche une catégorie gagne.
 * @return la catégorie à sélectionner, ou null seulement si [categories] est
 *   vide (auquel cas l'écran reste sur "Toutes", faute de mieux).
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

/** Écran Films — "LE DERNIER AJOUTE" (nouveautés) en priorité. */
val MOVIES_PREFERRED_CATEGORIES = listOf("LE DERNIER AJOUTE", "DERNIER AJOUTE", "DERNIERS AJOUTS", "NOUVEAUTE")

/** Écran Chaînes — "GENERAL FR" en priorité, puis un "GENERAL" quelconque. */
val CHANNELS_PREFERRED_CATEGORIES = listOf("GENERAL FR", "FR GENERAL", "GENERAL")
