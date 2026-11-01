package com.nicotv.iptv2.ui.chat

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import com.nicotv.iptv2.IptvApplication
import com.nicotv.iptv2.R
import com.nicotv.iptv2.chat.ChatMessage
import com.nicotv.iptv2.databinding.ActivityChatBinding
import com.nicotv.iptv2.ui.common.BaseActivity
import com.nicotv.iptv2.ui.common.isTvDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Tchat NicoTV ↔ Telegram (pendant de l'icône 💬 de la PWA) : fil privé avec Nicolas.
 * Texte, photos / fichiers, vocaux (envoi + lecture), modification et suppression de ses
 * messages. Temps réel via le bus WS (chat:*) avec un
 * sondage de secours toutes les 8 s tant que l'écran est visible.
 *
 * Même rendu que la PWA : papier peint, bulles blanche/crème, réponses, réactions, transfert,
 * recherche, séparateurs de date, bandeau « non lus », film partagé. Non porté : sélection multiple.
 */
class ChatActivity : BaseActivity() {

    private lateinit var binding: ActivityChatBinding
    private lateinit var adapter: ChatAdapter
    private val app get() = application as IptvApplication

    private var firstLoad = true
    private var sending = false
    private var editingId = 0L
    // Réponse en cours (citation) et état du fil / de la recherche.
    private var replyTo: ChatMessage? = null
    private var allMessages: List<ChatMessage> = emptyList()
    private var searchQuery = ""
    private var initialFocusDone = false
    // Dernier message lu à l'ouverture : sert au bandeau « Messages non lus ».
    private var lastReadAtOpen = 0L
    private val quickReactions = listOf("❤️", "👍", "👎", "🔥", "🥰", "👏", "😁")
    private val allEmojis = listOf("😀","😂","😍","🥰","👍","🙏","❤️","😢","😮","🎉","🔥","😅","🤔","😴","👋",
        "🥳","😡","👏","💪","🙌","😎","🤣","😘","🙄","😭","🤗","😱","🤩","👌","🫶")
    private var recorder: MediaRecorder? = null
    private var recordFile: File? = null
    private var player: MediaPlayer? = null

    private val pickFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) sendUri(uri)
    }
    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording() else toast("Autorisation du micro refusée")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Mode immersif (BaseActivity) : la fenêtre ne se redimensionne pas pour le clavier,
        // on remonte donc nous-mêmes la barre de saisie de la hauteur du clavier.
        ViewCompat.setOnApplyWindowInsetsListener(binding.chatRoot) { v, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(0, 0, 0, ime.bottom)
            insets
        }

        // Papier peint identique à la PWA (dégradé + motif doodle tuilé).
        val tile = android.graphics.BitmapFactory.decodeResource(resources, R.drawable.chat_pattern,
            android.graphics.BitmapFactory.Options().apply { inScaled = false })
        binding.chatRoot.background = ChatWallpaper(tile, resources.displayMetrics.density)
        lastReadAtOpen = app.chatApi.chatLastSeen().coerceAtLeast(0L)

        adapter = ChatAdapter(
            mediaUrl = { app.chatApi.chatMediaUrl(it) },
            onClick = { onBubbleClick(it) },
            onLongClick = { onBubbleLong(it) },
            onQuoteClick = { scrollToMessage(it) }
        )
        binding.rvChat.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.rvChat.adapter = adapter
        binding.rvChat.itemAnimator = null

        binding.btnChatBack.setOnClickListener { finish() }
        // Télécommande : Retour referme d'abord la recherche, la réponse ou la modification en cours, puis quitte le tchat.
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    binding.chatSearchBar.visibility == android.view.View.VISIBLE -> closeSearch()
                    replyTo != null -> setReply(null)
                    editingId > 0L -> cancelEdit(clearText = true)
                    else -> finish()
                }
            }
        })
        binding.btnChatSearch.setOnClickListener { openSearch() }
        binding.btnChatSearchClose.setOnClickListener { closeSearch() }
        binding.etChatSearch.doAfterTextChanged { searchQuery = it?.toString().orEmpty().trim(); render(scrollBottom = false) }
        binding.btnChatMenu.setOnClickListener {
            showMenu(listOf("🔍  Rechercher" to { openSearch() }, "🗑  Effacer l'historique" to { confirmClear() }))
        }
        binding.btnChatEmoji.setOnClickListener { showEmojiPicker() }
        binding.btnReplyCancel.setOnClickListener { setReply(null) }
        binding.btnChatJump.setOnClickListener { binding.rvChat.scrollToPosition(maxOf(adapter.itemCount - 1, 0)) }
        binding.rvChat.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                binding.btnChatJump.visibility = if (rv.canScrollVertically(1)) android.view.View.VISIBLE else android.view.View.GONE
            }
        })
        binding.btnChatAttach.setOnClickListener {
            if (recorder != null) stopRecording(send = false)
            showMenu(listOf(
                "🖼  Photo ou vidéo" to { pickFile.launch("image/*,video/*") },
                "📎  Fichier" to { pickFile.launch("*/*") },
                "🎬  Partager un film" to { showFilmShareDialog() }
            ))
        }
        binding.btnChatAction.setOnClickListener { onAction() }
        binding.tvEditBar.setOnClickListener { cancelEdit(clearText = true) }
        binding.etChat.doAfterTextChanged { updateActionIcon() }
        // Télécommande : un champ multiligne garde les flèches haut/bas pour le curseur → impossible d'en sortir.
        // On les redonne à la navigation (le curseur reste mobile au tactile / clavier physique via gauche-droite).
        binding.etChat.setOnKeyListener { v, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN &&
                (keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP || keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN)) {
                val dir = if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP) android.view.View.FOCUS_UP else android.view.View.FOCUS_DOWN
                v.focusSearch(dir)?.requestFocus()
                true
            } else false
        }
        updateActionIcon()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Temps réel (WebSocket) : nouveau message / réaction / modification → rechargement immédiat.
                launch { app.chatEvents.collect { reload() } }
                while (true) {
                    reload()
                    delay(30_000)   // filet si le WebSocket est coupé
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        stopPlayback()
        if (recorder != null) stopRecording(send = false)
    }

    // ------------------------------------------------------------ chargement

    private suspend fun reload() {
        val list = app.chatApi.chatHistory() ?: return
        allMessages = list
        val lm = binding.rvChat.layoutManager as LinearLayoutManager
        val atBottom = adapter.itemCount == 0 || lm.findLastVisibleItemPosition() >= adapter.itemCount - 2
        val scroll = firstLoad || atBottom
        firstLoad = false
        render(scrollBottom = scroll)
        list.maxOfOrNull { it.id }?.let { app.chatApi.chatMarkSeen(it) }
    }

    /** Applique le filtre de recherche puis affiche le fil. */
    private fun render(scrollBottom: Boolean) {
        val q = fold(searchQuery)
        val shown = if (q.isEmpty()) allMessages
            else allMessages.filter { fold(it.text).contains(q) || fold(it.mediaName.orEmpty()).contains(q) }
        binding.tvChatSearchCount.text = if (q.isEmpty()) "" else "${shown.size}/${allMessages.size}"
        adapter.submitMessages(shown, if (q.isEmpty()) lastReadAtOpen else 0L) {
            if (scrollBottom && adapter.itemCount > 0) binding.rvChat.scrollToPosition(adapter.itemCount - 1)
            // Télécommande : au premier affichage, le focus va sur le dernier message (sinon il reste sur « retour »).
            if (!initialFocusDone && adapter.itemCount > 0 && isTvDevice()) {
                initialFocusDone = true
                binding.rvChat.postDelayed({
                    val last = adapter.itemCount - 1
                    (binding.rvChat.findViewHolderForAdapterPosition(last) as? ChatAdapter.MsgVH)?.b?.bubble?.requestFocus()
                }, 350)
            }
        }
        binding.tvChatEmpty.text = if (q.isNotEmpty()) "Aucun résultat." else "Pas encore de message."
        binding.tvChatEmpty.visibility = if (shown.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun fold(s: String): String =
        java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    private fun openSearch() {
        binding.chatHeader.visibility = android.view.View.GONE
        binding.chatSearchBar.visibility = android.view.View.VISIBLE
        binding.etChatSearch.requestFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .showSoftInput(binding.etChatSearch, 0)
    }

    private fun closeSearch() {
        binding.etChatSearch.setText("")
        binding.chatSearchBar.visibility = android.view.View.GONE
        binding.chatHeader.visibility = android.view.View.VISIBLE
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .hideSoftInputFromWindow(binding.etChatSearch.windowToken, 0)
        render(scrollBottom = true)
    }

    private fun scrollToMessage(id: Long) {
        val pos = (0 until adapter.itemCount).firstOrNull { (adapter.currentList[it] as? ChatRow.Msg)?.m?.id == id }
        if (pos == null) { toast("Message introuvable (trop ancien)"); return }
        binding.rvChat.scrollToPosition(pos)
    }

    // ----------------------------------------------------------- réponse (citation)

    private fun setReply(m: ChatMessage?) {
        replyTo = m
        if (m == null) { binding.chatReplyBar.visibility = android.view.View.GONE; return }
        binding.chatReplyBar.visibility = android.view.View.VISIBLE
        binding.tvReplyWho.text = if (m.isOut) "Moi" else "Nicolas"
        binding.tvReplyText.text = previewOf(m)
        binding.etChat.requestFocus()
    }

    private fun previewOf(m: ChatMessage) = when (m.type) {
        "image" -> "📷 Photo"
        "voice" -> "🎤 Message vocal"
        "file" -> "📎 " + (m.mediaName ?: "Fichier")
        else -> m.text
    }

    // ----------------------------------------------------------------- envoi

    private fun onAction() {
        val text = binding.etChat.text.toString().trim()
        when {
            recorder != null -> stopRecording(send = true)
            text.isNotEmpty() -> submitText(text)
            else -> startRecordingFlow()
        }
    }

    private fun submitText(text: String) {
        if (sending) return
        sending = true
        val edit = editingId
        lifecycleScope.launch {
            val ok = if (edit > 0) app.chatApi.chatEdit(edit, text) else app.chatApi.chatSend(text, replyTo?.id ?: 0L)
            sending = false
            if (ok) {
                cancelEdit(clearText = true)
                setReply(null)
                reload()
                binding.rvChat.scrollToPosition(maxOf(adapter.itemCount - 1, 0))
            } else {
                toast("Échec de l'envoi")
            }
        }
    }

    private fun cancelEdit(clearText: Boolean) {
        editingId = 0L
        binding.tvEditBar.visibility = android.view.View.GONE
        if (clearText) binding.etChat.setText("")
    }

    private fun updateActionIcon() {
        val hasText = binding.etChat.text.isNotBlank()
        val recording = recorder != null
        binding.btnChatAction.setImageResource(if (hasText || recording) R.drawable.ic_chat_send else R.drawable.ic_chat_mic)
        // Envoi = bleu Telegram, micro = gris, enregistrement = rouge (comme la PWA).
        binding.btnChatAction.setColorFilter(Color.parseColor(
            if (recording) "#E94560" else if (hasText) "#3390EC" else "#707579"))
    }

    private fun sendUri(uri: Uri) {
        lifecycleScope.launch {
            val prepared = withContext(Dispatchers.IO) {
                runCatching {
                    var name = "fichier"
                    contentResolver.query(uri, null, null, null, null)?.use { c ->
                        val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (i >= 0 && c.moveToFirst()) name = c.getString(i) ?: name
                    }
                    val mime = contentResolver.getType(uri) ?: "application/octet-stream"
                    val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
                    val tmp = File(cacheDir, "up_${System.currentTimeMillis()}_$safe")
                    contentResolver.openInputStream(uri)!!.use { input ->
                        tmp.outputStream().use { input.copyTo(it) }
                    }
                    Triple(tmp, mime, name)
                }.getOrNull()
            }
            if (prepared == null) {
                toast("Fichier illisible")
                return@launch
            }
            sendFile(prepared.first, prepared.second, prepared.third, voice = false)
        }
    }

    private fun sendFile(file: File, mime: String, name: String, voice: Boolean) {
        if (file.length() > 20L * 1024 * 1024) {
            file.delete()
            toast("Fichier trop volumineux (max 20 Mo)")
            return
        }
        toast("Envoi…")
        lifecycleScope.launch {
            val ok = app.chatApi.chatSendMedia(file, mime, name, voice)
            file.delete()
            if (ok) {
                reload()
                binding.rvChat.scrollToPosition(maxOf(adapter.itemCount - 1, 0))
            } else {
                toast("Échec de l'envoi")
            }
        }
    }

    // ----------------------------------------------------------- vocal (envoi)

    private fun startRecordingFlow() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) startRecording() else askMic.launch(Manifest.permission.RECORD_AUDIO)
    }

    @Suppress("DEPRECATION")
    private fun startRecording() {
        val file = File(cacheDir, "vocal_${System.currentTimeMillis()}.m4a")
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(64_000)
            r.setAudioSamplingRate(44_100)
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            runCatching { r.release() }
            file.delete()
            toast("Micro indisponible")
            return
        }
        stopPlayback()
        recorder = r
        recordFile = file
        binding.etChat.hint = "Enregistrement… appuie sur envoyer"
        updateActionIcon()
    }

    private fun stopRecording(send: Boolean) {
        val r = recorder ?: return
        val f = recordFile
        recorder = null
        recordFile = null
        // stop() lève une exception si l'enregistrement est trop court : on le traite comme annulé.
        val stopped = runCatching { r.stop() }.isSuccess
        runCatching { r.release() }
        binding.etChat.hint = "Message…"
        updateActionIcon()
        if (f == null) return
        if (!send) {
            f.delete()
            return
        }
        if (!stopped || f.length() < 1000) {
            f.delete()
            toast("Vocal trop court")
            return
        }
        sendFile(f, "audio/mp4", f.name, voice = true)
    }

    // ---------------------------------------------------------- partage d'un film

    /** Recherche TMDb (films) puis envoi d'une bulle « 🎬 Titre (année) » avec jaquette. */
    private fun showFilmShareDialog() {
        val et = android.widget.EditText(this).apply {
            hint = "Titre du film"; setSingleLine(true)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        }
        val list = android.widget.ListView(this)
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(24, 8, 24, 8)
            addView(et); addView(list, android.widget.LinearLayout.LayoutParams(-1, 600))
        }
        val dlg = AlertDialog.Builder(this).setTitle("Partager un film").setView(box)
            .setNegativeButton(android.R.string.cancel, null).create()
        var results: List<com.nicotv.iptv2.data.tmdb.TmdbWork> = emptyList()
        fun search() {
            val q = et.text.toString().trim()
            if (q.isBlank()) return
            lifecycleScope.launch {
                results = app.playlistRepository.searchMoviesForChat(q)
                list.adapter = android.widget.ArrayAdapter(this@ChatActivity, android.R.layout.simple_list_item_1,
                    results.map { r -> r.title + (r.year.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "") })
                if (results.isEmpty()) toast("Aucun film trouvé")
            }
        }
        et.setOnEditorActionListener { _, _, _ -> search(); true }
        list.setOnItemClickListener { _, _, pos, _ ->
            val r = results.getOrNull(pos) ?: return@setOnItemClickListener
            dlg.dismiss()
            lifecycleScope.launch {
                val ok = app.chatApi.chatSendFilm(r.id, r.title, r.year, r.posterPath)
                if (!ok) toast("Envoi impossible") else reload()
            }
        }
        dlg.show()
    }

    // ---------------------------------------------------------- actions sur bulle

    private fun onBubbleClick(m: ChatMessage) {
        when (m.type) {
            "voice" -> togglePlay(m)
            "image" -> showImage(app.chatApi.chatMediaUrl(m.id))
            "file" -> runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(app.chatApi.chatMediaUrl(m.id))))
            }.onFailure { toast("Aucune application pour ouvrir ce fichier") }
        }
    }

    private fun showImage(url: String) {
        val iv = ImageView(this).apply {
            adjustViewBounds = true
            setBackgroundColor(Color.BLACK)
        }
        iv.load(url)
        AlertDialog.Builder(this).setView(iv).setPositiveButton("Fermer", null).show()
    }

    /** Appui long : barre de réactions rapide + menu (Répondre, Modifier, Copier, Transférer, Supprimer). */
    private fun onBubbleLong(m: ChatMessage) {
        val dp = resources.displayMetrics.density
        val dlg = android.app.Dialog(this)
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val col = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt())
        }
        // Barre de réactions
        val bar = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setBackgroundResource(R.drawable.bg_chat_menu)
            setPadding((8 * dp).toInt(), (6 * dp).toInt(), (8 * dp).toInt(), (6 * dp).toInt())
        }
        fun react(e: String) { dlg.dismiss(); lifecycleScope.launch { if (app.chatApi.chatReact(m.id, e)) reload() else toast("Réaction impossible") } }
        quickReactions.forEach { e ->
            bar.addView(android.widget.TextView(this).apply {
                text = e; textSize = 24f; gravity = android.view.Gravity.CENTER
                setPadding((7 * dp).toInt(), (4 * dp).toInt(), (7 * dp).toInt(), (4 * dp).toInt())
                isFocusable = true; isClickable = true
                if (m.reaction == e) setBackgroundResource(R.drawable.bg_chat_react_in)
                setOnClickListener { react(e) }
            })
        }
        col.addView(bar)
        // Menu
        val menu = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_chat_menu)
        }
        fun item(icon: Int, label: String, danger: Boolean = false, act: () -> Unit) {
            menu.addView(android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (12 * dp).toInt())
                isFocusable = true; isClickable = true
                setBackgroundResource(android.R.drawable.list_selector_background)
                addView(android.widget.ImageView(this@ChatActivity).apply {
                    setImageResource(icon); setColorFilter(Color.parseColor(if (danger) "#EF4444" else "#707579"))
                    layoutParams = android.widget.LinearLayout.LayoutParams((20 * dp).toInt(), (20 * dp).toInt())
                })
                addView(android.widget.TextView(this@ChatActivity).apply {
                    text = label; textSize = 15f
                    setTextColor(Color.parseColor(if (danger) "#EF4444" else "#111318"))
                    setPadding((14 * dp).toInt(), 0, 0, 0)
                })
                setOnClickListener { dlg.dismiss(); act() }
            })
        }
        item(R.drawable.ic_chat_reply, "Répondre") { setReply(m) }
        if (m.isOut && m.type == "text") item(R.drawable.ic_pwa_edit, "Modifier") {
            editingId = m.id
            binding.tvEditBar.visibility = android.view.View.VISIBLE
            binding.etChat.setText(m.text)
            binding.etChat.setSelection(binding.etChat.text.length)
            binding.etChat.requestFocus()
        }
        if (m.type == "text") item(R.drawable.ic_chat_copy, "Copier") {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("message", m.text)); toast("Copié")
        }
        item(R.drawable.ic_chat_forward, "Transférer") {
            lifecycleScope.launch { if (app.chatApi.chatForward(m.id)) reload() else toast("Transfert impossible") }
        }
        item(R.drawable.ic_chat_close, "Supprimer", danger = true) { deleteMessage(m) }
        col.addView(menu, android.widget.LinearLayout.LayoutParams(-1, -2).apply { topMargin = (8 * dp).toInt() })
        dlg.setContentView(col)
        dlg.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout((300 * dp).toInt().coerceAtMost(resources.displayMetrics.widthPixels - (24 * dp).toInt()), -2)
        }
        dlg.show()
    }

    /** Petit menu de pastilles blanches (⋮, Joindre) — même style que le menu contextuel. */
    private fun showMenu(entries: List<Pair<String, () -> Unit>>) {
        val dp = resources.displayMetrics.density
        val dlg = android.app.Dialog(this)
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val menu = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_chat_menu)
        }
        entries.forEach { (label, act) ->
            menu.addView(android.widget.TextView(this).apply {
                text = label; textSize = 15f; setTextColor(Color.parseColor("#111318"))
                setPadding((18 * dp).toInt(), (13 * dp).toInt(), (18 * dp).toInt(), (13 * dp).toInt())
                isFocusable = true; isClickable = true
                setBackgroundResource(android.R.drawable.list_selector_background)
                setOnClickListener { dlg.dismiss(); act() }
            })
        }
        dlg.setContentView(menu)
        dlg.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout((240 * dp).toInt(), -2)
        }
        dlg.show()
    }

    /** Sélecteur d'emojis : grille de 30 emojis ; l'emoji est inséré à la position du curseur. */
    private fun showEmojiPicker() {
        val dp = resources.displayMetrics.density
        val dlg = android.app.Dialog(this)
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val grid = android.widget.GridLayout(this).apply {
            columnCount = 6
            setBackgroundResource(R.drawable.bg_chat_menu)
            setPadding((8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt())
        }
        allEmojis.forEach { e ->
            grid.addView(android.widget.TextView(this).apply {
                text = e; textSize = 24f; gravity = android.view.Gravity.CENTER
                setPadding((9 * dp).toInt(), (7 * dp).toInt(), (9 * dp).toInt(), (7 * dp).toInt())
                isFocusable = true; isClickable = true
                setBackgroundResource(android.R.drawable.list_selector_background)
                setOnClickListener {
                    val et = binding.etChat
                    val start = et.selectionStart.coerceAtLeast(0)
                    et.text.insert(start, e)
                }
            })
        }
        dlg.setContentView(grid)
        dlg.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setGravity(android.view.Gravity.BOTTOM or android.view.Gravity.START)
            attributes = attributes.apply { y = (66 * dp).toInt(); x = (8 * dp).toInt() }
            setLayout(-2, -2)
        }
        dlg.show()
    }

    private fun deleteMessage(m: ChatMessage) {
        lifecycleScope.launch {
            if (app.chatApi.chatDelete(listOf(m.id))) {
                if (editingId == m.id) cancelEdit(clearText = true)
                reload()
            } else {
                toast("Échec de la suppression")
            }
        }
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("Vider la conversation ?")
            .setMessage("Tous les messages de ce fil seront supprimés (irréversible), y compris tes messages côté Telegram.")
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Vider") { _, _ ->
                lifecycleScope.launch {
                    if (app.chatApi.chatClear()) reload() else toast("Échec")
                }
            }
            .show()
    }

    // --------------------------------------------------------- vocal (lecture)

    private fun togglePlay(m: ChatMessage) {
        if (adapter.playingId == m.id) {
            stopPlayback()
            return
        }
        stopPlayback()
        val mp = MediaPlayer()
        try {
            mp.setDataSource(app.chatApi.chatMediaUrl(m.id))
            mp.setOnPreparedListener { it.start() }
            mp.setOnCompletionListener { stopPlayback() }
            mp.setOnErrorListener { _, _, _ ->
                stopPlayback()
                toast("Lecture du vocal impossible")
                true
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            runCatching { mp.release() }
            toast("Lecture du vocal impossible")
            return
        }
        player = mp
        adapter.playingId = m.id
    }

    private fun stopPlayback() {
        player?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        player = null
        if (adapter.playingId != -1L) adapter.playingId = -1L
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
