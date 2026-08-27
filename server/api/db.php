<?php
// Accès à la base SQLite + initialisation (création des tables, compte admin).

require_once __DIR__ . '/config.php';

function db(): PDO {
    static $pdo = null;
    if ($pdo !== null) return $pdo;

    $dir = dirname(DB_PATH);
    if (!is_dir($dir)) {
        mkdir($dir, 0700, true);
    }

    $pdo = new PDO('sqlite:' . DB_PATH);
    $pdo->setAttribute(PDO::ATTR_ERRMODE, PDO::ERRMODE_EXCEPTION);
    $pdo->exec('PRAGMA journal_mode = WAL');

    $pdo->exec('
        CREATE TABLE IF NOT EXISTS users (
            id            INTEGER PRIMARY KEY AUTOINCREMENT,
            username      TEXT UNIQUE NOT NULL,
            password_hash TEXT NOT NULL,
            is_admin      INTEGER NOT NULL DEFAULT 0,
            created_at    INTEGER NOT NULL
        )
    ');

    // Journal des tentatives de connexion échouées (anti force brute).
    $pdo->exec('
        CREATE TABLE IF NOT EXISTS login_attempts (
            ip        TEXT NOT NULL,
            username  TEXT NOT NULL,
            attempted INTEGER NOT NULL
        )
    ');

    // Migration : ajoute la colonne vpn_config si elle n'existe pas encore.
    $cols = $pdo->query('PRAGMA table_info(users)')->fetchAll(PDO::FETCH_ASSOC);
    $hasVpn = false;
    foreach ($cols as $c) {
        if ($c['name'] === 'vpn_config') { $hasVpn = true; break; }
    }
    if (!$hasVpn) {
        $pdo->exec('ALTER TABLE users ADD COLUMN vpn_config TEXT');
    }

    // Crée le compte admin par défaut s'il n'existe aucun utilisateur.
    $count = (int) $pdo->query('SELECT COUNT(*) FROM users')->fetchColumn();
    if ($count === 0) {
        $stmt = $pdo->prepare('
            INSERT INTO users (username, password_hash, is_admin, created_at)
            VALUES (?, ?, 1, ?)
        ');
        $stmt->execute([
            DEFAULT_ADMIN_USER,
            password_hash(DEFAULT_ADMIN_PASS, PASSWORD_DEFAULT),
            time(),
        ]);
    }

    return $pdo;
}
