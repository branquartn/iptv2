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
    // Cf. MovieEntity (audit perf 30/08/2026). Ici l'ORDER BY dépend d'un
    // CASE (tntRank ou sortOrder selon le contexte français), qu'aucun index ne
    // peut couvrir — d'où un index sur la seule "category" : il sert au
    // positionnement (WHERE) et au GROUP BY de la sidebar, le tri restant à la
    // charge de SQLite sur un sous-ensemble déjà réduit. `categoryOrder` en
    // queue rend la requête des catégories couvrante (cf. MovieEntity).
    indices = [
        Index(value = ["name", "streamUrl"], unique = true),
        Index(value = ["category", "sortOrder", "categoryOrder"]),
        Index(value = ["sortOrder"])
    ]
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
    // ⚠️ PLUS UTILISÉ POUR LE TRI depuis le 30/08/2026 : les chaînes suivent
    // désormais l'ordre du panel (`sortOrder`), comme les films — demande
    // explicite "garde l'ordre comme les films", qui annule le tri "ordre TNT"
    // du 28/08. La colonne reste calculée et stockée à dessein : la supprimer
    // imposerait une migration destructive (donc un rechargement complet de la
    // playlist) alors que la garder ne coûte rien — et restaurer le tri TNT
    // redeviendrait un simple changement d'ORDER BY.
    @ColumnInfo(defaultValue = "2147483647") val tntRank: Int = Int.MAX_VALUE,
    /** Cf. MovieEntity.categoryOrder — ordre de la catégorie dans la source. */
    @ColumnInfo(defaultValue = "0") val categoryOrder: Int = 0
) {
    /** ⚠️ Le nom est affiché BRUT (30/08/2026, demande explicite : "ne renomme
     * pas les chaînes") — le préfixe de langue reste visible, exactement comme
     * pour les catégories. Annule la demande du 28/08 ("enlève le FR|").
     * `nameStripped` reste calculé et stocké : le garder ne coûte rien et
     * évite une migration destructive (donc un rechargement complet de la
     * playlist) si l'affichage nettoyé était redemandé un jour. */
    fun toDomain(isFavorite: Boolean = false) = Channel(
        id = id,
        name = name,
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
