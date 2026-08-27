# Contexte du projet NicoTV

## État actuel (v1.0.11.59)

### Présence temps réel multi-appareils + contrôle à distance

`admin.nicotv.ovh` (« Qui regarde quoi ») affiche désormais une ligne **par session**
(uid + device_id persistant) au lieu d'une par compte — plusieurs appareils du même
compte (mobile + Shield) apparaissent séparément. Deux types : `watching` (lecture, avec
titre/position/durée) et `online` (app ouverte, écran courant affiché en clair). Pause/
reprendre/lancer un film à distance depuis admin.nicotv.ovh **et** depuis l'app mobile
elle-même (bandeau « en cours sur... » sur l'accueil, scopé à son propre compte). Détail
complet dans [`CLAUDE.md`](CLAUDE.md) → « Présence temps réel multi-appareils » et
« Contrôle à distance ».

**Bug non trivial corrigé** : les commandes pause/reprise n'avaient aucun effet malgré un
pipeline serveur 100% fonctionnel (vérifié en direct) — `RealtimeClient.onMessage()`
(callback OkHttp) tourne sur un thread de fond, et ExoPlayer refuse silencieusement d'être
piloté hors de son thread d'application. "Lancer un film" fonctionnait quand même car
`startActivity()` tolère n'importe quel thread. Fix : `Handler(Looper.getMainLooper())`
avant tout appel au `Player`.

### Picture-in-Picture

Bouton dédié + déclenchement auto (`onUserLeaveHint`, pas fiable à 100% selon
navigation gestuelle/OEM). Détail dans [`CLAUDE.md`](CLAUDE.md) → « Picture-in-Picture ».

### RotatingBorderView généralisé

Anneau blanc tournant au focus étendu du bouton retour (déjà en place sur la fiche
détail) à Films/Séries/Recherche/fiche série/Utilisateurs, aux icônes de la topbar
accueil (recherche/reprendre/favoris/téléchargements/comptes/déconnexion, mobile et
tablette/TV) et aux lignes d'épisode. Détail dans [`CLAUDE.md`](CLAUDE.md) → convention
RotatingBorderView.

### Fix onglet saison hors champ + audio FR piste 0

Fiche série : l'onglet de la saison active suit maintenant l'épisode ciblé à
l'ouverture (`scrollToSeasonTab`). Sélection audio FR : bug corrigé côté PWA où la
piste 0 était traitée comme "défaut, jamais à forcer" même quand c'est justement elle
qu'il fallait forcer (FR en AC3 piste 0 non copiable, EN en AAC piste 1 copiable → le
repli codec-compatible choisissait l'anglais à chaque fois). Non applicable à l'APK
(ExoPlayer `setPreferredAudioLanguage` natif).

- **Version** : passage en **1.0.11.59 (Code 268)**.

## État précédent (v1.0.5.8)

### Casting, fiche acteur, bande-annonce, films similaires (fiche film)

Portage des ajouts récents côté PWA (`iptv/app.js`, même session) — détail complet dans
[`CLAUDE.md`](CLAUDE.md) → « Casting / réalisateur / films similaires / bande-annonce / acteur ».
Résumé : rangée casting sous le synopsis (`CastAdapter`), clic → dialog acteur (bio +
filmographie, `dialog_actor.xml`), réalisateur cliquable, films similaires (recommandations
TMDb), bouton bande-annonce YouTube. Filmographie/similaires réutilisent le flux d'ajout
existant de `SearchActivity` (badge ✓ si déjà possédé → ouvre la fiche, + sinon → ajoute
à la file de téléchargement).

### Fix data : progression écrasée par la synchro (`MediaRepository.syncRemoteState`)

`replaceAll()` remplaçait aveuglément tout l'historique local par l'état serveur à chaque
synchro. Si `pushProgress()` échouait silencieusement (`runCatching` sans retry — coupure
réseau), la position restait bonne en local mais jamais reçue par le serveur ; le sync
suivant l'effaçait. Fusion par `watchedAt` (le plus récent gagne) à la place — même bug,
même correctif que côté PWA (`loadState()`). Détail dans [`CLAUDE.md`](CLAUDE.md).

- **Version** : passage en **1.0.5.8 (Code 157)**.

## État précédent (v1.0.5.6)

### Fix OTA — enchaînement téléchargement → installation (v1.0.5.5/1.0.5.6)

- **Régression (cause racine)** : la revue de code du 12 juin (`e1ef865`
  « Durcissement ») a passé le récepteur OTA de `RECEIVER_EXPORTED` à
  `RECEIVER_NOT_EXPORTED`. Or `ACTION_DOWNLOAD_COMPLETE` est émis par le
  **DownloadManager système** (process externe), pas par l'app : sur
  **Android 13+ (API 33)** un récepteur `NOT_EXPORTED` refuse tout broadcast
  d'une autre app/du système → le signal de fin de téléchargement n'arrivait
  plus jamais → l'APK se téléchargeait mais l'installation ne se déclenchait
  pas seule. Le filet `onStart` (`installPendingDownloadIfReady`) ne rattrapait
  qu'au **changement d'écran** (Films → Accueil) — d'où le symptôme signalé sur
  téléphone **et** Shield. **`NOT_EXPORTED` est correct pour les broadcasts
  internes à l'app, faux pour ceux du système** (il fallait `EXPORTED`).
- **Correctif retenu** : suppression totale du `BroadcastReceiver` au profit d'un
  **polling** de `DownloadManager` (`UpdateManager.pollDownload`, 1 s) dans un
  `CoroutineScope` de niveau `companion` (`pollScope`, indépendant du cycle de
  vie de l'Activity → survit à un changement d'écran pendant le téléchargement).
  L'install se lance dès `STATUS_SUCCESSFUL`, sans dépendre d'aucun broadcast ni
  de la version d'Android. Le filet `installPendingDownloadIfReady` (onStart)
  reste pour le cas « process tué en arrière-plan ».
- **Garde anti double-install** (`installApkOnce`, Set statique de `downloadId`) :
  le polling et le filet onStart pouvaient tous deux voir `STATUS_SUCCESSFUL`
  dans la fenêtre avant `clearPendingDownload` → sans garde, deux prompts d'install.
- **Piège de test OTA** : le code download→install s'exécute dans la version
  **installée**, pas la cible. Pour valider le fix il faut d'abord **sideloader
  manuellement** une version qui contient le polling (≥ 1.0.5.5), puis lancer
  l'OTA vers la suivante depuis cette version.
- **Version** : passage en **1.0.5.6 (Code 155)**.

## État précédent (v1.0.4.2)

### Modifications (v1.0.2.4 → v1.0.4.2) :
- **Reprise film/série unifiée à 5s** (`MediaRepository.saveWatchPosition`,
  `MIN_RESUME_MS`) : sous ce seuil (ex. après « recommencer à zéro » suivi d'une
  sortie rapide), la reprise existante est effacée (`removeHistory`) au lieu
  d'être laissée en l'état. Auparavant les épisodes avaient un seuil distinct
  (1s, « reprise dès la 1re seconde », introduit en v1.0.2.4) ; unifié à 5s
  film + série pour un comportement cohérent.
- **Fiche série (`SeriesDetailActivity`)** : ouverture scrolle et focus
  automatiquement sur l'épisode à reprendre — en cours → premier épisode jamais
  vu → dernier épisode de la série si tout est vu. Navigation télécommande vers
  le bouton « recommencer à zéro » d'un épisode corrigée (`nextFocusRight/Left`
  manquants dans `item_episode.xml`).
- **Lecteur (`PlayerActivity`)** : prompt « épisode suivant dans 5s » avant la
  fin réelle du fichier (heuristique : 20 dernières secondes, pas de vraies
  métadonnées de générique disponibles), avec lecture immédiate ou annulation.
  Refonte complète du controller custom (panel ⚙ paramètres à deux niveaux :
  vitesse/audio/sous-titres, icônes zoom focus TV, scrubber rouge au focus,
  seek accéléré au maintien, overlay pause style Netflix, rotation libre +
  reprise correcte après retour d'arrière-plan). Android Auto :
  `MediaLibraryService` déclaré au manifeste.
- **Login** : correctifs saisie clavier TV (capitalisation/espaces), suppression
  de l'auto-submit sur le mot de passe.
- **Mur d'affiches** (films/séries) : padding haut sur la RecyclerView pour que
  le zoom focus de la 1re ligne ne soit plus rogné par la barre de recherche.
- **Sécurité / infra** : clé TMDb injectée via interceptor OkHttp (plus de
  répétition en `@Query` sur chaque endpoint `TmdbApi`), en-tête `Authorization`
  redacté dans les logs OkHttp debug, APKs retirés du suivi git (`server/update/`
  reste sur disque, synchronisé avec le serveur live). Pipeline de build local
  (`apk-builder.sh`, déclenché par la mini-app `/home/nicolas/apk`) : après
  `assembleRelease` + publication sur `update.nicotv.ovh`, copie désormais
  automatiquement l'APK + `version.json` dans `server/update/` du dépôt et les
  commit/push sur `claude/stable`.
- **Gradle** : build cache + configuration cache + parallélisme activés ; fixes
  successifs de compatibilité configuration cache (`publishReleaseToNicoUpdate`,
  imports, `afterEvaluate`).
- **Version** : passage en **1.0.4.2 (Code 141)**.

## État précédent (v1.0.2.3)

### Modifications (v1.0.1.1 → v1.0.2.3) :
- **Séries — suivi de lecture par épisode** : badge « ✓ Vu » (épisode regardé
  jusqu'au bout, table Room `seen_episodes`) et « ▶ Reprendre » avec barre de
  progression + bouton « depuis le début » pour un épisode commencé
  (`EpisodeAdapter` ; `playEpisode(ep, resume)`).
- **Lecture automatique de l'épisode suivant** en fin d'épisode
  (`PlayerActivity.onPlaybackEnded` → `getNextEpisode`, extras `EXTRA_SERIES_ID` /
  `EXTRA_SERIES_TITLE`).
- **Films — badge « ✓ Vu »** quand le film est regardé jusqu'à la fin
  (`MovieEntity.seen`, table `seen_movies`), synchronisé serveur via `pushSeenState`.
- **Sync de l'état « vu » des épisodes entre appareils** : canal dédié **`epseen`**
  de l'action `state` (liste de `fileKey` "Série/Fichier.mkv"). Poussé à la fin d'un
  épisode (`pushSeenEpisodes`), tiré dans `syncRemoteState`. Distinct de `seen.episodes`
  (réservé à la détection « NOUVEAU » côté PWA). Serveur : nouveau kind `epseen` dans
  `api/iptv.php`. Voir [`CLAUDE.md`](CLAUDE.md) → « État synchronisé ».
- **Fiche série façon PWA** + correctifs de débordement (synopsis/boutons), icône &
  bannière Fire TV/Android TV, build (DSL AGP).
- **Version** : passage en **1.0.2.3 (Code 122)**.

## État précédent (v1.0.1.0)

### Modifications (v1.0.1.0) :
- **Fix synopsis débordant sur les boutons (fiche film)** : `activity_detail.xml`
  restructuré — le panneau droit passe de `LinearLayout` vertical à un
  `RelativeLayout`. Le `ScrollView` est contraint au-dessus de la barre
  de boutons (`layout_above`), celle-ci ancrée en bas (`layout_alignParentBottom`).
  Quel que soit le comportement de `clipChildren`, le synopsis ne peut
  plus dépasser sur les boutons.
- **Étoile favori + relink TMDb dans la fiche série** : nouveau
  `LinearLayout` d'actions au-dessus du synopsis dans
  `activity_series_detail.xml` ; `btn_favorite` (étoile teintée gris si
  non favori, couleur naturelle si favori) et `btn_relink_tmdb` (crayon).
  Même zoom ×1.25 au focus que l'accueil.
- **Favoris séries** : nouvelle table Room `series_favorites`
  (`SeriesFavoriteEntity` + `SeriesFavoriteDao`). Migration v6 → v7.
  `MediaRepository` expose `isSeriesFavorite()`, `toggleSeriesFavorite()`.
- **TMDb relink séries** : `MediaRepository.relinkSeriesToTmdb()` ;
  dialogue de recherche identique à la fiche film, relance `loadData()`
  et affiche un toast de confirmation.
- **Version** : Passage en **1.0.1.0 (Code 109)**.

## État précédent (v1.0.0.9)

### Modifications (v1.0.0.9) :
- **Vignette Fire TV 16:9 (nouvelle approche documentée)** : pour les apps
  sideloadées, le lanceur Fire TV affiche `android:icon` (pas la bannière),
  et Fire OS a un bug connu avec les icônes en `mipmap`. Restructuration :
  `android:icon="@drawable/ic_launcher"`, icône carrée dans
  `drawable-{dens}/`, bannière 16:9 comme `ic_launcher` dans
  `drawable-television-{dens}/` **et** `drawable-sw540dp-{dens}/`
  (un Fire TV 1080p se déclare 960×540dp/xhdpi — double filet au cas où
  le uiMode télévision ne serait pas déclaré). Tous les buckets `mipmap-*`
  supprimés. `android:banner="@drawable/banner"` conservé pour Android TV.
- **Fix flash des badges NOUVEAU au retour de lecture** : `syncRemoteState`
  faisait `deleteAll()` puis réinsérait favoris et historique → Room
  émettait un état transitoire « vide » (badges/étoiles clignotants).
  Remplacement transactionnel via `replaceAll()` (`@Transaction`) dans
  `FavoriteDao` et `WatchHistoryDao`.
- **Premier sync = référence** : sur base vide (installation fraîche), les
  films sont insérés avec `addedAt = 0` → rien n'est « NOUVEAU » ; seuls
  les titres ajoutés ensuite portent le badge.
- **Version** : Passage en **1.0.0.9 (Code 108)**.

## État précédent (v1.0.0.8)

### Modifications (v1.0.0.8) :
- **Fix crash au retour du player sur le mur d'affiches** :
  `PosterAdapter.bind()` appelait `itemView.animate().cancel()` pendant que
  le `DefaultItemAnimator` du RecyclerView animait la même vue avec le même
  `ViewPropertyAnimator` (mise à jour de la barre de progression après
  visionnage) → `IllegalArgumentException « Tmp detached view »`.
  L'adapter désactive maintenant l'item animator
  (`onAttachedToRecyclerView → itemAnimator = null`) sur les 4 écrans qui
  l'utilisent (Films, Séries, Favoris, Reprendre) : plus de crash et plus
  d'effet « refresh » visible.
- **Version** : Passage en **1.0.0.8 (Code 107)**.

## État précédent (v1.0.0.7)

### Modifications (v1.0.0.7) :
- **Header de l'accueil épuré** : les boutons du haut (reprendre, recherche,
  favoris, comptes, déconnexion) n'ont plus aucun fond ni soulignement
  (`bg_btn_secondary` retiré sur `activity_main` des deux layouts) — seule
  l'icône reste, et le focus télécommande est signalé uniquement par le
  zoom 1.25x. Sur sw600dp, le bouton déconnexion (icône + texte) devient
  une icône seule, et le header ne rogne plus le zoom (`clipChildren=false`).
- **Boutons retour épurés** : nouveau drawable `bg_btn_back` (transparent,
  uniquement un soulignement blanc au focus, sans cadre ni fond) appliqué
  aux 6 boutons retour (Films, Séries, Recherche, Détails, Détails série,
  Comptes). Le bouton retour de l'écran Détails passe de 48dp à 44dp pour
  aligner le soulignement.
- **Icône favoris de l'accueil** : étoile jaune s'il y a des favoris,
  teinte par défaut sinon, avec un badge bleu (`bg_badge_resume`) affichant
  le nombre de favoris — même style que le badge de reprise. Masqué si
  aucun favori. Le layout sw600dp est aligné sur le layout de base
  (`ic_star` + `iv_favorite_star`, au lieu du cœur accent fixe).
- **Fix défilement des vignettes de l'accueil** : pendant la synchro, Room
  émet la liste des films/séries en rafale et chaque émission redémarrait
  le job de rotation (donc rechargeait une image immédiatement) → les
  fonds défilaient rapidement. Le job de rotation n'est plus jamais
  redémarré : il lit la liste courante (`movieHubUrls`/`seriesHubUrls`) à
  chaque tick de 45 s, et `loadRotatingHubImage` ignore un rechargement
  vers l'URL déjà affichée (tag de l'ImageView).
- **Fiche film épurée** : les boutons favori, recommencer et TMDb n'ont
  plus le cercle bleu (`bg_btn_icon` supprimé du dépôt) — icône seule,
  focus signalé par un zoom 1.25x comme l'accueil (Lire/Retour restent à
  1.1x). `clipChildren/clipToPadding` désactivés sur les conteneurs
  d'`activity_detail` pour ne pas rogner le zoom.
- **Badge NOUVEAU** : déplacé à l'intérieur de la CardView de l'affiche →
  le bandeau diagonal est rogné aux bords arrondis du poster (équivalent
  `overflow: hidden`). Il disparaît désormais dès l'**ouverture de la
  fiche** du film (`DetailViewModel.loadMovie` → `markMovieSeen`, qui
  remet `addedAt` à 0), plus seulement après visionnage complet.
- **Fiabilisation du temps réel (WebSocket)** :
    - Resynchro de rattrapage à chaque (re)connexion de la WS (`onOpen`) :
      les évènements émis pendant que l'app était en arrière-plan ne sont
      plus perdus.
    - Les callbacks `onFailure`/`onClosed` ne touchent l'état que pour la
      socket courante → plus de double connexion lors d'allers-retours
      rapides fond/premier plan ; `start()`/`connect()` gardés contre les
      connexions en doublon.
    - `syncCatalog` sérialisé par un `Mutex` : la synchro du démarrage et
      celles du bus temps réel ne se chevauchent plus (risque de doublons).
- **Revue de code complète + durcissement** :
    - Logger réseau OkHttp uniquement en debug (la clé TMDb passait dans
      les logs release via les URLs).
    - Anti force brute sur le login PHP : table `login_attempts`, 8 échecs
      max par IP/identifiant sur 15 min → HTTP 429.
    - Synchro : appel TMDb seulement si nouveau titre / `tmdbId` changé /
      poster manquant (fini la rafale d'appels TMDb à chaque synchro).
    - `network_security_config` : cleartext interdit hors LAN ;
      `allowBackup=false` (jeton non sauvegardé) ; récepteur OTA
      `NOT_EXPORTED` **(⚠️ a cassé l'OTA sur Android 13+ — voir « Fix OTA » en
      tête ; le récepteur a depuis été remplacé par un polling)** ; garde
      `TIME_UNSET` sur l'avance rapide du player ;
      accents corrigés dans le message d'erreur login ; règles ProGuard
      mortes (WireGuard/BouncyCastle) supprimées.
- **Fix vignette Fire TV en 1:1** : le lanceur Fire TV ignore
  `android:banner` et affiche `android:icon` (carré). Ajout des buckets
  `mipmap-television-*` avec la bannière 16:9 comme `ic_launcher` : sur
  Fire TV (uiMode television), l'icône se résout en 16:9 ; téléphones et
  tablettes gardent l'icône carrée, le Shield garde `android:banner`.
  Ressources `_v2` orphelines supprimées.
- **Version** : Passage en **1.0.0.7 (Code 106)**. APK/version.json à
  publier depuis le poste de build (keystore hors dépôt).

## État précédent (v1.0.0.6)

L'application a été épurée des protocoles de streaming adaptatif (HLS/DASH) pour privilégier la lecture directe des fichiers.

### Modifications majeures (v1.0.0.6) :
- **Suppression HLS/DASH** : Retrait des dépendances et de la logique de détection HLS dans le lecteur pour forcer la lecture progressive (direct MKV/MP4). Cela devrait résoudre les problèmes de barre de temps manquante sur les séries.
- **Version** : Passage en **1.0.0.6 (Code 105)**.

### Modifications visuelles récentes :
- **Badges de compteur** : Uniformisés en pastilles bleues arrondies (`bg_badge_resume`) sur tous les écrans (Accueil, Films, Séries, Favoris, Reprendre).
- **Badge NOUVEAU** : Modernisé en bandeau diagonal (-45°) dans le coin supérieur gauche des posters. Disparaît après visionnage.
- **Écran Accueil** :
    - Boutons de la barre supérieure 100% transparents.
    - Animation de focus renforcée (zoom 1.25x).
    - Étoile des favoris jaune si non vide.
    - Correction du badge de notification (enfin parfaitement rond).
- **Écran Détails** :
    - Suppression du "Voir plus", l'overview est scrollable et visible.
    - Bouton "Lire" intelligent affichant le temps de reprise.

### Points d'attention :
- `isMinifyEnabled = true` (R8 activé).
- Filtrage ABI : `armeabi-v7a` et `arm64-v8a` uniquement.
- Autorité FileProvider : `com.nicotv.iptv.fileprovider`.
