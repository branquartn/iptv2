package com.nicotv.iptv2.ui.chat

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.format.DateUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.nicotv.iptv2.R
import com.nicotv.iptv2.chat.ChatMessage
import com.nicotv.iptv2.databinding.ItemChatDayBinding
import com.nicotv.iptv2.databinding.ItemChatMessageBinding
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Ligne du fil : séparateur de date, bandeau « non lus » ou bulle. */
sealed class ChatRow {
    abstract val key: String
    data class Day(val label: String, val day: String) : ChatRow() { override val key get() = "d$day" }
    object Unread : ChatRow() { override val key get() = "unread" }
    data class Msg(val m: ChatMessage) : ChatRow() { override val key get() = "m${m.id}" }
}

/**
 * Bulles du tchat, même rendu que la PWA : bulle blanche à gauche (Nicolas), crème à droite
 * (moi) avec double coche, citation, « Transféré de », réaction, film partagé, vocal, fichier.
 */
class ChatAdapter(
    private val mediaUrl: (Long) -> String,
    private val onClick: (ChatMessage) -> Unit,
    private val onLongClick: (ChatMessage) -> Unit,
    private val onQuoteClick: (Long) -> Unit
) : ListAdapter<ChatRow, RecyclerView.ViewHolder>(DIFF) {

    /** Id du vocal en cours de lecture (⏸ au lieu de ▶), -1 si aucun. */
    var playingId: Long = -1L
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    class MsgVH(val b: ItemChatMessageBinding) : RecyclerView.ViewHolder(b.root)
    class DayVH(val b: ItemChatDayBinding) : RecyclerView.ViewHolder(b.root)
    class UnreadVH(v: View) : RecyclerView.ViewHolder(v)

    /** Transforme les messages en lignes (séparateurs de date + bandeau « non lus » avant le
     *  premier message REÇU depuis [lastRead], jamais devant les siens — comme la PWA). */
    fun submitMessages(list: List<ChatMessage>, lastRead: Long, commit: (() -> Unit)? = null) {
        val rows = ArrayList<ChatRow>(list.size + 8)
        var lastDay: String? = null
        var unreadDone = false
        val dayFmt = SimpleDateFormat("yyyyMMdd", Locale.FRANCE)
        val longFmt = SimpleDateFormat("d MMMM", Locale.FRANCE)
        for (m in list) {
            val d = Date(m.createdAt * 1000)
            val day = dayFmt.format(d)
            if (day != lastDay) {
                lastDay = day
                val label = when {
                    DateUtils.isToday(d.time) -> "Aujourd'hui"
                    isYesterday(d.time) -> "Hier"
                    else -> longFmt.format(d)
                }
                rows += ChatRow.Day(label, day)
            }
            if (!unreadDone && !m.isOut && lastRead > 0 && m.id > lastRead) {
                unreadDone = true
                rows += ChatRow.Unread
            }
            rows += ChatRow.Msg(m)
        }
        submitList(rows, commit)
    }

    private fun isYesterday(t: Long): Boolean {
        val c = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        val x = Calendar.getInstance().apply { timeInMillis = t }
        return c.get(Calendar.YEAR) == x.get(Calendar.YEAR) && c.get(Calendar.DAY_OF_YEAR) == x.get(Calendar.DAY_OF_YEAR)
    }

    override fun getItemViewType(position: Int) = when (getItem(position)) {
        is ChatRow.Day -> 1
        is ChatRow.Unread -> 2
        is ChatRow.Msg -> 0
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return when (viewType) {
            1 -> DayVH(ItemChatDayBinding.inflate(inf, parent, false))
            2 -> UnreadVH(inf.inflate(R.layout.item_chat_unread, parent, false))
            else -> MsgVH(ItemChatMessageBinding.inflate(inf, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is ChatRow.Day -> (holder as DayVH).b.tvDay.text = row.label
            is ChatRow.Unread -> Unit
            is ChatRow.Msg -> bindMessage((holder as MsgVH).b, row.m)
        }
    }

    private fun oval(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

    private fun bindMessage(b: ItemChatMessageBinding, m: ChatMessage) {
        val out = m.isOut
        val lp = b.bubble.layoutParams as LinearLayout.LayoutParams
        lp.gravity = if (out) Gravity.END else Gravity.START
        b.bubble.layoutParams = lp
        b.bubble.setBackgroundResource(if (out) R.drawable.bg_chat_out else R.drawable.bg_chat_in)

        // Une bulle ne dépasse jamais 80 % de l'écran (comme la PWA).
        val maxW = (b.root.resources.displayMetrics.widthPixels * 0.80f).toInt()
        b.tvText.maxWidth = maxW
        b.tvQText.maxWidth = maxW - 60

        val accent = if (out) Color.parseColor("#C9A227") else Color.parseColor("#3390EC")
        val btn = if (out) Color.parseColor("#E8A33D") else Color.parseColor("#3390EC")

        // En-tête « Transféré de … »
        if (m.fwdWho.isNullOrBlank()) b.tvFwd.visibility = View.GONE
        else { b.tvFwd.visibility = View.VISIBLE; b.tvFwd.text = "↪ Transféré de ${m.fwdWho}" }

        // Citation
        if (m.replyPreview.isNullOrBlank()) b.quote.visibility = View.GONE
        else {
            b.quote.visibility = View.VISIBLE
            b.quoteBar.setBackgroundColor(accent)
            b.tvQWho.text = m.replyWho.orEmpty()
            b.tvQText.text = m.replyPreview
            b.quote.setOnClickListener { m.replyToId?.let(onQuoteClick) }
        }

        // Contenu : un seul bloc visible selon le type
        b.ivImage.visibility = View.GONE
        b.voiceBox.visibility = View.GONE
        b.fileBox.visibility = View.GONE
        b.filmBox.visibility = View.GONE
        b.tvText.visibility = View.GONE
        when (m.type) {
            "image" -> {
                b.ivImage.visibility = View.VISIBLE
                b.ivImage.load(mediaUrl(m.id))
            }
            "voice" -> {
                b.voiceBox.visibility = View.VISIBLE
                b.ivVoicePlay.background = oval(btn)
                b.ivVoicePlay.setImageResource(if (playingId == m.id) R.drawable.ic_chat_pause else R.drawable.ic_chat_play)
                b.voiceBar.setBackgroundColor(Color.parseColor("#29000000"))
            }
            "file" -> {
                b.fileBox.visibility = View.VISIBLE
                b.tvFileName.text = m.mediaName ?: m.text.ifBlank { "Fichier" }
            }
            "film" -> {
                b.filmBox.visibility = View.VISIBLE
                val label = m.text.replace(Regex("^🎬\\s*"), "")
                val mt = Regex("^(.*?)\\s*\\((\\d{4})\\)\\s*$").find(label)
                b.tvFilmTitle.text = mt?.groupValues?.get(1) ?: label
                val yr = mt?.groupValues?.get(2).orEmpty()
                b.tvFilmYear.text = yr
                b.tvFilmYear.visibility = if (yr.isEmpty()) View.GONE else View.VISIBLE
                if (m.mediaName.isNullOrBlank()) b.ivFilmPoster.setImageDrawable(null)
                else b.ivFilmPoster.load("https://image.tmdb.org/t/p/w185${m.mediaName}")
            }
            else -> {
                b.tvText.visibility = View.VISIBLE
                b.tvText.text = m.text
            }
        }

        // Heure + double coche
        b.tvTime.text = formatTime(m.createdAt)
        b.tvTick.visibility = if (out) View.VISIBLE else View.GONE

        // Réaction
        if (m.reaction.isNullOrBlank()) b.tvReact.visibility = View.GONE
        else {
            b.tvReact.visibility = View.VISIBLE
            b.tvReact.text = m.reaction
            b.tvReact.setBackgroundResource(if (out) R.drawable.bg_chat_react_out else R.drawable.bg_chat_react_in)
        }

        b.bubble.setOnClickListener { onClick(m) }
        b.bubble.setOnLongClickListener { onLongClick(m); true }
    }

    private fun formatTime(seconds: Long): String =
        SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(seconds * 1000))

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ChatRow>() {
            override fun areItemsTheSame(a: ChatRow, b: ChatRow) = a.key == b.key
            override fun areContentsTheSame(a: ChatRow, b: ChatRow) = a == b
        }
    }
}
