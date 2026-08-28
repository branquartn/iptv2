package com.nicotv.iptv2.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Cache local du mini-guide "en cours / à suivre" d'une chaîne Xtream
 * (get_short_epg). Clé = id local de la chaîne (ChannelEntity.id) — comme le
 * catalogue entier est réécrit à chaque chargement de profil (nouveaux id
 * autoIncrement), cette table est vidée en même temps (PlaylistRepository) :
 * pas de rattachement possible entre deux chargements, même limite que
 * favoris/reprise. [fetchedAt] pilote le TTL de rafraîchissement côté
 * PlaylistRepository.getShortEpg — pas de colonne "expire à" figée : le
 * programme "en cours" avance dans le temps, un TTL court (30 min) suffit à
 * l'invalider sans avoir à recalculer une date de fin à chaque lecture. */
@Entity(tableName = "epg_cache")
data class EpgCacheEntity(
    @PrimaryKey val channelId: Long,
    val nowTitle: String = "",
    val nowStart: Long = 0,
    val nowEnd: Long = 0,
    val nextTitle: String = "",
    val nextStart: Long = 0,
    val fetchedAt: Long = System.currentTimeMillis()
)
