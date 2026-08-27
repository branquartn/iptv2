# NicoTV - Mémoire de l'Assistant IA

Ce fichier contient les instructions et l'état de référence mémorisés pour l'assistance au développement du projet NicoTV.

## État de référence (v1.0.11.59)

- **Package Name** : `com.nicotv.iptv`.
- **Version** : `1.0.11.59` (versionCode `268`).
- **Cible Prioritaire** : Fire TV Stick 4K (Fire OS 6.7.1.1).
- **Release** : Claude bumpe la version + commit/push, **sans jamais compiler** (ni
  `assembleDebug` ni `assembleRelease`) ; l'utilisateur fait TOUJOURS lui-même le build
  (y compris debug) et le déploiement — jamais Claude (consigne fixe, voir `CLAUDE.md`
  → « Process de release »).

## Principes d'Interface (UX/UI) mémorisés

1.  **Boutons Accueil** :
    - Toujours **transparents** (pas de fond, pas de bordure par défaut).
    - Effet de **zoom à 1.25x** lors du focus (navigation à la télécommande).
    - Icônes : `↻` (Reprendre), `+` (Recherche), `★` (Favoris), `→` (Déconnexion).
2.  **Système de Badges** :
    - **Compteurs** : Pastilles bleues arrondies (`bg_badge_resume`) utilisées partout (accueil et en-têtes de listes).
    - **Badge NOUVEAU** : Bandeau incliné à -45° dans le coin supérieur gauche des affiches. Disparaît automatiquement quand le film est marqué comme vu.
    - **Badge « ✓ Vu »** (films & épisodes regardés jusqu'au bout) et **« ▶ Reprendre »** (commencé, avec barre + bouton « depuis le début »). Seuil unique **5s** avant qu'une reprise soit créée/affichée (film comme série) — sous ce seuil, une reprise existante est effacée plutôt que laissée en l'état. L'état « vu » des épisodes est synchronisé entre appareils via le canal `epseen` (cf. `CLAUDE.md`).
3.  **Fiche Détail** :
    - Pas de système "Voir plus" pour le résumé (tout doit être visible).
    - Bouton de lecture dynamique affichant le timestamp de reprise (ex: `v 1:24:05`).
    - **Casting** (rangée horizontale sous le synopsis) → clic sur un acteur ouvre
      sa fiche (bio + filmographie). **Réalisateur** cliquable au-dessus du synopsis.
      **Films similaires** (recommandations TMDb) en rangée sous le casting. Ces deux
      dernières listes portent un badge rond ✓ (déjà possédé → ouvre la fiche) ou +
      (absent → ajoute à la file de téléchargement, même flux que l'écran Recherche).
      **Bande-annonce** : bouton dédié, ouvre YouTube (app ou navigateur).
4.  **Favoris** : L'étoile du bouton d'accueil devient jaune (`#FFD700`) uniquement si la liste n'est pas vide.
5.  **Fiche série** : à l'ouverture, focus/scroll auto sur l'épisode en cours, sinon
    le premier jamais vu, sinon le dernier épisode de la série.
6.  **Lecteur** : prompt « épisode suivant dans 5s » dans les 20 dernières secondes
    du fichier (pas de vraies métadonnées de générique — heuristique par position).
    Bouton PiP dédié à côté de Retour (Android 8+) — déclenchement Home pas fiable
    à 100% partout, filet manuel.
7.  **Focus clavier/télécommande** : anneau blanc tournant (`RotatingBorderView`,
    `startAnim()`/`stopAnim()` piloté en code) plutôt qu'un contour statique ou un
    bouton texte — généralisé au bouton retour (tous écrans), icônes topbar accueil,
    lignes d'épisode. Pas sur les éléments non circulaires/rectangulaires simples
    (ex. pastille pilule « N nouveautés » → zoom seul).
8.  **Présence multi-appareils** : `admin.nicotv.ovh` et le bandeau accueil mobile
    affichent une ligne par appareil (pas par compte), avec pause/reprendre/lancer un
    film à distance. Voir `CLAUDE.md` pour le détail (piège thread ExoPlayer notamment).

## Contraintes Techniques

- **Ressources TV** : Utiliser des PNG classiques pour les icônes et bannières (compatibilité Fire OS 6). Éviter les Adaptive Icons XML complexes qui provoquent le bug du "triangle bleu".
- **Déploiement** : `apk-builder.sh` (déclenché depuis la mini-app `/home/nicolas/apk`)
  publie l'APK sur `update.nicotv.ovh` (`/var/www/html/update/`, servi directement
  depuis cette même machine) **et** synchronise + commit/push automatiquement
  `server/update/` du dépôt Git sur `claude/stable`.
- **Secrets** : clé TMDb injectée via interceptor OkHttp (pas de répétition en
  `@Query`), jamais loggée en clair (`Authorization` redacté en debug).
