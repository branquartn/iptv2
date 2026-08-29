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

## Favoris (FavoritesActivity)

⚠️ **Bug corrigé 29/08/2026 : une chaîne en favori n'apparaissait nulle
part** — signalé par l'utilisateur ("quand je mets une chaîne en favoris
elle n'apparaît [pas] dans les favoris"). `FavoritesViewModel` n'interrogeait
que `repository.getFavoriteMoviesAndSeries()` — `getFavoriteChannels()`
existait déjà (utilisée par le bouton favoris de l'écran Chaînes) mais
n'était jamais branchée ici. `FavoritesActivity` a maintenant son **propre
layout dédié** (`activity_favorites.xml`, ne partage plus
`activity_movies.xml` avec `MoviesActivity`/`ResumeActivity`) : une section
"Chaînes" (mosaïque `ChannelGridAdapter`, même tuile que l'écran Chaînes,
appui long = retirer des favoris) au-dessus du mur d'affiches films/séries
existant (`PosterAdapter`, inchangé). `rv_channels` en hauteur fixe (160dp,
scroll interne si plusieurs lignes) plutôt que `wrap_content` — un
`RecyclerView` avec `GridLayoutManager` a besoin d'une hauteur bornée dans ce
contexte (page entière dans un `ScrollView`, `nestedScrollingEnabled=false`
sur `rv_posters` pour éviter le double-scroll).

⚠️ **Tip affiché quand aucune chaîne n'est en favori** (même jour, demande
explicite : "un texte ou tips qui explique comment mettre en favoris les
chaînes") — le geste (appui long sur une tuile de l'écran Chaînes) n'est pas
découvrable de lui-même. `tv_channels_tip`/`favorites_channels_tip`
remplacent la mosaïque tant que `getFavoriteChannels()` est vide,
visibilités mutuellement exclusives.

⚠️ **Même astuce réutilisée sur l'écran Chaînes (`LiveActivity`)** (même jour,
demande explicite : "aussi avoir le tips des favoris dans les favoris des
chaînes") — le filtre "favoris uniquement" de cet écran (`btnFavoritesFilter`)
n'avait qu'un message générique ("Aucune chaîne trouvée",
`live_empty_title`) quand il ne restait rien après filtrage, sans distinguer
"aucun résultat pour cette recherche/catégorie" de "aucune chaîne en favori
du tout". `tv_empty.text` bascule maintenant sur `favorites_channels_tip`
(même string que l'écran Favoris) si `favoritesOnly.value == true`, sinon
`live_empty_title` — recalculé à chaque émission de `filteredChannels`
(inclut les toggles du filtre, `LiveViewModel` n'a pas de
`distinctUntilChanged` donc chaque bascule republie même si la liste
filtrée reste vide des deux côtés).

⚠️ **"Vide" global attend les DEUX flux** (favoris films/séries et chaînes
répondent indépendamment, l'un peut émettre avant l'autre) —
`movieFavoritesLoaded`/`channelFavoritesLoaded` (booléens, pas de valeur par
défaut significative) évitent un flash "aucun favori" pendant que le second
flux n'a pas encore répondu. Si un 3ᵉ type de favori est ajouté un jour,
reprendre ce même patron plutôt qu'un simple `isEmpty()` sur une seule liste.

⚠️ **CRASH `too many SQL variables` (30/08/2026, introduit par le chargement
d'une catégorie entière, corrigé le jour même)** — signalé "ça bug sur Films,
ça s'ouvre et se referme direct et revient à l'accueil". Stack trace récupérée
sur le Shield (`adb logcat -b crash`) : `SQLiteException: too many SQL
variables ... SELECT * FROM watch_history WHERE historyKey IN (?,?,?...)`,
depuis `PlaylistRepository.getMoviesPage` → `WatchHistoryDao.getPositions`.

**Cause** : SQLite plafonne le nombre de paramètres liés d'une requête
(`SQLITE_MAX_VARIABLE_NUMBER`, **999** sur les Android concernés). Tant que
chaque lecture restait bornée (page de 60, recherche limitée à 200), le
`IN (:keys)` de la jointure "reprise de lecture" passait. Le passage au
chargement d'une **catégorie entière** (`NO_LIMIT`, v1.0.68) a fait exploser
ce nombre — des milliers de clés d'un coup — et la requête est devenue
impossible à compiler. Rendu systématique par la v1.0.69, qui ouvre
justement Films sur une catégorie précise dès le lancement : crash immédiat,
retour à l'accueil.

**Correctif** : `PlaylistRepository.watchPositionsFor(keys)` découpe la liste
en lots de `SQLITE_MAX_VARIABLES` (900, marge de sécurité) avant d'appeler le
DAO, et **les 3 appels** concernés passent par lui (`getMoviesPage`,
`searchMoviesByTitle`, `getEpisodeProgressMap`). **Ne jamais repasser un
`getPositions(...)` brut sur une liste non bornée** — c'est exactement ce qui
a cassé ici. Même vigilance pour toute future requête `WHERE x IN (:liste)`
alimentée par le catalogue : `WatchHistoryDao.removeHistories` a la même
forme (aujourd'hui inutilisée, donc sans risque — à découper aussi si elle
est un jour appelée).

⚠️ **Leçon de méthode** : ce bug n'était PAS détectable en relisant le code
des écrans — la requête est correcte, c'est sa taille d'entrée qui a changé
en amont. Sur ce projet où Claude ne compile jamais (cf. consigne de build),
`adb logcat -b crash` sur le Shield reste le moyen le plus rapide d'obtenir
la cause exacte plutôt que de deviner.

⚠️ **Piège recyclerview 1.0.0** (build cassé le 30/08/2026, `Unresolved
reference 'currentList'`) : le projet ne déclare PAS `androidx.recyclerview`
directement, il le tire **transitivement de `androidx.leanback` 1.0.0**, donc
en **version 1.0.0**. `ListAdapter.currentList` n'existe qu'à partir de
recyclerview **1.1.0** — d'où `CategorySidebarAdapter.positionOf()` qui
parcourt `getItem()`/`itemCount` à la place. Avant d'utiliser une API
RecyclerView "moderne" (`currentList`, `submitList(list, commitCallback)`,
`ConcatAdapter`...), vérifier qu'elle existe en 1.0.0 — sinon ça compile chez
personne. Monter la version de recyclerview est possible mais n'a pas été
fait : ça toucherait aussi leanback, non testé ici.

⚠️ **Focus D-pad posé sur la catégorie par défaut — Chaînes uniquement**
(30/08/2026, demande explicite : "quand ça va dans Général FR je voudrais que
le curseur soit focus dessus") — `LiveActivity.focusCategoryWhenReady()`.
**Trois asynchronismes se cumulent**, d'où une boucle de tentatives (max 20
frames) plutôt qu'un `requestFocus()` direct qui échouerait silencieusement :
(1) `CategorySidebarAdapter` est un `ListAdapter`, sa diff est **asynchrone** —
juste après `submitList`, `positionOf()` peut encore renvoyer -1 ; (2) le
`ViewHolder` de cette position n'existe pas tant que le RecyclerView n'a pas
fait sa passe de layout ; (3) une catégorie hors écran n'est jamais créée sans
`scrollToPositionWithOffset` préalable. Passé les 20 tentatives on abandonne
(focus système par défaut) — jamais de boucle infinie si la catégorie a
disparu entre-temps.

Le focus n'est posé **qu'une seule fois** (`initialCategoryFocusDone`) : tout
changement de catégorie ultérieur vient d'un clic utilisateur, qui a déjà le
focus au bon endroit — le lui reprendre serait pire que de ne rien faire.
**Films n'a volontairement pas ce comportement** (non demandé) : y ajouter le
même appel suffirait, `positionOf` est déjà partagé dans l'adapter.

## Audit perf "expert" : index SQL + invalidations RecyclerView

⚠️ **30/08/2026, demande "regarde bien si tout est super bien optimisé comme un
expert"** — quatre trouvailles réelles, toutes en dehors de ce qui avait été
corrigé jusque-là (qui portait sur *quoi* on charge, pas sur *comment*).

**1. AUCUN index SQL sur les colonnes filtrées** (le plus rentable). Les
entités n'avaient que leurs index d'unicité (`title`+`streamUrl`, `name`+
`streamUrl`, `title`). Or toutes les requêtes chaudes filtrent sur `category`,
`languageCode`, `nameLanguageCode`, ou trient sur `updatedAt` : chacune
**balayait les ~47 000 lignes**. Index ajoutés :
- `movies`/`series` : `(category, title, categoryOrder)`, `(languageCode)`,
  `(updatedAt)` ;
- `channels` : `(category, categoryOrder)`, `(nameLanguageCode)` ;
- `favorites` : `(itemType)` — la PK est `(itemId, itemType)`, or **toutes** les
  requêtes filtrent par `itemType` seul, qui n'en est pas la colonne de tête :
  l'index de la PK ne pouvait donc pas servir.

⚠️ **`categoryOrder` est placé en QUEUE de l'index composite**, alors qu'il
n'entre dans aucun filtre : c'est délibéré. `(category, title)` suffit au cas
le plus chaud (une catégorie triée par titre = l'écran par défaut, positionnement
direct + lignes déjà triées, aucun tri à faire), et l'ajout de `categoryOrder`
rend en prime la requête de la sidebar
(`GROUP BY category ORDER BY MIN(categoryOrder)`) **entièrement satisfaisable
depuis l'index**, sans lire une ligne de table — un index couvrant plutôt qu'un
4ᵉ index à maintenir à chaque insert. Chaque index coûte au CHARGEMENT de la
playlist (47 000 insertions) : ne pas en ajouter un de plus sans vérifier
qu'aucun index existant ne peut couvrir le besoin en changeant l'ordre de ses
colonnes.

⚠️ Room **version 10** → rechargement de playlist obligatoire.

**2. `notifyDataSetChanged()` à l'ajout d'une page.** `PosterAdapter`/
`ChannelGridAdapter` invalidaient TOUTE la liste à chaque page ajoutée par le
scroll infini — sur Android TV ça peut déplacer ou perdre le focus D-pad
précisément pendant qu'on défile. Remplacé par `notifyItemRangeInserted` quand
la nouvelle liste **commence exactement par l'ancienne** (comparaison
d'identité, l'ajout de page réutilise les mêmes instances). ⚠️ Ce n'est **pas**
un retour à `DiffUtil`/`ListAdapter` (retiré délibérément, cf. section dédiée) :
aucun diff n'est calculé, et tout autre changement retombe sur
`notifyDataSetChanged`.

**3. Re-soumission de la même liste.** Le rendu des 3 écrans est recalculé sur
deux sources (`movies` + `isReady`), donc la liste identique était soumise deux
fois → une invalidation complète pour rien. Garde `if (list === items) return`.

**4. `refreshFavoriteStates()` reconstruisait tout à chaque retour de fiche.**
Appelée dans `onResume`, elle recopiait l'intégralité de la liste affichée
(potentiellement des milliers d'entrées pour une catégorie chargée en entier)
même quand aucun favori n'avait bougé. Elle ne fait plus rien si aucun état ne
diffère réellement.

**Restes connus, assumés** : la recherche `LIKE '%…%'` ne peut profiter
d'aucun index (SQLite n'indexe pas un préfixe joker) — d'où le `LIMIT 200` ;
la pagination par `OFFSET` reste O(offset) sur un défilement très profond dans
"Toutes" (désormais adossée à un index) ; `getAvailableContentLanguages`
balaie noms + catégories, mais uniquement à l'ouverture du sélecteur de langue
dans Réglages.

## Règle générale : aucun écran ne charge le catalogue entier

⚠️ **Aboutissement de toute la séquence perf des 29-30/08/2026** (question de
l'utilisateur : "je veux que tout s'affiche le plus rapidement possible, est-ce
le cas ?"). L'audit a montré qu'il restait **un** écran à mapper l'intégralité
du catalogue : **Favoris** (`getFavoriteMoviesAndSeries`/`getFavoriteChannels`
passaient par `getMovies()`/`getSeries()`/`getChannels()` pour ne garder que
quelques favoris). Pire : tant que l'accueil préchauffait ces StateFlow, le
coût était payé d'avance et invisible — **le retrait du préchauffage aurait
donc déplacé la lenteur sur l'ouverture de Favoris**, une régression franche
introduite par le correctif précédent. Réécrits : on lit la table `favorites`
(petite) et on ne charge QUE les lignes qu'elle référence
(`moviesByIds`/`seriesByIds`/`channelsByIds`, découpés).

**Les 3 StateFlow "chauds" du catalogue complet ont alors été SUPPRIMÉS**
(`moviesFlow`/`seriesFlow`/`channelsFlow` + `getMovies()`/`getSeries()`/
`getChannels()`) : plus aucun consommateur. Leur histoire, parce que la
tentation de les recréer sera forte :
1. créés le 28/08 pour éviter de remapper le catalogue à chaque ouverture
   d'écran (`stateIn(Eagerly)`, préchauffés depuis l'accueil) ;
2. vidés de leur rôle par la pagination (29-30/08) ;
3. retirés un par un de leurs derniers appelants — historique de reprise, fond
   de l'accueil, puis Favoris — chacun réécrit pour ne lire que son nécessaire.

**Règle qui en découle, à appliquer à tout nouvel écran** : sur un panel de
plusieurs dizaines de milliers d'entrées, **aucun écran ne doit charger le
catalogue entier en mémoire — pas même "une seule fois, en cache"**. Un cache
chaud de 47 000 objets coûte son mapping (CPU + GC) et fait ramer tout le
reste, y compris les écrans qui ne s'en servent pas. Devant un besoin de "tout
le catalogue", se demander d'abord **quelle requête SQL bornée répond à la
question posée** (`LIMIT`, `WHERE ... IN (:ids)` découpé, `COUNT(*)`,
`GROUP BY`).

État après ce lot — ce que chaque écran charge réellement :
| Écran | Chargé |
|---|---|
| Accueil | compteur favoris (`COUNT`), historique de reprise (lignes référencées), 12 jaquettes pour le fond |
| Films / Séries / Chaînes | 1 page (60) sur "Toutes", la catégorie choisie sinon |
| Recherche (globale et interne) | résultats SQL `LIKE`, bornés à 200 |
| Favoris | uniquement les favoris |
| Reprise | uniquement les titres en cours |

⚠️ **Point restant assumé** : une catégorie précise est chargée en ENTIER
(demande explicite du 30/08 : compteur juste + scroll complet). Sur une
catégorie énorme, c'est plus lourd qu'une page — acceptable parce qu'une
catégorie reste très inférieure au catalogue, mais c'est le premier endroit à
regarder si une lenteur réapparaît sur un panel où une catégorie contiendrait
des dizaines de milliers de titres.

## Ordre des catégories = celui de la playlist + défilement rapide D-pad

⚠️ **30/08/2026, demande explicite** : "peut-être trier par id au lieu que par
ordre alphabétique ? sinon récupère les catégories dans la playlist
téléchargée" — et "si j'appuie 2 fois rapidement sur flèche du haut ou bas je
veux scroller plusieurs catégories d'un seul coup".

**1. L'ordre vient désormais de la SOURCE** (colonne `categoryOrder` sur
`MovieEntity`/`SeriesEntity`/`ChannelEntity`, remplie au chargement) :
- **Xtream** : index de la catégorie dans `get_vod_categories` /
  `get_live_categories` / `get_series_categories` — le panel les renvoie déjà
  dans son ordre. D'où le fait de garder les **listes** en plus des `Map`
  (`vodCatList`/`vodCatOrder`...) dans `loadXtream`.
- **M3U** : ordre de **première apparition** du `group-title` dans le fichier
  (`LinkedHashMap` + `getOrPut`, cf. `loadM3u/orderOf`).

Le tri se fait en SQL (`GROUP BY category ORDER BY MIN(categoryOrder), category`)
— `MIN` parce qu'une catégorie couvre plusieurs lignes, et `category` en second
critère pour départager un catalogue chargé AVANT cette version (tous les rangs
à 0 → retour au comportement alphabétique, pas de liste en désordre).

⚠️ **Ceci remplace DEUX tris précédents**, tous deux supprimés : le tri
alphabétique "France d'abord" (`isFrenchLabel`) sur les 3 sidebars, ET la liste
d'ordre codée en dur (`MOVIES_CATEGORY_ORDER`) qui n'aura vécu que quelques
heures le même jour. **Ne pas réintroduire de liste en dur** : elle
redeviendrait fausse au premier renommage côté panel. `isFrenchLabel` reste
utilisé par `LiveViewModel.frenchSortFor` (tri TNT), rien d'autre.

⚠️ **Room version 9** — nouvelle colonne, donc `fallbackToDestructiveMigration`
vide le catalogue : **rechargement de la playlist obligatoire** après cette
mise à jour. Inévitable ici : l'ordre n'existe QUE dans la source, il ne peut
être capté qu'au moment d'un chargement.

**2. Défilement accéléré de la sidebar** (`ui/common/CategoryFastScroll`,
branché sur les 3 écrans via `dispatchKeyEvent`) : deux appuis HAUT/BAS à moins
de 300 ms d'intervalle déclenchent une rafale — chaque appui suivant saute 5
lignes au lieu d'une. Dès qu'on ralentit ou qu'on change de direction, retour
au pas à pas (la navigation fine reste possible).
- ⚠️ **Pas** basé sur `KeyEvent.repeatCount` (touche maintenue) : la demande
  portait sur des appuis répétés, et beaucoup de télécommandes Android TV
  n'émettent jamais de répétition matérielle. D'où la mesure explicite du délai
  entre deux `ACTION_DOWN`.
- ⚠️ L'évènement doit être **consommé** (`return true` dans `dispatchKeyEvent`)
  quand un saut a lieu, sinon le focus bougerait deux fois (le saut + le
  déplacement normal d'une ligne).
- ⚠️ Comme pour `focusCategoryWhenReady`, la ligne visée peut être hors écran
  et donc n'avoir aucun `ViewHolder` : `scrollToPositionWithOffset` puis
  `requestFocus` au `post` suivant.
- En bout de liste, l'évènement n'est PAS consommé : sinon la télécommande
  paraîtrait bloquée en haut/bas.

## Ordre des catégories Films + fin du travail full-catalogue sur l'accueil

⚠️ **30/08/2026, demande explicite** : "pour les films ça charge encore tous
puis la catégorie, je veux charger la catégorie tout de suite ; et en première
catégorie c'est FR - LE DRENIER AJOUTEE puis FR - ACTION, FR - HORREUR, trouve
l'ordre qui correspond".

**1. Le libellé réel du panel contient une faute de frappe.** Relevé par dump
`uiautomator` de la sidebar sur le Shield (101 catégories films, dont 28
françaises) : le panel écrit **`FR - LE DRENIER AJOUTEE`** — "DRENIER", pas
"DERNIER", et "AJOUTEE". La première version de `MOVIES_PREFERRED_CATEGORIES`
cherchait "LE DERNIER AJOUTE" : elle ne matchait donc **jamais**, et
`pickDefaultCategory` retombait sur son repli (première catégorie de la
liste). **Ne jamais "corriger" l'orthographe de ces fragments** — ils doivent
coller au panel, pas au français. Les variantes correctes restent listées
derrière au cas où le fournisseur corrigerait.

**2. Ordre de la sidebar Films** (`MOVIES_CATEGORY_ORDER` +
`sortCategoriesByPreferredOrder`) : nouveautés → genres → collections/qualité
→ plateformes → sport. Une catégorie non listée n'est pas perdue, elle passe
après, avec l'ancien classement (françaises d'abord via `isFrenchLabel`, puis
alphabétique) — c'est le cas de toutes les catégories non francophones
(NETFLIX, NORDIC, PT/BR...). Les deux `LE DRENIER AJOUTEE` (simple et
`ᴰᴼᴸᴮʸ ᴬᵁᴰᴵᴼ`) matchent le même fragment et se départagent alphabétiquement,
donc la simple passe devant : voulu.

**3. "Ça charge encore tous" : c'était l'ACCUEIL, pas l'écran Films.** L'écran
Films ne chargeait déjà plus que sa page (pagination), mais deux traitements
full-catalogue tournaient encore en fond **depuis l'accueil**, saturant le CPU
du Shield avant même d'ouvrir Films :

- **`getUnifiedHistory()`** (bouton "Reprendre") combinait l'historique avec
  `getAllMovies()` **et** `getAllEpisodesFlow()` — soit un mapping des ~47 000
  films + tous les épisodes **à chaque émission**. Réécrit : seul l'historique
  (quelques lignes) reste un Flow, et on ne va chercher en base que les
  films/épisodes qu'il référence (`getMoviesByIds`/`getEpisodesByWatchKeys`,
  **découpés** via `SQLITE_MAX_VARIABLES`, cf. le crash "too many SQL
  variables" plus bas).
- **Le préchauffage** des StateFlow catalogue dans `MainActivity.observeData()`
  (`getMovies()/getSeries()/getChannels()` référencés pour payer d'avance leur
  mapping) : pertinent tant que les 3 écrans consommaient ces Flow, **inutile**
  depuis la pagination — ils ne les touchent plus du tout. Il ne faisait donc
  plus que brûler du CPU. **Retiré.** Favoris/Reprise/Recherche, seuls
  consommateurs restants, initialisent à leur ouverture. Ne pas le
  réintroduire sans vérifier qui consomme réellement ces Flow.
- **Le fond aléatoire de l'accueil** filtrait/triait lui aussi tout le
  catalogue en Kotlin → remplacé par `MovieDao/SeriesDao.getRecentWithArt`
  (tri + filtre + `LIMIT 12` en SQL).

Leçon : après un changement d'architecture (ici la pagination), **re-vérifier
qui consomme encore les anciens Flow** — un préchauffage devenu inutile ne
disparaît pas tout seul et se paie en CPU à chaque lancement.

## Catégories : plus de renommage + catégorie ouverte par défaut

⚠️ **30/08/2026, demande explicite** : "je ne veux plus de renommage des
catégories, laisse le FR ; et dans Films va directement dans FR - LE DERNIER
AJOUTE si il existe, sinon va dans un autre mais pas Toutes ; pareil pour les
chaînes, va dans Général FR".

**1. Le préfixe de langue reste affiché.** Les 3 DAO lisent/filtrent désormais
`category` (brut) au lieu de `categoryStripped` — sidebar comme filtre. Le
renommage automatique ("FR - Action" → "Action"), demandé le 28/08 puis annulé
ici, n'existe plus nulle part sur les catégories. `displayCategory()` a été
supprimé des 3 ViewModel ; le chemin recherche compare aussi `it.category`
brut.

⚠️ **Aucun changement de schéma** : les colonnes `categoryStripped` restent en
base (simplement plus lues pour les catégories) — Room reste en **version 8**,
donc **pas de rechargement de catalogue imposé** par cette modif. Ne pas les
supprimer juste pour "faire propre" : ça coûterait une migration destructive
de plus à l'utilisateur.

⚠️ **Le nom des CHAÎNES, lui, continue d'être nettoyé** ("FR: TF1" → "TF1",
via `nameStripped`/`ChannelEntity.toDomain`) — la demande ne visait que les
catégories, et le nettoyage des noms était lui-même une demande explicite du
28/08. Ne pas l'étendre aux noms sans redemander.

**2. Catégorie ouverte par défaut** (`util/DefaultCategory.kt`) :
`pickDefaultCategory(categories, preferred)` cherche, par ordre de préférence,
un libellé CONTENANT l'un des fragments voulus (comparaison tolérante :
`foldAccents` + majuscules, car les libellés varient d'un panel à l'autre et
gardent maintenant leur préfixe). À défaut → **première catégorie de la
liste** (donc française, cf. tri `isFrenchLabel`) — **jamais "Toutes"**, qui
reste sélectionnable à la main mais n'est plus l'état initial : c'est
précisément le cas le plus lourd (catalogue entier paginé) alors qu'une
catégorie se charge en entier et instantanément.

- Films → `MOVIES_PREFERRED_CATEGORIES` ("LE DERNIER AJOUTE", puis variantes
  "DERNIERS AJOUTS"/"NOUVEAUTE").
- Chaînes → `CHANNELS_PREFERRED_CATEGORIES` ("GENERAL FR", puis "FR GENERAL",
  puis un "GENERAL" quelconque).
- **Séries : inchangé, ouvre toujours sur "Toutes"** — non demandé. Reprendre
  le même patron si ça change.

⚠️ **`awaitingDefaultCategory`** (Movies/LiveViewModel) bloque le tout premier
chargement tant que la catégorie par défaut n'est pas connue. Sans ce
garde-fou, l'écran chargerait d'abord "Toutes" (le cas le plus coûteux) avant
de tout jeter pour recharger la catégorie — deux chargements, dont le pire
pour rien. **Ce drapeau doit impérativement finir à `false` dans tous les
chemins** : d'où le `try/catch` autour de la lecture des catégories (une
exception non rattrapée laisserait l'écran figé sur le spinner pour de bon).

⚠️ **`CategorySidebarAdapter.setSelectedSilently()`** (ajouté pour l'occasion)
met à jour la surbrillance SANS rappeler `onSelect`. Les Activity observent
`selectedCategory` et passent par là — utiliser le setter public `selected =`
ici relancerait `onSelect` → ViewModel → observateur, soit un rechargement en
double pour une valeur déjà appliquée.

## Pagination — extension à Séries/Chaînes + chargement complet par catégorie

⚠️ **30/08/2026, demande explicite** : "quand ça charge les films ça se limite
à 60 pour Toutes, mais pour chaque catégorie tu peux tout charger, et faire
pareil pour série et live". Deux changements, tous les deux appliqués aux 3
écrans :

1. **Pagination uniquement sur "Toutes"** — dès qu'une catégorie précise est
   sélectionnée, elle est chargée **en entier** d'un coup
   (`PlaylistRepository.NO_LIMIT` = `LIMIT -1`, comportement SQLite standard
   pour "aucune limite" ; `pageLimitFor(category)` dans les 3 ViewModel).
   Rationnel : une catégorie donnée est toujours bien plus petite que le
   catalogue complet, et l'utilisateur veut alors le compteur juste et le
   scroll complet immédiatement. `endReached` est donc mis à `true` d'emblée
   quand une catégorie est active — `loadNextPage()` devient un no-op, il n'y
   a plus rien à paginer.
2. **Séries et Chaînes reprennent le patron de Films** (colonnes précalculées
   + DAO paginé + scroll infini + `onResume` → `refreshFavoriteStates()`),
   ce que la première itération avait volontairement laissé de côté.

Room **version 8** (`fallbackToDestructiveMigration` — catalogue à recharger
une fois de plus après cette mise à jour).

**Colonnes précalculées ajoutées** (au chargement de la playlist uniquement,
jamais au runtime — helpers partagés `util.leadingLanguageCodeOrEmpty`/
`withoutLeadingLanguageCode`, avec l'invariant "code vide ⇒ version nettoyée
== version brute" sur lequel repose tout le filtrage SQL) :

- `SeriesEntity` : `languageCode`, `categoryStripped` (identique à
  `MovieEntity`).
- `ChannelEntity` : **deux paires**, parce que l'écran Chaînes filtre la
  langue sur le **NOM** de la chaîne ("FR: TF1") mais construit sa sidebar sur
  le préfixe de la **CATÉGORIE** ("FR| Sport") — deux conventions distinctes
  déjà traitées séparément avant la pagination, cf. `util.LanguageCode`. D'où
  `nameLanguageCode`/`nameStripped` + `categoryLanguageCode`/
  `categoryStripped`. Plus `tntRank` (cf. ci-dessous).

⚠️ **Le tri "ordre TNT" a dû passer en SQL** (`ChannelDao.getChannelsPage`,
`ORDER BY CASE WHEN :frenchSort = 1 THEN tntRank ELSE sortOrder END`) : un tri
appliqué **page par page** en Kotlin, comme avant, donnerait un ordre **global
incohérent** (chaque page triée dans son coin, TF1 pouvant apparaître en page
3 après des chaînes inconnues de la page 1). D'où la colonne `tntRank`
précalculée et `util.TntOrder.kt` (`TNT_ORDER` + `tntRankFor`, extraits de
`LiveViewModel`) : une seule source de vérité, partagée entre le chargement
(colonne) et le chemin **recherche**, qui reste trié en Kotlin puisqu'il n'est
pas paginé (résultat déjà borné à 200 lignes côté SQL).

⚠️ **Filtre "favoris uniquement" aussi passé en SQL** (même raison) —
sous-requête `id IN (SELECT itemId FROM favorites WHERE itemType = :favType)`,
pas de clé étrangère Room (cf. `FavoriteEntity`, on filtre toujours par type).
Conséquence à ne pas oublier : quand ce filtre est actif, retirer un favori
doit faire **disparaître** la tuile, pas seulement changer son étoile — d'où
`LiveViewModel.refreshFavoriteStates()` qui, dans ce cas précis, relance un
chargement complet au lieu d'une simple mise à jour d'état en mémoire.

**Code mort supprimé au passage** : `PlaylistRepository.isMoviesReady()`/
`isSeriesReady()`/`isChannelsReady()` (+ les `_xReady`/`onEach` associés,
correctif du 29/08 sur le "spinner honnête") — les 3 ViewModel ont désormais
leur propre `_isReady`, piloté par le chargement de leur première page. Les
Flow `moviesFlow`/`seriesFlow`/`channelsFlow` eux **restent** : ils ne servent
plus les 3 écrans catalogue, mais toujours Favoris, Reprise, Recherche globale
et le fond aléatoire de l'accueil.

## Pagination écran Films (MoviesViewModel)

⚠️ **Réécriture 29/08/2026, après plusieurs correctifs insuffisants seuls**
(dans l'ordre : filtre/catégories déportés en `Dispatchers.Default`, debounce
150ms limité à la recherche texte, fond aléatoire accueil déporté hors thread
principal, spinner honnête distinguant "pas encore chargé" de "vraiment
vide" — cf. sections dédiées ci-dessous pour chacun) — signalé par
l'utilisateur après le dernier de ces correctifs : "encore pire, film met du
temps encore à charger, on dirait que rien n'est en cache". Diagnostic par
instrumentation réelle (adb wireless sur le Shield de test, captures d'écran
en rafale + logcat) : tous les correctifs précédents avaient bien éliminé
les blocages du thread principal, mais le coût de fond restait entier —
mapper la TOTALITÉ du catalogue (jusqu'à ~136 000 films sur un gros panel
Xtream) en objets domaine prend plusieurs dizaines de secondes de CPU, même
en arrière-plan (GC libérant plus de 100 Mo par passe, heap montant à
~300 Mo). Le spinner honnête (correctif précédent) ne faisait que RÉVÉLER
cette attente réelle au lieu de la masquer derrière un faux "Aucun titre
trouvé" — d'où le ressenti "encore pire".

**Solution retenue (demande explicite : "refonte pagination", après avoir
présenté l'alternative plus sûre mais à gain limité)** : ne plus charger tout
le catalogue Films en mémoire avant affichage.

- `MovieEntity` : deux colonnes ajoutées, calculées **une seule fois** au
  chargement de la playlist (`MovieEntity.languageCodeFor`/
  `categoryStrippedFor`, mêmes `util.extractLeadingLanguageCode`/
  `stripLeadingLanguageCode` qu'avant, mais plus jamais rappelées à chaque
  écran) : `languageCode` (code détecté en tête de `category`, vide si
  aucun) et `categoryStripped` (`category` avec ce préfixe retiré si détecté,
  identique à `category` sinon). Peuplées dans `PlaylistRepository.loadM3u`/
  `loadXtream` aux 3 sites de construction de `MovieEntity`. Room version 7
  (`fallbackToDestructiveMigration`, catalogue à recharger une fois après la
  mise à jour — coût déjà accepté dans ce projet à chaque bump de schéma).
- `MovieDao.getMoviesPage(lang, category, limit, offset)` : `SELECT` filtré
  directement en SQL sur ces deux colonnes (`:lang IS NULL OR languageCode
  = '' OR languageCode = :lang`, idiome Room standard pour un paramètre
  optionnel — reproduit exactement l'ancien filtre Kotlin `applyLanguageFilter`)
  + `LIMIT`/`OFFSET`. `getDistinctCategoriesForLanguage(lang)` : sidebar
  catégories sans mapper le catalogue complet.
  ⚠️ Preuve que `categoryStripped` peut remplacer l'ancien `displayCategory()`
  sans changer le comportement : pour un item qui SURVIT au filtre langue
  (`languageCode == '' OU == contentLanguage`), l'ancien `displayCategory()`
  valait déjà `categoryStripped` dans les deux cas — quand `languageCode ==
  ''`, rien n'a été retiré donc `categoryStripped == category` par
  construction (l'une des deux branches de `displayCategory`) ; quand
  `languageCode == contentLanguage`, `displayCategory` retourne explicitement
  `categoryStripped`. Les deux branches convergent, donc filtrer/afficher sur
  `categoryStripped` post-filtre-langue est strictement équivalent.
- `PlaylistRepository.getMoviesPage()`/`getMoviesCategories()`/
  `getFavoriteMovieIds()` : même principe que `searchMoviesByTitle` déjà
  existant (favoris/historique joints seulement sur le sous-ensemble déjà
  réduit par SQL, jamais sur tout le catalogue). `MOVIES_PAGE_SIZE = 60`
  (constante `companion object`).
- `MoviesViewModel` : `movies` (`MediatorLiveData`, remplace `filteredMovies`)
  charge la première page à l'ouverture/changement de recherche ou catégorie
  (reset `pagingOffset = 0`), `loadNextPage()` ajoute la page suivante à la
  liste déjà affichée (déclenché par `MoviesActivity` au scroll, cf.
  plus bas). `categories` chargé une fois via `getMoviesCategories`, trié
  "France en premier" côté Kotlin (liste petite, coût négligeable).
  **Recherche texte non paginée** (delta volontaire) : `searchMoviesByTitle`
  reste borné à 200 résultats côté SQL (déjà rapide, cf. section suivante),
  pas besoin de pagination en plus — filtre langue/catégorie appliqué en
  Kotlin sur ce résultat déjà réduit, comme avant.
- `MoviesActivity` : `GridLayoutManager` + `RecyclerView.OnScrollListener.
  onScrolled` déclenche `viewModel.loadNextPage()` quand il reste moins de 2
  rangées visibles avant la fin du contenu déjà chargé (infini scroll
  classique). Rendu (spinner/vide/grille) recombine `movies` + `isReady` dans
  un `MediatorLiveData<Unit>`, même principe que le correctif précédent.
  ⚠️ **Favoris non réactifs pendant la pagination** (régression assumée,
  compensée) — l'ancienne version (`moviesFlow`, Flow réactif à la table
  `favorites`) répercutait un favori togglé depuis `DetailActivity`
  automatiquement sur la grille déjà affichée ; la pagination fait des
  requêtes ponctuelles (`suspend fun`, pas un `Flow`), donc plus de mise à
  jour automatique. Compensé par `MoviesActivity.onResume()` →
  `viewModel.refreshFavoriteStates()` (relit juste les ids favoris — table
  toujours petite, jamais 47 000 lignes — et met à jour les films déjà en
  mémoire) : le cœur du retour depuis la fiche détail.
- ~~**Portée volontairement limitée à Films**~~ — **étendu à Séries/Chaînes
  le 30/08/2026** sur demande explicite, cf. la section juste au-dessus
  ("Pagination — extension à Séries/Chaînes").
- `PlaylistRepository.getMovies()`/`moviesFlow` (catalogue complet, chaud)
  **reste utilisé tel quel** par Favoris/Reprise/Recherche globale/l'accueil
  (fond aléatoire) — ces écrans ont besoin du catalogue complet et ne sont
  pas la douleur signalée ; le coût de leur premier calcul (cf. section
  suivante) n'a pas disparu, seulement déplacé hors du chemin critique de
  l'écran Films.

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

⚠️ **Filtre/catégories sur main thread malgré le cache chaud (corrigé
29/08/2026, signalé par l'utilisateur : "même si j'y suis déjà allé, Films
est long à charger, pourtant tout est en cache")** — `moviesFlow`/`seriesFlow`/
`channelsFlow` sont bien chauds (cf. section suivante), mais les 3 ViewModel
(Movies/Series/Live) recalculaient `categories` (filter+map+distinct+sort) et
`filteredMovies`/`filteredSeries`/`filteredChannels` (filtre langue+catégorie,
+ tri TNT côté Live) **sur le thread principal** à chaque ouverture d'écran :
`viewModelScope.launch` utilise `Dispatchers.Main.immediate` par défaut, et
aucun de ces blocs n'avait de `withContext(Dispatchers.Default)` malgré un
commentaire l'affirmant. Sur ~136 000 films/47 000 chaînes, ce calcul CPU
synchrone sur Main donnait le freeze perçu comme "toujours long", alors
qu'aucune requête réseau/DB n'était en cause. Les 3 blocs sont maintenant
enveloppés dans `withContext(Dispatchers.Default)`, seule la réassignation de
`value` (obligatoire sur Main pour `LiveData.setValue`) reste hors de ce
bloc. Si un futur écran (re)filtre un gros catalogue dans un `addSource`/
`viewModelScope.launch`, vérifier qu'un `withContext(Dispatchers.Default)`
entoure bien le calcul — ne pas se fier à un commentaire l'affirmant sans
relire le code.

⚠️ **Debounce 150ms appliqué même hors recherche (corrigé 29/08/2026, signalé
"je sors de Films et je reviens, ça recharge tout")** — `filteredMovies`/
`filteredSeries`/`filteredChannels` faisaient `delay(150)` **avant toute
chose** dans `filter()`, y compris à l'appel initial (`addSource(allMovies) {
filter() }`, déclenché à chaque ouverture d'écran vu que chaque visite crée un
nouveau ViewModel) et à chaque changement de catégorie/favoris — pas
seulement à la frappe dans le champ recherche, seul cas où ce debounce a un
sens (éviter une requête SQL par caractère tapé). Conséquence : spinner plein
écran + liste vide pendant ~150ms à CHAQUE retour sur Films/Séries/Chaînes,
perçu comme un rechargement complet alors que le catalogue était déjà en
cache (`moviesFlow` chaud, cf. section dédiée) et le filtre déjà rapide
(`Dispatchers.Default`, cf. correctif précédent le même jour). Le `delay(150)`
n'est maintenant exécuté que si `searchQuery` n'est pas vide — ouverture
d'écran/changement de catégorie filtrent immédiatement.

⚠️ **Vrai coupable du freeze "ça recharge tout" (corrigé 29/08/2026, trouvé
par instrumentation réelle — adb wireless sur le Shield de test, `uiautomator
dump` pour localiser la tuile Films, `input tap`+`DPAD_CENTER` pour naviguer,
`screencap` en rafale pour dater l'apparition des données, `logcat` pour le
détail)** : ni le filtre des ViewModel (déjà corrigé plus tôt le même jour,
cf. sections précédentes) ni le cache Room n'étaient en cause. Le vrai
coupable était **`MainActivity.observeData()`** — les deux `lifecycleScope.
launch { app.database.movieDao().getAllMovies().map { ... } ... }` (fond
plein écran aléatoire, `maybeSetHomeBg`) : `lifecycleScope.launch` tourne sur
`Dispatchers.Main.immediate` par défaut, et un `Flow.map` s'exécute dans le
contexte du **collecteur** sans `flowOn` explicite — donc le filter+sort+map
sur la **totalité** du catalogue films (~47 000 sur le panel de test, jusqu'à
~136 000 constaté ailleurs) et séries tournait **entièrement sur le thread
principal**, à chaque création de `MainActivity` (donc à chaque retour à
l'accueil si l'activité a été détruite entre-temps — fréquent sur Android
TV/Fire TV, mémoire limitée). Logcat du Shield de test a confirmé sans
ambiguïté : `Choreographer: Skipped 179 frames! The application may be doing
too much work on its main thread`, `Davey! duration=3012ms` (une seule frame
a mis 3 secondes), et une rafale de GC pendant plusieurs secondes (heap
grimpant à 253 Mo, millions d'objets alloués/libérés) pendant la fenêtre
exacte où l'écran Films restait sur "Aucun titre trouvé". Le screencap en
rafale a montré le compteur de films bloqué à 0 pendant plus de 20 secondes
après un démarrage à froid — largement au-delà des "5 secondes" rapportées,
le pire cas dépendant de la vitesse du device/de l'état du GC à ce moment.
Fix : `.flowOn(Dispatchers.Default)` ajouté entre le `.map` et le `.collect`
sur les deux Flow (`movieDao().getAllMovies()`/`seriesDao().getAllSeries()`)
— seul le `.collect` (qui touche `binding.ivHomeBg`) reste sur Main, tout le
calcul lourd passe en arrière-plan. Aucun autre endroit du code ne consomme
directement `movieDao().getAllMovies()`/`seriesDao().getAllSeries()`/
`channelDao().getAllChannels()` en dehors de `PlaylistRepository`
(`moviesFlow`/`seriesFlow`/`channelsFlow`, déjà sur `appScope` =
`Dispatchers.Default`, donc déjà sûrs) — vérifié par grep avant de conclure
le correctif complet. **Si un futur écran ajoute un `Flow.map` sur une DAO
qui renvoie potentiellement des dizaines de milliers de lignes, toujours
vérifier qu'un `flowOn(Dispatchers.Default)` est présent avant le `collect`**
— l'absence ne casse rien visuellement en dev sur un petit catalogue de test,
elle ne se voit qu'à l'échelle d'un vrai panel Xtream volumineux.

⚠️ **"La première fois que je vais dans Films il ne charge pas" (corrigé
29/08/2026, distinct du correctif précédent sur l'accueil)** — confirmé
"ça finit par charger si j'attends" : ce n'était pas un blocage permanent
mais un affichage trompeur. `moviesFlow`/`seriesFlow`/`channelsFlow`
(`stateIn(appScope, SharingStarted.Eagerly, emptyList())`) fournissent
`emptyList()` comme valeur de départ **synchrone**, disponible immédiatement
pour tout nouveau collecteur, AVANT même que la vraie requête Room (des
dizaines de milliers de lignes) ait fini de s'exécuter en arrière-plan. Rien
ne distinguait "catalogue vraiment vide" de "pas encore chargé" : les 3
écrans (`MoviesActivity`/`SeriesActivity`/`LiveActivity`) cachaient le
spinner et affichaient "Aucun titre trouvé" dès cette première valeur
(vide), puis se repeuplaient d'un coup une fois la vraie valeur arrivée —
perçu comme "ça ne charge pas" plutôt que "ça charge encore, patiente".
Fix : `PlaylistRepository` expose maintenant `isMoviesReady()`/
`isSeriesReady()`/`isChannelsReady()` (`StateFlow<Boolean>`, mis à `true` via
un `.onEach{}` sur le `combine()` AVANT le `stateIn` — `onEach` s'exécute
pour chaque émission de l'amont, y compris la toute première, contrairement
à la valeur de départ de `stateIn` qui n'en fait jamais partie : signal
fiable de "la vraie requête a répondu au moins une fois", peu importe si le
résultat est vide ou non). Chaque ViewModel expose `isReady` (`asLiveData()`
dessus), et chaque Activity combine `isReady` + sa liste filtrée dans un
`MediatorLiveData<Unit>` qui recalcule l'affichage à chaque changement de
l'un ou l'autre — spinner tant que `!ready`, "Aucun titre trouvé" seulement
si `ready && liste.isEmpty()`. Une fois `true`, `isReady` ne redevient jamais
`false` (la requête ne "désapprend" pas avoir répondu), donc pas de flicker
après coup. Si un futur écran consomme directement `getMovies()`/
`getSeries()`/`getChannels()` sans passer par le ViewModel/Activity existant,
reprendre le même patron (`isXReady()` + spinner tant que non prêt) plutôt
que de se fier à `liste.isEmpty()` seul pour décider d'afficher "vide".

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
l'écran (avatar rond coloré par profil, tap = recharger, crayon = modifier,
croix = supprimer — cf. `ProfileAdapter`/`item_profile.xml`), puis 2 cartes
« Charger votre playlist » (URL M3U **ou** fichier local, un seul formulaire,
un seul bouton qui priorise le fichier choisi) et « Xtream Codes ». Wordmark
désormais dans la barre d'en-tête (cf. plus bas), plus mi-page.

⚠️ **Tap sur le profil déjà actif ne recharge plus rien** (29/08/2026,
demande explicite : "si je suis déjà actif... il se recharge et je ne veux
pas") — `loadProfile()` compare `profileId` à `profileAdapter.activeProfileId`
avant tout, et va direct à l'accueil (`goToMain()`) si égal : le catalogue
actif est déjà servi depuis le cache Room, un rechargement réseau complet
(potentiellement long sur un gros panel) n'apportait rien. Seul un profil
DIFFÉRENT déclenche un vrai rechargement — modifier le profil actif via le
crayon (`editProfile`) puis "Charger" le recharge quand même normalement
(passe par `AddPlaylistActivity`/`AddXtreamActivity`, pas ce chemin).

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

⚠️ **Titre "Profils" masqué tant qu'aucun profil n'existe** (29/08/2026,
demande explicite : "au premier démarrage... pas voir le texte Profils") —
`tv_screen_title` (id ajouté à cette occasion) suit exactement la même
condition que `section_profiles` (`profiles.isEmpty()`, même collecteur
dans `SetupActivity.setupProfilesList()`) : rien à "Profils" au sens propre
avant le tout premier enregistrement. La flèche retour, elle, garde sa
propre condition indépendante (`EXTRA_FORCE_SHOW`) — les deux peuvent donc
être masqués en même temps (premier démarrage) ou l'un sans l'autre.

⚠️ **En-tête passée en superposition + logo, dénouement du même jour**
(29/08/2026, demande explicite finale : "pareil que Xtream Codes... logo en
haut à droite... bandeau en haut" — après un aller-retour : le wordmark
mi-page avait d'abord été juste rendu visible, `layout_width="0dp"` sans
poids le rendant invisible depuis toujours, bug jamais remarqué avant "je
veux aussi mon logo sur la page des profils"). État final : la barre
d'en-tête (`btn_back` + `tv_screen_title`) n'est plus empilée dans le flux
de contenu — même principe de superposition que `activity_settings.xml`
(posée en dernier enfant du `FrameLayout` racine, donc dessinée par-dessus,
fond `@color/bg_nav` + `elevation`, contenu compensé par un `paddingTop`
fixe sur la `LinearLayout` de contenu). Logo `ic_nicotv_wordmark` ajouté
dans cette barre, après le titre (à droite) — le wordmark mi-page devenu
redondant a été **retiré entièrement** (un seul logo par écran, comme
Réglages/Ajouter une source). Un futur ajout de contenu à cet écran doit
revérifier le budget vertical (`~360dp` sans scroll, cf. plus haut) en
tenant compte de cette nouvelle structure, pas de l'ancienne empilée.

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

⚠️ **Logo dans la barre d'en-tête** (29/08/2026, demande explicite — 2 essais
le même jour : d'abord dans le contenu défilant/centré, puis déplacé ici sur
clarification "dans la zone en haut avec Xtream Codes") — `ic_nicotv_wordmark`
posé dans la `LinearLayout` d'en-tête elle-même (celle avec `btn_back` et le
titre), après le titre (`wrap_content`+`adjustViewBounds`, hauteur 20dp).
Ces 2 pages n'avaient jusqu'ici aucun logo. **Profils et l'accueil
(MainActivity) non touchés** par cette demande — cf. leur section respective
(Profils a son propre logo, ailleurs dans son layout, corrigé séparément).

⚠️ **Rond de chargement au milieu, pas en bas, avec pourcentage** (29/08/2026,
demande explicite : "au milieu pas en bas... indique chargement en cours",
puis "avec un pourcentage ça serait bien" le jour même) — le `ProgressBar`
`progress_loading` était un petit rond sans texte, tout en bas du formulaire
(après le bouton "Charger"/"Se connecter"), invisible sans scroller. Retiré
des 4 écrans concernés (`AddPlaylistActivity`/`AddXtreamActivity`/
`SetupActivity` — ce dernier avait le même souci pour le tap sur une
carte — puis `SettingsActivity.refreshCatalog()` le jour même, demande
explicite "quand j'actualise le catalogue je veux aussi un pourcentage" :
remplace le petit spinner discret de la barre du haut, tout aussi peu
visible), remplacé par `ui/common/LoadingDialog` (classe réutilisable —
construit et affiche un `AlertDialog` centré sur `dialog_loading.xml` :
`ProgressBar` déterminé + `%` + message d'étape, même style que
`UpdateManager.showUpdateProgress`). `setLoading()` sur les 4 écrans
construit/affiche `LoadingDialog` à `true`, `dismiss()` à `false`.

`PlaylistRepository.loadProfile(profileId, onProgress)` pousse les valeurs —
`onProgress: (percent, message) -> Unit`, no-op par défaut (les appelants
sans dialogue, ex. `refreshActiveProfileIfStale`, n'ont rien à changer).
**Best-effort, pas précis partout** : réel (`n/total`) pendant
l'enrichissement TMDb d'un M3U (`enrichMovies`, seule étape à la fois longue
et dénombrable, mappée sur la plage 20-90%) ; paliers fixes ailleurs
(connexion Xtream 10%, récupération chaînes 30%, films/séries 60%,
enregistrement 90-92% — ces appels réseau renvoient un bloc entier d'un
coup, pas de compteur naturel). `LoadingDialog.onProgress()` fait le
`runOnUiThread` lui-même — safe à appeler depuis le thread où tourne
`enrichMovies` (`Dispatchers.IO`/`Default`), pas besoin d'y penser côté
appelant.

⚠️ **Cercle + valeur lissée, pas un miroir direct des paliers** (corrigé le
jour même, demande explicite : "ne correspond pas à la réalité... 0 à 30 en
2 secondes et après 60 elle met 5 minutes... un cercle avec le pourcentage
dedans qui avance de pourcentage en pourcentage... en temps réel") —
`dialog_loading.xml` remplacé par `CircularProgressView` (`ui/common/`, arc
déterminé façon `RotatingBorderView` mais sans rotation) + `%` superposé au
centre. `LoadingDialog` ne reflète plus `onProgress()` directement : un
ticker interne (`Handler`, 150ms) fait (1) un **rattrapage visible** vers la
dernière valeur réelle (jamais un saut instantané, pas de vraie sous-étape
disponible), et (2) une **avance lente automatique** (~1%/2,5s, plafonnée à
dernière valeur réelle +20, jamais au-delà de 96%) si aucun nouveau palier
réel n'arrive depuis 1,5s — évite un chiffre figé pendant un palier long
(ex. "récupération des films et séries" sur un gros panel Xtream) sans
jamais prétendre être plus avancé que ce qui est su. Un vrai palier suivant
relance normalement le rattrapage. Cette avance automatique est un
compromis assumé : fluide un moment, puis honnête (plateau) si le palier
réel dure plus longtemps que le plafond ne le permet — pas une fausse
précision inventée de toutes pièces. Seul le chemin M3U (`enrichMovies`,
vrai compteur `n/total`) donne une progression réellement fidèle de bout en
bout ; le chemin Xtream reste par nature approximatif entre ses paliers.

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

⚠️ **Titre 16sp + logo à droite, alignés sur Ajouter une source** (corrigé
29/08/2026, demande explicite "pareil que quand j'ajoute un Xtream
Codes... même grosseur de police") — `settings_title` passé de `20sp` à
`16sp`. La flèche retour garde ses 48dp/24dp propres à cet écran, pas les
36dp/18dp d'`AddXtreamActivity` — pas demandé, `Live/Movies/Series/Detail`
partagent cette même taille 48dp, la réduire ici casserait CETTE
cohérence-là. `ic_nicotv_wordmark` ajouté après le titre
(`wrap_content`+`adjustViewBounds`, hauteur 20dp).

⚠️ **Hauteur de bandeau alignée sur Xtream Codes (64dp fixe)** (corrigé le
jour même, demande explicite : "la hauteur du bandeau... ne sont pas les
mêmes... la hauteur idéale est celle de Xtream") — Réglages/Profils/Add*
avaient chacun une hauteur `wrap_content` différente (padding + taille de
flèche retour propres à chaque écran : 88dp/54dp/64dp). Les 3 bandeaux sont
passés en `layout_height="64dp"` **fixe** (référence = celle d'Add-Xtream,
`gravity="center_vertical"` déjà présent sur les 3 centre le contenu — flèche
48dp sur Réglages, 36dp sur Profils/Add* — sans besoin d'ajuster le padding
vertical au cas par cas). Padding vertical retiré de ces 3 `LinearLayout`
d'en-tête (redondant avec la hauteur fixe + le centrage). Contenu compensé
par un `paddingTop` uniforme de **72dp** sur les 4 écrans concernés
(Réglages, Profils, `activity_add_playlist.xml`, `activity_add_xtream.xml`)
— 64dp de bandeau + 8dp de respiration. Si un futur écran de ce groupe
(gestion des profils/sources) ajoute encore un en-tête, reprendre ces
mêmes valeurs (64dp fixe, `paddingTop` 72dp) plutôt que d'en inventer une
nouvelle.

- **Cache images (Coil)** : config explicite dans `IptvApplication`
  (`ImageLoaderFactory`) — 300 Mo disque, 25% de la RAM en mémoire. Par défaut
  Coil n'a pas de limite fiable en usage réel sur un mur d'affiches
  film/série/chaîne d'un gros panel Xtream. Vidé via `ImageCacheUtil.clear()`
  ("Vider le cache images"). **Taille réelle affichée** (29/08/2026, demande
  explicite "voir aussi la taille") — avant, le sous-texte n'indiquait que le
  plafond configuré (300 Mo) en dur, jamais l'usage réel.
  `ImageCacheUtil.diskCacheSizeLabel()` lit `DiskCache.size` (Coil, octets
  déjà occupés — pas `maxSize`, le plafond), `SettingsActivity.
  updateImageCacheSizeLabel()` l'affiche dans `tv_image_cache_size`
  (`settings_clear_image_cache_sub_sized`, "%s utilisés sur 300 Mo max"),
  rafraîchi à l'ouverture, après un "Vider" et à chaque `onResume` (la
  navigation entre-temps a pu charger de nouvelles jaquettes).
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

  ⚠️ **Défaut = "FR" fixe, pas la langue de l'appareil** (revenu en arrière
  29/08/2026, demande explicite : "je veux que la langue par défaut soit fr
  même quand j'ai une nouvelle installation") — un essai plus tôt le même
  jour avait calé le défaut sur `Locale.getDefault().language.uppercase()`
  (langue système de l'appareil, "FR" seulement en repli si code inexploitable),
  changé sur nouvelle demande : `ContentLanguagePrefs.getLanguage()` renvoie
  maintenant **toujours** `FRENCH` ("FR") tant que l'utilisateur n'a jamais
  ouvert le dialogue — plus aucune dépendance à `Locale`/langue système. Piège
  corrigé au passage (toujours valable) : `setLanguage(null)` faisait avant un
  `remove()` de la clé — indiscernable de "jamais touché", donc un choix
  explicite de "Toutes" se refaisait écraser par le défaut FR à la relecture
  suivante. Stocke un sentinel `"__ALL__"` en dur pour un choix explicite,
  distinct de l'absence de clé. `SettingsActivity.languageLabel()`/le filtre
  lui-même restent génériques (n'importe quel code 2 lettres, pas juste "FR")
  — seul le DÉFAUT est figé sur FR, l'utilisateur peut toujours changer vers
  n'importe quel code découvert dans son catalogue via Réglages.

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
  collage **fourni par l'utilisateur**, remplacé une fois depuis (29/08/2026,
  toujours trouvé dans `iptv2/update/` — `chaines.jpg` cette fois, Canal+/TF1/
  OCS/M6/MTV/beIN, ratio ~1264×843 déjà ~3:2, reconverti en JPEG qualité 90)
  — pas une image générée ou choisie par Claude. **⚠️ Inclut de vraies
  marques déposées**, réserve sur le risque juridique déjà actée avec
  l'utilisateur pour ces images précises (cf. historique de la mosaïque de
  logos qui les a précédées). Un fichier déposé dans `iptv2/update/` avec un
  nom explicite (ex. `chaines.jpg`) est le canal établi par l'utilisateur
  pour remettre une image de remplacement à Claude — vérifier ce dossier en
  premier si une future demande "remplace la photo par celle dans update".
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
- ⚠️ **`appChangelog` oublié pendant ~15 versions** (29/08/2026, bug signalé
  par l'utilisateur : "le texte de la maj n'est pas le bon") — `appVersionCode`/
  `appVersionName` (`app/build.gradle.kts`) ont été bumpés à chaque commit
  d'une longue session sans jamais toucher `appChangelog` juste en dessous,
  qui alimente le champ `changelog` de `version.json` (tâche `publishUpdate`)
  affiché dans le modal "Mise à jour disponible". Résultat : le texte
  affiché décrivait un changement du 28/08 alors que l'app en était déjà à
  v1.0.50. **Systématiquement mettre à jour `appChangelog` avec le VRAI
  contenu de la release, dans le même commit que le bump de version** — pas
  après coup, pas "je le ferai au prochain bump".
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
