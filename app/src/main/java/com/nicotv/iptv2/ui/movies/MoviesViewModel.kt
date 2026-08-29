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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.viewModelScope

class MoviesViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as IptvApplication
    private val repository = app.playlistRepository

    private val allMovies = repository.getMovies().asLiveData()

    // ⚠️ Distingue "pas encore chargé" de "vraiment vide" (corrigé 29/08/2026,
    // signalé "la première fois que je vais dans Films il ne charge pas") —
    // cf. PlaylistRepository.isMoviesReady pour le détail. Observé par
    // MoviesActivity pour garder le spinner (et ne pas afficher "Aucun titre
    // trouvé") tant que la vraie requête Room n'a jamais répondu.
    val isReady = repository.isMoviesReady().asLiveData()

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
        // ⚠️ Calcul déporté en Dispatchers.Default (corrigé 29/08/2026) : sur
        // ~136 000 films, filter+map+distinct+sort tournait en synchrone dans ce
        // callback — livré sur le thread principal par LiveData, donc un vrai
        // freeze perçu comme "toujours long à charger" à CHAQUE ouverture de
        // l'écran Films, malgré allMovies déjà en cache (moviesFlow chaud, cf.
        // PlaylistRepository). Seule la réassignation de `value` reste sur Main
        // (obligatoire pour LiveData.setValue).
        addSource(allMovies) { list ->
            viewModelScope.launch {
                val result = withContext(Dispatchers.Default) {
                    applyLanguageFilter(list).map { displayCategory(it.category) }.filter { it.isNotBlank() }.distinct()
                        .sortedWith(compareByDescending<String> { isFrenchLabel(it) }.thenBy { it })
                }
                value = result
            }
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
    // ⚠️ Debounce seulement si une recherche est en cours (corrigé 29/08/2026,
    // signalé "je sors de Films et je reviens, ça recharge tout") : le
    // `delay(150)` s'appliquait AUSSI au tout premier appel (`addSource
    // (allMovies) { filter() }`, déclenché dès l'ouverture de l'écran) et à
    // chaque changement de catégorie — pas seulement à la frappe. Résultat :
    // ~150ms de spinner plein écran + liste vide à CHAQUE retour sur Films,
    // même avec le catalogue déjà en cache (moviesFlow chaud). Le debounce ne
    // sert qu'à éviter une requête SQL par caractère tapé — inutile quand il
    // n'y a pas de recherche texte en cours.
    val filteredMovies: LiveData<List<Movie>> = MediatorLiveData<List<Movie>>().apply {
        var job: Job? = null
        fun filter() {
            job?.cancel()
            job = viewModelScope.launch {
                val query = searchQuery.value.orEmpty().trim()
                if (query.isNotBlank()) delay(150)
                // ⚠️ applyLanguageFilter/filtre catégorie déportés en
                // Dispatchers.Default (corrigé 29/08/2026) : viewModelScope
                // lance sur Main.immediate par défaut — malgré le commentaire
                // au-dessus (qui promettait "même sur un thread de fond"), ce
                // filtre tournait en réalité sur le thread principal à chaque
                // ouverture de l'écran/frappe, sur un catalogue déjà en cache
                // mais toujours ~136 000 films à parcourir. searchMoviesByTitle
                // fait déjà son propre withContext(Dispatchers.IO) en interne.
                val movies = withContext(Dispatchers.Default) {
                    var m = if (query.isBlank()) allMovies.value ?: emptyList()
                            else repository.searchMoviesByTitle(query)
                    m = applyLanguageFilter(m)
                    selectedCategory.value?.let { cat -> m = m.filter { displayCategory(it.category) == cat } }
                    m
                }
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
