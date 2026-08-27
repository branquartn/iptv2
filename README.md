# NicoTV

Application Android (Kotlin, MVVM) de streaming personnel : films, séries,
lecteur Media3/ExoPlayer, catalogue synchronise via l API NicoTV/DB,
authentification via un backend PHP et mise à jour OTA. Cible **mobile**,
**Android TV** et **Fire TV**.

## Structure du dépôt

```
app/                     # application Android (module :app)
  src/main/java/com/nicotv/iptv/
    AppConfig.kt         # constantes (URLs API, TMDb, Update, Realtime)
    data/                # Room, Retrofit, RealtimeClient (WebSocket), repository
    domain/model/        # modèles UI
    player/              # PlayerActivity (Media3/ExoPlayer)
    ui/                  # login, main, movies, series, detail, favorites,
                         # resume, search, users, common
    update/UpdateManager # OTA : lit version.json, télécharge + installe l'APK
    util/SessionManager  # session utilisateur (jeton, prefs)
server/
  api/                   # backend PHP d'authentification (SQLite)
  update/                # APKs publiés (5 derniers) + version.json (OTA)
scripts/                 # scripts de déploiement
```

Package applicatif : `com.nicotv.iptv`.

## Compiler

Prérequis : JDK 17+, Android SDK (platform 34, build-tools 34.0.0).

> **Windows** : `scripts\setup-build-windows.ps1` installe tout
> automatiquement (JDK 17, SDK Android, `local.properties` pré-rempli).
> Il ne reste qu'à copier le keystore et renseigner ses mots de passe.
> Ensuite, `scripts\build-release.ps1` vérifie les prérequis, fait un
> `git pull` et lance le build signé en une commande.

```bash
# Indiquer l'emplacement du SDK
echo "sdk.dir=/chemin/vers/android-sdk" > local.properties

# Build debug (non signé)
./gradlew assembleDebug      # → app/build/outputs/apk/debug/app-debug.apk

# Build release (signé via app/nicotv-release.jks)
./gradlew assembleRelease    # → app/build/outputs/apk/release/app-release.apk
```

> Le keystore `app/nicotv-release.jks` et ses mots de passe ne doivent pas etre
> versionnes. Pour `assembleRelease`, renseigner dans `local.properties` :
> `nicotvStorePassword=...`, `nicotvKeyPassword=...`, et optionnellement
> `nicotvKeyAlias=nicotv`. Les variables d'environnement
> `NICOTV_STORE_PASSWORD`, `NICOTV_KEY_PASSWORD` et `NICOTV_KEY_ALIAS` sont aussi supportees.

## Synchronisation Temps Réel (WebSocket)

Le catalogue est synchronisé automatiquement via un bus temps réel
(`RealtimeClient`) utilisant le protocole WebSocket (`wss://ws.nicotv.ovh/`).

- À chaque modification sur le serveur (ajout d'un film, renommage TMDb), un message est poussé vers l'application.
- L'application déclenche alors une synchronisation silencieuse en arrière-plan pour mettre à jour la base de données locale (Room).

**État synchronisé par utilisateur** (action `state` de l'API) : favoris, progression de
lecture, films/épisodes **vus** et corrections TMDb. Les épisodes vus utilisent un canal
dédié `epseen` (distinct de la détection « NOUVEAU » de la PWA) → le badge « ✓ Vu » des
séries est partagé entre tous les appareils. Voir [`CLAUDE.md`](CLAUDE.md) (section « État
synchronisé »).

## Mise à jour OTA

`UpdateManager.checkForUpdate()` lit `https://update.nicotv.ovh/version.json`
et propose la mise à jour si `versionCode` distant > `BuildConfig.VERSION_CODE`
(déclenché dans `MainActivity.onStart()`, throttlé à 2 min).

`version.json` (Exemple v1.0.4.4) :
```json
{ "versionCode": 143, "versionName": "1.0.4.4",
  "apkUrl": "https://update.nicotv.ovh/iptv-1.0.4.4.apk",
  "changelog": "Mise a jour NicoTV 1.0.4.4." }
```

## Dernières modifications (v1.0.11.59)

- **Présence temps réel multi-appareils** (admin.nicotv.ovh « Qui regarde quoi ») : une
  ligne par session (compte + appareil) au lieu de par compte, deux états (`watching`/
  `online` avec écran courant affiché). **Contrôle à distance** (pause/reprendre/lancer
  un film) depuis admin.nicotv.ovh et depuis l'app mobile elle-même (bandeau « en cours
  sur... »). Bug notable corrigé : commandes pilotées depuis le thread WS (OkHttp) au
  lieu du thread UI → ExoPlayer les ignorait silencieusement.
- **Picture-in-Picture** (Android 8+) : bouton dédié dans le lecteur + déclenchement
  automatique au Home.
- **RotatingBorderView** (anneau blanc tournant au focus) généralisé : bouton retour
  sur tous les écrans, icônes topbar accueil, lignes d'épisode.
- **Fixes** : onglet saison qui restait hors champ quand l'épisode en cours avait le
  focus ; sélection audio FR ignorée quand la piste FR est en index 0 (côté PWA).

Détail complet dans [`CLAUDE.md`](CLAUDE.md). Voir [`context.md`](context.md) pour le
détail des versions précédentes.

## Process de release

À chaque livraison — répartition fixe : Claude bumpe la version + commit/push son
code, **sans jamais compiler** (ni debug ni release) ; l'utilisateur fait toujours
lui-même le build (y compris debug, pour tester) et le déploiement.

1. Bumper `versionCode` (+1) et `versionName` dans `app/build.gradle.kts`. *(Claude)*
2. `./gradlew assembleRelease` (signé via `nicotv-release.jks`). *(utilisateur)*
3. Copier l'APK dans `server/update/iptv-<versionName>.apk` et ne garder
   que les **5 derniers** APKs. *(utilisateur)*
4. Mettre à jour `server/update/version.json` (code, name, apkUrl, changelog). *(utilisateur)*
5. Commit + push sur la branche de travail (`claude/stable`). *(Claude, pour le code)*
6. Déployer l'APK + `version.json` sur le serveur live `update.nicotv.ovh`
   (hors dépôt). *(utilisateur)*

## Backend d'authentification

Voir [`server/README.md`](server/README.md) pour le déploiement du service PHP
(utilisateurs, mots de passe bcrypt, jetons HMAC).

## Conventions

- Code et commentaires en **français**.
- Layouts : `layout/` (base, en paysage) et `layout-sw600dp/` (uniquement
  `activity_main`) : répliquer les changements de header de l'accueil
  dans les deux.
- Toutes les activités sont en `screenOrientation="landscape"`.
- Secrets (clé TMDb, keystore) : jamais loggés, affichés ni committés
  en clair.
