# IPTV2 — contexte projet

App Android (Kotlin, MVVM) : lecteur IPTV **grand public, sans compte**. Dérivée
de [NicoTV](https://github.com/branquartn/iptv) (moteur ExoPlayer, conventions
UI RotatingBorderView identiques) mais sans login ni backend métier — la
source (playlist M3U ou Xtream Codes) est fournie par l'utilisateur à l'écran
de démarrage. Cible mobile, Android TV et Fire TV.

## Branche de travail

Développer sur `main` sauf indication contraire. Commit + push à chaque
livraison.

## Architecture

```
app/src/main/java/com/nicotv/iptv2/
  AppConfig.kt            # Update.VERSION_URL + clé/URLs TMDb
  data/
    PlaylistSourcePrefs.kt    # id du profil actif (SharedPreferences) — rien d'autre
    ProfileBackupPrefs.kt     # copie JSON des profils, filet si Room est vidé
    ContentLanguagePrefs.kt   # réglage "Langue du contenu" (liste dynamique), cf. Réglages
    ImageCacheUtil.kt         # vide le cache Coil (mémoire+disque), cf. Réglages
    m3u/M3uParser.kt          # #EXTINF/URL → M3uEntry, classification live/VOD/série
    xtream/XtreamClient.kt    # player_api.php (login, catégories, live/VOD/séries,
                              # get_vod_info, get_series_info)
    xtream/XtreamModels.kt   # modèles Xtream — parsing JSON à la main (org.json),
                              # jamais de désérialisation Gson stricte (panels incohérents)
    tmdb/TmdbClient.kt       # recherche par titre (jaquettes) + credits/recommandations/
                              # bande-annonce/fiche acteur — org.json, best-effort
    database/                # Room : profils + cache du catalogue chargé (dao/, entity/)
    repository/PlaylistRepository.kt  # CRUD profils, charge la source → Room, expose
                                       # films/séries/chaînes joints favoris+reprise
                                       # (StateFlow chauds, cf. section dédiée)
  domain/model/            # Movie (films+séries+épisodes unifiés, .displayTitle),
                           # Series, Channel, SimilarWork/OpenTarget
  util/                    # foldAccents, isFrenchLabel, stripReleaseTags, LanguageCode —
                           # partagées entre plusieurs écrans/ViewModels
  player/                  # PlayerActivity (Media3/ExoPlayer)
  ui/
    setup/SetupActivity      # lanceur : profils (cartes façon Netflix) + 2 cartes
                             # d'ajout ; saut auto si profil déjà actif (pas de login)
    main/MainActivity        # accueil : 3 tuiles Chaînes/Films/Séries, jaquettes/
                             # logo en rotation, fond aléatoire
    settings/SettingsActivity  # cache images/playlist, langue du contenu,
                               # changer de source
    live/                    # chaînes : mosaïque + sidebar catégories + favoris
    movies/ series/ detail/  # films/séries : mur d'affiches, sidebar catégories,
                             # fiche, épisodes
    favorites/ resume/ search/
    common/                  # BaseActivity, PosterAdapter, CategorySidebarAdapter,
                             # RotatingBorderView...
  update/UpdateManager      # OTA : lit version.json, télécharge + installe l'APK
```

## Modèle de données — pas de compte

**Profils nommés** (`PlaylistProfileEntity`, table Room `playlist_profiles`) :
l'utilisateur enregistre autant de sources qu'il veut (`M3U_URL`, `M3U_FILE`
— URI SAF avec permission persistante — ou `XTREAM` host/user/pass), chacune
avec un nom. Une seule est chargée à la fois ; `PlaylistSourcePrefs` ne retient
que **l'id du profil actif**, pas les identifiants.

⚠️ **Piège vécu** : cet id vit dans SharedPreferences (jamais effacé) alors que
les profils vivent dans Room, que `fallbackToDestructiveMigration` vide à chaque
montée de schéma → l'app pointait vers un profil disparu (catalogue vide,
rechargement impossible). D'où `hasValidActiveProfile()`, qui vérifie l'existence
en base et nettoie la prefs. Ne pas revenir à un simple test `!= null`.

**Copie de secours hors Room** (`ProfileBackupPrefs`, ajoutée en 1.0.15) : les
profils sont aussi sérialisés en JSON dans SharedPreferences, réécrits à chaque
`save*Profile`/`deleteProfile`, et `restoreProfilesIfEmpty()` (appelée au
démarrage de `SetupActivity`) les réinjecte si la table Room est vide alors que
la sauvegarde ne l'est pas. Motivation d'origine : plainte répétée « je dois
retaper mes identifiants Xtream à chaque ouverture », jamais reproduite en
inspection de code ni confirmée par un logcat à l'époque — cette copie couvrait
toutes les hypothèses de persistance sans dépendre du diagnostic.

⚠️ **Cause réelle trouvée le 28/08/2026, via logcat** (`Log.i("SetupActivity",
"Profils en base : ...")`) : les profils étaient **bien enregistrés et bien
transmis à l'adapter** (log confirmé : 2 profils en base, stables sur 3
réaffichages) — ce n'était donc **jamais un problème de persistance**.
`rv_profiles` (`activity_setup.xml`) n'avait tout simplement **aucun
`layoutManager`** assigné (ni en XML, ni dans `SetupActivity.setupProfilesList()`,
qui ne posait que `.adapter =`) : un RecyclerView sans layoutManager ne dessine
rien, même avec une liste non vide — la section restait vide à l'écran alors
que `section_profiles` passait bien en `VISIBLE`. Fixé en ajoutant
`binding.rvProfiles.layoutManager = LinearLayoutManager(this)` juste avant
`.adapter =`. Levée : **toujours vérifier le layoutManager en premier** sur un
RecyclerView vide à l'écran malgré des données confirmées en base — avant de
soupçonner Room/migrations/backup.

⚠️ **Erreurs de formulaire invisibles** (corrigé en 1.0.12, à ne pas
réintroduire) : les dialogues Playlist/Xtream utilisaient `showStatus()`, qui
écrit dans un `TextView` de l'écran **principal** — donc caché derrière le
dialogue encore ouvert. Un champ oublié (typiquement « Nom du profil », absent
de la plupart des apps IPTV concurrentes) bloquait la validation sans aucun
retour visible : rien ne se passait au clic, rien n'était enregistré. Tout
message destiné à l'utilisateur pendant qu'un dialogue est ouvert doit passer
par un `Toast` (et `.error` sur le champ concerné), jamais par `showStatus()`.

Charger un profil **remplace tout le catalogue Room** (chaînes, films, séries —
`PlaylistRepository.loadProfile()`), ré-attribuant de nouveaux id autoIncrement :
favoris/reprise d'un ancien catalogue ne sont pas rattachés automatiquement après
un rechargement (même limite que NicoTV sur ses resynchronisations).

- **M3U** : classification live/VOD/série **heuristique** (`M3uParser.classify`),
  par ordre de fiabilité décroissante : chemin de l'URL (`/live/`, `/movie/`,
  `/series/` — fiable sur les exports Xtream, de loin le cas le plus courant),
  puis motif `SxxEyy`/`1x02` dans le nom, puis `group-title` (mots-clés
  vod/film/movie, série/series/show), puis extension vidéo (`.mkv`, `.mp4`…).
  Le `group-title` seul ne suffit pas : beaucoup de panels classent les VOD par
  genre/pays sans jamais écrire « film »/« vod » (des films finissaient en
  direct). Un fournisseur hors conventions peut toujours être mal classé — le
  format M3U ne type pas ses entrées, pas de solution générale.
- **Xtream Codes** : catalogue léger (catégories + streams/séries) chargé au
  `loadFromCurrentSource()`, mais **épisodes chargés à la demande**
  (`PlaylistRepository.loadEpisodesForSeries`, appel `get_series_info` à
  l'ouverture de `SeriesDetailActivity`) — un catalogue de plusieurs milliers
  de séries rendrait un chargement upfront bien trop long. Trois répliques
  successives, toutes constatées sur un panel réel de test (~215 000 entrées :
  47 405 chaînes / 136 693 films / 31 472 séries) :
  1. **Identifiants non encodés** dans les URLs (`player_api.php` et flux
     `/live|/movie|/series`) — un `+`, `&`, `%` ou espace cassait la requête
     silencieusement. Toujours passer par `enc()` (`XtreamClient`).
  2. **`get_vod_streams`/`get_series` sans `category_id` renvoient une liste
     vide** sur certains panels, alors que `get_live_streams` répond
     normalement → films/séries à 0 sans erreur. Repli : appel par catégorie
     (`fetchVodStreamsByCategory`/`fetchSeriesByCategory`, parallélisme borné
     par `xtreamSemaphore`).
  3. **API JSON entièrement muette** malgré un login accepté → repli sur
     l'export M3U `get.php?...&type=m3u_plus&output=mpegts`
     (`XtreamClient.playlistM3uUrl()`), qui réutilise tel quel le pipeline M3U.
  `android:largeHeap="true"` est nécessaire : le tas 256 Mo par défaut explosait
  (`OutOfMemoryError` dans `JSONArray(body)`) en parsant `get_vod_streams` sur
  ce panel.
- **Favoris** : table unique `FavoriteEntity(itemId, itemType)`, `itemType` ∈
  MOVIE/SERIES/CHANNEL — pas de FK, juste un filtre par type.
- **Reprise de lecture** : `WatchHistoryEntity`, clé `"m<id>"` (film) ou
  `"e:<fileKey>"` (épisode). Pas de notion de « vu » permanente séparée
  (simplifié vs NicoTV) : une entrée disparaît de l'historique dès que la
  lecture est considérée terminée (`PlayerActivity` proche de la fin, ou
  position < 5s) — cf. `PlaylistRepository.saveWatchPosition`.
- **Recherche** : locale uniquement (`PlaylistRepository.searchTitle`) — cherche
  dans ce que la playlist contient déjà, par titre.
  ⚠️ **Perf (corrigé 28/08/2026)** : passait par `getMovies()`/`getSeries()`/
  `getChannels()` (Flow combine + mapping domaine + jointure favoris/historique
  sur **tout** le catalogue), puis filtrait en Kotlin — sur un gros panel
  Xtream (cf. section suivante, panel de test ~215 000 entrées), ça remappait
  l'intégralité de la base à **chaque frappe**, recherche perceptiblement
  lente. Filtre désormais en SQL (`MovieDao.searchByTitle`/`SeriesDao.
  searchByTitle`/`ChannelDao.searchByName`, `LIKE '%...%' COLLATE NOCASE
  LIMIT 200`) — ne mappe/joint favoris+historique que sur les résultats déjà
  filtrés. Si la recherche redevient lente, vérifier qu'un futur appel n'a
  pas réintroduit un `getMovies().first().filter{}` à la place de ces
  requêtes SQL dédiées.
- **Filtre « FR »** (écran Chaînes, bouton à côté du filtre favoris) : Ni
  Xtream ni M3U n'exposent de champ pays exploitable et les catégories des
  panels réels ne suivent aucune norme (`AFR| AFRICA VIP HD/4K`, `4K| 24/7 UHD
  3840P`…) → heuristique sur nom + catégorie, `util.isFrenchLabel()` (token
  exact `FR` délimité, sinon `AFR`/`OFFER` matcheraient, ou sous-chaîne
  `FRANCE`/`FRENCH`). Extraite courant 28/08/2026 pour être réutilisée
  ailleurs — cf. tri des catégories, réglage "Langue du contenu" et tri TNT,
  sections dédiées plus bas.

## Catégories — sidebar gauche (Chaînes/Films/Séries)

Ajouté 28/08/2026 (demande explicite, comportement IPTV Smarters Pro) :
`CategorySidebarAdapter` (`ui/common/`, partagé par les 3 écrans) remplace les
anciennes chips horizontales de `LiveActivity` (`CategoryChipAdapter`,
supprimé) — colonne fixe 180dp à gauche (`rv_categories`), séparateur, contenu
à droite. "Toutes" toujours en premier, géré en interne par l'adapter
(`submitList` le préfixe automatiquement — l'appelant ne passe que les vraies
catégories). Chaque écran garde son propre `categories`/`selectedCategory`
dans son ViewModel (même principe pour les 3, catégories tirées de son
propre catalogue) :

- `LiveViewModel` avait déjà ce filtre (chips) — seule l'UI change.
- `MoviesViewModel`/`SeriesViewModel` ne filtraient **que par titre** avant
  ce lot : `categories`/`selectedCategory` sont nouveaux, mêmes noms de champs
  que `LiveViewModel` pour rester cohérent.

⚠️ **`ResumeActivity`/`FavoritesActivity` masquent la sidebar** (corrigé
28/08/2026, demande explicite "juste les jaquettes, sans le bandeau") — ces
deux écrans **réutilisent `activity_movies.xml`** (même `ActivityMoviesBinding`
que `MoviesActivity`) mais n'ont jamais câblé `rv_categories` : la colonne
180dp + séparateur (`sidebar_divider`, id ajouté pour l'occasion) s'affichait
donc vide, comme un bandeau gris inutile. Les deux masquent maintenant
`rv_categories`/`sidebar_divider` en `onCreate()` (`View.GONE`) — le
`FrameLayout` du mur d'affiches, seul enfant `weight="1"` restant visible,
récupère automatiquement toute la largeur. Leurs `computeSpanCount()`
**n'ont jamais été mis à jour** pour déduire la largeur de la sidebar
(contrairement à `MoviesActivity`/`SeriesActivity`) — normal, elle n'existe
plus visuellement chez eux, ne pas "corriger" cet écart en pensant à un
oubli. Si un futur écran réutilise encore `activity_movies.xml`, penser à
faire ce même masquage dès le départ plutôt que de laisser un bandeau vide
traîner jusqu'au prochain signalement.

`MoviesActivity`/`SeriesActivity.computeSpanCount()` déduit maintenant 210dp
(largeur sidebar + séparateur + paddings) de `screenWidthDp` avant de diviser
par la largeur d'affiche — sans ça le nombre de colonnes était calculé sur la
largeur totale de l'écran alors que le mur d'affiches dispose de moins
d'espace depuis l'ajout de la sidebar.

⚠️ **Catégories France en premier** (demande explicite, même jour) :
`util.isFrenchLabel()` trie les 3 listes de catégories —
`sortedWith(compareByDescending { isFrenchLabel(it) }.thenBy { it })`. Ne
regarde QUE le libellé de catégorie (pas le nom des chaînes/titres) : une
catégorie au nom neutre contenant des chaînes françaises ne remonte pas en
tête, seul le nom de la catégorie compte ici. `LiveViewModel.isFrench()`
(nom+catégorie, plus permissif) a existé un temps pour un bouton "FR" dédié
sur l'écran Chaînes, **retiré le 28/08/2026** (demande explicite, redondant
avec Réglages > Langue du contenu une fois ce dernier corrigé) — cf. section
Réglages plus bas.

⚠️ **Bug vécu (corrigé 28/08/2026) — sélectionner une catégorie ne changeait
rien à l'affichage** : la logique de filtre fonctionnait bien (le compteur de
résultats se mettait à jour, ex. 136718 → 94), mais `PosterAdapter`/
`ChannelAdapter` étaient des `ListAdapter` (`DiffUtil`) — sur un catalogue de
plusieurs dizaines/centaines de milliers d'items, un changement de catégorie
vers un sous-ensemble beaucoup plus petit force DiffUtil à calculer un diff
entre une liste énorme et une liste réduite (Myers, coût proche de l'ordre du
carré sur un retrait massif) : plusieurs secondes à largement plus, perçu
comme "le filtre ne fait rien". Diagnostiqué via `uiautomator dump` +
captures d'écran sur le téléphone du user (wireless debugging) : le compteur
changeait, la grille non, même après plusieurs secondes d'attente. **Les
deux adapters sont repassés en `RecyclerView.Adapter` simple + liste mutable +
`notifyDataSetChanged()`** (coût constant, ne redessine que les vues
visibles) — aucune perte : l'item animator était déjà désactivé
(`onAttachedToRecyclerView`, conflit avec le zoom de focus), DiffUtil ne
servait donc qu'à calculer des animations jamais jouées. Si un futur écran
réintroduit `ListAdapter` sur une liste issue du catalogue complet (pas une
petite liste bornée comme les catégories, `CategorySidebarAdapter` reste un
`ListAdapter` sans souci), refaire le même raisonnement avant de l'utiliser.

⚠️ **Champ de recherche interne (Chaînes/Films/Séries) — pas l'écran Recherche
dédié** (corrigé 28/08/2026) : `MoviesViewModel`/`SeriesViewModel`/
`LiveViewModel` filtraient `selectedCategory`/`searchQuery` **en synchrone,
sur le thread principal, dans le callback `addSource`** — `foldAccents()`
(Unicode `Normalizer`, coûteux) appelé sur le titre de **chaque** film/série/
chaîne à **chaque frappe**. Sur ~136 000 films ou ~47 000 chaînes, assez lent
pour saccader l'app et carrément **perdre des caractères tapés** : reproduit
via `adb shell input text "avatar"` sur le champ recherche de l'écran Films —
seul "av" arrivait dans le champ, le reste perdu (le thread principal était
occupé à filtrer pendant que d'autres évènements clavier arrivaient). Passé
en coroutine debouncée (150ms, `viewModelScope.launch` + `Dispatchers.Default`
pour le filtre par titre) — même principe que `SearchViewModel.search()`
(écran Recherche dédié, déjà async depuis l'origine, jamais eu ce problème).
Si un futur filtre réintroduit un `addSource(...) { ... calcul lourd ... }`
synchrone sur une source alimentée par un gros catalogue, le reproduire avec
`adb shell input text` sur un vrai appareil avant de conclure que "ça a l'air
d'aller" — un test manuel qui tape lentement ne révèle pas ce genre de perte.

## Mosaïque de chaînes (LiveActivity)

Ajouté 28/08/2026 (demande explicite, comportement IPTV Smarters Pro) :
`ChannelGridAdapter` + `item_channel_tile.xml` remplacent la liste verticale
sur l'écran Chaînes — `GridLayoutManager` (comme `PosterAdapter`), tuile
logo+nom, pas de bouton favori dédié (place limitée) : **tap = lecture, appui
long = ajouter/retirer des favoris**. L'ancien `ChannelAdapter` (liste, avec
bouton favori explicite) est **gardé tel quel** pour `SearchActivity`
uniquement, où chaînes/films/séries se mélangent dans un écran de résultats
compact — pas de mosaïque là, une liste reste plus lisible mélangée à un mur
d'affiches.

⚠️ **Mini-guide EPG retiré entièrement** (29/08/2026, demande explicite) :
les deux adapters affichaient un "en cours" par chaîne (Xtream uniquement,
`get_short_epg`) — supprimé partout (adapters, layouts `tv_epg`, `Channel
Repository.getShortEpg`/`XtreamClient.getShortEpg`, modèles `EpgNowNext`/
`XtEpgListing`, table Room `epg_cache` + son dao/entity, bouton "Vider le
cache EPG" de Réglages). DB `version` passée à 6 (`fallbackToDestructive
Migration`, cf. section Room plus bas) pour refléter la table en moins. Si
un mini-guide EPG redevient utile, repartir de zéro plutôt que de chercher
un reste — rien n'a été laissé en place derrière un flag.

## Cache catalogue chaud (PlaylistRepository) + recherche interne en SQL

⚠️ **"Toujours long à recharger en revisitant Films" (corrigé 28/08/2026)** :
`PlaylistRepository.getMovies()`/`getSeries()`/`getChannels()` créaient un
**nouveau** `combine()` (requête SQL + mapping domaine + jointure favoris/
historique sur tout le catalogue) à **chaque appel** — comme chaque visite de
Films/Séries/Chaînes instancie un nouveau ViewModel (`getMovies().asLiveData()`
dans son constructeur), rouvrir l'écran remappait les ~136 000 films à
chaque fois, aucune réutilisation entre deux visites même dans la même
session. Passés en `StateFlow` "chauds" au niveau du repository
(`moviesFlow`/`seriesFlow`/`channelsFlow`, `by lazy` + `.stateIn(appScope,
SharingStarted.Eagerly, emptyList())`) : calculés une fois, gardés en mémoire
pour tout le process, recalculés seulement si movies/favoris/historique
changent réellement (Room réémet) — pas à chaque ouverture d'écran.
`MainActivity.observeData()` **préchauffe** les trois en les référençant dès
l'accueil (suffit à déclencher le `by lazy`, pas besoin de collecter) : la
plupart du temps, le coût est déjà payé avant même que l'utilisateur clique
sur Films.

⚠️ **Recherche interne Films/Séries/Chaînes "pas immédiate" comparée à
l'accueil (corrigé le même jour)** : même une fois déplacé en coroutine
(cf. section précédente sur ce point), filtrer `getMovies().value` en Kotlin
avec `foldAccents()` (Normalizer) par titre restait notablement plus lent que
l'écran Recherche global, déjà en SQL. `MoviesViewModel`/`SeriesViewModel`/
`LiveViewModel` appellent maintenant `repository.searchMoviesByTitle`/
`searchSeriesByTitle`/`searchChannelsByName` (mêmes requêtes `LIKE` que
`searchTitle`, factorisées dans `PlaylistRepository` — `searchTitle` les
appelle désormais toutes les trois au lieu de dupliquer la logique) dès qu'une
recherche est en cours ; catégorie/favoris/FR appliqués ensuite sur le
résultat déjà réduit, jamais sur les 136 000/47 000 lignes à la fois. Coût
accepté : perte de l'insensibilité aux accents sur ces 3 champs de recherche
(SQLite `LIKE` ne connaît pas `foldAccents()`) — déjà le cas côté recherche
globale, jamais signalé comme un manque.

## Titre "propre" à l'affichage (Movie.displayTitle)

Demande explicite 28/08/2026 : "voir le vrai nom du film" — `util.
stripReleaseTags()` (extrait des regex de `TmdbClient.cleanTitle`, partagé)
retire les tags qualité/langue/codec ("4K-EN - Avatar (2009)" → "Avatar
(2009)", garde l'année contrairement à `cleanTitle` qui la retire aussi pour
la recherche TMDb). Exposé via `Movie.displayTitle` (computed property) —
**mur d'affiches (`PosterAdapter`) et fiche film/série l'utilisent, pas
`title`** (`title` brut reste utilisé pour `searchByTitle` en SQL : taper
"4K" doit encore trouver ces titres). Callers qui passent un titre en `Intent`
extra vers un autre écran (`SeriesActivity`/`FavoritesActivity`/
`SearchActivity` → `SeriesDetailActivity`, `DetailActivity`/`ResumeActivity`
→ `PlayerActivity`) le font aussi en `displayTitle` — l'écran de destination
affiche tel quel ce qu'il reçoit, pas de nettoyage à refaire côté récepteur.

⚠️ **"FR" oublié dans `QUALITY_LANG_TAG` au premier passage** (corrigé le
jour même, signalé par l'utilisateur : "FR - Ghost (1990)" toujours affiché
tel quel) — la liste avait EN/DE/ES... mais pas le tag le plus courant sur un
panel français. Conséquence **double**, pas seulement l'affichage : le même
résidu cassait aussi `TmdbClient.cleanTitle()` → la recherche TMDb (cast/
réalisateur/similaires) échouait pour tout titre préfixé "FR - " (chaîne
envoyée telle quelle à l'API, "FR - Ghost" au lieu de "Ghost"). Diagnostiqué
en ouvrant la fiche en direct sur le téléphone du user (adb) : synopsis
Xtream déjà correct (`get_vod_info` fonctionne bien), mais aucune section
casting/similaires visible → `hasTmdbMatch` restait `false`. Un seul token
manquant dans une regex partagée peut donc casser deux fonctionnalités
différentes en même temps — vérifier `stripReleaseTags()`/`cleanTitle()`
ensemble si l'une des deux semble à nouveau ne pas fonctionner sur un préfixe
de playlist particulier.

Réglage "Langue du contenu" (filtre FR pour Films/Séries/Chaînes) : cf.
section **Réglages** plus bas — `ContentLanguagePrefs`.

## Tri "ordre TNT" des chaînes françaises (LiveViewModel)

Demande explicite 28/08/2026 : quand le contexte est français (catégorie
sélectionnée reconnue par `isFrenchLabel`, ou réglage Langue du contenu = FR),
les chaînes sont triées par `tntRank()` (TF1, France 2, France 3, Canal+, France 5, M6,
Arte, C8, W9, TMC, TFX, NRJ 12, LCP, France 4, BFM TV, CNews, CStar, Gulli,
TF1 Séries Films, L'Équipe, 6ter, RMC Story, RMC Découverte, Chérie 25,
franceinfo:) plutôt que l'ordre de la playlist. Comparaison par sous-chaîne
sur le nom nettoyé (accents/casse, `foldAccents()`), tolérant aux préfixes de
playlist ("FR| TF1 HD"...) — même limite heuristique que `isFrenchLabel` :
un nom de chaîne hors norme n'est simplement pas reconnu (`Int.MAX_VALUE`,
relégué en fin de liste). Hors contexte français, ordre inchangé.

## TMDb — jaquettes et fiche film

Aucun backend : l'app interroge TMDb directement (clé dans `AppConfig.Tmdb`).

- **Au chargement d'un M3U seulement** (`enrichMovies`) : recherche par titre
  pour **chaque** film, en parallèle borné à 6 requêtes simultanées
  (`tmdbSemaphore`). Jaquette/synopsis/note/année TMDb **prioritaires** sur
  celles de la source — un `tvg-logo` de M3U public est souvent un lien mort ou
  une icône générique. N'enrichir *que* les entrées sans jaquette (version
  précédente) laissait donc des affiches cassées. `tmdbId` est stocké
  (`MovieEntity.tmdbId`) pour éviter une seconde recherche à l'ouverture de la
  fiche. Coût assumé : une recherche par film au chargement.
- ⚠️ **Aucun appel TMDb au CHARGEMENT côté Xtream** (depuis 1.0.10, toujours
  valable) : `stream_icon`/`rating` (VOD) sont bien fournis par
  `get_vod_streams` sur la quasi-totalité des panels, mais **`plot`/`genre`
  quasiment jamais** en pratique (case `it.plot` souvent vide malgré le champ
  prévu dans `XtStream`, constaté 28/08/2026 sur un vrai panel) — ces deux
  champs ne vivent que dans `get_vod_info`, un appel **par film**. Sur un
  panel réel de ~136 000 films, l'appeler pour CHAQUE film au chargement
  représenterait des milliers de requêtes : chargement interminable (perçu
  comme un figeage) puis téléphone saturé en navigation. **Ne jamais appeler
  `get_vod_info` (ni TMDb) dans `loadXtream`** — uniquement à la demande, cf.
  point suivant.
- **Synopsis Xtream à la demande** (`PlaylistRepository.
  enrichMovieFromXtreamIfNeeded`, 28/08/2026) : appelé uniquement par
  `DetailViewModel.load()`, donc seulement quand l'utilisateur ouvre CETTE
  fiche précise (jamais en lot) — `get_vod_info(vod_id)` remplit
  overview/genre/note/durée/backdrop si `MovieEntity.overview` est vide et
  que le film a un `xtreamStreamId` (vide pour un film M3U). Écrit en base
  (`movieDao().insertAll`, REPLACE sur l'id) pour ne plus jamais rappeler
  l'API sur ce même film. Si le panel ne renvoie toujours rien (`plot` vide
  même via `get_vod_info`), le film reste sans synopsis — pas de repli TMDb
  ici, cf. point suivant qui s'en charge séparément (casting/similaires).
  ⚠️ **`duration_secs` pas fiable sur tous les panels** (constaté 28/08/2026,
  affichait "0h 2min" pour un long-métrage) : `runtime` n'est mis à jour que
  si `durationSecs >= 300` (5 min), sinon l'ancienne valeur est gardée —
  filet, pas une vraie correction (aucun moyen de savoir si c'est le panel ou
  un champ mal nommé, `MovieDao/XtVodInfo` n'ont qu'un seul champ durée).
- **`TmdbClient.cleanTitle()`** nettoie les noms scene-release réels
  (`Movie.Title.2020.FRENCH.1080p.BluRay.x264-GROUP`) — séparateurs `._+`,
  année, tags qualité/langue, suffixe `-GROUPE` en fin de chaîne uniquement
  (sinon « Spider-Man » serait tronqué). Sans ça la recherche ne trouve rien.
  ⚠️ **Étendu 28/08/2026** pour les préfixes typiques Xtream ("4K-EN - Avatar
  (2009)", "3D-DE- 300...") : `3D`/`HDR`/`DV`/`ATMOS` et codes langue bruts
  (`EN`/`DE`/`ES`...) ajoutés aux tags reconnus, + un nettoyage final des
  tirets/espaces résiduels en **bord de chaîne uniquement** (`^`/`$`, jamais
  au milieu — ne touche pas "Spider-Man"). Avant ce correctif, ces titres
  laissaient un résidu du type "-EN - Avatar" après nettoyage, que TMDb ne
  matchait jamais → cast/réalisateur/similaires n'apparaissaient tout
  simplement pas sur une bonne partie du catalogue Xtream, sans erreur
  visible pour l'expliquer.
- **Fiche film** (`DetailActivity`) : casting, réalisateur, films similaires,
  bande-annonce, fiche acteur/filmographie — mêmes layouts que NicoTV,
  **fonctionne pour Xtream comme pour M3U** (aucune branche par source dans
  `DetailActivity`/`DetailViewModel.loadExtras` : `MovieEntity.tmdbId` vaut 0
  pour tout film Xtream — pas de résolution TMDb au chargement, cf. point
  précédent — donc `loadExtras` retombe sur la recherche par titre à chaque
  ouverture de fiche, c'est `cleanTitle()` qui détermine si elle aboutit).
  Différence forcée par l'absence de backend : un titre similaire déjà présent
  dans le catalogue chargé s'ouvre (badge ✓, résolution **par titre** —
  `MovieDao/SeriesDao.findByTitle`, nos entrées n'ont pas d'id TMDb propre).

  ⚠️ **Badge "+" retiré (29/08/2026, demande explicite)** — contrairement à
  NicoTV (convention `feedback_tmdb_addcard_convention`, mémoire utilisateur :
  "+" ajoute réellement à une file d'attente côté backend), le "+" d'iptv2
  n'a **jamais** fait qu'un `Toast` "pas dans votre playlist" — il n'existe
  pas de backend ici pour ajouter quoi que ce soit. Sur demande, ce badge
  n'apparaît donc plus du tout pour un titre absent du catalogue (case vide,
  `SimilarWorkAdapter`/`DetailActivity.btnAddWrap`) ; seul le ✓ (déjà dans le
  catalogue) reste affiché. **Divergence assumée d'iptv2 par rapport à la
  convention NicoTV** — ne pas reporter ce retrait vers NicoTV, où le "+"
  reste fonctionnel et doit rester tel quel.

  ⚠️ **Bug corrigé le jour même : le ✓ n'apparaissait quasiment jamais** —
  `MovieDao/SeriesDao.findByTitle` comparait par **égalité exacte**
  (`title = :title COLLATE NOCASE`) le titre TMDb nu ("Avatar") au titre
  catalogue brut, qui garde ses tags qualité/langue/codec et son année
  ("4K-EN - Avatar (2009)") — ça ne matchait presque jamais. Renommé
  `findCandidatesByTitle` (`LIKE '%:title%'`, ramène les titres catalogue
  qui CONTIENNENT le titre TMDb comme sous-chaîne, `LIMIT 20`), vérifié
  ensuite dans `PlaylistRepository.findMovieByCleanTitle`/
  `findSeriesByCleanTitle` par égalité **après nettoyage complet** du titre
  catalogue (`util.cleanTitleForMatch`, tags+année — extrait de l'ancien
  `TmdbClient.cleanTitle`, désormais un simple appel à cette fonction
  partagée). Le `LIKE` seul aurait pu créer des faux positifs (ex. TMDb
  "Up" matchant un catalogue "Wake Up") — c'est la vérification par titre
  nettoyé, pas le `LIKE`, qui tranche.

Tous les appels TMDb sont **best-effort** : `TmdbClient` avale les exceptions et
renvoie `null`/liste vide, un échec réseau ne doit jamais bloquer un écran.

## Réseau

`network_security_config.xml` autorise le **cleartext HTTP globalement**
(contrairement à NicoTV, hôtes fixes tout HTTPS) : la grande majorité des
panels Xtream/M3U tiers tournent en HTTP simple, pas de certificat. C'est
voulu, ne pas restreindre sans en avoir reparlé avec l'utilisateur.

## Écran de démarrage (SetupActivity)

Profils enregistrés en **cartes façon sélecteur Netflix** tout en haut de
l'écran, avant même le wordmark (avatar rond coloré par profil, tap =
recharger, crayon = modifier, croix = supprimer — cf. `ProfileAdapter`/
`item_profile.xml`), puis 2 cartes « Charger votre playlist » (URL M3U **ou**
fichier local, un seul formulaire, un seul bouton qui priorise le fichier
choisi) et « Xtream Codes ».

⚠️ **Barre d'en-tête + mise en page sans scroll (1.0.37, demande explicite)** :
`activity_setup.xml` avait un `ScrollView` — sur un écran bas
(`sensorLandscape` ~360dp), profils + wordmark + cartes dépassaient largement
la hauteur visible, l'utilisateur devait scroller. Le `ScrollView` a été
retiré et toutes les dimensions/marges resserrées (avatars `item_profile.xml`
84dp→60dp, cartes 120dp→84dp, wordmark 36dp→24dp, paddings/marges réduits
partout) pour que tout tienne dans ~360dp sans scroll. Une barre d'en-tête a
été ajoutée, même structure que `activity_settings.xml` : flèche retour
(`btn_back`, **visible seulement** si l'écran est ouvert via Réglages →
« Changer de source », `EXTRA_FORCE_SHOW` — au lancement normal, aucun profil
actif, il n'y a rien à quoi revenir) + titre `setup_screen_title` ("Profils"). Si une future modif de cet écran
ajoute du contenu, revérifier sur un appareil bas de gamme/petit écran en
`sensorLandscape` que ça tient toujours sans réintroduire de scroll.

⚠️ **Accès Réglages depuis Profils : essayé puis abandonné** (29/08/2026,
3 revirements le même jour — icône roue crantée, puis texte "Réglages" en
couleur accent, puis **retiré entièrement**, demande explicite à chaque
fois). Il n'y a **plus aucun accès à Réglages depuis Profils** : seul
`MainActivity` (icône engrenage de l'accueil) l'ouvre, comme à l'origine.
Ne pas réintroduire ce bouton sans redemander — ce point précis a changé
3 fois dans la journée avant de revenir à zéro.

⚠️ **Historique du raccourci auto-load (inversé en 1.0.20)** : une version
antérieure sautait à l'accueil dès qu'un profil était actif, avec un défaut
précis — l'écran de sélection devenait **définitivement inaccessible**, aucun
moyen d'en changer. D'où la règle "toujours affiché" qui a suivi. Le
raccourci a été **réintroduit en 1.0.20** (demande explicite, comportement
IPTV Smarters Pro) mais cette fois sans le défaut d'origine : Réglages →
« Changer de source » (`SettingsActivity`) reste une porte de sortie
explicite vers cet écran, via `EXTRA_FORCE_SHOW` qui désactive le saut
(`SetupActivity.maybeAutoLoadLastProfile`). **Ne pas réintroduire l'ancien
saut sans cette porte de sortie** — c'est elle qui rend le raccourci sûr.
Le saut lui-même : `PlaylistRepository.hasValidActiveProfile()` (lecture Room
seule, rapide) décide, testé pendant que le splash screen reste affiché
(`splashScreen.setKeepOnScreenCondition`) pour qu'aucun flash de l'écran de
sélection n'apparaisse avant le saut — catalogue servi depuis le cache Room,
zéro attente réseau au lancement (le rafraîchissement si périmé reste géré en
fond par `MainActivity.onStart` → `refreshActiveProfileIfStale`, cf. section
Réglages ci-dessous).

⚠️ **Formulaires en pages complètes, plus en `AlertDialog`** (29/08/2026,
demande explicite — 2ᵉ étape après un premier passage en dialogue centré) :
`AddPlaylistActivity`/`AddXtreamActivity` (`ui/setup/`), lancées par les 2
cartes (nouveau profil) ou le crayon d'un profil existant (édition — extras
`EXTRA_EDIT_*`, pas de `Parcelable`, juste les champs primitifs nécessaires
au pré-remplissage). Chacune a sa propre flèche retour (même structure de
barre d'en-tête que Profils/Réglages) qui `finish()` sans rien enregistrer —
« retour sur Profils » explicite demandé. Un chargement réussi navigue direct
vers `MainActivity` (comme avant), une erreur affiche le statut **sur cette
page** et la laisse ouverte pour corriger/réessayer (`SetupActivity` n'est
plus impliqué une fois la page ouverte). Contenu des champs identique aux
anciens `dialog_form_playlist.xml`/`dialog_form_xtream.xml` (supprimés),
toujours dans un `ScrollView` : l'écran reste en `sensorLandscape` (~360dp
de haut), le clavier ouvert sur le dernier champ fait sortir le bouton
« Charger »/« Se connecter » de la zone visible sans ça.

⚠️ **`installSplashScreen()` obligatoire** : `SetupActivity` déclare
`android:theme="@style/Theme.IPTV.Splash"` dans le manifeste (parent
`Theme.SplashScreen`, chrome clair). Sans l'appel `installSplashScreen()` **avant
`super.onCreate()`**, le thème ne bascule jamais vers `postSplashScreenTheme`
(`Theme.IPTV`, sombre) : l'activité reste sur le thème splash toute sa vie, d'où
un bandeau clair permanent affichant le nom de l'app en haut de l'écran. Piège
vécu — un premier correctif posant `windowNoTitle` sur `Theme.IPTV` visait le
mauvais thème et n'avait donc aucun effet.

## Réglages (SettingsActivity) — cache images / playlist / langue

Écran ouvert depuis l'engrenage de l'accueil (`btn_settings` — **a changé de
sens** : ouvrait `SetupActivity` directement jusqu'ici, ouvre maintenant
`SettingsActivity`, qui elle-même propose "Changer de source" vers
`SetupActivity(EXTRA_FORCE_SHOW)`). Ne pas revenir à l'ancien raccourci direct.
`SetupActivity` (Profils), elle, n'a **aucun** accès Réglages — cf. sa
section plus haut (retiré définitivement le 29/08/2026 après 2 essais).

⚠️ **En-tête en superposition, pas empilée avec le ScrollView** (corrigé
29/08/2026, demande explicite : "le texte passe encore au-dessus de
Réglages... créer une zone en haut") — `activity_settings.xml` empilait
l'en-tête et le `ScrollView` comme deux frères dans une `LinearLayout`
verticale (poids 0dp sur le ScrollView) ; le contenu qui défile pouvait
visuellement passer devant le titre "Réglages", l'en-tête n'ayant ni fond
opaque propre ni z-index garanti au-dessus. Restructuré en superposition :
le `ScrollView` occupe tout l'écran, l'en-tête est un frère ajouté **après**
lui dans le XML (donc dessiné par-dessus, l'ordre de dessin suit l'ordre des
enfants) avec son propre fond opaque (`@color/bg_nav`, distinct de
`bg_dark`) + `elevation="4dp"` — le contenu ne peut plus jamais passer
devant, quelle que soit la mesure. Le contenu du `ScrollView` compense avec
un `paddingTop` fixe (96dp) qui dégage la hauteur de la barre. **Même
correctif appliqué à `activity_add_playlist.xml`/`activity_add_xtream.xml`**
(même risque structurel, ces deux pages construites plus tôt le même jour) —
si un futur écran empile encore un en-tête `wrap_content` au-dessus d'un
`ScrollView` en simples frères, reprendre ce même patron de superposition
plutôt que redemander "pourquoi ça déborde".

- **Cache images (Coil)** : config explicite dans `IptvApplication`
  (`ImageLoaderFactory`) — 300 Mo disque, 25% de la RAM en mémoire. Par défaut
  Coil n'a pas de limite fiable en usage réel sur un mur d'affiches
  film/série/chaîne d'un gros panel Xtream. Vidé via `ImageCacheUtil.clear()`
  ("Vider le cache images").
- **Cache playlist/catalogue** : le tap sur un profil dans `SetupActivity`
  reste **toujours** un rechargement réseau complet (comportement volontaire
  et documenté plus haut, ne pas changer — un flux/token Xtream peut tourner).
  Ce qui est ajouté ici : `MainActivity.onStart()` déclenche un rafraîchissement
  silencieux best-effort (`refreshActiveProfileIfStale`) si le profil actif n'a
  pas été rechargé depuis 24h (`PlaylistProfileEntity.lastUsedAt`), et Réglages
  propose un "Actualiser" manuel immédiat. Aucun des deux ne bloque/casse le
  flux existant en cas d'échec réseau.
- **Langue du contenu** (`ContentLanguagePrefs`) : liste **dynamique**, pas
  câblée en dur (`PlaylistRepository.getAvailableContentLanguages()` scanne
  le catalogue chargé — noms de chaîne + catégories films/séries — et
  découvre les codes réellement présents : "FR", "AF", "CA"... chaque panel a
  les siens). Filtre sur le code en tête (`util.LanguageCode`, extrait le
  28/08/2026 après une confusion avec `isFrenchLabel` — voir paragraphe
  suivant), pas une heuristique substring. Null = Toutes.
  ⚠️ **Pas un "exact match obligatoire"** (corrigé le jour même, régression
  signalée par l'utilisateur : plus aucune série, "beIN Sport" disparu des
  Chaînes) — un premier passage exigeait `extractLeadingLanguageCode(...) ==
  contentLanguage` pour garder un item, ce qui excluait tout item **sans
  aucun préfixe**. Or sur ce panel, la plupart des chaînes/catégories
  françaises n'ont justement AUCUN préfixe (pas de norme, seuls certains
  bouquets étrangers sont explicitement marqués `"CA:"`/`"AL - "`...) : exiger
  "FR" en tête revenait à ne garder qu'une poignée d'items marqués, effaçant
  tout le reste. Règle retenue partout où ce filtre s'applique
  (`LiveViewModel.filteredChannels`/`categories`,
  `Movies|SeriesViewModel.applyLanguageFilter`) : garder si **aucun préfixe
  détecté** OU préfixe == `contentLanguage` ; exclure seulement un préfixe
  explicite d'une **autre** langue.
  ⚠️ **Deux conventions de délimiteur différentes constatées sur un panel
  réel** — d'où `extractLeadingLanguageCode()` teste les deux : noms de
  chaîne en `"FR: TF1 HD"`/`"AF: TF1"` (deux-points, parfois barre verticale
  — l'utilisateur avait d'abord supposé "FR|", à vérifier en direct plutôt
  que de faire confiance à la mémoire de l'utilisateur sur ce point précis) ;
  catégories/titres films-séries en `"FR - Ghost (1990)"` (tiret espacé).
  Sur **Chaînes**, en plus de filtrer, le préfixe est **retiré du nom
  affiché** (`stripLeadingLanguageCode`, `LiveViewModel.filteredChannels`) —
  demande explicite. Lu **une seule fois à la création du ViewModel**
  (`MoviesViewModel`/`SeriesViewModel`/`LiveViewModel` — un nouveau ViewModel
  à chaque ouverture d'écran, cf. section cache catalogue) : changer le
  réglage ne met PAS à jour un écran déjà ouvert, seulement le prochain.
  Cohabite avec le tri "France en premier" des sidebars catégories (aussi
  `isFrenchLabel`), inchangé.

  ⚠️ **Bouton "FR" de l'écran Chaînes retiré** (28/08/2026, demande
  explicite) : `LiveViewModel.frenchOnly`/`isFrench()` (heuristique
  `isFrenchLabel`, nom+catégorie complets, pré-coché si le réglage valait
  exactement "FR") existaient en plus du réglage "Langue du contenu"
  ci-dessus, redondants une fois ce dernier corrigé (cf. plus bas — le
  premier passage du filtre par code excluait à tort tout item sans préfixe).
  Supprimés entièrement (ViewModel + `btn_french_filter`/`tv_french_filter`
  dans `activity_live.xml` + câblage `LiveActivity`), pas juste masqués. Si
  un filtre "France uniquement" séparé redevient utile, repartir de
  `isFrenchLabel` (toujours présent, utilisé par le tri des catégories et
  `tntRank`) plutôt que de réintroduire `frenchOnly` tel quel.

  ⚠️ **Libellés de catégorie (sidebar Chaînes ET Films) aussi nettoyés**
  (ajouté 28/08/2026, demande explicite en deux temps — d'abord Chaînes,
  puis complété le jour même car la sidebar affichait encore les catégories
  d'autres langues type "CA|"/"AL|" à côté des FR nettoyées) :
  `LiveViewModel.displayCategory()`/`MoviesViewModel.displayCategory()`
  retirent le préfixe langue du nom de catégorie ("FR| Sport" → "Sport",
  "FR - Action" → "Action") quand il correspond à `contentLanguage` — même
  helper `extractLeadingLanguageCode`/`stripLeadingLanguageCode` que sur le
  nom des chaînes. **La liste de catégories elle-même est maintenant filtrée**
  sur le PROPRE préfixe de la catégorie (pas celui des chaînes/titres
  qu'elle contient) quand `contentLanguage` est actif — Live suit ici le
  même principe que Movies (`applyLanguageFilter`, préexistant côté Movies,
  ajouté côté Live pour l'occasion) : une catégorie d'une autre langue ne
  s'affiche plus du tout dans la sidebar, plutôt que de rester visible avec
  son préfixe brut. La sidebar ne connaissant que le libellé déjà nettoyé
  (pas le brut), le filtre par catégorie compare aussi
  `displayCategory(item.category)` côté `filteredChannels`/`filteredMovies`,
  jamais `item.category` directement — à refaire si une future modif de ce
  filtre repart du champ brut. Séries non touché (pas demandé).

## Room — migrations

`fallbackToDestructiveMigration()` uniquement, **pas de `Migration` écrite à la
main**. Une tentative de `MIGRATION_2_3` (`ALTER TABLE movies ADD COLUMN tmdbId
INTEGER NOT NULL DEFAULT 0`) a fait planter l'app au démarrage : SQLite exige un
`DEFAULT` pour ajouter une colonne `NOT NULL`, mais le schéma attendu par Room
n'en déclarait pas (`@ColumnInfo(defaultValue)` manquant) → `IllegalStateException:
Migration didn't properly handle…` dès le premier accès à la base. Coût du choix
retenu : la base est recréée à chaque bump de `version` — les profils, eux, sont
désormais rattrapés par `ProfileBackupPrefs` (cf. « Modèle de données »), mais
catalogue/favoris/reprise restent perdus. Si une vraie migration devient
nécessaire, il **faut** aligner `@ColumnInfo(defaultValue = "…")` sur le SQL — et
la tester sur un vrai upgrade, pas seulement sur une install neuve.

## Conventions UI reprises de NicoTV (inchangées)

- **RotatingBorderView** (anneau blanc tournant au focus clavier/télécommande)
  sur tous les boutons icône et les affiches — piloté en code
  (`startAnim()`/`stopAnim()` + visibilité sur `setOnFocusChangeListener`).
- **PosterAdapter** (mur d'affiches, films+séries unifiés via `domain.Movie`
  + `Type.MOVIE/SERIES/EPISODE`) — `DiffUtil` compare `(id, type)` : films et
  séries partagent le même espace d'id autoIncrement Room, une collision est
  possible sans le `type` dans la comparaison.
- Toutes les activités en `screenOrientation="sensorLandscape"`.
- Code et commentaires en **français**.
- **`BaseActivity`** pose `FLAG_KEEP_SCREEN_ON` sur toutes les écrans (demandé
  28/08/2026 : l'écran s'éteignait en pleine navigation menus, pas seulement
  en lecture). Redondant avec le flag déjà posé par `PlayerActivity` — sans
  conséquence, jamais retiré nulle part.

## Accueil (MainActivity) — 3 cartes en images collage statiques

⚠️ **Dénouement d'une longue série de revirements le 29/08/2026** — les 3
cartes (Chaînes/Films/Séries) sont passées par plusieurs traitements dans la
même journée (rotation de jaquettes catalogue, mosaïque de logos par
correspondance dynamique, logos embarqués en dur...) avant de se stabiliser
sur un principe unique et volontairement simple pour les 3 : **une image
collage statique en plein cadre, aucune dépendance au catalogue de
l'utilisateur, pas de dégradé, pas d'icône superposée**. **Ne pas
réintroduire de rotation/logique dynamique sur ces 3 cartes sans
redemander** — ce point précis a changé plusieurs fois avant de se fixer
ici.

⚠️ **Taille calculée à l'exécution, pas fixe** (corrigé le jour même,
demande explicite "réduire pour ne pas dépasser de l'écran... bien
centrées") : les 280dp fixes débordaient sur les téléphones en
`sensorLandscape`, où la largeur dispo est bien moindre que sur une
tablette/TV. `MainActivity.sizeHubCards()` calcule la largeur à partir de
`resources.configuration.screenWidthDp` — même principe que
`MoviesActivity.computeSpanCount()` — coercée entre 120dp et 280dp (280dp
reste la taille max, confortable sur grand écran), hauteur dérivée au ratio
3:2 (`cardWidthDp / 1.5f`, identique à celui des 3 images collage — aucun
rognage `centerCrop` nécessaire quand ça tombe pile dessus). Les valeurs
`280dp`/`188dp` dans `activity_main.xml` ne sont qu'un repli XML, toujours
écrasées par `sizeHubCards()` avant le premier rendu. `layout_gravity=
"center"` sur la rangée (XML, inchangé) centre le résultat quelle que soit
la taille retenue — pas de logique de centrage séparée à maintenir.

- **Chaînes** (`card_live`) : `res/drawable-nodpi/hub_live_collage.jpg`,
  collage Canal+/TF1/OCS/Netflix/Prime/beIN **fourni par l'utilisateur**
  (trouvé dans `iptv2/update/`, PNG original ~2,4 Mo converti en JPEG
  qualité 90 → ~400 Ko) — pas une image générée ou choisie par Claude.
  **⚠️ Inclut de vraies marques déposées**, réserve sur le risque juridique
  déjà actée avec l'utilisateur pour cette image précise (cf. historique de
  la mosaïque de logos qui l'a précédée).
- **Films** (`card_films`) : `res/drawable-nodpi/hub_films_collage.jpg`,
  couloir de cinéma avec affiches de films (image **générée** via
  `mcp__pollinations__generateImageUrl`, modèle `sana`, seed fixe pour
  reproductibilité — prompt simple et concret plutôt que des instructions de
  composition détaillées, `sana` suit mal les prompts complexes multi-zones :
  premiers essais avec un prompt décrivant une grille de 5 cases précises
  ont donné des images abstraites sans rapport). Aucune marque/titre réel
  représenté — image générique, pas de risque équivalent à Chaînes.
- **Séries** (`card_series`) : `res/drawable-nodpi/hub_series_collage.jpg`,
  salle de home cinéma avec écran allumé, même méthode de génération que
  Films.

`iv_home_bg` (fond plein écran, alpha 0.26) est **indépendant** de ces 3
images de carte : il continue de tirer un fond au hasard parmi les
films/séries du catalogue chargé de l'utilisateur (`maybeSetHomeBg`,
`movieHubUrls`/`seriesHubUrls` — ces deux champs n'existent plus que pour
alimenter ce fond, `loadRotatingHubImage`/`movieRotationJob`/
`seriesRotationJob` ont disparu avec la rotation des cartes), stable pour
tout le process (`MainActivity.cachedHomeBgUrl`) — remis à `null` par
`SetupActivity.loadProfile()` quand un nouveau catalogue est chargé
(`resetHomeBg()`). Le filtre FR des films pour ce fond (`extractLeading
LanguageCode(it.category)`) a gardé son correctif du 29/08/2026 : `c == null
|| c == "FR"`, jamais une égalité stricte (un panel où la plupart des
catégories françaises n'ont aucun préfixe de langue détecté — égalité
stricte = quasi aucun film retenu).

⚠️ **Compteurs retirés des 3 cartes** (`tv_live_count`/`tv_films_count`/
`tv_series_count`, 28/08/2026, demande explicite) — les vues XML **et** leurs
bindings Kotlin ont été supprimés (pas juste cachés), `formatCount()` a
disparu avec eux. Si un besoin de compteur revient, il faudra les
réintroduire de zéro, pas les "réafficher".

## Fiche série (SeriesDetailActivity) — 2 colonnes, pas un empilement vertical

⚠️ **Bug vécu (corrigé 28/08/2026)** : l'ancien layout empilait bandeau
(260dp) + affiche + titre + résumé **au-dessus** de saisons/épisodes dans une
seule colonne verticale, avec `paddingTop="150dp"`. Sur un écran bas
(téléphone en `sensorLandscape`, hauteur limitée), ce bloc fixe consommait à
lui seul presque toute la hauteur disponible — `rv_episodes` (`layout_height=
"0dp" layout_weight="1"`) héritait d'un reste proche de zéro : quasiment rien
à l'écran, et rien à scroller puisqu'il n'y avait presque plus de hauteur à
l'intérieur du RecyclerView lui-même. Diagnostiqué via screencap adb
(connexion wireless debugging au téléphone Android du user, pas besoin d'USB) :
le bas de l'écran ne montrait qu'un filet de contenu sous le résumé.

Repris de **NicoTV** (même fichier, structure identique) : layout **2
colonnes horizontales**. Colonne gauche (260dp, affiche/titre/résumé) dans
son propre `ScrollView` — quelle que soit sa hauteur de contenu, elle ne peut
plus jamais réduire l'espace de la colonne droite. Colonne droite (poids 1,
`match_parent` en hauteur) : onglets saisons (`wrap_content`) + `rv_episodes`
(poids 1) qui récupère donc **tout** le reste de la hauteur d'écran, peu
importe ce qu'il y a à gauche. `iv_backdrop` passé en plein écran (au lieu de
260dp fixe) avec `gradient_detail` par-dessus, comme NicoTV. Si un autre écran
empile un bloc de hauteur variable au-dessus d'une liste qui doit rester
utilisable, préférer ce découpage en colonnes/un `ScrollView` dédié plutôt
qu'un simple empilement vertical avec `layout_weight` sur le dernier élément —
`layout_weight` protège contre l'overflow mais pas contre un reste ridicule.

## Lecteur (PlayerActivity)

Repris quasiment tel quel de NicoTV (ExoPlayer, pistes audio/sous-titres,
vitesse, PiP, prompt épisode suivant, filet anti-blocage fin de flux) —
**retiré** : télécommande à distance (WebSocket, comptes multi-appareils),
heartbeat de présence, téléchargements hors-ligne (aucun sens sans compte
serveur). `historyKey` calculé depuis `seriesId`/`fileKeyExtra` (voir
`PlayerActivity.historyKey`) plutôt que transmis en extra séparé.

⚠️ **Barre de contrôle disparaissait trop vite au premier tap** (corrigé
29/08/2026, demande explicite "au moins 5s") : `controllerShowTimeoutMs`
n'était mis à 5000ms que dans `setControllerVisibilityListener`/
`closePanel` — donc seulement à partir de la **deuxième** apparition ; la
toute première (lecture démarrée ou premier tap de l'utilisateur) utilisait
le défaut `PlayerView` (3000ms), d'où l'impression qu'elle "disparaît vite".
Fixé en initialisant aussi `controllerShowTimeoutMs = 5000` dès la config
du player (à côté de `controllerAutoShow`/`controllerHideOnTouch`). Un seul
`PlayerActivity` pour Chaînes/Films/Séries — le correctif couvre les 3.

⚠️ **Redirections cross-protocole obligatoires** (corrigé en 1.0.6) :
`DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)`, passé au
player via un `DefaultMediaSourceFactory` dédié. Sans ça, les flux d'un panel
qui redirige (`303` vers un CDN, cas du panel de test) échouaient sur
`InvalidResponseCodeException: Response code: 303` — écran noir, `00:00`, aucune
erreur affichée à l'utilisateur. Contrairement à NicoTV (hôtes fixes maîtrisés),
la source ici est fournie par l'utilisateur : la redirection est la norme.

## Mise à jour OTA + panel de build

- `UpdateManager.checkForUpdate()` lit `AppConfig.Update.VERSION_URL`
  (`https://iptv2.nicotv.ovh/update/version.json`), propose la MAJ si
  `remote.versionCode > BuildConfig.VERSION_CODE`.
- **Rond qui tourne pendant le téléchargement** (29/08/2026, demande
  explicite) : `dialog_update_progress.xml` (`ProgressBar` indéterminé +
  texte), affiché par `showUpdateProgress()` dès le clic sur "Mettre à
  jour", fermé par le callback `onFinished` de `downloadAndInstall()`
  (ajouté à cette occasion — appelé une fois sur le thread principal, succès
  **ou** échec, cf. `pollDownload`). Avant : `DownloadManager` système avec
  notif masquée (`VISIBILITY_HIDDEN`) + un `Toast` furtif au lancement,
  aucun retour visuel ensuite jusqu'à l'ouverture de l'installeur — ce
  `Toast` a été retiré, redondant avec le dialogue désormais visible tout du
  long. Même trick que `showQuitDialog` pour le fond (`bg_dialog` sur la vue
  elle-même, fenêtre du dialogue passée en transparent).
- Déclenché dans `SetupActivity.maybeAutoLoadLastProfile()` et
  `MainActivity.onStart()`, throttlé à 2 min (`lastUpdateCheck`, partagé entre
  tous les écrans).
  ⚠️ **Piège vécu (corrigé 28/08/2026)** : `SetupActivity` appelait
  `checkForAppUpdate()` inconditionnellement dans `onCreate()`, **avant**
  `maybeAutoLoadLastProfile()` (saut auto vers l'accueil, cf. section
  correspondante). La vérif Room de ce dernier est quasi instantanée, quand
  l'appel réseau du check MAJ ne l'est pas — `finish()` s'exécutait
  systématiquement avant la réponse, que `checkForAppUpdate()` ignore
  silencieusement (`if (isFinishing) return@launch`). Pire : le throttle
  partagé était déjà consommé, donc `MainActivity.onStart()` sautait aussi
  son propre appel juste après → plus aucune MAJ jamais proposée. Fix :
  `checkForAppUpdate()` n'est appelé **que dans la branche où on reste sur
  SetupActivity** (pas de profil actif, ou `EXTRA_FORCE_SHOW`) ; en cas de
  saut vers l'accueil, c'est `MainActivity.onStart()` qui fait CE check, à
  froid. Si la MAJ cesse à nouveau de se proposer, vérifier en premier qu'un
  appel réseau (check MAJ ou autre) n'a pas été replacé avant un `finish()`/
  une navigation qui s'exécute plus vite que lui.
- `iptv2.nicotv.ovh` héberge **aussi** un panel de build (Git Pull/Build,
  calqué sur `apk2.nicotv.ovh`) — racine du domaine protégée par Cloudflare
  Access, `/update/` en bypass explicite (2 Access Applications distinctes,
  sinon l'app ne peut pas lire `version.json`). Détail complet, y compris le
  script `iptv2-builder.sh` et les unités systemd : [`server/README.md`](server/README.md).
  **Consigne fixe** : Claude bumpe/commit/push, ne compile **jamais** et ne
  déclenche **jamais** de build (même via le trigger systemd) — le build reste
  toujours à l'utilisateur, depuis le panel.
- Le panel liste les derniers commits via `git log` lancé par Apache : chaque
  dépôt affiché doit être déclaré dans **`/etc/gitconfig`** (`safe.directory`),
  sinon git refuse (« dubious ownership », le dépôt appartient à `nicolas`, pas
  à `www-data`) et la liste revient vide sans erreur visible.

## Branding — à finaliser

Icône (`ic_launcher`), bannière (`banner`) et wordmark (`ic_nicotv_wordmark.png`)
sont encore les PNG **NicoTV** hérités de la copie initiale (rouge, texte
« NICOTV ») — assets raster, pas modifiables en édition de texte. `app_name`
a été renommé en "IPTV2" mais l'identité visuelle reste à refaire si l'app
doit se démarquer visuellement de NicoTV.

## Secrets (NE JAMAIS logger, afficher, ni committer en clair)

Le mot de passe du keystore release (`iptv2StorePassword`/`iptv2KeyPassword`
dans `local.properties`, jamais committé) est sensible.
