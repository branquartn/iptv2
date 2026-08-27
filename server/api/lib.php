<?php
// Fonctions utilitaires : réponses JSON, jetons signés, authentification.

require_once __DIR__ . '/config.php';
require_once __DIR__ . '/db.php';

/** Envoie une réponse JSON et termine le script. */
function json_response($data, int $status = 200): void {
    http_response_code($status);
    header('Content-Type: application/json; charset=utf-8');
    echo json_encode($data, JSON_UNESCAPED_UNICODE);
    exit;
}

function json_error(string $message, int $status = 400): void {
    json_response(['ok' => false, 'error' => $message], $status);
}

/** Lit le corps JSON de la requête. */
function read_json_body(): array {
    $raw = file_get_contents('php://input');
    $data = json_decode($raw, true);
    return is_array($data) ? $data : [];
}

function base64url_encode(string $s): string {
    return rtrim(strtr(base64_encode($s), '+/', '-_'), '=');
}

function base64url_decode(string $s): string {
    return base64_decode(strtr($s, '-_', '+/'));
}

/**
 * Crée un jeton signé : base64(payload).hmac
 * payload = { uid, username, is_admin, exp }
 */
function make_token(array $user): string {
    $payload = [
        'uid'      => (int) $user['id'],
        'username' => $user['username'],
        'is_admin' => (int) $user['is_admin'],
        'exp'      => time() + TOKEN_TTL,
    ];
    $body = base64url_encode(json_encode($payload));
    $sig  = base64url_encode(hash_hmac('sha256', $body, TOKEN_SECRET, true));
    return $body . '.' . $sig;
}

/** Vérifie un jeton et renvoie son payload, ou null si invalide/expiré. */
function verify_token(?string $token): ?array {
    if (!$token) return null;
    $parts = explode('.', $token);
    if (count($parts) !== 2) return null;
    [$body, $sig] = $parts;
    $expected = base64url_encode(hash_hmac('sha256', $body, TOKEN_SECRET, true));
    if (!hash_equals($expected, $sig)) return null;
    $payload = json_decode(base64url_decode($body), true);
    if (!is_array($payload) || ($payload['exp'] ?? 0) < time()) return null;
    return $payload;
}

/** Récupère le jeton depuis l'en-tête Authorization: Bearer xxx. */
function bearer_token(): ?string {
    $headers = function_exists('getallheaders') ? getallheaders() : [];
    foreach ($headers as $k => $v) {
        if (strcasecmp($k, 'Authorization') === 0 && stripos($v, 'Bearer ') === 0) {
            return trim(substr($v, 7));
        }
    }
    return null;
}

/** Exige un utilisateur connecté ; renvoie son payload ou 401. */
function require_auth(): array {
    $payload = verify_token(bearer_token());
    if (!$payload) json_error('Non authentifié', 401);
    return $payload;
}

/** Exige un administrateur ; renvoie son payload ou 403. */
function require_admin(): array {
    $payload = require_auth();
    if ((int) ($payload['is_admin'] ?? 0) !== 1) json_error('Accès réservé à l\'administrateur', 403);
    return $payload;
}

function user_to_public(array $row): array {
    $cfg = isset($row['vpn_config']) ? (string) $row['vpn_config'] : '';
    return [
        'id'         => (int) $row['id'],
        'username'   => $row['username'],
        'is_admin'   => (int) $row['is_admin'] === 1,
        'has_vpn'    => trim($cfg) !== '',
        'vpn_config' => $cfg,
    ];
}
