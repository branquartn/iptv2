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
  AppConfig.kt            # une seule constante : Update.VERSION_URL
  data/
    PlaylistSourcePrefs.kt   # source active (SharedPreferences) : type + params
    m3u/M3uParser.kt         # #EXTINF/URL → M3uEntry, classification live/VOD/série
    xtream/XtreamClient.kt   # player_api.php (login, catégories, live/VOD/séries)
    xtream/XtreamModels.kt   # modèles Xtream — parsing JSON à la main (org.json),
                              # jamais de désérialisation Gson stricte (panels incohérents)
    database/                # Room : cache du catalogue chargé (dao/, entity/)
    repository/PlaylistRepository.kt  # charge la source → Room, expose
                                       # films/séries/chaînes joints favoris+reprise
  domain/model/            # Movie (films+séries+épisodes unifiés), Series, Channel
  player/                  # PlayerActivity (Media3/ExoPlayer)
  ui/
    setup/SetupActivity      # lanceur : choix de la source (pas de login)
    main/MainActivity        # accueil : 3 tuiles Chaînes/Films/Séries
    live/                    # chaînes : liste + catégories + favoris
    movies/ series/ detail/  # films/séries : mur d'affiches, fiche, épisodes
    favorites/ resume/ search/
    common/                  # BaseActivity, PosterAdapter, RotatingBorderView...
  update/UpdateManager      # OTA : lit version.json, télécharge + installe l'APK
```

## Modèle de données — pas de compte

Une seule source active à la fois (`PlaylistSourcePrefs`) : `M3U_URL`,
`M3U_FILE` (URI SAF, permission persistante) ou `XTREAM` (host/user/pass).
Charger une nouvelle source **remplace tout le catalogue Room** (chaînes,
films, séries — `PlaylistRepository.loadFromCurrentSource()`), ré-attribuant
de nouveaux id autoIncrement : favoris/reprise d'un ancien catalogue ne sont
pas rattachés automatiquement après un rechargement. Comportement hérité
(même limite existait déjà côté NicoTV pour les resynchronisations) — acceptable
pour v1, la source ne change pas souvent.

- **M3U** : classification live/VOD/série **heuristique** (`M3uParser.classify`)
  sur `group-title` (mots-clés vod/film/movie, série/series/show) et sur un
  motif `SxxEyy`/`1x02` dans le nom. Un fournisseur qui s'écarte des
  conventions usuelles peut être mal classé — pas de solution générale, IPTV
  M3U ne type pas ses entrées.
- **Xtream Codes** : catalogue léger (catégories + streams/séries) chargé au
  `loadFromCurrentSource()`, mais **épisodes chargés à la demande**
  (`PlaylistRepository.loadEpisodesForSeries`, appel `get_series_info` à
  l'ouverture de `SeriesDetailActivity`) — un catalogue de plusieurs milliers
  de séries rendrait un chargement upfront bien trop long.
- **Favoris** : table unique `FavoriteEntity(itemId, itemType)`, `itemType` ∈
  MOVIE/SERIES/CHANNEL — pas de FK, juste un filtre par type.
- **Reprise de lecture** : `WatchHistoryEntity`, clé `"m<id>"` (film) ou
  `"e:<fileKey>"` (épisode). Pas de notion de « vu » permanente séparée
  (simplifié vs NicoTV) : une entrée disparaît de l'historique dès que la
  lecture est considérée terminée (`PlayerActivity` proche de la fin, ou
  position < 5s) — cf. `PlaylistRepository.saveWatchPosition`.
- **Recherche** : locale uniquement (`PlaylistRepository.searchTitle`), pas de
  TMDb/backend — cherche dans ce que la playlist contient déjà, par titre.

## Réseau

`network_security_config.xml` autorise le **cleartext HTTP globalement**
(contrairement à NicoTV, hôtes fixes tout HTTPS) : la grande majorité des
panels Xtream/M3U tiers tournent en HTTP simple, pas de certificat. C'est
voulu, ne pas restreindre sans en avoir reparlé avec l'utilisateur.

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

## Lecteur (PlayerActivity)

Repris quasiment tel quel de NicoTV (ExoPlayer, pistes audio/sous-titres,
vitesse, PiP, prompt épisode suivant, filet anti-blocage fin de flux) —
**retiré** : télécommande à distance (WebSocket, comptes multi-appareils),
heartbeat de présence, téléchargements hors-ligne (aucun sens sans compte
serveur). `historyKey` calculé depuis `seriesId`/`fileKeyExtra` (voir
`PlayerActivity.historyKey`) plutôt que transmis en extra séparé.

## Mise à jour OTA

- `UpdateManager.checkForUpdate()` lit `AppConfig.Update.VERSION_URL`
  (`https://iptv2.nicotv.ovh/version.json`), propose la MAJ si
  `remote.versionCode > BuildConfig.VERSION_CODE`.
- Déclenché dans `SetupActivity.onCreate()` (avant tout chargement) et
  `MainActivity.onStart()`, throttlé à 2 min.
- Process de release détaillé : [`server/README.md`](server/README.md).
  **Consigne fixe** : Claude bumpe/commit/push, ne compile **jamais** — le
  build (debug inclus) et le déploiement restent toujours à l'utilisateur.

## Branding — à finaliser

Icône (`ic_launcher`), bannière (`banner`) et wordmark (`ic_nicotv_wordmark.png`)
sont encore les PNG **NicoTV** hérités de la copie initiale (rouge, texte
« NICOTV ») — assets raster, pas modifiables en édition de texte. `app_name`
a été renommé en "IPTV2" mais l'identité visuelle reste à refaire si l'app
doit se démarquer visuellement de NicoTV.

## Secrets (NE JAMAIS logger, afficher, ni committer en clair)

Le mot de passe du keystore release (`iptv2StorePassword`/`iptv2KeyPassword`
dans `local.properties`, jamais committé) est sensible.
