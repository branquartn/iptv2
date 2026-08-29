package com.nicotv.iptv2.ui.live

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.nicotv.iptv2.IptvApplication
import com.nicotv.iptv2.data.ContentLanguagePrefs
import com.nicotv.iptv2.data.database.entity.FavoriteEntity
import com.nicotv.iptv2.data.repository.PlaylistRepository
import com.nicotv.iptv2.domain.model.Channel
import com.nicotv.iptv2.util.CHANNELS_PREFERRED_CATEGORIES
import com.nicotv.iptv2.util.extractLeadingLanguageCode
import com.nicotv.iptv2.util.isFrenchLabel
import com.nicotv.iptv2.util.pickDefaultCategory
import com.nicotv.iptv2.util.stripLeadingLanguageCode
import com.nicotv.iptv2.util.tntRankFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ⚠️ Réécrit en pagination le 30/08/2026 — même patron que
 * [com.nicotv.iptv2.ui.movies.MoviesViewModel] (lire son en-tête pour le
 * pourquoi), étendu à cet écran sur demande explicite ("faire pareil pour
 * série et live").
 *
 * Trois spécificités de l'écran Chaînes, toutes déplacées en SQL (cf.
 * ChannelDao.getChannelsPage) parce qu'un filtrage/tri fait page par page en
 * Kotlin donnerait un résultat global incohérent :
 * 1. le filtre de langue porte sur le NOM de la chaîne ("FR: TF1"), pas sur la
 *    catégorie — et le préfixe est retiré du nom affiché (cf.
 *    ChannelEntity.toDomain) ;
 * 2. le filtre "favoris uniquement" (bouton de la barre du haut) ;
 * 3. le tri "ordre TNT" en contexte français, via la colonne précalculée
 *    `tntRank` (cf. util.tntRankFor).
 *
 * La sidebar catégories, elle, reste filtrée sur le préfixe de la CATÉGORIE
 * (`categoryLanguageCode`) — deux conventions distinctes, inchangé.
 */
class LiveViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as IptvApplication
    private val repository = app.playlistRepository
    // Réglage persistant (Réglages > Langue du contenu) — n'importe quel code
    // découvert dans le catalogue (pas seulement "FR", cf. SettingsActivity).
    // Lu une seule fois à la création du ViewModel, comme avant.
    private val contentLanguage = app.contentLanguagePrefs.getLanguage()

    val searchQuery = MutableLiveData("")
    val selectedCategory = MutableLiveData<String?>(null)
    val favoritesOnly = MutableLiveData(false)

    private val _categories = MutableLiveData<List<String>>(emptyList())
    val categories: LiveData<List<String>> = _categories

    private val _isReady = MutableLiveData(false)
    val isReady: LiveData<Boolean> = _isReady

    private val _isLoadingMore = MutableLiveData(false)
    val isLoadingMore: LiveData<Boolean> = _isLoadingMore

    private var pagingOffset = 0
    private var endReached = false
    private var isSearching = false

    private val _channels = MediatorLiveData<List<Channel>>()
    val channels: LiveData<List<Channel>> = _channels

    /** Cf. MoviesViewModel.pageLimitFor — pagination sur "Toutes" seulement. */
    private fun pageLimitFor(category: String?): Int =
        if (category == null) PlaylistRepository.MOVIES_PAGE_SIZE else PlaylistRepository.NO_LIMIT

    /** Contexte français : réglage langue = FR, ou catégorie sélectionnée
     * reconnue française (cf. isFrenchLabel) → tri "ordre TNT" (demande
     * explicite 28/08/2026) plutôt que l'ordre de la playlist. Inchangé, mais
     * désormais transmis à SQL au lieu d'être appliqué après coup en Kotlin. */
    private fun frenchSortFor(category: String?): Boolean =
        contentLanguage == ContentLanguagePrefs.FRENCH || category?.let { isFrenchLabel(it) } == true

    private var job: Job? = null

    /** Cf. MoviesViewModel.awaitingDefaultCategory — même garde-fou, pour
     * ouvrir directement sur "Général FR" plutôt que sur "Toutes". */
    private var awaitingDefaultCategory = true

    init {
        _channels.addSource(searchQuery) { reload() }
        _channels.addSource(selectedCategory) { reload() }
        _channels.addSource(favoritesOnly) { reload() }
        loadCategories()
    }

    private fun reload() {
        if (awaitingDefaultCategory) return
        job?.cancel()
        pagingOffset = 0
        endReached = false
        _isReady.value = false
        job = viewModelScope.launch {
            val query = searchQuery.value.orEmpty().trim()
            if (query.isNotBlank()) delay(150)
            val cat = selectedCategory.value
            val favOnly = favoritesOnly.value == true
            val result = withContext(Dispatchers.Default) {
                if (query.isNotBlank()) {
                    // Chemin recherche : déjà borné à 200 lignes côté SQL,
                    // filtres/tri appliqués en Kotlin comme avant la
                    // pagination (coût négligeable sur si peu de lignes).
                    var base = repository.searchChannelsByName(query)
                    if (favOnly) base = base.filter { it.isFavorite }
                    // Catégorie BRUTE (30/08/2026 : plus de renommage,
                    // le préfixe "FR| " reste affiché).
                    cat?.let { c -> base = base.filter { it.category == c } }
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
                    if (frenchSortFor(cat)) base.sortedWith(compareBy({ tntRankFor(it.name) }, { it.name })) else base
                } else {
                    repository.getChannelsPage(
                        lang = contentLanguage, category = cat, favoritesOnly = favOnly,
                        frenchSort = frenchSortFor(cat), offset = 0, limit = pageLimitFor(cat)
                    )
                }
            }
            isSearching = query.isNotBlank()
            pagingOffset = result.size
            endReached = isSearching || cat != null || result.size < PlaylistRepository.MOVIES_PAGE_SIZE
            _channels.value = result
            _isReady.value = true
        }
    }

    private fun loadCategories() {
        viewModelScope.launch {
            // ⚠️ Cf. MoviesViewModel.loadCategories — try/catch obligatoire,
            // awaitingDefaultCategory bloque tout chargement tant qu'il est vrai.
            val result = try {
                withContext(Dispatchers.Default) {
                    // Ordre de la playlist — cf. MoviesViewModel.loadCategories.
                    repository.getChannelsCategories(contentLanguage).filter { it.isNotBlank() }
                }
            } catch (e: Exception) {
                emptyList()
            }
            _categories.value = result

            // Catégorie ouverte par défaut — cf. util.pickDefaultCategory.
            val default = pickDefaultCategory(result, CHANNELS_PREFERRED_CATEGORIES)
            awaitingDefaultCategory = false
            if (default != null) {
                selectedCategory.value = default
            } else {
                reload()
            }
        }
    }

    /** Cf. MoviesViewModel.loadNextPage. */
    fun loadNextPage() {
        if (isSearching || endReached || _isLoadingMore.value == true) return
        _isLoadingMore.value = true
        viewModelScope.launch {
            val cat = selectedCategory.value
            val page = withContext(Dispatchers.Default) {
                repository.getChannelsPage(
                    lang = contentLanguage, category = cat, favoritesOnly = favoritesOnly.value == true,
                    frenchSort = frenchSortFor(cat), offset = pagingOffset, limit = PlaylistRepository.MOVIES_PAGE_SIZE
                )
            }
            pagingOffset += page.size
            if (page.size < PlaylistRepository.MOVIES_PAGE_SIZE) endReached = true
            _channels.value = (_channels.value ?: emptyList()) + page
            _isLoadingMore.value = false
        }
    }

    /** Cf. MoviesViewModel.refreshFavoriteStates — appelé par
     * LiveActivity.onResume. ⚠️ Quand le filtre "favoris uniquement" est
     * actif, un retrait de favori doit aussi FAIRE DISPARAÎTRE la tuile :
     * dans ce cas on relance un chargement complet plutôt qu'une simple mise
     * à jour d'état (la liste elle-même change, pas seulement les étoiles). */
    fun refreshFavoriteStates() {
        if (favoritesOnly.value == true) {
            // Réémet la même valeur : déclenche `reload()` via addSource, donc
            // un rechargement propre de la première page avec le filtre à jour.
            favoritesOnly.value = true
            return
        }
        val current = _channels.value
        if (current.isNullOrEmpty()) return
        viewModelScope.launch {
            val favIds = repository.getFavoriteChannelIds()
            // Cf. MoviesViewModel.refreshFavoriteStates — rien à refaire si
            // aucun favori n'a bougé.
            if (current.none { (it.id in favIds) != it.isFavorite }) return@launch
            _channels.value = current.map { it.copy(isFavorite = it.id in favIds) }
        }
    }

    fun toggleFavorite(channel: Channel) {
        viewModelScope.launch {
            repository.toggleFavorite(channel.id, FavoriteEntity.Type.CHANNEL, channel.isFavorite)
            refreshFavoriteStates()
        }
    }
}
