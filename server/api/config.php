<?php
// Configuration du backend d'authentification NicoTV.
// ⚠️ Change ABSOLUMENT TOKEN_SECRET par une longue chaîne aléatoire à toi.
//    Exemple pour en générer une : php -r "echo bin2hex(random_bytes(32));"

// Clé secrète servant à signer les jetons de session. NE PAS partager.
define('TOKEN_SECRET', 'CHANGE_MOI_avec_une_longue_chaine_aleatoire_64_caracteres_minimum');

// Durée de validité d'un jeton (en secondes). 30 jours par défaut.
define('TOKEN_TTL', 30 * 24 * 60 * 60);

// Emplacement de la base SQLite (hors racine web de préférence).
define('DB_PATH', __DIR__ . '/data/nicotv.sqlite');

// Compte administrateur créé automatiquement au premier lancement.
define('DEFAULT_ADMIN_USER', 'admin');
define('DEFAULT_ADMIN_PASS', 'admin'); // à changer dès la première connexion
