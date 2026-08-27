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
  de séries rendrait un chargement upfront bien trop long.
- **Favoris** : table unique `FavoriteEntity(itemId, itemType)`, `itemType` ∈
  MOVIE/SERIES/CHANNEL — pas de FK, juste un filtre par type.
- **Reprise de lecture** : `WatchHistoryEntity`, clé `"m<id>"` (film) ou
  `"e:<fileKey>"` (épisode). Pas de notion de « vu » permanente séparée
  (simplifié vs NicoTV) : une entrée disparaît de l'historique dès que la
  lecture est considérée terminée (`PlayerActivity` proche de la fin, ou
  position < 5s) — cf. `PlaylistRepository.saveWatchPosition`.
- **Recherche** : locale uniquement (`PlaylistRepository.searchTitle`) — cherche
  dans ce que la playlist contient déjà, par titre.

## TMDb — jaquettes et fiche film

Aucun backend : l'app interroge TMDb directement (clé dans `AppConfig.Tmdb`).

- **Au chargement de la playlist** (`enrichMovies`) : recherche par titre pour
  **chaque** film, en parallèle borné à 6 requêtes simultanées (`tmdbSemaphore`).
  Jaquette/synopsis/note/année TMDb **prioritaires** sur celles de la source —
  un `tvg-logo`/`stream_icon` de M3U public est souvent un lien mort ou une
  icône générique. N'enrichir *que* les entrées sans jaquette (version
  précédente) laissait donc des affiches cassées. `tmdbId` est stocké
  (`MovieEntity.tmdbId`) pour éviter une seconde recherche à l'ouverture de la
  fiche. Coût assumé : une recherche par film au chargement.
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

Affiché **toujours** au lancement, jamais court-circuité — c'est la demande
explicite (comportement IPTV Smarters Pro) : profils enregistrés listés en haut
(tap = recharger, croix = supprimer), puis 2 cartes « Charger votre playlist »
(URL M3U **ou** fichier local, un seul formulaire, un seul bouton qui priorise
le fichier choisi) et « Xtream Codes ». Une version antérieure sautait à
l'accueil dès qu'un profil était actif : l'écran de sélection devenait
définitivement invisible après la première configuration. Ne pas réintroduire ce
raccourci.

## Room — migrations

`fallbackToDestructiveMigration()` uniquement, **pas de `Migration` écrite à la
main**. Une tentative de `MIGRATION_2_3` (`ALTER TABLE movies ADD COLUMN tmdbId
INTEGER NOT NULL DEFAULT 0`) a fait planter l'app au démarrage : SQLite exige un
`DEFAULT` pour ajouter une colonne `NOT NULL`, mais le schéma attendu par Room
n'en déclarait pas (`@ColumnInfo(defaultValue)` manquant) → `IllegalStateException:
Migration didn't properly handle…` dès le premier accès à la base. Coût du choix
retenu : la base est recréée à chaque bump de `version` (profils enregistrés
perdus, à re-saisir). Si une vraie migration devient nécessaire, il **faut**
aligner `@ColumnInfo(defaultValue = "…")` sur le SQL — et la tester sur un vrai
upgrade, pas seulement sur une install neuve.

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

## Mise à jour OTA + panel de build

- `UpdateManager.checkForUpdate()` lit `AppConfig.Update.VERSION_URL`
  (`https://iptv2.nicotv.ovh/update/version.json`), propose la MAJ si
  `remote.versionCode > BuildConfig.VERSION_CODE`.
- Déclenché dans `SetupActivity.onCreate()` (avant tout chargement) et
  `MainActivity.onStart()`, throttlé à 2 min.
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
