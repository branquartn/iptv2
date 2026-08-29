package com.nicotv.iptv2.ui.live

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.asLiveData
import com.nicotv.iptv2.IptvApplication
import com.nicotv.iptv2.data.ContentLanguagePrefs
import com.nicotv.iptv2.data.database.entity.FavoriteEntity
import com.nicotv.iptv2.domain.model.Channel
import com.nicotv.iptv2.util.extractLeadingLanguageCode
import com.nicotv.iptv2.util.foldAccents
import com.nicotv.iptv2.util.isFrenchLabel
import com.nicotv.iptv2.util.stripLeadingLanguageCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.viewModelScope

class LiveViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as IptvApplication
    private val repository = app.playlistRepository

    private val allChannels = repository.getChannels().asLiveData()

    val searchQuery = MutableLiveData("")
    val selectedCategory = MutableLiveData<String?>(null)
    val favoritesOnly = MutableLiveData(false)

    // Réglage persistant (Réglages > Langue du contenu) — n'importe quel code
    // découvert dans le catalogue (pas seulement "FR", cf. SettingsActivity).
    // Filtre sur le préfixe du nom de chaîne ("FR: TF1" → code "FR") et le
    // retire de l'affichage une fois filtré — demande explicite 28/08/2026 :
    // "si fr selected... voir que celle qui commence par FR|... enlève le
    // FR|" (délimiteur réel constaté : ":", pas "|", cf. util.LanguageCode).
    // ⚠️ Ni "exact match obligatoire" (corrigé le jour même, régression
    // signalée par l'utilisateur : "beIN Sport" disparu) — la plupart des
    // chaînes françaises de ce panel n'ont AUCUN préfixe (pas de norme, seuls
    // certains bouquets étrangers sont explicitement marqués "CA:"/"AL:"...).
    // Exiger le préfixe "FR" pour garder une chaîne excluait donc tout le
    // catalogue non marqué. Règle retenue : garder si aucun préfixe détecté
    // OU préfixe == contentLanguage ; exclure seulement un préfixe explicite
    // d'une AUTRE langue. Même principe côté catégories un peu plus bas et
    // dans Movies/SeriesViewModel.applyLanguageFilter.
    private val contentLanguage = app.contentLanguagePrefs.getLanguage()

    val categories: LiveData<List<String>> = MediatorLiveData<List<String>>().apply {
        // ⚠️ Calcul déporté en Dispatchers.Default (corrigé 29/08/2026) — cf.
        // MoviesViewModel.categories, même freeze main thread constaté sur
        // l'écran Chaînes (jusqu'à ~47 000 chaînes).
        addSource(allChannels) { list ->
            viewModelScope.launch {
                val result = withContext(Dispatchers.Default) {
                    // Catégories France en premier (demande explicite) — cf. isFrenchLabel.
                    // N'exclut que les catégories dont le PROPRE préfixe désigne
                    // explicitement une AUTRE langue (demande explicite 28/08/2026 :
                    // la sidebar affichait encore "CA|"/"AL|"... à côté des catégories
                    // FR une fois nettoyées) — garde les catégories sans préfixe du
                    // tout (cf. commentaire sur contentLanguage plus haut : pas un
                    // "exact match", sinon "Sport" sans préfixe disparaissait aussi).
                    val base = if (contentLanguage == null) list
                               else list.filter { val c = extractLeadingLanguageCode(it.category); c == null || c == contentLanguage }
                    base.map { displayCategory(it.category) }.filter { it.isNotBlank() }.distinct()
                        .sortedWith(compareByDescending<String> { isFrenchLabel(it) }.thenBy { it })
                }
                value = result
            }
        }
    }

    // ⚠️ Recherche par nom en SQL (repository.searchChannelsByName) quand une
    // requête est tapée — cf. MoviesViewModel.filteredMovies, même principe/
    // même raison (jusqu'à ~47 000 chaînes, foldAccents() par frappe trop lent
    // même en coroutine). Favoris/catégorie/FR appliqués ensuite sur le
    // résultat déjà réduit par le SQL (ou sur le catalogue complet sans
    // recherche en cours).
    // ⚠️ Debounce seulement si recherche en cours — cf. MoviesViewModel.
    // filteredMovies, même correctif (le delay(150) s'appliquait aussi à
    // l'ouverture de l'écran/changement de catégorie ou favoris, "recharge
    // tout" perçu à chaque retour sur Chaînes).
    val filteredChannels: LiveData<List<Channel>> = MediatorLiveData<List<Channel>>().apply {
        var job: Job? = null
        fun filter() {
            job?.cancel()
            job = viewModelScope.launch {
                val query = searchQuery.value.orEmpty().trim()
                if (query.isNotBlank()) delay(150)
                val favOnly = favoritesOnly.value == true
                val cat = selectedCategory.value
                // ⚠️ Déporté en Dispatchers.Default (corrigé 29/08/2026) — cf.
                // MoviesViewModel.filteredMovies, même correctif : ce filtre +
                // le tri TNT (foldAccents par chaîne) tournaient sur le thread
                // principal jusqu'à ~47 000 chaînes.
                val result = withContext(Dispatchers.Default) {
                    var base = if (query.isBlank()) allChannels.value ?: emptyList()
                               else repository.searchChannelsByName(query)
                    if (favOnly) base = base.filter { it.isFavorite }
                    cat?.let { c -> base = base.filter { displayCategory(it.category) == c } }

                    // Réglage persistant "Langue du contenu" : exclut seulement un
                    // préfixe explicite d'une AUTRE langue ("FR: TF1" avec réglage
                    // "AF" → exclu) et retire le préfixe quand il correspond
                    // ("FR: TF1" → "TF1") — une chaîne sans préfixe du tout est
                    // gardée telle quelle, cf. commentaire sur contentLanguage
                    // plus haut (pas un "exact match", régression "beIN Sport"
                    // corrigée le 28/08/2026).
                    if (contentLanguage != null) {
                        base = base.mapNotNull { channel ->
                            val code = extractLeadingLanguageCode(channel.name)
                            when {
                                code == null -> channel
                                code == contentLanguage -> channel.copy(name = stripLeadingLanguageCode(channel.name, contentLanguage))
                                else -> null
                            }
                        }
                    }

                    // Tri "ordre TNT" (demande explicite) : quand on regarde du
                    // français (catégorie FR, ou réglage langue = FR), TF1/
                    // France 2/France 3/... dans l'ordre de la numérotation
                    // officielle plutôt qu'alphabétique/ordre de la playlist. Hors
                    // contexte FR, ordre inchangé (sortOrder/nom, cf.
                    // ChannelDao.getAllChannels).
                    val frenchContext = contentLanguage == ContentLanguagePrefs.FRENCH || cat?.let { isFrenchLabel(it) } == true
                    if (frenchContext) base.sortedWith(compareBy({ tntRank(it) }, { it.name })) else base
                }
                value = result
            }
        }
        addSource(allChannels) { filter() }
        addSource(searchQuery) { filter() }
        addSource(selectedCategory) { filter() }
        addSource(favoritesOnly) { filter() }
    }

    /** Nom de catégorie affiché dans la sidebar — retire le préfixe langue
     * ("FR| Sport" → "Sport") quand il correspond au réglage "Langue du
     * contenu" (contentLanguage), même principe que sur le nom des chaînes
     * (cf. contentLanguage plus haut). Redondant une fois filtré sur une
     * seule langue, inutile de le garder affiché. Sert aussi de clé de
     * comparaison pour selectedCategory (sidebar ne connaît que le libellé
     * déjà nettoyé, jamais le brut). */
    private fun displayCategory(category: String): String {
        val code = contentLanguage ?: return category
        return if (extractLeadingLanguageCode(category) == code) stripLeadingLanguageCode(category, code) else category
    }

    /** Rang dans la numérotation officielle de la TNT française (1 à 25) —
     * comparaison par sous-chaîne sur le nom nettoyé (accents/casse), tolérant
     * aux préfixes de playlist ("FR| TF1 HD", "FR - TF1", "TF1 FHD"...). Pas de
     * source fiable de numéro de chaîne côté Xtream/M3U (même limite que
     * isFrenchLabel) : chaîne non reconnue → Int.MAX_VALUE, reléguée en fin de
     * liste plutôt que de casser le tri. */
    private fun tntRank(channel: Channel): Int {
        val name = channel.name.foldAccents().uppercase()
        val idx = TNT_ORDER.indexOfFirst { name.contains(it) }
        return if (idx >= 0) idx else Int.MAX_VALUE
    }

    fun toggleFavorite(channel: Channel) {
        viewModelScope.launch {
            repository.toggleFavorite(channel.id, FavoriteEntity.Type.CHANNEL, channel.isFavorite)
        }
    }

    companion object {
        // Numérotation officielle TNT (hertzien national, hors chaînes locales/
        // régionales) — ordre exact demandé (TF1, France 2, France 3...).
        private val TNT_ORDER = listOf(
            "TF1", "FRANCE 2", "FRANCE 3", "CANAL+", "FRANCE 5", "M6", "ARTE", "C8", "W9",
            "TMC", "TFX", "NRJ 12", "LCP", "FRANCE 4", "BFM TV", "CNEWS", "CSTAR", "GULLI",
            "TF1 SERIES FILMS", "EQUIPE", "6TER", "RMC STORY", "RMC DECOUVERTE",
            "CHERIE 25", "FRANCEINFO"
        )
    }
}
