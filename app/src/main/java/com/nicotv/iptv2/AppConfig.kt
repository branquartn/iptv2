package com.nicotv.iptv2

object AppConfig {
    object Update {
        // /update/ sur iptv2.nicotv.ovh : le reste du domaine (panel de build)
        // est protégé par Cloudflare Access, ce chemin est en bypass explicite
        // pour rester accessible à l'app sans authentification interactive.
        const val VERSION_URL = "https://iptv2.nicotv.ovh/update/version.json"
    }

    object Tmdb {
        // Même clé/compte que NicoTV (obtenir une clé gratuite sur
        // https://www.themoviedb.org/settings/api) — utilisée uniquement en
        // secours pour les films/séries dont le M3U/Xtream ne fournit pas de
        // jaquette (cf. PlaylistRepository.enrichArtwork).
        const val API_KEY = "be621d27c02423535518d21ff252ca0c"
        const val BASE_URL = "https://api.themoviedb.org/3/"
        const val IMAGE_BASE_W500 = "https://image.tmdb.org/t/p/w500"
        const val IMAGE_BASE_W780 = "https://image.tmdb.org/t/p/w780"
        const val LANGUAGE = "fr-FR"
    }
}
