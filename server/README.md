# Serveur IPTV2

Contrairement à NicoTV, cette app n'a **aucun backend métier** (pas de compte,
pas de catalogue serveur) — le seul service côté serveur est la **mise à jour
OTA** : ce dossier `server/update/` contient les APKs publiés
(`iptv2-<version>.apk`) et `version.json`, lus par `UpdateManager` dans l'app.

## Hébergement

Servi directement en HTTPS à la racine de `iptv2.nicotv.ovh` (DocumentRoot
`/home/nicolas/iptv2` sur le serveur — même principe que `update.nicotv.ovh`
pour NicoTV) :

```
https://iptv2.nicotv.ovh/version.json
https://iptv2.nicotv.ovh/iptv2-1.0.0.apk
```

L'app lit `version.json` (`UpdateManager.checkForUpdate()`) et propose la
mise à jour uniquement si `versionCode` distant est **strictement supérieur**
à la version installée. On ne conserve que les **5 derniers** APK dans ce
dossier.

## Process de release

**Répartition (même consigne que NicoTV) :** Claude bumpe la version, commit
et push — **jamais de build**, ni debug ni release. L'utilisateur s'occupe
toujours lui-même du build et du déploiement.

1. Bumper `versionCode` (+1) et `versionName` dans `app/build.gradle.kts`,
   mettre à jour `appChangelog`. *(Claude)*
2. `./gradlew assembleRelease` (signé via `app/iptv2-release.jks`, voir
   [`README.md`](../README.md) racine pour le générer). *(utilisateur)*
3. Copier l'APK dans `server/update/iptv2-<versionName>.apk`, ne garder que
   les 2-5 derniers. *(utilisateur)*
4. Mettre à jour `server/update/version.json` (code, name, apkUrl,
   changelog). *(utilisateur)*
5. Commit + push. *(Claude, pour le code — pas pour l'APK)*
6. Déployer l'APK + `version.json` sur `/home/nicolas/iptv2/` (racine servie
   par `iptv2.nicotv.ovh`) :
   ```bash
   cp server/update/iptv2-<version>.apk server/update/version.json /home/nicolas/iptv2/
   ```
   *(utilisateur — ou script à ajouter si le rythme de release le justifie,
   sur le modèle de `apk-builder.sh`/`apk2-builder.sh` du serveur NicoTV.)*
