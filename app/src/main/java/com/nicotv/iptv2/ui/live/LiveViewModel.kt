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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    // Filtre EXACT sur le préfixe du nom de chaîne ("FR: TF1" → code "FR") et
    // le retire de l'affichage une fois filtré — demande explicite 28/08/2026 :
    // "si fr selected... voir que celle qui commence par FR|... enlève le
    // FR|" (délimiteur réel constaté : ":", pas "|", cf. util.LanguageCode).
    private val contentLanguage = app.contentLanguagePrefs.getLanguage()

    // Pré-coché si le réglage est spécifiquement Français (bouton FR déjà
    // existant, heuristique isFrenchLabel plus permissive — nom+catégorie,
    // pas que le préfixe exact) — reste décochable pour la session, comme
    // avant. Les deux mécanismes peuvent cohabiter (le filtre par code est
    // plus strict, le bouton FR plus large).
    val frenchOnly = MutableLiveData(contentLanguage == ContentLanguagePrefs.FRENCH)

    val categories: LiveData<List<String>> = MediatorLiveData<List<String>>().apply {
        addSource(allChannels) { list ->
            // Catégories France en premier (demande explicite) — cf. isFrenchLabel.
            value = list.map { displayCategory(it.category) }.filter { it.isNotBlank() }.distinct()
                .sortedWith(compareByDescending<String> { isFrenchLabel(it) }.thenBy { it })
        }
    }

    // ⚠️ Recherche par nom en SQL (repository.searchChannelsByName) quand une
    // requête est tapée — cf. MoviesViewModel.filteredMovies, même principe/
    // même raison (jusqu'à ~47 000 chaînes, foldAccents() par frappe trop lent
    // même en coroutine). Favoris/catégorie/FR appliqués ensuite sur le
    // résultat déjà réduit par le SQL (ou sur le catalogue complet sans
    // recherche en cours).
    val filteredChannels: LiveData<List<Channel>> = MediatorLiveData<List<Channel>>().apply {
        var job: Job? = null
        fun filter() {
            job?.cancel()
            job = viewModelScope.launch {
                delay(150)
                val query = searchQuery.value.orEmpty().trim()
                var base = if (query.isBlank()) allChannels.value ?: emptyList()
                           else repository.searchChannelsByName(query)
                if (favoritesOnly.value == true) base = base.filter { it.isFavorite }
                selectedCategory.value?.let { cat -> base = base.filter { displayCategory(it.category) == cat } }
                val onlyFrench = frenchOnly.value == true
                if (onlyFrench) base = base.filter { isFrench(it) }

                // Réglage persistant "Langue du contenu" : filtre EXACT sur le
                // préfixe du nom ("FR: TF1" → garde seulement code == "FR") et
                // le retire de l'affichage ("FR: TF1" → "TF1") — cf. commentaire
                // sur contentLanguage plus haut.
                if (contentLanguage != null) {
                    base = base.mapNotNull { channel ->
                        if (extractLeadingLanguageCode(channel.name) != contentLanguage) null
                        else channel.copy(name = stripLeadingLanguageCode(channel.name, contentLanguage))
                    }
                }

                // Tri "ordre TNT" (demande explicite) : quand on regarde du
                // français (catégorie FR, filtre FR actif, ou réglage langue =
                // FR), TF1/France 2/France 3/... dans l'ordre de la
                // numérotation officielle plutôt qu'alphabétique/ordre de la
                // playlist. Hors contexte FR, ordre inchangé (sortOrder/nom,
                // cf. ChannelDao.getAllChannels).
                val frenchContext = onlyFrench || contentLanguage == ContentLanguagePrefs.FRENCH ||
                    selectedCategory.value?.let { isFrenchLabel(it) } == true
                value = if (frenchContext) base.sortedWith(compareBy({ tntRank(it) }, { it.name })) else base
            }
        }
        addSource(allChannels) { filter() }
        addSource(searchQuery) { filter() }
        addSource(selectedCategory) { filter() }
        addSource(favoritesOnly) { filter() }
        addSource(frenchOnly) { filter() }
    }

    /** Heuristique "France" (cf. util.isFrenchLabel) appliquée nom+catégorie —
     * plus permissive que le tri des catégories (qui ne regarde que la
     * catégorie seule) : une chaîne FR peut être classée dans une catégorie au
     * nom neutre. */
    private fun isFrench(channel: Channel): Boolean = isFrenchLabel("${channel.category} ${channel.name}")

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

    /** Mini-guide "en cours/à suivre" d'une chaîne — cf. ChannelAdapter (appelé
     * au bind d'une ligne, résultat mis en cache par le repository). */
    suspend fun getShortEpg(channel: Channel) = repository.getShortEpg(channel)

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
