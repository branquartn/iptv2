package com.nicotv.iptv2.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.nicotv.iptv2.domain.model.Channel
import com.nicotv.iptv2.util.leadingLanguageCodeOrEmpty
import com.nicotv.iptv2.util.tntRankFor
import com.nicotv.iptv2.util.withoutLeadingLanguageCode

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
    val sortOrder: Int = 0,
    // Vide pour une chaîne issue d'un M3U — rempli pour Xtream (id propre à
    // cette source, utilisé pour les appels d'API dédiés à ces chaînes).
    val xtreamStreamId: String = "",
    // ⚠️ Ajoutés 29/08/2026 (pagination écran Chaînes) — cf. MovieEntity pour
    // le principe général. Deux paires ici, contrairement aux films/séries,
    // parce que l'écran Chaînes filtre la langue sur le NOM de la chaîne
    // ("FR: TF1 HD") mais construit sa sidebar sur le préfixe de la CATÉGORIE
    // ("FR| Sport") — deux conventions distinctes constatées sur un panel réel
    // (cf. util.LanguageCode), déjà traitées séparément avant la pagination.
    @ColumnInfo(defaultValue = "") val nameLanguageCode: String = "",
    @ColumnInfo(defaultValue = "") val nameStripped: String = "",
    @ColumnInfo(defaultValue = "") val categoryLanguageCode: String = "",
    @ColumnInfo(defaultValue = "") val categoryStripped: String = "",
    // Rang dans la numérotation TNT française (cf. util.tntRankFor) — précalculé
    // car le tri "ordre TNT" doit désormais se faire en SQL : trié page par page
    // en Kotlin (comme avant la pagination), l'ordre global serait incohérent.
    // Int.MAX_VALUE = chaîne non reconnue, reléguée en fin de liste.
    @ColumnInfo(defaultValue = "2147483647") val tntRank: Int = Int.MAX_VALUE
) {
    /** [useStrippedName] : vrai quand un filtre de langue est actif — le
     * préfixe est alors retiré du nom affiché (demande explicite 28/08/2026,
     * "enlève le FR|"). Faux quand le réglage vaut "Toutes" : aucun filtre, on
     * affiche les noms bruts tels que la playlist les fournit. */
    fun toDomain(isFavorite: Boolean = false, useStrippedName: Boolean = false) = Channel(
        id = id,
        name = if (useStrippedName) nameStripped else name,
        streamUrl = streamUrl,
        logoUrl = logoUrl,
        category = category,
        isFavorite = isFavorite,
        xtreamStreamId = xtreamStreamId
    )

    companion object {
        /** Cf. MovieEntity.languageCodeFor — mêmes helpers partagés, appliqués
         * ici au nom ET à la catégorie (cf. commentaire sur les colonnes). */
        fun nameLanguageCodeFor(name: String): String = leadingLanguageCodeOrEmpty(name)
        fun nameStrippedFor(name: String): String = withoutLeadingLanguageCode(name)
        fun categoryLanguageCodeFor(category: String): String = leadingLanguageCodeOrEmpty(category)
        fun categoryStrippedFor(category: String): String = withoutLeadingLanguageCode(category)
        fun tntRankForName(name: String): Int = tntRankFor(name)
    }
}
