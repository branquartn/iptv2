# Serveur IPTV2

Contrairement à NicoTV, cette app n'a **aucun backend métier** (pas de compte,
pas de catalogue serveur) — le seul service côté serveur est la **mise à jour
OTA**, servie depuis `iptv2.nicotv.ovh` avec un panel de build intégré (calqué
sur `apk2.nicotv.ovh` pour MonCV IA).

## Hébergement (serveur, hors dépôt)

`iptv2.nicotv.ovh` (DocumentRoot `/home/nicolas/iptv2`) sert deux choses sur
le **même domaine** :

- **Panel de build** (`index.php`/`api.php`/`apk.css`) — boutons Git Pull /
  Build, journal en direct, derniers commits. **Protégé par Cloudflare
  Access** (Zero Trust, email `branquart@gmail.com`) + bloqué depuis le LAN au
  niveau Apache (`<Location />` dans `iptv2.conf`, contourne pas l'un sans
  l'autre) — mêmes protections que `apk.nicotv.ovh`/`apk2.nicotv.ovh`.
- **`/update/`** (`/home/nicolas/iptv2/update/`) — APK publiés + `version.json`,
  lus par `UpdateManager` dans l'app. Ce chemin a une **Access Application
  Cloudflare séparée** (`iptv2.nicotv.ovh/update`, policy `bypass`/`everyone`,
  précédence sur l'app racine) : sans ça, l'app ne pourrait jamais lire
  `version.json` sans passer par un login Access interactif. Cf. l'app
  `nodered.nicotv.ovh/alexa` (même pattern, déjà en place) si retouché.

```
https://iptv2.nicotv.ovh/            # panel de build (Access requis)
https://iptv2.nicotv.ovh/update/version.json   # public (bypass Access)
https://iptv2.nicotv.ovh/update/iptv2-1.0.0.apk
```

Déclenchement build : `/usr/local/bin/iptv2-builder.sh` (root), watcher
`iptv2-builder.path`/`.service` (systemd, `PathExists` sur
`/var/lib/iptv2-builder/trigger-{pull,build}`, posés par `api.php`). Même
mécanique que `apk2-builder.sh`, AAB Play Store inclus.

Le build produit **APK + AAB** (`assembleRelease bundleRelease`) :
- APK → `/update/` (sideload + OTA), 2 derniers conservés ;
- AAB → `/home/nicolas/iptv2-apk/releases/` (dossier privé, **jamais** servi
  par Apache), 2 derniers conservés, récupérable via le bouton « Télécharger
  l'AAB » du panel (`api.php?action=aab`) pour l'upload manuel Play Console.

Deux pièges de permissions rencontrés, à connaître si le panel se remet à
répondre « vide » ou à refuser un déclenchement :
- `/var/lib/iptv2-builder` doit être **775 root:www-data** — Apache y écrit les
  fichiers trigger ;
- le dépôt doit être déclaré dans **`/etc/gitconfig`** (`safe.directory =
  /home/nicolas/iptv2-apk`) — sinon le `git log` du panel échoue en « dubious
  ownership » (dépôt à `nicolas`, Apache tourne en `www-data`) et la liste des
  commits revient vide, sans erreur affichée.

L'app lit `version.json` (`UpdateManager.checkForUpdate()`) et propose la MAJ
uniquement si `versionCode` distant est **strictement supérieur** à la
version installée. Les APK sont aussi synchronisés dans `server/update/` du
dépôt (commit + push automatiques après un build réussi).

## Process de release

**Répartition (même consigne que NicoTV) :** Claude bumpe la version, commit
et push — **jamais de build, et ne déclenche jamais le build** (même via le
trigger systemd). L'utilisateur s'occupe du build depuis le panel.

1. Bumper `versionCode` (+1) et `versionName` dans `app/build.gradle.kts`,
   mettre à jour `appChangelog`. *(Claude)*
2. Commit + push. *(Claude)*
3. Sur `iptv2.nicotv.ovh` (après login Cloudflare Access) : bouton **Git
   Pull** puis **Build** — signé via `app/iptv2-release.jks` (voir
   [`README.md`](../README.md) racine pour le générer, requis en local sur
   le serveur avant le premier build). *(utilisateur, quelques clics)*
4. Le script publie automatiquement l'APK + `version.json` dans `/update/`
   **et** dans `server/update/` du dépôt (commit + push inclus), et dépose
   l'AAB dans `releases/`.

⚠️ Un correctif poussé n'est **pas** dans l'APK tant que Git Pull + Build
n'ont pas été relancés : vérifier le commit affiché par le panel (ou
`status.json`) avant de conclure qu'un bug persiste.

Fallback manuel (script/panel indisponible) : `./gradlew assembleRelease
-Piptv2UpdateDir=/home/nicolas/iptv2/update`, puis copier dans
`server/update/`, committer.
