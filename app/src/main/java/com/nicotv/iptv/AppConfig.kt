package com.nicotv.iptv

object AppConfig {

    object Auth {
        // Backend d'authentification PHP (voir dossier server/), exposé en HTTPS
        // via api.nicotv.ovh. Le routeur index.php utilise ?action=... pour le login,
        // la gestion des comptes.
        const val BASE_URL = "https://api.nicotv.ovh/"
    }

    object NicoTv {
        // API qui reçoit les demandes d'ajout depuis la recherche TMDb.
        // Le serveur décide où ranger le film/série selon le login transmis.
        const val BASE_URL = "https://api.nicotv.ovh/"
    }

    object Catalog {
        // API catalogue : library listing + stream URLs pour séries
        const val BASE_URL = "https://api.nicotv.ovh/"
    }
    object Update {
        // Hébergé sur update.nicotv.ovh (répertoire public), fichier version.json
        // et APK dans le même répertoire.
        const val VERSION_URL = "https://update.nicotv.ovh/version.json"
    }

    object Realtime {
        // Bus temps réel : hub WebSocket partagé par tous les sous-domaines NicoTV.
        // Authentifié par le MÊME jeton HMAC que l'API (token en query : <video>/WS ne
        // posent pas d'en-tête). Le serveur abonne la connexion à l'utilisateur du jeton
        // et pousse « iptv:add » (titre rangé) / « iptv:lib » (suppression/transfert/TMDb)
        // → l'app relance alors une synchro DB pour rafraîchir le catalogue en direct.
        const val WS_URL = "wss://ws.nicotv.ovh/"
    }

    object Tmdb {
        // Obtenir une clé gratuite sur https://www.themoviedb.org/settings/api
        const val API_KEY = "be621d27c02423535518d21ff252ca0c"
        const val BASE_URL = "https://api.themoviedb.org/3/"
        const val IMAGE_BASE_W500 = "https://image.tmdb.org/t/p/w500"
        const val IMAGE_BASE_W185 = "https://image.tmdb.org/t/p/w185"   // casting/acteur (petites vignettes)
        const val IMAGE_BASE_W780 = "https://image.tmdb.org/t/p/w780"   // aperçu film/série (backdrop)
        const val IMAGE_BASE_ORIGINAL = "https://image.tmdb.org/t/p/original"
        const val LANGUAGE = "fr-FR"
    }
}
