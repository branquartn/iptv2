package com.nicotv.iptv2.ui.setup

import android.graphics.drawable.GradientDrawable
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.nicotv.iptv2.R
import com.nicotv.iptv2.data.SourceType
import com.nicotv.iptv2.data.database.entity.PlaylistProfileEntity
import com.nicotv.iptv2.data.xtream.XtAccountInfo
import com.nicotv.iptv2.data.xtream.formatDate
import com.nicotv.iptv2.databinding.ItemProfileBinding

/** Sélecteur de profils façon Netflix : cartes horizontales (avatar coloré +
 * nom), pas une liste de lignes. [activeProfileId] (id du profil actuellement
 * chargé, cf. PlaylistRepository.getActiveProfile) pilote l'anneau/badge actif
 * — mis à jour par SetupActivity à chaque changement de la liste des profils ;
 * comme ce n'est pas un champ de PlaylistProfileEntity, un changement de
 * [activeProfileId] seul ne serait pas détecté par le DiffUtil ci-dessous
 * (notifyDataSetChanged() explicite côté appelant dans ce cas). */
class ProfileAdapter(
    private val onClick: (PlaylistProfileEntity) -> Unit,
    private val onEdit: (PlaylistProfileEntity) -> Unit,
    private val onDelete: (PlaylistProfileEntity) -> Unit
) : ListAdapter<PlaylistProfileEntity, ProfileAdapter.VH>(DIFF) {

    var activeProfileId: Long? = null

    /** Infos d'abonnement Xtream par id de profil (cf.
     * PlaylistRepository.getXtreamAccount) — hors entité, même remarque que
     * [activeProfileId] : notifyDataSetChanged() côté appelant après MAJ. */
    var accountFor: (Long) -> XtAccountInfo? = { null }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemProfileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(private val b: ItemProfileBinding) : RecyclerView.ViewHolder(b.root) {
        init {
            b.root.setOnFocusChangeListener { _, hasFocus ->
                b.profileRowRing.visibility = if (hasFocus) View.VISIBLE else View.INVISIBLE
                if (hasFocus) b.profileRowRing.startAnim() else b.profileRowRing.stopAnim()
            }
        }

        fun bind(profile: PlaylistProfileEntity) {
            val context = b.root.context
            val isActive = profile.id == activeProfileId

            b.tvName.text = profile.name

            val typeLabel = when (profile.type) {
                SourceType.M3U_URL.name -> context.getString(R.string.setup_profile_type_m3u_url)
                SourceType.M3U_FILE.name -> context.getString(R.string.setup_profile_type_m3u_file)
                SourceType.XTREAM.name -> context.getString(R.string.setup_profile_type_xtream)
                SourceType.SERVER.name -> "Films et séries du serveur"
                else -> profile.type
            }
            val relativeUse = DateUtils.getRelativeTimeSpanString(
                profile.lastUsedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
            )
            if (isActive) {
                b.tvMeta.text = context.getString(R.string.setup_profile_active)
                b.tvMeta.setTextColor(ContextCompat.getColor(context, R.color.accent))
            } else {
                b.tvMeta.text = "$typeLabel · $relativeUse"
                b.tvMeta.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            }
            bindExpiry(profile)
            b.ivActiveRing.visibility = if (isActive) View.VISIBLE else View.GONE
            b.badgeActive.visibility = if (isActive) View.VISIBLE else View.GONE

            // Couleur d'avatar stable par profil (comme un sélecteur Netflix) —
            // dérivée de l'id (toujours positif, autoIncrement Room), pas du
            // nom (deux profils peuvent partager un nom).
            val color = AVATAR_COLORS[(profile.id % AVATAR_COLORS.size).toInt()]
            (b.avatarCircle.background.mutate() as GradientDrawable).setColor(ContextCompat.getColor(context, color))

            b.ivTypeIcon.setImageResource(
                when (profile.type) {
                    SourceType.XTREAM.name -> R.drawable.ic_settings
                    SourceType.M3U_FILE.name -> R.drawable.ic_download
                    SourceType.SERVER.name -> R.drawable.ic_movies
                    else -> R.drawable.ic_refresh
                }
            )
            b.root.setOnClickListener { onClick(profile) }
            // Profils gérés par l'admin : ni modification ni suppression côté appli.
            b.btnEdit.visibility = android.view.View.GONE
            b.btnDelete.visibility = android.view.View.GONE
        }

        private fun bindExpiry(profile: PlaylistProfileEntity) {
            val context = b.root.context
            val info = if (profile.type == SourceType.XTREAM.name) accountFor(profile.id) else null
            if (info == null) {
                b.tvExpiry.visibility = View.GONE
                return
            }
            b.tvExpiry.visibility = View.VISIBLE
            val date = info.expDateMs?.let { formatDate(it) }
            b.tvExpiry.text = when {
                info.isExpired -> context.getString(R.string.xtream_expired_short)
                date == null -> context.getString(R.string.xtream_unlimited)
                else -> context.getString(R.string.xtream_expires_short, date)
            }
            b.tvExpiry.setTextColor(
                ContextCompat.getColor(
                    context,
                    when {
                        info.isExpired -> R.color.error
                        info.isExpiringSoon -> R.color.favorite_yellow
                        else -> R.color.text_secondary
                    }
                )
            )
        }
    }

    companion object {
        // bg_avatar_circle.xml est un <shape> (GradientDrawable) : setColor()
        // fonctionne directement dessus, pas besoin de ColorStateList/tint.
        private val AVATAR_COLORS = listOf(
            R.color.accent, R.color.rating_color, R.color.success,
            R.color.error, R.color.favorite_yellow, R.color.accent_dark
        )

        private val DIFF = object : DiffUtil.ItemCallback<PlaylistProfileEntity>() {
            override fun areItemsTheSame(a: PlaylistProfileEntity, b: PlaylistProfileEntity) = a.id == b.id
            override fun areContentsTheSame(a: PlaylistProfileEntity, b: PlaylistProfileEntity) = a == b
        }
    }
}
