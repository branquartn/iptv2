package com.nicotv.iptv2

object AppConfig {
    object Update {
        // /update/ sur iptv2.nicotv.ovh : le reste du domaine (panel de build)
        // est protégé par Cloudflare Access, ce chemin est en bypass explicite
        // pour rester accessible à l'app sans authentification interactive.
        const val VERSION_URL = "https://iptv2.nicotv.ovh/update/version.json"
    }

    object Transcode {
        // Réutilise le même serveur/pipeline ffmpeg que NicoTV (cf. api/iptv.php,
        // actions pub_remux/pub_audiofmt) — pool de quota SÉPARÉ de NicoTV, ne peut
        // jamais priver la maison de sa propre capacité. Phase de test explicite :
        // pas de compte, identité par DeviceId (util/DeviceId.kt) uniquement.
        const val API_BASE = "https://api.nicotv.ovh/"
    }

    object Tmdb {
        // Même clé/compte que NicoTV (obtenir une clé gratuite sur
        // https://www.themoviedb.org/settings/api) — utilisée uniquement en
        // secours pour les films/séries dont le M3U/Xtream ne fournit pas de
        // jaquette (cf. PlaylistRepository.enrichArtwork).
        const val API_KEY = "be621d27c02423535518d21ff252ca0c"
        const val BASE_URL = "https://api.themoviedb.org/3/"
        const val IMAGE_BASE_W185 = "https://image.tmdb.org/t/p/w185"
        const val IMAGE_BASE_W500 = "https://image.tmdb.org/t/p/w500"
        const val IMAGE_BASE_W780 = "https://image.tmdb.org/t/p/w780"
        const val LANGUAGE = "fr-FR"
    }
}
