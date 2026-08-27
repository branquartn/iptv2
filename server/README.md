# Serveur NicoTV (PHP)

Ce dossier `server/` contient les deux services côté serveur de NicoTV :

- **`api/`** — backend d'authentification (utilisateurs, mots de passe).
- **`update/`** — serveur de mise à jour OTA (APKs publiés + `version.json`).

En production, l'API et l'OTA sont servis en **HTTPS** via `update.nicotv.ovh`
(et/ou `192.168.1.202` sur le réseau local). Voir les sections ci-dessous.

---

# Backend d'authentification (`server/api/`)

Petit service PHP qui gère les utilisateurs et mots de passe de l'app NicoTV.
Stockage dans une base SQLite, mots de passe hachés (bcrypt via `password_hash`),
sessions par jeton signé (HMAC-SHA256).

L'app appelle ce service au lieu de l'identifiant codé en dur. Un compte
**admin** peut créer/supprimer des utilisateurs et réinitialiser les mots de
passe depuis l'app.

---

## Prérequis serveur

- PHP 7.4+ avec les extensions **pdo_sqlite** et **json** (presque toujours là par défaut)
- Apache ou nginx servant déjà `http://192.168.1.202/`
- Le dossier doit être **accessible en écriture** par PHP (pour créer la base SQLite)

Vérifier PHP et SQLite :
```bash
php -v
php -m | grep -i sqlite     # doit afficher pdo_sqlite
```

---

## Déploiement (pas à pas)

1. **Copier le dossier `server/api/` dans la racine web du serveur**, dans un
   sous-dossier `api`.

   ```
   /var/www/html/api/index.php
   /var/www/html/api/config.php
   /var/www/html/api/db.php
   /var/www/html/api/lib.php
   /var/www/html/api/.htaccess
   ```

   Tu peux copier le dossier `api` via SSH :
   ```bash
   scp -r server/api root@192.168.1.202:/var/www/html/
   ```
   (adapte `/var/www/html` à la racine web réelle de ton serveur)

2. **Changer la clé secrète.** Édite `api/config.php` et remplace `TOKEN_SECRET`
   par une longue chaîne aléatoire. Pour en générer une :
   ```bash
   php -r "echo bin2hex(random_bytes(32));"
   ```

3. **Donner les droits d'écriture** pour que PHP puisse créer la base :
   ```bash
   mkdir -p /var/www/html/api/data
   chown -R www-data:www-data /var/www/html/api/data    # Apache/Debian
   chmod 700 /var/www/html/api/data
   ```
   (sous nginx/php-fpm, l'utilisateur peut être `nginx` ou `php`)

4. **Tester** depuis n'importe quelle machine du réseau :
   ```bash
   curl -X POST "http://192.168.1.202/api/?action=login" \
        -H "Content-Type: application/json" \
        -d '{"username":"admin","password":"admin"}'
   ```
   Réponse attendue :
   ```json
   {"ok":true,"token":"...","user":{"id":1,"username":"admin","is_admin":true}}
   ```

5. **Se connecter dans l'app** avec `admin` / `admin`, puis **changer le mot de
   passe admin** immédiatement (menu → gestion des comptes).

> Le premier appel crée automatiquement la base `api/data/nicotv.sqlite` et le
> compte `admin` / `admin`.

---

## Endpoints

| Méthode | URL | Auth | Corps | Réponse |
|---------|-----|------|-------|---------|
| POST | `?action=login` | — | `{username,password}` | `{ok,token,user}` |
| GET  | `?action=me` | Bearer | — | `{ok,user}` |
| POST | `?action=change_password` | Bearer | `{old,new}` | `{ok}` |
| GET  | `?action=users` | Bearer admin | — | `{ok,users[]}` |
| POST | `?action=create_user` | Bearer admin | `{username,password,is_admin}` | `{ok,user}` |
| POST | `?action=reset_password` | Bearer admin | `{id,password}` | `{ok}` |
| POST | `?action=delete_user` | Bearer admin | `{id}` | `{ok}` |
| GET  | `?action=vpn_config` | Bearer | — | `{ok,config}` |
| POST | `?action=set_vpn_config` | Bearer admin | `{id,config}` | `{ok}` |

Le jeton se transmet dans l'en-tête `Authorization: Bearer <token>`.

---

## Sécurité

- Mots de passe **jamais stockés en clair** (bcrypt).
- Le dossier `data/` est protégé par `.htaccess` (404 si accès direct). Si tu
  utilises nginx, ajoute l'équivalent :
  ```nginx
  location ~ ^/api/data/ { return 404; }
  ```
- Le trafic est en **HTTP clair** sur ton réseau local. Acceptable en LAN privé ;
  à ne pas exposer sur Internet tel quel (mettre du HTTPS / reverse proxy sinon).
- Pense à changer `TOKEN_SECRET` **et** le mot de passe admin par défaut.

---

# Serveur de mise à jour OTA (`server/update/`)

Ce dossier contient les APKs publiés (`iptv-<version>.apk`) et le
`version.json` lu par l'app. Il est servi en HTTP(S) à la racine de
`update.nicotv.ovh` :

```
https://update.nicotv.ovh/version.json
https://update.nicotv.ovh/iptv-1.0.4.4.apk
```

L'app lit `version.json` (`UpdateManager.checkForUpdate()`) et propose la mise à
jour uniquement si `versionCode` distant est **strictement supérieur** à la
version installée. Concrètement : builder sans bumper `versionCode` produit un
APK utilisable pour un test manuel (sideload/adb), mais **ne déclenche aucune
MAJ OTA** sur les appareils déjà à jour — ne bumper que lorsque la version doit
réellement être diffusée à tous. On ne conserve que les **5 derniers** APKs
dans ce dossier.

### Déploiement

Sur le serveur de build actuel, `update.nicotv.ovh` est servi **directement
depuis cette même machine** (`/var/www/html/update/`) — pas de scp vers un
serveur distant. Le build est déclenché depuis la mini-app web
`/home/nicolas/apk` (boutons Git Pull / Build), qui pose un fichier trigger
lu par `apk-builder.sh` (`apk-builder.service` + `apk-builder.path`, root) :

1. `git pull` (si demandé) puis `./gradlew assembleRelease
   -PnicotvUpdateDir=/var/www/html/update`.
2. La tâche Gradle `publishReleaseToNicoUpdate` copie l'APK +
   `version.json` dans ce dossier (chown/chmod appliqués ensuite).
3. `apk-builder.sh` recopie ensuite ces deux fichiers dans `server/update/`
   du dépôt, ne garde que les **5 derniers** APKs, et **commit + push**
   automatiquement sur `claude/stable`.

Pour un déploiement vers un **autre** serveur (pas cette machine), la méthode
manuelle reste :
```bash
scp server/update/iptv-<version>.apk server/update/version.json \
    root@192.168.1.202:/var/www/html/update/
```
> Toujours déposer l'APK **avant** (ou en même temps que) le `version.json`,
> sinon l'app proposera une mise à jour dont le téléchargement échouera.

### Process de release (rappel)

1. Bumper `versionCode` / `versionName` dans `app/build.gradle.kts`.
2. `./gradlew assembleRelease` (ou bouton « Build » de `/home/nicolas/apk`,
   qui enchaîne aussi la publication + le commit/push — voir ci-dessus).
3. Si build manuel : copier l'APK dans `server/update/iptv-<versionName>.apk`,
   ne garder que les 5 derniers, mettre à jour `server/update/version.json`,
   commit + push sur `claude/stable`.

---

## Endpoints VPN (héritage)

Le module VPN WireGuard a été **retiré de l'application**. Les endpoints
`vpn_config` / `set_vpn_config` et la colonne `vpn_config` existent toujours
dans l'API (compatibilité), mais ne sont plus utilisés par l'app.
