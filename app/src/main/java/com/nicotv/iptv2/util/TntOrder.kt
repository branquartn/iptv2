package com.nicotv.iptv2.util

/**
 * Numérotation officielle de la TNT française (hertzien national, hors chaînes
 * locales/régionales) — ordre exact demandé le 28/08/2026 (TF1, France 2,
 * France 3...). Extrait de `LiveViewModel` le 29/08/2026 pour être partagé avec
 * `ChannelEntity` : le rang est désormais précalculé en base au chargement de
 * la playlist (colonne `tntRank`), pour que le tri "ordre TNT" puisse se faire
 * en SQL — indispensable avec la pagination (cf. CLAUDE.md), un tri appliqué
 * page par page en Kotlin donnerait un ordre global incohérent.
 *
 * `LiveViewModel` continue de l'utiliser pour le chemin RECHERCHE, qui n'est
 * pas paginé (résultat déjà borné à 200 lignes, trié en Kotlin) — une seule
 * source de vérité pour les deux chemins.
 */
private val TNT_ORDER = listOf(
    "TF1", "FRANCE 2", "FRANCE 3", "CANAL+", "FRANCE 5", "M6", "ARTE", "C8", "W9",
    "TMC", "TFX", "NRJ 12", "LCP", "FRANCE 4", "BFM TV", "CNEWS", "CSTAR", "GULLI",
    "TF1 SERIES FILMS", "EQUIPE", "6TER", "RMC STORY", "RMC DECOUVERTE",
    "CHERIE 25", "FRANCEINFO"
)

/** Rang TNT d'un nom de chaîne (0 = TF1), ou [Int.MAX_VALUE] si non reconnu —
 * relégué en fin de liste plutôt que de casser le tri. Comparaison par
 * sous-chaîne sur le nom nettoyé (accents/casse), tolérant aux préfixes de
 * playlist ("FR| TF1 HD", "FR - TF1", "TF1 FHD"...) : même limite heuristique
 * que `isFrenchLabel`, aucune source fiable de numéro de chaîne côté Xtream/M3U. */
fun tntRankFor(channelName: String): Int {
    val name = channelName.foldAccents().uppercase()
    val idx = TNT_ORDER.indexOfFirst { name.contains(it) }
    return if (idx >= 0) idx else Int.MAX_VALUE
}
