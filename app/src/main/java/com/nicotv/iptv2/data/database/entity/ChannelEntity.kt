package com.nicotv.iptv2.data.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.nicotv.iptv2.domain.model.Channel

/** Chaîne live TV. Index unique sur (name, streamUrl) : un rechargement de la
 * playlist remplace la ligne au lieu de créer un doublon (deux chaînes peuvent
 * légitimement partager un nom, ex. plusieurs qualités d'un même flux). */
@Entity(
    tableName = "channels",
    indices = [Index(value = ["name", "streamUrl"], unique = true)]
)
data class ChannelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val streamUrl: String,
    val logoUrl: String = "",
    val category: String = "",
    val sortOrder: Int = 0
) {
    fun toDomain(isFavorite: Boolean = false) = Channel(
        id = id,
        name = name,
        streamUrl = streamUrl,
        logoUrl = logoUrl,
        category = category,
        isFavorite = isFavorite
    )
}
