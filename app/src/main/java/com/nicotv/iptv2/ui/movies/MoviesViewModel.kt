package com.nicotv.iptv2.ui.movies

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.asLiveData
import com.nicotv.iptv2.IptvApplication
import com.nicotv.iptv2.data.database.entity.FavoriteEntity
import com.nicotv.iptv2.domain.model.Movie
import com.nicotv.iptv2.util.extractLeadingLanguageCode
import com.nicotv.iptv2.util.isFrenchLabel
import com.nicotv.iptv2.util.stripLeadingLanguageCode
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.lifecycle.viewModelScope

class MoviesViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as IptvApplication
    private val repository = app.playlistRepository

    private val allMovies = repository.getMovies().asLiveData()

    // Réglage persistant (Réglages > Langue du contenu) — lu une fois à
    // l'ouverture de l'écran (nouveau ViewModel à chaque visite, cf.
    // CLAUDE.md), pas besoin d'être réactif en cours d'écran. Liste de codes
    // découverte dynamiquement (pas de "FR" figé, cf. SettingsActivity) —
    // filtre EXACT sur le code en tête de la catégorie ("FR - Ghost" → "FR"),
    // pas une heuristique substring comme isFrenchLabel (utilisée seulement
    // pour trier les catégories France en premier, cf. plus bas).
    private val contentLanguage = app.contentLanguagePrefs.getLanguage()
    // ⚠️ Pas un "exact match" (corrigé 28/08/2026, régression signalée par
    // l'utilisateur : plus aucune série/film sans préfixe visible) — la
    // plupart des catégories françaises de ce panel n'ont AUCUN préfixe (pas
    // de norme, seuls certains bouquets étrangers sont explicitement marqués
    // "CA -"/"AL -"...). Exiger "FR" pour garder excluait donc tout le
    // catalogue non marqué. Garde si aucun préfixe détecté OU préfixe ==
    // contentLanguage ; exclut seulement un préfixe explicite d'une AUTRE
    // langue — même principe que LiveViewModel.
    private fun applyLanguageFilter(list: List<Movie>): List<Movie> =
        if (contentLanguage == null) list
        else list.filter { val c = extractLeadingLanguageCode(it.category); c == null || c == contentLanguage }

    /** Nom de catégorie affiché dans la sidebar — retire le préfixe langue
     * ("FR - Action" → "Action") quand il correspond à contentLanguage, même
     * principe que LiveViewModel.displayCategory. La liste est déjà filtrée à
     * ce stade (applyLanguageFilter) donc toute catégorie restante matche —
     * la vérif reste explicite pour ne rien retirer si contentLanguage est
     * null (aucun filtre actif, tous les préfixes bruts affichés). */
    private fun displayCategory(category: String): String {
        val code = contentLanguage ?: return category
        return if (extractLeadingLanguageCode(category) == code) stripLeadingLanguageCode(category, code) else category
    }

    val searchQuery = MutableLiveData("")
    // null = "Toutes" — cf. LiveViewModel, même principe de filtre par catégorie
    // (sidebar gauche, comme IPTV Smarters Pro).
    val selectedCategory = MutableLiveData<String?>(null)

    val categories: LiveData<List<String>> = MediatorLiveData<List<String>>().apply {
        // Catégories France en premier (demande explicite) — cf. isFrenchLabel.
        // Calculées sur le catalogue déjà filtré par langue : pas de catégorie
        // 100% non-FR listée si "Français uniquement" est actif, elle donnerait
        // toujours zéro résultat une fois sélectionnée.
        addSource(allMovies) { list ->
            value = applyLanguageFilter(list).map { displayCategory(it.category) }.filter { it.isNotBlank() }.distinct()
                .sortedWith(compareByDescending<String> { isFrenchLabel(it) }.thenBy { it })
        }
    }

    // ⚠️ Recherche par titre en SQL (repository.searchMoviesByTitle), pas en
    // filtrant getMovies().value en Kotlin (corrigé 28/08/2026) : même sur un
    // thread de fond, filtrer ~136 000 titres avec foldAccents() (Normalizer)
    // par frappe restait perceptiblement plus lent que l'écran Recherche
    // global (déjà en SQL) — d'où le "pas immédiat" signalé en comparaison.
    // Catégorie appliquée en Kotlin ensuite, sur le résultat déjà réduit par
    // le SQL (ou sur le catalogue complet si pas de recherche en cours) —
    // jamais sur les 136 000 lignes à la fois. Debounce (150ms) pour ne pas
    // lancer une requête par caractère tapé rapidement.
    val filteredMovies: LiveData<List<Movie>> = MediatorLiveData<List<Movie>>().apply {
        var job: Job? = null
        fun filter() {
            job?.cancel()
            job = viewModelScope.launch {
                delay(150)
                val query = searchQuery.value.orEmpty().trim()
                var movies = if (query.isBlank()) allMovies.value ?: emptyList()
                             else repository.searchMoviesByTitle(query)
                movies = applyLanguageFilter(movies)
                selectedCategory.value?.let { cat -> movies = movies.filter { displayCategory(it.category) == cat } }
                value = movies
            }
        }
        addSource(allMovies) { filter() }
        addSource(searchQuery) { filter() }
        addSource(selectedCategory) { filter() }
    }

    fun toggleFavorite(movie: Movie) {
        viewModelScope.launch {
            repository.toggleFavorite(movie.id, FavoriteEntity.Type.MOVIE, movie.isFavorite)
        }
    }
}
