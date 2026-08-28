package com.nicotv.iptv2.data

import android.content.Context
import coil.Coil

/** Vide le cache Coil (mémoire + disque, cf. IptvApplication.newImageLoader)
 * — bouton "Vider le cache images" de l'écran Réglages. Les jaquettes/logos
 * se rechargeront simplement au prochain affichage. */
object ImageCacheUtil {
    fun clear(context: Context) {
        val loader = Coil.imageLoader(context)
        loader.memoryCache?.clear()
        loader.diskCache?.clear()
    }
}
