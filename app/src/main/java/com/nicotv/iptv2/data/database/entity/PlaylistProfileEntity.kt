package com.nicotv.iptv2.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Un profil de connexion sauvegardé (M3U url/fichier ou Xtream Codes), nommé
 * par l'utilisateur — permet de garder plusieurs sources et de rebasculer de
 * l'une à l'autre sans retaper les identifiants (écran de démarrage). */
@Entity(tableName = "playlist_profiles")
data class PlaylistProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: String, // SourceType.name : M3U_URL / M3U_FILE / XTREAM
    val m3uUrl: String = "",
    val m3uFileUri: String = "",
    val xtreamHost: String = "",
    val xtreamUsername: String = "",
    val xtreamPassword: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long = System.currentTimeMillis()
)
