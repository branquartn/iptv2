package com.nicotv.iptv2.ui.common

import android.os.SystemClock
import android.view.KeyEvent
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Défilement accéléré de la sidebar catégories à la télécommande — demande
 * explicite 30/08/2026 : "si j'appuie 2 fois rapidement sur flèche du haut ou
 * bas je veux scroller plusieurs catégories d'un seul coup".
 *
 * Principe : deux appuis HAUT/BAS séparés de moins de [burstThresholdMs]
 * comptent comme une rafale — à partir du deuxième, chaque appui déplace le
 * focus de [jump] lignes au lieu d'une. Dès qu'on ralentit (ou qu'on change de
 * direction), on repart au pas à pas, donc la navigation fine reste possible.
 *
 * ⚠️ On ne s'appuie PAS sur `KeyEvent.getRepeatCount()` (touche maintenue) :
 * l'utilisateur a décrit des appuis répétés, pas un appui long — et sur
 * beaucoup de télécommandes Android TV la répétition matérielle n'arrive
 * jamais. D'où la mesure explicite du temps entre deux `ACTION_DOWN`.
 *
 * ⚠️ Le focus D-pad n'existe que sur une vue réellement attachée : après un
 * saut, la ligne visée peut ne pas encore avoir de `ViewHolder` (hors écran).
 * On force donc `scrollToPositionWithOffset` puis on demande le focus au
 * prochain passage de layout (`post`) — même contrainte que
 * LiveActivity.focusCategoryWhenReady.
 */
class CategoryFastScroll(
    private val recyclerView: RecyclerView,
    private val burstThresholdMs: Long = 300L,
    private val jump: Int = 5
) {
    private var lastKeyCode = 0
    private var lastKeyTime = 0L

    /**
     * À appeler depuis `Activity.dispatchKeyEvent`.
     * @return true si l'évènement a été consommé (saut effectué) — l'appelant
     *   doit alors NE PAS le transmettre, sinon le focus bougerait deux fois.
     */
    fun handleKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        val code = event.keyCode
        if (code != KeyEvent.KEYCODE_DPAD_UP && code != KeyEvent.KEYCODE_DPAD_DOWN) return false

        // Uniquement quand le focus est DANS la sidebar : ailleurs (mur
        // d'affiches, champ de recherche...), on ne touche à rien.
        val focused = recyclerView.findFocus() ?: return false
        val holder = recyclerView.findContainingViewHolder(focused) ?: return false

        val now = SystemClock.uptimeMillis()
        val isBurst = code == lastKeyCode && now - lastKeyTime < burstThresholdMs
        lastKeyCode = code
        lastKeyTime = now
        if (!isBurst) return false // premier appui (ou trop lent) : pas à pas

        val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return false
        val current = holder.bindingAdapterPosition
        if (current == RecyclerView.NO_POSITION) return false

        val count = recyclerView.adapter?.itemCount ?: return false
        val delta = if (code == KeyEvent.KEYCODE_DPAD_DOWN) jump else -jump
        val target = (current + delta).coerceIn(0, count - 1)
        // Déjà au bout : on laisse passer l'évènement, sinon la télécommande
        // paraîtrait bloquée en bas/haut de liste.
        if (target == current) return false

        layoutManager.scrollToPositionWithOffset(target, 0)
        recyclerView.post {
            recyclerView.findViewHolderForAdapterPosition(target)?.itemView?.requestFocus()
        }
        return true
    }
}
