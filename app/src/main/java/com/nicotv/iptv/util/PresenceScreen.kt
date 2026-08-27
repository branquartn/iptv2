package com.nicotv.iptv.util

/** Écran actuellement affiché (accueil, films, fiche détail…), lu par le heartbeat
 *  d'app périodique (cf. IptvApplication.reportScreen/startAppHeartbeat) — pendant
 *  Android de State.view/screenLabel() côté PWA. */
object PresenceScreen {
    @Volatile var label: String = "Accueil"
}
