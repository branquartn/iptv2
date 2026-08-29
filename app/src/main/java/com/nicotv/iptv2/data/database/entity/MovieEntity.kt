package com.nicotv.iptv2.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.nicotv.iptv2.domain.model.Movie
import com.nicotv.iptv2.util.leadingLanguageCodeOrEmpty
import com.nicotv.iptv2.util.withoutLeadingLanguageCode

/** Un film VOD. Index unique (title, streamUrl) : un rechargement de la playlist
 * met à jour la ligne existante au lieu d'en créer une doublonnée. */
@Entity(
    tableName = "movies",
    // ⚠️ Index ajoutés 30/08/2026 (audit perf). Sans eux, CHAQUE requête de
    // l'écran Films faisait un balayage complet de la table (~47 000 lignes sur
    // le panel de test) : filtrer par catégorie, lister les catégories
    // (GROUP BY), ou sortir les 12 dernières jaquettes du fond d'accueil.
    // - ("category", "sortOrder", "categoryOrder") : couvre le cas le PLUS
    //   chaud — une catégorie précise, triée dans l'ordre du panel (l'écran par
    //   défaut). SQLite se positionne directement sur la catégorie ET les
    //   lignes sortent déjà triées, donc aucun tri à faire. `categoryOrder` en
    //   QUEUE (il n'entre dans aucun filtre) rend en prime la requête des
    //   catégories `GROUP BY category ORDER BY MIN(categoryOrder)` satisfaisable
    //   depuis l'index seul — un index couvrant plutôt qu'un index de plus.
    // - ("sortOrder") : même tri, sans catégorie sélectionnée ("Toutes").
    // - ("languageCode") : filtre "Langue du contenu".
    // - ("updatedAt") : ORDER BY updatedAt DESC LIMIT 12 du fond d'accueil.
    // Chaque index coûte au CHARGEMENT de la playlist (47 000 insertions) : ne
    // pas en ajouter sans vérifier qu'aucun existant ne peut couvrir le besoin
    // en réordonnant ses colonnes.
    indices = [
        Index(value = ["title", "streamUrl"], unique = true),
        Index(value = ["category", "sortOrder", "categoryOrder"]),
        Index(value = ["sortOrder"]),
        Index(value = ["languageCode"]),
        Index(value = ["updatedAt"])
    ]
)
data class MovieEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val streamUrl: String,
    val posterUrl: String = "",
    val backdropUrl: String = "",
    val overview: String = "",
    val releaseYear: String = "",
    val runtime: Int = 0,
    val rating: Float = 0f,
    val genres: String = "",
    val category: String = "",
    // Résolu au chargement de la playlist (recherche TMDb par titre, cf.
    // PlaylistRepository.enrichMovies) — 0 si aucune correspondance trouvée.
    // Réutilisé par la fiche détail pour éviter de re-chercher à l'ouverture.
    // @ColumnInfo(defaultValue) OBLIGATOIRE ici : sans lui, le schéma que Room
    // attend (colonne NOT NULL sans DEFAULT) ne correspond plus à celui produit
    // par MIGRATION_2_3 (ALTER TABLE ... DEFAULT 0 — SQLite l'exige pour ajouter
    // une colonne NOT NULL à une table non vide) → Room refuse la migration et
    // l'app crash au démarrage (IllegalStateException: Migration didn't
    // properly handle...), même symptôme que "rien ne s'affiche".
    @ColumnInfo(defaultValue = "0")
    val tmdbId: Int = 0,
    val updatedAt: Long = System.currentTimeMillis(),
    // Vide pour un film issu d'un M3U — rempli pour Xtream (stream_id), utilisé
    // par PlaylistRepository.enrichMovieFromXtreamIfNeeded pour aller chercher
    // le synopsis via get_vod_info à l'ouverture de la fiche (get_vod_streams,
    // liste en masse, ne le fournit pas sur la plupart des panels réels).
    val xtreamStreamId: String = "",
    // ⚠️ Ajoutés 29/08/2026 (pagination MoviesViewModel, cf. CLAUDE.md) —
    // calculés UNE FOIS ici, au chargement de la playlist (util.
    // extractLeadingLanguageCode/stripLeadingLanguageCode sur `category`),
    // au lieu d'être recalculés en Kotlin sur chaque film à CHAQUE ouverture
    // de l'écran Films (coût réel sur ~136 000 films, confirmé par
    // instrumentation logcat). Permettent de filtrer langue/catégorie
    // directement en SQL (MovieDao.getMoviesPage) sans mapper tout le
    // catalogue en mémoire au préalable.
    // - languageCode : code détecté en tête de `category` ("FR", "AF"...),
    //   vide si aucun préfixe reconnu — jamais recalculé au runtime, un film
    //   ne change pas de préfixe de catégorie après coup.
    // - categoryStripped : `category` avec ce préfixe retiré si détecté,
    //   sinon identique à `category` (rien à retirer). Après le filtre
    //   langue (languageCode == '' OU == contentLanguage), c'est TOUJOURS la
    //   valeur affichée dans la sidebar (même principe que l'ancien
    //   MoviesViewModel.displayCategory, cf. CLAUDE.md pour la démonstration :
    //   quand languageCode == '', categoryStripped == category par
    //   construction — rien n'a été retiré — donc les deux branches de
    //   l'ancien displayCategory convergent vers categoryStripped une fois le
    //   filtre langue appliqué).
    @ColumnInfo(defaultValue = "") val languageCode: String = "",
    @ColumnInfo(defaultValue = "") val categoryStripped: String = "",
    // ⚠️ Rang de la catégorie DANS LA SOURCE (30/08/2026, demande explicite :
    // "peut-être trier par id au lieu que par ordre alphabétique ? sinon
    // récupère les catégories dans la playlist téléchargée"). C'est l'ordre
    // voulu par le fournisseur — celui qu'on voit dans les autres applis IPTV,
    // nouveautés en tête — et il remplace le tri alphabétique ET la liste
    // d'ordre codée en dur qui l'avait précédé (supprimée : impossible à tenir
    // à jour, et fausse dès qu'un panel renomme une catégorie).
    // Xtream : index de la catégorie dans get_vod_categories (le panel les
    // renvoie déjà dans son ordre). M3U : ordre de PREMIÈRE APPARITION du
    // group-title dans le fichier. 0 par défaut → un catalogue chargé avant
    // cette version a toutes ses catégories à égalité, d'où le tri
    // alphabétique conservé en second critère (cf. les DAO).
    @ColumnInfo(defaultValue = "0") val categoryOrder: Int = 0,
    // ⚠️ Rang du FILM lui-même dans la source (30/08/2026, demande explicite :
    // "dans le mur de films aussi, l'ordre des jaquettes"). Même principe que
    // categoryOrder, mais au niveau de l'entrée : le panel classe ses films
    // dans un ordre voulu (nouveautés en tête, cf. capture IPTV Smarters
    // fournie par l'utilisateur), que le tri alphabétique détruisait.
    // Xtream : index dans get_vod_streams. M3U : ordre d'apparition.
    @ColumnInfo(defaultValue = "0") val sortOrder: Int = 0
) {
    fun toDomain(
        isFavorite: Boolean = false,
        watchProgress: Int = 0,
        isFinished: Boolean = false
    ) = Movie(
        id = id,
        title = title,
        streamUrl = streamUrl,
        posterUrl = posterUrl,
        backdropUrl = backdropUrl,
        overview = overview,
        releaseYear = releaseYear,
        runtime = runtime,
        rating = rating,
        genres = if (genres.isBlank()) emptyList() else genres.split(","),
        category = category,
        tmdbId = tmdbId,
        isFavorite = isFavorite,
        watchProgress = watchProgress,
        isFinished = isFinished,
        xtreamStreamId = xtreamStreamId
    )

    companion object {
        /** cf. commentaire sur languageCode/categoryStripped plus haut —
         * centralisé ici pour que les chemins M3U et Xtream (PlaylistRepository)
         * restent cohérents. Appelé une fois par film au chargement, jamais au
         * runtime. Délègue aux helpers partagés (util.LanguageCode), communs aux
         * 3 entités catalogue depuis le 29/08/2026. */
        fun languageCodeFor(category: String): String = leadingLanguageCodeOrEmpty(category)

        fun categoryStrippedFor(category: String): String = withoutLeadingLanguageCode(category)
    }
}
