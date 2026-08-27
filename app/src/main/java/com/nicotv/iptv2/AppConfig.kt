package com.nicotv.iptv2

object AppConfig {
    object Update {
        // /update/ sur iptv2.nicotv.ovh : le reste du domaine (panel de build)
        // est protégé par Cloudflare Access, ce chemin est en bypass explicite
        // pour rester accessible à l'app sans authentification interactive.
        const val VERSION_URL = "https://iptv2.nicotv.ovh/update/version.json"
    }
}
