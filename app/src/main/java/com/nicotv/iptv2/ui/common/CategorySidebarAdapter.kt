package com.nicotv.iptv2.ui.common

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.nicotv.iptv2.databinding.ItemCategorySidebarBinding

/** Sidebar de catégories (Chaînes/Films/Séries), comme IPTV Smarters Pro —
 * remplace les chips horizontales (ancien CategoryChipAdapter). Une seule
 * instance de layout partagée entre les 3 écrans, chacun garde sa propre
 * notion de catégories (tirées de son propre catalogue, cf.
 * LiveViewModel/MoviesViewModel/SeriesViewModel.categories). "Toutes" toujours
 * en premier (représenté par [ALL] en interne, exposé comme null côté
 * ViewModel). */
class CategorySidebarAdapter(
    private val allLabel: String,
    private val onSelect: (String?) -> Unit
) : ListAdapter<String, CategorySidebarAdapter.VH>(DIFF) {

    // null = "Toutes"
    private var selectedValue: String? = null

    /** Sélection courante. L'affecter simule un choix utilisateur : la
     * surbrillance suit ET [onSelect] est notifié (c'est le chemin du clic). */
    var selected: String?
        get() = selectedValue
        set(value) {
            val old = selectedValue
            selectedValue = value
            notifyItemRangeChanged(0, itemCount)
            if (old != value) onSelect(value)
        }

    /** Met à jour la surbrillance SANS notifier [onSelect] — pour refléter une
     * sélection décidée ailleurs que par un clic (catégorie ouverte par défaut
     * au lancement, cf. util.pickDefaultCategory, 30/08/2026). Passer par
     * `selected =` ici relancerait onSelect → ViewModel → observateur →
     * `selected =` : un aller-retour inutile, et un rechargement en double
     * pour une valeur déjà appliquée. */
    fun setSelectedSilently(value: String?) {
        selectedValue = value
        notifyItemRangeChanged(0, itemCount)
    }

    override fun submitList(list: List<String>?) {
        super.submitList(listOf(ALL) + list.orEmpty())
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemCategorySidebarBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(private val b: ItemCategorySidebarBinding) : RecyclerView.ViewHolder(b.root) {
        init {
            b.root.setOnFocusChangeListener { _, hasFocus ->
                b.categoryRing.visibility = if (hasFocus) View.VISIBLE else View.INVISIBLE
                if (hasFocus) b.categoryRing.startAnim() else b.categoryRing.stopAnim()
            }
        }

        fun bind(category: String) {
            val isAll = category == ALL
            b.tvCategory.text = if (isAll) allLabel else category
            b.tvCategory.isSelected = (isAll && selectedValue == null) || category == selectedValue
            b.root.setOnClickListener { selected = if (isAll) null else category }
        }
    }

    companion object {
        private const val ALL = "__all__"
        private val DIFF = object : DiffUtil.ItemCallback<String>() {
            override fun areItemsTheSame(a: String, b: String) = a == b
            override fun areContentsTheSame(a: String, b: String) = a == b
        }
    }
}
