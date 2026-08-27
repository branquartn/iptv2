<?php
// Routeur unique de l'API d'authentification NicoTV.
//
// Endpoints (tous en JSON) :
//   POST ?action=login            { username, password }            → { ok, token, user }
//   GET  ?action=me               (Bearer)                          → { ok, user }
//   POST ?action=change_password  (Bearer) { old, new }             → { ok }
//   GET  ?action=users            (Bearer admin)                    → { ok, users[] }
//   POST ?action=create_user      (Bearer admin) { username, password, is_admin } → { ok, user }
//   POST ?action=reset_password   (Bearer admin) { id, password }   → { ok }
//   POST ?action=delete_user      (Bearer admin) { id }             → { ok }
//   GET  ?action=vpn_config       (Bearer)                          → { ok, config }
//   POST ?action=set_vpn_config   (Bearer admin) { id, config }     → { ok }
//
// Déploiement : copier le dossier server/api/ dans la racine web du serveur,
// p.ex. //192.168.1.202/Web/api/ → http://192.168.1.202/api/

require_once __DIR__ . '/lib.php';

// Anti force brute sur le login : nombre d'échecs tolérés par IP ou identifiant
// dans la fenêtre glissante, avant un refus temporaire (HTTP 429).
const LOGIN_MAX_ATTEMPTS = 8;
const LOGIN_WINDOW_S     = 900; // 15 minutes

header('Access-Control-Allow-Origin: *');
header('Access-Control-Allow-Headers: Authorization, Content-Type');
header('Access-Control-Allow-Methods: GET, POST, OPTIONS');
if ($_SERVER['REQUEST_METHOD'] === 'OPTIONS') { http_response_code(204); exit; }

$action = $_GET['action'] ?? '';

switch ($action) {

    case 'login': {
        $body = read_json_body();
        $username = trim($body['username'] ?? '');
        $password = (string) ($body['password'] ?? '');
        if ($username === '' || $password === '') json_error('Identifiant ou mot de passe manquant');

        // Anti force brute : on purge la fenêtre expirée puis on refuse si le
        // quota d'échecs est atteint pour cette IP ou cet identifiant.
        $ip    = $_SERVER['REMOTE_ADDR'] ?? '';
        $since = time() - LOGIN_WINDOW_S;
        $pdo   = db();
        $pdo->prepare('DELETE FROM login_attempts WHERE attempted < ?')->execute([$since]);
        $cnt = $pdo->prepare('SELECT COUNT(*) FROM login_attempts WHERE ip = ? OR username = ?');
        $cnt->execute([$ip, $username]);
        if ((int) $cnt->fetchColumn() >= LOGIN_MAX_ATTEMPTS) {
            json_error('Trop de tentatives. Réessayez dans quelques minutes.', 429);
        }

        $stmt = $pdo->prepare('SELECT * FROM users WHERE username = ?');
        $stmt->execute([$username]);
        $user = $stmt->fetch(PDO::FETCH_ASSOC);

        if (!$user || !password_verify($password, $user['password_hash'])) {
            $log = $pdo->prepare('INSERT INTO login_attempts (ip, username, attempted) VALUES (?, ?, ?)');
            $log->execute([$ip, $username, time()]);
            json_error('Identifiants incorrects', 401);
        }
        // Connexion réussie : on efface les échecs liés à cette IP / cet identifiant.
        $pdo->prepare('DELETE FROM login_attempts WHERE ip = ? OR username = ?')->execute([$ip, $username]);
        json_response([
            'ok'    => true,
            'token' => make_token($user),
            'user'  => user_to_public($user),
        ]);
    }

    case 'me': {
        $payload = require_auth();
        json_response(['ok' => true, 'user' => [
            'id' => $payload['uid'], 'username' => $payload['username'],
            'is_admin' => (int) $payload['is_admin'] === 1,
        ]]);
    }

    case 'change_password': {
        $payload = require_auth();
        $body = read_json_body();
        $old = (string) ($body['old'] ?? '');
        $new = (string) ($body['new'] ?? '');
        if (strlen($new) < 4) json_error('Le nouveau mot de passe doit faire au moins 4 caractères');

        $stmt = db()->prepare('SELECT * FROM users WHERE id = ?');
        $stmt->execute([$payload['uid']]);
        $user = $stmt->fetch(PDO::FETCH_ASSOC);
        if (!$user || !password_verify($old, $user['password_hash'])) {
            json_error('Ancien mot de passe incorrect', 403);
        }
        $upd = db()->prepare('UPDATE users SET password_hash = ? WHERE id = ?');
        $upd->execute([password_hash($new, PASSWORD_DEFAULT), $payload['uid']]);
        json_response(['ok' => true]);
    }

    case 'users': {
        require_admin();
        $rows = db()->query('SELECT * FROM users ORDER BY username')->fetchAll(PDO::FETCH_ASSOC);
        json_response(['ok' => true, 'users' => array_map('user_to_public', $rows)]);
    }

    case 'create_user': {
        require_admin();
        $body = read_json_body();
        $username = trim($body['username'] ?? '');
        $password = (string) ($body['password'] ?? '');
        $isAdmin  = !empty($body['is_admin']) ? 1 : 0;
        if ($username === '' || strlen($password) < 4) {
            json_error('Identifiant requis et mot de passe d\'au moins 4 caractères');
        }
        try {
            $stmt = db()->prepare('
                INSERT INTO users (username, password_hash, is_admin, created_at)
                VALUES (?, ?, ?, ?)
            ');
            $stmt->execute([$username, password_hash($password, PASSWORD_DEFAULT), $isAdmin, time()]);
        } catch (PDOException $e) {
            json_error('Cet identifiant existe déjà', 409);
        }
        $id = (int) db()->lastInsertId();
        json_response(['ok' => true, 'user' => [
            'id' => $id, 'username' => $username, 'is_admin' => $isAdmin === 1,
        ]]);
    }

    case 'reset_password': {
        require_admin();
        $body = read_json_body();
        $id  = (int) ($body['id'] ?? 0);
        $new = (string) ($body['password'] ?? '');
        if ($id <= 0 || strlen($new) < 4) json_error('Identifiant utilisateur et mot de passe (4+ caractères) requis');
        $stmt = db()->prepare('UPDATE users SET password_hash = ? WHERE id = ?');
        $stmt->execute([password_hash($new, PASSWORD_DEFAULT), $id]);
        json_response(['ok' => true]);
    }

    case 'delete_user': {
        $admin = require_admin();
        $body = read_json_body();
        $id = (int) ($body['id'] ?? 0);
        if ($id <= 0) json_error('Identifiant utilisateur requis');
        if ($id === (int) $admin['uid']) json_error('Vous ne pouvez pas supprimer votre propre compte', 400);
        // Le compte admin est protégé : il ne peut jamais être supprimé.
        $target = db()->prepare('SELECT username FROM users WHERE id = ?');
        $target->execute([$id]);
        $targetName = $target->fetchColumn();
        if ($targetName === 'admin') json_error("Le compte admin ne peut pas être supprimé", 400);
        $stmt = db()->prepare('DELETE FROM users WHERE id = ?');
        $stmt->execute([$id]);
        json_response(['ok' => true]);
    }

    case 'vpn_config': {
        // L'utilisateur récupère sa propre configuration WireGuard.
        $payload = require_auth();
        $stmt = db()->prepare('SELECT vpn_config FROM users WHERE id = ?');
        $stmt->execute([$payload['uid']]);
        $cfg = (string) ($stmt->fetchColumn() ?: '');
        json_response(['ok' => true, 'config' => $cfg]);
    }

    case 'set_vpn_config': {
        // L'admin définit la configuration WireGuard d'un utilisateur.
        require_admin();
        $body = read_json_body();
        $id  = (int) ($body['id'] ?? 0);
        $cfg = (string) ($body['config'] ?? '');
        if ($id <= 0) json_error('Identifiant utilisateur requis');
        $stmt = db()->prepare('UPDATE users SET vpn_config = ? WHERE id = ?');
        $stmt->execute([$cfg, $id]);
        json_response(['ok' => true]);
    }

    default:
        json_error('Action inconnue', 404);
}
