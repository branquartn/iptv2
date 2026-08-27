package com.nicotv.iptv2.util

import java.text.Normalizer

/** Retire les diacritiques (accents) pour une comparaison insensible aux accents :
 * chercher "leon" doit trouver "Léon". Utilisé partout où on compare une requête
 * de recherche à un titre/nom (MoviesViewModel, SeriesViewModel, LiveViewModel). */
fun String.foldAccents(): String =
    Normalizer.normalize(this, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
