package com.nicotv.iptv2.data.database.entity

import androidx.room.Entity

/** Favori unifié : [itemType] distingue à quelle table [itemId] fait référence
 * (pas de clé étrangère Room — les 3 tables sources ont chacune leur propre
 * PrimaryKey auto-générée, aucun risque de collision d'id entre elles ici
 * puisqu'on filtre toujours par type). Clé primaire composite (itemId, itemType) :
 * un même id de film et de série ne se marchent pas dessus. */
// ⚠️ Index sur itemType (30/08/2026) : la clé primaire est (itemId, itemType),
// or toutes les requêtes filtrent par itemType SEUL — ce n'est pas la colonne
// de tête de la PK, donc son index ne pouvait pas servir. Table petite, mais
// elle est lue à chaque page de catalogue (jointure favoris).
@Entity(
    tableName = "favorites",
    primaryKeys = ["itemId", "itemType"],
    indices = [androidx.room.Index(value = ["itemType"])]
)
data class FavoriteEntity(
    val itemId: Long,
    val itemType: String, // MOVIE / SERIES / CHANNEL — cf. Type ci-dessous
    val addedAt: Long = System.currentTimeMillis()
) {
    object Type {
        const val MOVIE = "MOVIE"
        const val SERIES = "SERIES"
        const val CHANNEL = "CHANNEL"
    }
}
