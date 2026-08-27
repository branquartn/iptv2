# IPTV2

Lecteur IPTV grand public (Kotlin, MVVM) : **pas de compte, pas de login**.
L'utilisateur charge sa propre source à l'écran de démarrage — fichier M3U
local, URL de playlist M3U, ou identifiants Xtream Codes — et regarde
chaînes live, films et séries. Mise à jour OTA intégrée. Cible **mobile**,
**Android TV** et **Fire TV**.

Dérivé de [NicoTV](https://github.com/branquartn/iptv) (même moteur ExoPlayer,
mêmes conventions UI) mais sans compte ni backend métier : toute la logique
catalogue est locale (parsing M3U / client Xtream Codes + cache Room).

## Structure du dépôt

```
app/src/main/java/com/nicotv/iptv2/
  AppConfig.kt          # une seule constante : l'URL de mise à jour OTA
  data/
    PlaylistSourcePrefs.kt   # config de la source active (SharedPreferences)
    m3u/M3uParser.kt         # parsing M3U + classification live/VOD/série
    xtream/XtreamClient.kt   # client Xtream Codes (player_api.php)
    database/                # Room (dao/, entity/) — cache du catalogue chargé
    repository/PlaylistRepository.kt  # charge la source, expose films/séries/
                                       # chaînes joints aux favoris/reprise
  domain/model/          # modèles UI (Movie, Series, Channel)
  player/                # PlayerActivity (Media3/ExoPlayer)
  ui/
    setup/                # écran de démarrage : choix de la source
    main/                 # accueil (3 tuiles : Chaînes / Films / Séries)
    live/ movies/ series/ detail/ favorites/ resume/ search/
    common/               # BaseActivity, PosterAdapter, RotatingBorderView...
  update/UpdateManager.kt # OTA : lit version.json, télécharge + installe l'APK
server/
  update/                # APKs publiés (5 derniers) + version.json (OTA)
scripts/                 # aide au build Windows
```

Package applicatif : `com.nicotv.iptv2`.

## Compiler

Prérequis : JDK 17+, Android SDK (platform 34, build-tools 34.0.0).

```bash
export ANDROID_HOME=/chemin/vers/android-sdk   # ou renseigner sdk.dir dans local.properties
./gradlew assembleRelease
```

Signature release : générer un keystore dédié (`app/iptv2-release.jks`, jamais
committé — voir `.gitignore`) puis renseigner dans `local.properties` :

```properties
iptv2StorePassword=...
iptv2KeyPassword=...
iptv2KeyAlias=iptv2
```

```bash
keytool -genkeypair -v -keystore app/iptv2-release.jks -alias iptv2 \
  -keyalg RSA -keysize 2048 -validity 10000
```

## Sources supportées

- **Fichier M3U local** : sélection via le sélecteur de fichiers système
  (permission de lecture persistante — survit à un redémarrage de l'app).
- **URL M3U** : playlist hébergée, récupérée à chaque chargement.
- **Xtream Codes** : serveur + identifiants, requêtes `player_api.php`
  (catégories/flux live, VOD, séries — épisodes chargés à la demande à
  l'ouverture d'une fiche série, pas au chargement initial).

La classification VOD/série/live depuis un M3U est heuristique (group-title +
motif `SxxEyy`/`1x02` dans le nom) — un fournisseur qui s'écarte des
conventions usuelles peut être mal classé.

## Mise à jour OTA

Voir [`server/README.md`](server/README.md).
