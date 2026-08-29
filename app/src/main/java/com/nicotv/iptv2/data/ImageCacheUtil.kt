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

    /** Taille actuelle du cache disque, formatée pour l'affichage Réglages
     * (29/08/2026, demande explicite "voir aussi la taille") — avant, seul le
     * plafond configuré (300 Mo) était affiché en dur dans le texte, jamais
     * l'usage réel. `DiskCache.size` (Coil) : octets déjà occupés, pas le
     * plafond (`maxSize`). */
    fun diskCacheSizeLabel(context: Context): String {
        val bytes = Coil.imageLoader(context).diskCache?.size ?: 0L
        val mb = bytes / (1024.0 * 1024.0)
        return "%.0f Mo".format(mb)
    }
}
