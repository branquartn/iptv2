# NicoTV — contexte projet

App Android (Kotlin, MVVM) de streaming : films, séries, lecteur Media3,
auth via backend PHP, et mise à jour OTA.
Cible mobile, Android TV et **Fire TV**.

## Branche de travail

Développer sur `claude/stable`. Commit + push à chaque
livraison. Ne jamais pousser sur une autre branche sans accord.

## Architecture

```
app/src/main/java/com/nicotv/iptv/
  AppConfig.kt          # toutes les constantes (URLs, TMDb, Update, Realtime)
  data/
    database/           # Room (dao/, entity/)
    network/            # Retrofit (model/) + RealtimeClient (WebSocket)
    repository/         # MediaRepository : sync Catalogue → Room
  domain/model/         # modèles UI
  player/               # PlayerActivity (Media3/ExoPlayer)
  ui/                   # login, main, movies, series, detail, favorites,
                        # resume, search, users, common (BaseActivity, PosterAdapter)
  update/UpdateManager  # OTA : lit version.json, télécharge + installe l'APK
  util/SessionManager   # session utilisateur (jeton, prefs)
```

Package applicatif : `com.nicotv.iptv` (renommé depuis `com.branquartn.iptv`).
Le module VPN WireGuard a été retiré de l'application.

- ViewModels `AndroidViewModel`, données exposées en `LiveData`.
- Films/Séries : `syncIfNeeded()` au démarrage (flag `syncStarted`),
  `refresh()` pour le swipe-to-refresh et le bouton refresh. `isRefreshing`
  pilote le spinner. **Pas** de refresh périodique automatique — mais un refresh
  **temps réel** sur évènement (bus WebSocket ci-dessous).
- La sync met à jour Room (ajout des nouveaux éléments, suppression des obsolètes, MAJ des URLs).
- **Badges sur les affiches** (`PosterAdapter` / `item_poster.xml`) : badge
  « NOUVEAU » si `MovieEntity.addedAt` < 7 jours (date d'ajout réelle,
  préservée même quand l'URL change), et barre de reprise en bas de
  l'affiche pour un film commencé non terminé (progression issue de
  `WatchHistoryEntity`, brassée dans `getMoviesWithFavorites`).
- **Temps réel (bus WebSocket `ws.nicotv.ovh`)** : `data/network/RealtimeClient.kt`
  (OkHttp WebSocket) se connecte avec le jeton HMAC de session (query `?token=`,
  client WS dédié **sans intercepteur de log** → ne pas fuiter le jeton). Sur
  `iptv:add` / `iptv:lib` / `state`, `IptvApplication` relance une synchro Catalogue→Room débouncée
  (qui inclut `syncRemoteState`) → les listes **et l'état synchronisé** se rafraîchissent via la
  LiveData de Room, sans action utilisateur.
  Connecté au premier plan (`ProcessLifecycleOwner` + `MainActivity.onStart` après
  login), coupé en arrière-plan et au logout. URL dans `AppConfig.Realtime.WS_URL`.
- **Ajout à la médiathèque** (`ui/search/SearchActivity` + `SearchViewModel`) : recherche
  TMDb multi (`TmdbApi.searchMulti`), bouton « + Ajouter » par résultat →
  `app.nicoTvApi.addMedia` (POST `AddMediaRequest`, queue de téléchargement côté serveur,
  même endpoint que la PWA). Existe **indépendamment** de la fiche détail (voir
  « Casting / acteur / similaires » ci-dessous, qui réutilise ce même appel).

## État synchronisé (action `state`)

Favoris, progression de lecture, films/épisodes **vus** et corrections TMDb séries sont
synchronisés par utilisateur via l'action `state` de l'API (table serveur `iptv_state`,
un enregistrement par *kind*). Côté `MediaRepository` : `pushFavoriteState` / `pushProgress` /
`pushSeenState` / `pushSeenEpisodes` / `pushTvIdsState` pour l'envoi (fusion avec l'état serveur,
jamais de perte), `syncRemoteState` pour la réception.

- **Vu films** : `seen.mkeys` (`m<tmdbId>`) — marque `MovieEntity.seen` → badge « ✓ Vu ».
- **Vu épisodes** : canal dédié **`epseen`** (liste de `fileKey` `"Série/Fichier.mkv"`, table
  `seen_episodes`). Poussé à la fin d'un épisode (`saveWatchPosition`), tiré dans
  `syncRemoteState` → badge « ✓ Vu » sur les autres appareils. **Distinct de `seen.episodes`**,
  que la PWA réserve à la détection « NOUVEAU » (elle l'écrase avec tout le catalogue) → canal
  séparé pour ne pas se faire écraser. La clé `fileKey` est identique à celle de la PWA
  (`nom_série/fichier`), donc cohérente entre tous les appareils.
- **Progression épisode** : clé `"e:" + fileKey` dans `progress` → barre + « ▶ Reprendre ».
- **Lecture auto épisode suivant** : à la fin (`STATE_ENDED`), `PlayerActivity` enchaîne
  l'épisode d'index +1 (`getNextEpisode`), d'où les extras `EXTRA_SERIES_ID` / `EXTRA_SERIES_TITLE`.
- Sync **temps réel** : l'event WebSocket `state` déclenche `syncRemoteState` (même chemin
  débouncé que le rafraîchissement du catalogue).

## Casting / réalisateur / films similaires / bande-annonce / acteur (v1.0.5.8)

Portage des fonctionnalités ajoutées côté PWA (`iptv/app.js`), fiche film uniquement
(`DetailActivity`/`DetailViewModel`, cf. `ui/detail/`) :

- **Endpoints TMDb** (`TmdbApi.kt`) : `movie/{id}/credits`, `movie/{id}/recommendations`,
  `movie/{id}/videos`, `person/{id}`, `person/{id}/combined_credits`. Modèles dans
  `TmdbModels.kt` (`TmdbCredits`, `TmdbCastMember`, `TmdbCrewMember`, `TmdbPerson`) ;
  la filmographie acteur réutilise `TmdbMultiResult` (mêmes champs qu'une recherche multi).
- **Casting** : rangée horizontale (`CastAdapter`, `item_cast_member.xml`) sous le synopsis,
  avatars ronds via Coil `CircleCropTransformation`. Clic → dialog acteur
  (`dialog_actor.xml`) : photo, bio, filmographie complète en grille horizontale.
- **Réalisateur** : `TmdbCredits.crew.firstOrNull { job == "Director" }`, ligne cliquable
  au-dessus du synopsis → ouvre le même dialog acteur.
- **Films similaires** (recommandations TMDb, films uniquement — l'endpoint ne renvoie pas
  de séries) et **filmographie acteur** (films + séries) : même carte réutilisable
  (`SimilarWorkAdapter`/`SimilarWork`, `item_similar_movie.xml`), badge rond ✓/+ en coin
  (`bg_badge_owned`/`bg_badge_add`) — ✓ si déjà dans la médiathèque de l'utilisateur
  (`MovieDao.getMovieByTmdbId` / `SeriesDao.getSeriesByTmdbId`), auquel cas le clic ouvre
  sa fiche (`DetailActivity`/`SeriesDetailActivity`) ; sinon **+** et le clic ajoute à la
  file de téléchargement via le même `nicoTvApi.addMedia` que `SearchActivity`
  (`DetailViewModel.resolveOrAdd` → `MediaRepository.findOwnedMovie`/`findOwnedSeries`).
- **Bande-annonce** : bouton dédié, appel `suspend` direct (pas de LiveData — un clic
  déclenche un fetch + une action, sans l'ambiguïté `null` = « pas chargé » vs « aucune
  bande-annonce trouvée » qu'une LiveData réutilisée introduirait). YouTube priorité
  française sinon toute langue (`MediaRepository.getMovieTrailerKey`), ouverture via
  l'app YouTube (`vnd.youtube:`) avec repli navigateur (`ActivityNotFoundException`).
- **`DetailViewModel.loadExtras`** ne recharge credits/recommandations qu'une fois par
  film (garde `extrasLoadedFor`) — pas de re-fetch à chaque `onResume()`.

## Présence temps réel multi-appareils (v1.0.11.x, admin.nicotv.ovh « Qui regarde quoi »)

Chaque appareil (APK et PWA) envoie un heartbeat à `api/iptv.php` (côté serveur), affiché
en direct dans le panel admin.nicotv.ovh — **une ligne par SESSION** (`uid` + `device_id`),
pas par compte : un même compte ouvert sur plusieurs appareils (mobile + Shield par ex.)
apparaît séparément sur chaque ligne.

- **`device_id`** : UUID persistant par appareil (`SessionManager.getOrCreateDeviceId()`,
  généré une fois, survit logout/re-login), envoyé en en-tête `X-Device-Id` sur chaque
  requête API (interceptor OkHttp, `IptvApplication.okHttpClient`).
- **Deux types de présence** (`kind`, calculé côté serveur) :
  - `watching` — lecture en cours (heartbeat du lecteur, `PlayerViewModel.sendHeartbeat`,
    ~20s + instantané sur pause/play via `onIsPlayingChanged`).
  - `online` — app ouverte mais pas de lecture (accueil, listes, fiche détail...),
    heartbeat périodique `IptvApplication.startAppHeartbeat()` (20s, silencieux tant que
    `PlayerActivity.current` n'est pas null) + instantané à chaque écran
    (`reportScreen()`, appelé depuis `onResume()` de chaque activité). Écran courant
    remonté en clair (« Accueil », « Films », « Fiche : *Titre* »...), lu depuis
    `util/PresenceScreen.label`.
- **Présence "arrêtée" à la sortie du lecteur** : `PlayerViewModel.sendPresenceStop()`
  enchaîne un heartbeat "en ligne" juste après (même coroutine, séquentiel) — sans ça,
  course avec `onResume()` de l'activité qui reprend la main (elle redémarre AVANT que
  `PlayerActivity.onStop()` ne s'exécute) : le heartbeat "en ligne" de l'activité pouvait
  arriver avant `presence_stop`, qui le supprimait juste après → rien affiché côté admin
  (incident 2026-08-01, notamment reproductible sur Shield/Android TV).

## Contrôle à distance (pause / reprendre / lancer un film / télécommande complète)

Trois surfaces de contrôle, même mécanisme WS sous le capot (topic `user:<uid>`, event
`remote`, `{cmd, deviceId, value}` — `ws_publish()` côté serveur) :
- **admin.nicotv.ovh** (panel web, tous comptes, admin-only) — `remote_pause`/
  `remote_resume`/`remote_play` prennent un `uid` explicite.
- **App mobile elle-même** (`MainActivity`, bandeau « en cours sur... » en bas d'écran) —
  mêmes actions mais scopées au `uid` du jeton (pas un paramètre client) : impossible de
  cibler un autre compte que le sien. `MediaRepository.otherDevicesPresence/
  remotePauseOther/remoteResumeOther`, poll 15s tant que l'accueil est au premier plan.
  Limité à play/pause, ne montre que la session `kind=watching`.
- **Télécommande complète** (v1.0.11.62, icône à côté du pseudo sur l'accueil, mobile +
  sw600dp — `btn_remote`/`btn_remote_ring`, visible dès qu'une autre session du même
  compte, watching ou juste online, est active) → `RemoteControlActivity` (D-pad +
  contrôles lecteur). Pendant PWA : icône équivalente dans `topbarHTML()` (`iptv/app.js`),
  panneau `openRemotePanel()`.
  - Nouvelle action serveur unique **`remote_cmd`** (au lieu d'une action par commande) :
    `device_id` + `cmd` (`seek`/`volume`/`mute`/`unmute`/`audio`/`subtitle`/`nav_up`/
    `nav_down`/`nav_left`/`nav_right`/`nav_select`/`nav_back`) + `value` optionnel
    (normalisé en nombre côté PHP si numérique — sinon JSON string côté client, ambiguïté
    de parsing selon la plateforme).
  - **Navigation menus (D-pad)** : relayée en **vrais KeyEvent** (`KEYCODE_DPAD_*`/
    `KEYCODE_BACK`) dispatchés à l'activité au premier plan (`IptvApplication.
    dispatchNavKey()`, activité trackée via `Application.ActivityLifecycleCallbacks` →
    `currentActivity`). Générique à tout écran (accueil, listes, menus du lecteur) car
    toute l'app est déjà navigable au D-pad (Leanback/TV) — **aucune logique de
    navigation dupliquée**. Côté PWA : `navMove()`/`navCandidates()` existants (nav TV
    au clavier) rejoués directement par `handleRemoteCommand()`.
  - **Contrôles lecteur** (visibles seulement si la cible est `kind=watching`) : seek
    ±10s (`PlayerActivity.remoteSeek`), volume logiciel + mute (`remoteSetVolume`/
    `remoteSetMute`, volume ExoPlayer/`vid.volume` — **jamais** le volume système/matériel),
    piste audio et sous-titres — ces deux derniers **ouvrent le sous-menu existant**
    (`remoteOpenAudioMenu`/`remoteOpenSubtitleMenu`, `binding.rowAudio/rowSubtitles.
    performClick()`) plutôt que de cycler en aveugle : la sélection précise se fait
    ensuite via les flèches du D-pad (même relais KeyEvent), pas de logique de piste
    dupliquée. Toujours posté sur `mainHandler` (thread UI) — même piège ExoPlayer que
    `remotePause`/`remoteResume`, cf. section suivante.
  - Muet/volume affiché dans le panneau/l'activité est une **mémoire locale du dernier
    ordre envoyé**, pas une vérité synchronisée : la cible ne renvoie pas son propre
    niveau de volume via la présence.

Réception : `RealtimeClient.onMessage()` → `IptvApplication.handleRemoteCommand()`, qui
ignore la commande si `deviceId` ne correspond pas à CET appareil (plusieurs sessions du
même compte partagent le topic `user:<uid>`), puis appelle
`PlayerActivity.current?.remotePause()/remoteResume()`.

**Piège thread (résolu, important si retouché)** : `RealtimeClient.onMessage()` est un
callback OkHttp WebSocket qui tourne sur un **thread de fond**, pas le thread UI. ExoPlayer
exige d'être piloté depuis son thread d'application (le thread principal ici) — un appel
direct à `player.pause()/play()` depuis ce callback échoue **silencieusement** (pas
d'exception visible, la commande "lancer un film" fonctionnait quand même car
`startActivity()` tolère n'importe quel thread, contrairement à un appel direct sur le
`Player`). `handleRemoteCommand()` poste donc sur le thread UI via
`Handler(Looper.getMainLooper())` avant d'appeler `remotePause()`/`remoteResume()`.

`remotePause()`/`remoteResume()` (dans `PlayerActivity`) n'ont **aucune garde sur
`Player.isPlaying`** (`player?.pause()`/`player?.play()` bruts) : `isPlaying` peut renvoyer
`false` pendant un simple rebuffer transitoire (`STATE_BUFFERING` avec `playWhenReady=true`)
même si la vidéo tourne visuellement — une garde y avait été ajoutée puis retirée après
un vrai incident (la commande ne faisait alors rien à ces moments-là).

## Picture-in-Picture (Android 8+)

`PlayerActivity` : `android:supportsPictureInPicture="true"` dans le manifest (déjà couvert
par `configChanges` pour ne pas être recréée à l'entrée en PiP).
- **Déclenchement double** : `onUserLeaveHint()` (auto au Home/changement d'appli — pas
  fiable à 100% selon navigation gestuelle/OEM, notamment Samsung) **et** un bouton dédié
  `btn_pip` dans la barre du lecteur (filet manuel, même logique via
  `enterPipIfPossible()`).
- Ratio d'aspect calculé depuis `player.videoSize`, borné aux limites Android (max 2.39:1)
  pour éviter une exception sur un ratio extrême, repli 16:9 si taille inconnue.
- En PiP : tous les contrôles/overlays custom masqués (`onPictureInPictureModeChanged`,
  ne garde que l'image), `useController = false` sur le `PlayerView`.

## Convention UI — RotatingBorderView (anneau blanc tournant au focus)

Remplace progressivement les anciens boutons texte/contours statiques. `app:circular`
(rond, boutons icône) ou rectangulaire à coins configurables (`app:cornerRadius`, ex.
lignes d'épisode). Toujours piloté en code (`startAnim()`/`stopAnim()` + visibilité sur
`setOnFocusChangeListener`), jamais automatique.

Déjà en place : bouton retour (`btn_back`/`btn_back_ring`, sur `DetailActivity`,
`MoviesActivity`, `SeriesActivity`, `SearchActivity`, `SeriesDetailActivity`,
`UsersActivity`), icônes topbar accueil (recherche/reprendre/favoris/téléchargements/
comptes/déconnexion, `activity_main.xml` **et** `layout-sw600dp/activity_main.xml`),
lignes d'épisode (`item_episode.xml` — racine passée de `LinearLayout` à `FrameLayout`
pour superposer l'anneau sans perturber le flux vertical ; `bg_episode_item.xml` ne garde
que le fond léger au focus, contour statique retiré).

**Pièges** : la pastille « N nouveautés » (`tv_new_badge`, pilule) n'a **pas** d'anneau
(forme incompatible avec un cercle) — zoom seul. `btn_resume`/`btn_resume_ring` absents
du layout `sw600dp` (tablette/TV) → binding nullable, gérer les deux layouts en `?.let`
plutôt qu'un force-unwrap.

## Onglet saison hors champ (SeriesDetailActivity)

L'épisode ciblé à l'ouverture (en cours / premier jamais vu / dernier) était bien scrollé
et focusé dans `rvEpisodes`, mais l'onglet de sa saison dans `rvSeasons` (au-dessus) ne
suivait jamais — sur une série à beaucoup de saisons, l'onglet actif pouvait rester hors
champ. `scrollToSeasonTab()` appelé à chaque changement de saison dans
`applySeasonEpisodes()` (auto au chargement **et** clic manuel sur un onglet).

## Piste audio FR par défaut + persistance par film (parité PWA)

`checkAudioFormat` (probe ffprobe) : préfère la piste FR si présente, **même quand elle
est en index 0** — un bug traitait "index 0" comme "= piste par défaut, rien à forcer",
faux sur des fichiers où la FR est justement en piste 0 en codec non copiable (ex. AC3
5.1) pendant que l'EN (copiable, AAC) est en piste 1 : le repli codec-compatible
sélectionnait alors l'anglais à chaque fois quel que soit l'index de la piste préférée.
Choix manuel (écrou du lecteur) désormais persisté **par film** en plus des séries
(`nicotv_maudio_<user>` côté PWA ; côté APK la préférence par langue reste native
ExoPlayer `setPreferredAudioLanguage("fr")`, pas concernée par ce bug spécifique aux
index PWA).

## Fix sync progression (v1.0.5.8) — MediaRepository.syncRemoteState

`syncRemoteState()` faisait un `watchHistoryDao.replaceAll()` **aveugle** : tout
l'historique local remplacé par l'état serveur reçu, à chaque synchro catalogue ou
évènement WS `state`. Or `pushProgress()` est enveloppé dans un `runCatching` **sans
retry** — si ce POST échouait silencieusement (coupure réseau, timeout), la position
restait correcte en Room mais jamais reçue par le serveur ; le `replaceAll` suivant
l'effaçait purement et simplement (le serveur ne la connaît pas). **Fusion par
`watchedAt`** ajoutée à la place (le plus récent gagne, `WatchHistoryDao.getAllHistorySnapshot`)
— même classe de bug (et même correctif) que celui identifié et corrigé côté PWA
(`iptv/app.js` → `loadState()`), mécanisme de déclenchement différent mais symptôme
identique : progression qui « revient en arrière » sans raison apparente après un
moment. Si ce symptôme est de nouveau signalé, vérifier que cette fusion n'a pas
régressé avant de chercher ailleurs.

## Mise à jour OTA

- `UpdateManager.checkForUpdate()` lit `AppConfig.Update.VERSION_URL`
  (`https://update.nicotv.ovh/version.json`) et propose la MAJ si
  `remote.versionCode > BuildConfig.VERSION_CODE`.
- Déclenché dans `MainActivity.onStart()` (après login), throttlé à 2 min.
- `version.json` : `{ versionCode, versionName, apkUrl, changelog }`.

## Process de release (à chaque livraison)

**Répartition fixe (consigne utilisateur, ne pas dévier sans qu'il le redemande) :**
Claude bumpe la version, commit et push — **jamais aucun build, même `assembleDebug`**
(consigne renforcée : plus de compilation du tout côté Claude, ni debug ni release, ni
copie dans `server/update/`). L'utilisateur s'occupe TOUJOURS lui-même du build (debug
inclus, pour tester) et du déploiement (étapes 2 à 5, 7 ci-dessous).

1. Bumper `versionCode` (+1) et `versionName` dans `app/build.gradle.kts`. *(Claude)*
2. `./gradlew assembleRelease` (signé via `nicotv-release.jks`). *(utilisateur)*
3. Copier l'APK dans `server/update/iptv-<versionName>.apk`. *(utilisateur)*
4. Supprimer les APKs anciens de `server/update/` **et** du live servi
   `/var/www/html/update/` pour n'en garder que les **2 derniers** de chaque
   (tri par date de build). *(utilisateur, ou automatique via `apk-builder.sh`)*
5. Mettre à jour `server/update/version.json` (code, name, apkUrl, changelog). *(utilisateur)*
6. Commit + push sur la branche de travail. *(Claude, pour ses propres changements de code —
   pas pour l'APK/version.json du point 3-5)*
7. L'APK + version.json doivent ensuite être déployés sur le serveur
   live `update.nicotv.ovh` (hors dépôt). *(utilisateur)*

## Plateformes & pièges connus

- **Vignette Fire TV (app sideloadée)** : le lanceur Fire TV affiche
  **`android:icon`**, pas `android:banner` (les vignettes 16:9 des apps du
  store viennent des serveurs Amazon). Fire OS a en plus un **bug connu
  avec les icônes en `mipmap`** → tout est en `drawable` :
  `android:icon="@drawable/ic_launcher"`, icône carrée PNG dans
  `drawable-{dens}/`, bannière 16:9 nommée `ic_launcher.png` dans
  `drawable-television-{dens}/` **et** `drawable-sw540dp-{dens}/`
  (Fire TV 1080p = 960×540dp/xhdpi ; le sw540dp couvre le cas où Fire OS
  ne déclare pas le uiMode télévision). **Aucun bucket `mipmap-*`.**
- **`android:banner="@drawable/banner"`** (PNG 16:9 par densité dans
  `drawable-{dens}/`) reste nécessaire pour **Android TV** (Shield), sur
  `<application>` **et** sur l'activité `LEANBACK_LAUNCHER` (LoginActivity).
- Pas de vector drawables pour bannière/icône (Fire TV ne les rend pas).
- Bannière (`tv_banner`) et icône (`ic_launcher`) : **fond blanc + wordmark
  NICOTV** centré, sans transparence (rendu propre sur Nvidia Shield et
  Fire TV).
- Logo : wordmark NICOTV style Netflix (rouge, arc parabolique profondeur
  0.134). Wordmark dans le header et le login
  (`@drawable/ic_nicotv_wordmark`, PNG unique haute résolution dans
  `drawable/`).

## Conventions

- Code et commentaires en **français**.
- Layouts : `layout/` (base, en paysage) et `layout-sw600dp/` (uniquement
  `activity_main` avec les grandes cartes TV) — penser à répliquer les
  changements de header de l'accueil dans les deux.
- Ressources image : `drawable/` (XMLs + `ic_nicotv_wordmark.png`) et les
  `drawable-{dens}` par densité (`ic_launcher.png` carré + `banner.png`
  16:9), plus `drawable-television-{dens}` / `drawable-sw540dp-{dens}`
  (bannière 16:9 en `ic_launcher.png` pour la vignette Fire TV).
  **Pas de buckets `mipmap-*`** (bug lanceur Fire OS).
- Toutes les activités sont en `screenOrientation="landscape"`.

## Secrets (NE JAMAIS logger, afficher, ni committer en clair)

La clé API TMDb et le mot de passe du keystore release sont
sensibles. Ils existent dans le code/config mais ne doivent jamais être
reproduits dans les réponses, commits, logs ou commentaires.
