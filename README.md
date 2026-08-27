# IPTV2

Lecteur IPTV grand public (Kotlin, MVVM) : **pas de compte, pas de login**.
L'utilisateur enregistre une ou plusieurs sources nommées (profils) à l'écran de
démarrage — fichier M3U local, URL de playlist M3U, ou identifiants Xtream Codes
— et regarde chaînes live, films et séries. Jaquettes et fiches films (casting,
réalisateur, films similaires, bande-annonce) complétées via TMDb. Mise à jour
OTA intégrée. Cible **mobile**, **Android TV** et **Fire TV**.

Dérivé de [NicoTV](https://github.com/branquartn/iptv) (même moteur ExoPlayer,
mêmes conventions UI, même présentation du mur d'affiches et de la fiche film)
mais sans compte ni backend métier : toute la logique catalogue est locale
(parsing M3U / client Xtream Codes + cache Room), TMDb interrogé en direct.

## Structure du dépôt

```
app/src/main/java/com/nicotv/iptv2/
  AppConfig.kt          # URL de mise à jour OTA + clé/URLs TMDb
  data/
    PlaylistSourcePrefs.kt   # id du profil actif (SharedPreferences)
    m3u/M3uParser.kt         # parsing M3U + classification live/VOD/série
    xtream/XtreamClient.kt   # client Xtream Codes (player_api.php)
    tmdb/TmdbClient.kt       # recherche par titre, credits, recommandations,
                             # bande-annonce, fiche acteur
    database/                # Room (dao/, entity/) — profils + catalogue chargé
    repository/PlaylistRepository.kt  # CRUD profils, charge la source, expose
                                       # films/séries/chaînes + favoris/reprise
  domain/model/          # Movie, Series, Channel, SimilarWork/OpenTarget
  player/                # PlayerActivity (Media3/ExoPlayer)
  ui/
    setup/                # écran de démarrage : profils + choix de la source
    main/                 # accueil (3 tuiles : Chaînes / Films / Séries)
    live/ movies/ series/ detail/ favorites/ resume/ search/
    common/               # BaseActivity, PosterAdapter, RotatingBorderView...
  update/UpdateManager.kt # OTA : lit version.json, télécharge + installe l'APK
server/
  update/                # APKs publiés (2 derniers) + version.json (OTA)
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

Chaque source est enregistrée comme un **profil nommé** : l'écran de démarrage
liste les profils existants (tap = recharger, sans retaper les identifiants) et
propose deux cartes pour en ajouter un.

- **Charger votre playlist** — au choix dans le même formulaire :
  - *fichier M3U local*, via le sélecteur de fichiers système (permission de
    lecture persistante, survit à un redémarrage de l'app) ;
  - *URL M3U*, playlist hébergée récupérée à chaque chargement.
- **Xtream Codes** : serveur + identifiants, requêtes `player_api.php`
  (catégories/flux live, VOD, séries — épisodes chargés à la demande à
  l'ouverture d'une fiche série, pas au chargement initial).

La classification VOD/série/live depuis un M3U est heuristique, par ordre de
fiabilité : chemin de l'URL (`/live/`, `/movie/`, `/series/`), motif
`SxxEyy`/`1x02` dans le nom, `group-title`, puis extension vidéo. Un
fournisseur hors conventions peut être mal classé — le format M3U ne type pas
ses entrées.

## TMDb

Jaquettes, synopsis, notes et fiches films (casting, réalisateur, films
similaires, bande-annonce, filmographie acteur) viennent de TMDb, interrogé
directement par l'app (clé dans `AppConfig.Tmdb`). Une recherche par titre est
faite pour chaque film au chargement de la playlist — les visuels TMDb sont
prioritaires sur les `tvg-logo`/`stream_icon` de la source, souvent absents,
morts ou génériques sur les playlists publiques.

## Mise à jour OTA et panel de build

`iptv2.nicotv.ovh` sert à la fois l'OTA (`/update/`) et un panel de build
(Git Pull / Build APK+AAB, journal en direct). Voir
[`server/README.md`](server/README.md).
