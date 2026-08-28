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
    PlaylistSourcePrefs.kt   # id du profil actif (SharedPreferences) — rien d'autre
    m3u/M3uParser.kt         # #EXTINF/URL → M3uEntry, classification live/VOD/série
    xtream/XtreamClient.kt   # player_api.php (login, catégories, live/VOD/séries)
    xtream/XtreamModels.kt   # modèles Xtream — parsing JSON à la main (org.json),
                              # jamais de désérialisation Gson stricte (panels incohérents)
    tmdb/TmdbClient.kt       # recherche par titre (jaquettes) + credits/recommandations/
                              # bande-annonce/fiche acteur — org.json, best-effort
    database/                # Room : profils + cache du catalogue chargé (dao/, entity/)
    repository/PlaylistRepository.kt  # CRUD profils, charge la source → Room, expose
                                       # films/séries/chaînes joints favoris+reprise
  domain/model/            # Movie (films+séries+épisodes unifiés), Series, Channel,
                           # SimilarWork/OpenTarget (films similaires, filmographie)
  player/                  # PlayerActivity (Media3/ExoPlayer)
  ui/
    setup/SetupActivity      # lanceur : profils enregistrés + 2 cartes (pas de login)
    main/MainActivity        # accueil : 3 tuiles Chaînes/Films/Séries
    live/                    # chaînes : liste + catégories + favoris
    movies/ series/ detail/  # films/séries : mur d'affiches, fiche, épisodes
    favorites/ resume/ search/
    common/                  # BaseActivity, PosterAdapter, RotatingBorderView...
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
- **Filtre « FR »** (écran Chaînes, `LiveViewModel.isFrench`) : bouton bascule à
  côté du filtre favoris. Ni Xtream ni M3U n'exposent de champ pays exploitable
  et les catégories des panels réels ne suivent aucune norme (`AFR| AFRICA VIP
  HD/4K`, `4K| 24/7 UHD 3840P`…) → heuristique sur nom + catégorie : token exact
  `FR` (délimité, sinon `AFR`/`OFFER` matcheraient) ou sous-chaîne
  `FRANCE`/`FRENCH`.

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

`MoviesActivity`/`SeriesActivity.computeSpanCount()` déduit maintenant 210dp
(largeur sidebar + séparateur + paddings) de `screenWidthDp` avant de diviser
par la largeur d'affiche — sans ça le nombre de colonnes était calculé sur la
largeur totale de l'écran alors que le mur d'affiches dispose de moins
d'espace depuis l'ajout de la sidebar.

⚠️ **Catégories France en premier** (demande explicite, même jour) :
`util.isFrenchLabel()` (extrait de l'ancien `LiveViewModel.isFrench`, gardé
pour le filtre "FR" existant qui regarde nom+catégorie) trie les 3 listes de
catégories — `sortedWith(compareByDescending { isFrenchLabel(it) }.thenBy { it })`.
Ne regarde QUE le libellé de catégorie (pas le nom des chaînes/titres), plus
restrictif que le filtre FR de l'écran Chaînes : une catégorie au nom neutre
contenant des chaînes françaises ne remonte pas en tête, seul le nom de la
catégorie compte ici.

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
logo+nom+mini-guide EPG, pas de bouton favori dédié (place limitée) :
**tap = lecture, appui long = ajouter/retirer des favoris**. L'ancien
`ChannelAdapter` (liste, avec bouton favori explicite) est **gardé tel quel**
pour `SearchActivity` uniquement, où chaînes/films/séries se mélangent dans
un écran de résultats compact — pas de mosaïque là, une liste reste plus
lisible mélangée à un mur d'affiches. Les deux adapters dupliquent la même
logique EPG (fetch à la demande au bind, job annulé au recyclage) — accepté,
pas mutualisé pour éviter une abstraction commune forcée entre deux layouts
très différents.

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

## Tri "ordre TNT" des chaînes françaises (LiveViewModel)

Demande explicite 28/08/2026 : quand le contexte est français (catégorie
sélectionnée reconnue par `isFrenchLabel`, ou filtre FR actif), les chaînes
sont triées par `tntRank()` (TF1, France 2, France 3, Canal+, France 5, M6,
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
- ⚠️ **Aucun appel TMDb côté Xtream** (depuis 1.0.10) : le panel fournit déjà
  `stream_icon`/`plot`/`rating` (VOD) et `cover`/`plot`/`rating`/`genre`
  (séries) pour la quasi-totalité de son catalogue. Sur un panel réel de
  ~136 000 films, enrichir ne serait-ce que les entrées sans jaquette
  représentait des milliers de requêtes : chargement interminable (perçu comme
  un figeage) puis téléphone saturé en navigation. Ne pas réintroduire
  d'enrichissement TMDb dans `loadXtream`.
- **`TmdbClient.cleanTitle()`** nettoie les noms scene-release réels
  (`Movie.Title.2020.FRENCH.1080p.BluRay.x264-GROUP`) — séparateurs `._+`,
  année, tags qualité/langue, suffixe `-GROUPE` en fin de chaîne uniquement
  (sinon « Spider-Man » serait tronqué). Sans ça la recherche ne trouve rien.
- **Fiche film** (`DetailActivity`) : casting, réalisateur, films similaires,
  bande-annonce, fiche acteur/filmographie — mêmes layouts que NicoTV.
  Différence forcée par l'absence de backend : un titre similaire déjà présent
  dans le catalogue chargé s'ouvre (badge ✓, résolution **par titre** —
  `MovieDao/SeriesDao.findByTitle`, nos entrées n'ont pas d'id TMDb propre) ;
  sinon (+) affiche juste « pas dans votre playlist », pas de file d'ajout.

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

Les 2 formulaires s'ouvrent dans un **`AlertDialog` centré**
(`dialog_form_playlist.xml` / `dialog_form_xtream.xml`) et non plus en dessous
des cartes. Chacun est enveloppé dans un `ScrollView` : l'écran est en
`sensorLandscape` (~360 dp de haut), le clavier ouvert sur le dernier champ
faisait sortir le bouton « Charger »/« Se connecter » de la zone visible.

⚠️ **`installSplashScreen()` obligatoire** : `SetupActivity` déclare
`android:theme="@style/Theme.IPTV.Splash"` dans le manifeste (parent
`Theme.SplashScreen`, chrome clair). Sans l'appel `installSplashScreen()` **avant
`super.onCreate()`**, le thème ne bascule jamais vers `postSplashScreenTheme`
(`Theme.IPTV`, sombre) : l'activité reste sur le thème splash toute sa vie, d'où
un bandeau clair permanent affichant le nom de l'app en haut de l'écran. Piège
vécu — un premier correctif posant `windowNoTitle` sur `Theme.IPTV` visait le
mauvais thème et n'avait donc aucun effet.

## Réglages (SettingsActivity) — cache images / playlist / EPG

Écran ouvert depuis l'engrenage de l'accueil (`btn_settings` — **a changé de
sens** : ouvrait `SetupActivity` directement jusqu'ici, ouvre maintenant
`SettingsActivity`, qui elle-même propose "Changer de source" vers
`SetupActivity(EXTRA_FORCE_SHOW)`). Ne pas revenir à l'ancien raccourci direct.

- **Mini-guide EPG "en cours / à suivre"** (`PlaylistRepository.getShortEpg`,
  affiché sous chaque chaîne dans `LiveActivity`/`ChannelAdapter`) :
  **Xtream Codes uniquement** (`get_short_epg`, `ChannelEntity.xtreamStreamId`)
  — un M3U n'expose aucune source EPG exploitable (pas d'URL XMLTV gérée),
  `xtreamStreamId` y reste vide et la chaîne n'essaie même pas l'appel.
  Résultat mis en cache (`epg_cache`, TTL 30 min) — vidé automatiquement à
  chaque rechargement de catalogue (les id de chaîne sont réattribués) et
  depuis Réglages ("Vider le cache EPG"). `title`/`description` du panel sont
  en base64 (norme XMLTV) : `XtreamClient.decodeMaybeBase64` décode, tolérant
  si un panel non conforme renvoie déjà du clair. Appelé à la demande au bind
  de chaque ligne visible (`ChannelAdapter`, job annulé si la vue est recyclée
  avant la réponse) — jamais au chargement du catalogue entier.
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

## Accueil (MainActivity) — jaquettes en rotation + fond aléatoire

Porté depuis NicoTV (28/08/2026, demande explicite « ressemble plus à mon
apli NicoTV ») : `iv_films_bg`/`iv_series_bg` affichent une jaquette/backdrop
du catalogue chargé, tirée dans les 12 titres les plus récents
(`updatedAt`), et tournent toutes les 45s (`loadRotatingHubImage`, offset
décalé entre films/séries pour ne pas changer en même temps). `iv_home_bg`
(plein écran, alpha 0.26) tire un fond au hasard parmi films+séries, **stable
pour tout le process** (`MainActivity.cachedHomeBgUrl`, companion) — remis à
`null` par `SetupActivity.loadProfile()` quand un nouveau catalogue est
chargé (`resetHomeBg()`), sinon l'ancien fond resterait affiché après un
changement de source.

⚠️ **Carte Chaînes (`card_live`/`iv_live_bg`) — revirement le jour même** :
d'abord volontairement laissée sans image ("pas de jaquette pertinente pour
du live", demande explicite du matin), **puis redemandée l'après-midi même**
("je veux quand même une image"). A maintenant sa propre rotation
(`HUB_LIVE_OFFSET`), mais sur des **logos de chaîne** (pas des posters) :
`iv_live_bg` est en `scaleType="fitCenter"` + `padding` (pas `centerCrop`
comme Films/Séries) — un logo est souvent transparent et pas prévu pour être
rogné plein cadre, contrairement à un backdrop TMDb/Xtream. Pas de champ
`updatedAt` sur `ChannelEntity` (contrairement à Movie/SeriesEntity) : la
liste de rotation est un simple échantillon de logos non vides, pas un tri
par ajout récent. **Ne pas re-proposer de retirer cette image sans redemander
à l'utilisateur** — c'est un choix qui a déjà changé une fois dans la
journée.

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
