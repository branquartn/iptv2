package com.nicotv.iptv.domain.model

/** État d'un épisode dans la fiche série :
 *  - [seen]    : regardé jusqu'au bout (badge « ✓ Vu »).
 *  - [percent] : position de reprise en % (1..99) pour un épisode commencé non terminé.
 *  - [positionMs]/[durationMs] : temps réel affiché sur la fiche (comme la PWA :
 *    "12:34 / 45:00" au lieu d'un simple pourcentage/texte générique).
 *  La présence d'une entrée = « en cours » (reprise), indépendamment du pourcentage :
 *  la reprise s'affiche à partir de 5s de lecture (cf. MediaRepository.MIN_RESUME_MS)
 *  et jusqu'à la dernière position mémorisée. */
data class EpisodeProgress(
    val seen: Boolean,
    val percent: Int,
    val watchedAt: Long = 0L,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L
)
