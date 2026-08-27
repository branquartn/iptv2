package com.nicotv.iptv.ui.remote

import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.nicotv.iptv.IptvApplication
import com.nicotv.iptv.R
import com.nicotv.iptv.data.network.PresenceItem
import com.nicotv.iptv.databinding.ActivityRemoteControlBinding
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Télécommande complète d'une autre session DU MÊME COMPTE (icône à côté du pseudo sur
 *  l'accueil, cf. MainActivity) : navigation menus (D-pad) + contrôles lecteur (seek/
 *  volume/muet/piste audio/sous-titres) quand la cible regarde un film. Même relais WS
 *  que le bandeau « en cours sur… » de MainActivity (topic user:<uid>, event "remote"),
 *  pendant Android de openRemotePanel() côté PWA (iptv/app.js). */
class RemoteControlActivity : com.nicotv.iptv.ui.common.BaseActivity() {

    private lateinit var binding: ActivityRemoteControlBinding
    private val app get() = application as IptvApplication

    private var devices: List<PresenceItem> = emptyList()
    private var targetId: String? = null
    private var pollJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setupUi()
        } catch (e: Exception) {
            android.util.Log.e("RemoteControlActivity", "Crash onCreate", e)
            showCrashDialog(e)
        }
    }

    /** Filet de diagnostic (incident 2026-08-04) : un Toast se ferme trop vite pour
     *  être lu/copié — boîte de dialogue restant à l'écran, texte sélectionnable +
     *  bouton Copier (presse-papiers), pour relayer l'erreur exacte sans deviner. */
    private fun showCrashDialog(e: Throwable) {
        val text = "${e.javaClass.name}: ${e.message}\n\n" + android.util.Log.getStackTraceString(e)
        val tv = android.widget.TextView(this).apply {
            this.text = text
            setTextIsSelectable(true)
            textSize = 12f
            setPadding(40, 30, 40, 30)
        }
        val scroll = android.widget.ScrollView(this).apply { addView(tv) }
        android.app.AlertDialog.Builder(this)
            .setTitle("Erreur télécommande")
            .setView(scroll)
            .setCancelable(false)
            .setPositiveButton("Copier") { _, _ ->
                val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("crash", text))
                android.widget.Toast.makeText(this, "Copié", android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Fermer") { _, _ -> finish() }
            .show()
    }

    private fun setupUi() {
        binding = ActivityRemoteControlBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnBack.setOnFocusChangeListener { v, hasFocus ->
            v.animate().scaleX(if (hasFocus) 1.25f else 1f).scaleY(if (hasFocus) 1.25f else 1f).setDuration(150).start()
            binding.btnBackRing.visibility = if (hasFocus) View.VISIBLE else View.INVISIBLE
            if (hasFocus) binding.btnBackRing.startAnim() else binding.btnBackRing.stopAnim()
        }

        binding.btnNavUp.setOnClickListener { send("nav_up") }
        binding.btnNavDown.setOnClickListener { send("nav_down") }
        binding.btnNavLeft.setOnClickListener { send("nav_left") }
        binding.btnNavRight.setOnClickListener { send("nav_right") }
        binding.btnNavOk.setOnClickListener { send("nav_select") }
        binding.btnNavBack.setOnClickListener { send("nav_back") }
        binding.btnSeekBack.setOnClickListener { send("seek", "-10") }
        binding.btnSeekFwd.setOnClickListener { send("seek", "10") }
        binding.btnAudio.setOnClickListener { send("audio") }
        binding.btnSubtitle.setOnClickListener { send("subtitle") }
        binding.btnPlayPause.setOnClickListener {
            val playing = devices.firstOrNull { it.deviceId == targetId }?.playing ?: false
            send(if (playing) "pause" else "resume")
        }
        binding.btnMute.setOnClickListener {
            muted = !muted
            binding.ivMute.setImageResource(if (muted) R.drawable.ic_pwa_mute else R.drawable.ic_pwa_vol)
            send(if (muted) "mute" else "unmute")
        }
        binding.sbVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                send("volume", (seekBar!!.progress / 100f).toString())
            }
        })

        binding.spDevice.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position in devices.indices) { targetId = devices[position].deviceId; render() }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.tabDpad.setOnClickListener { showTab(RemoteTab.DPAD) }
        binding.tabMouse.setOnClickListener { showTab(RemoteTab.MOUSE) }
        binding.tabKeyboard.setOnClickListener { showTab(RemoteTab.KEYBOARD) }
        binding.touchpad.setOnTouchListener { _, event -> onTouchpadEvent(event) }

        // Saisie clavier (parité PWA, cf. sendRemoteCmd('text_set', ...) dans app.js) :
        // debounce ~150ms pour ne pas spammer une requête par frappe.
        var kbTimer: java.util.Timer? = null
        binding.etKeyboard.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                kbTimer?.cancel()
                val text = s?.toString() ?: ""
                kbTimer = java.util.Timer().apply {
                    schedule(object : java.util.TimerTask() {
                        override fun run() { runOnUiThread { send("text_set", text) } }
                    }, 150)
                }
            }
        })
    }

    /** Bascule D-pad/Souris (parité PWA, cf. openRemotePanel()/renderRemotePanel()
     *  dans iptv/app.js). */
    private enum class RemoteTab { DPAD, MOUSE, KEYBOARD }

    private fun showTab(tab: RemoteTab) {
        binding.dpadContent.visibility = if (tab == RemoteTab.DPAD) View.VISIBLE else View.GONE
        binding.mouseContent.visibility = if (tab == RemoteTab.MOUSE) View.VISIBLE else View.GONE
        binding.keyboardContent.visibility = if (tab == RemoteTab.KEYBOARD) View.VISIBLE else View.GONE
        val tabs = listOf(binding.tabDpad to RemoteTab.DPAD, binding.tabMouse to RemoteTab.MOUSE, binding.tabKeyboard to RemoteTab.KEYBOARD)
        tabs.forEach { (v, t) ->
            v.setTextColor(ContextCompat.getColor(this, if (t == tab) R.color.accent else R.color.text_secondary))
            v.setBackgroundResource(if (t == tab) R.drawable.bg_remote_tab_active else 0)
        }
        if (tab == RemoteTab.KEYBOARD) binding.etKeyboard.requestFocus()
    }

    // ── Pavé tactile (parité PWA, cf. wireRemoteTrackpad() dans iptv/app.js) ──
    // Deltas RELATIFS accumulés entre deux envois (throttle ~40ms) : ne pas remettre
    // à zéro le point de référence à chaque évènement ACTION_MOVE ignoré par le
    // throttle, sinon le petit mouvement entre deux envois est perdu (même bug que
    // celui corrigé côté PWA le 2026-08-03). Pas de compensation de rotation
    // nécessaire ici : Activity native en sensorLandscape, pas de pivot CSS.
    private var tpLastX = 0f
    private var tpLastY = 0f
    private var tpPendingDx = 0f
    private var tpPendingDy = 0f
    private var tpMoved = false
    private var tpStartT = 0L
    private var tpLastSend = 0L

    private fun onTouchpadEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tpLastX = event.rawX; tpLastY = event.rawY
                tpPendingDx = 0f; tpPendingDy = 0f; tpMoved = false
                tpStartT = System.currentTimeMillis()
                binding.touchpad.setBackgroundResource(R.drawable.bg_dashed_trackpad_active)
            }
            MotionEvent.ACTION_MOVE -> {
                tpPendingDx += event.rawX - tpLastX
                tpPendingDy += event.rawY - tpLastY
                tpLastX = event.rawX; tpLastY = event.rawY
                if (abs(tpPendingDx) > 1 || abs(tpPendingDy) > 1) tpMoved = true
                val now = System.currentTimeMillis()
                if (now - tpLastSend >= 40) {
                    tpLastSend = now
                    sendMouseMove()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                binding.touchpad.setBackgroundResource(R.drawable.bg_dashed_trackpad)
                if (!tpMoved && System.currentTimeMillis() - tpStartT < 400) send("mouse_click")
                else if (tpPendingDx != 0f || tpPendingDy != 0f) sendMouseMove()
            }
        }
        return true
    }

    private fun sendMouseMove() {
        send("mouse_move", "${(tpPendingDx * 2.2f).toInt()},${(tpPendingDy * 2.2f).toInt()}")
        tpPendingDx = 0f; tpPendingDy = 0f
    }

    override fun onResume() {
        super.onResume()
        pollJob = lifecycleScope.launch {
            while (isActive) {
                try {
                    refresh()
                } catch (e: Exception) {
                    android.util.Log.e("RemoteControlActivity", "Crash refresh()", e)
                    showCrashDialog(e)
                    return@launch
                }
                delay(3_000)   // « maj en live » pour naviguer aux flèches sans latence perçue
            }
        }
    }

    override fun onPause() {
        super.onPause()
        pollJob?.cancel(); pollJob = null
    }

    private suspend fun refresh() {
        val bearer = app.sessionManager.bearer()
        devices = runCatching { app.mediaRepository.otherDevicesPresence(bearer) }.getOrDefault(emptyList())
        if (devices.isEmpty()) { finish(); return }
        if (devices.none { it.deviceId == targetId }) targetId = devices.first().deviceId
        render()
    }

    private fun render() {
        val target = devices.firstOrNull { it.deviceId == targetId }
        binding.tvEmpty.visibility = if (devices.isEmpty()) View.VISIBLE else View.GONE
        if (target == null) return

        if (devices.size > 1) {
            binding.spDevice.visibility = View.VISIBLE
            binding.tvTarget.visibility = View.GONE
            val labels = devices.map { "${it.device.ifBlank { "Appareil" }} — ${it.title.ifBlank { "Accueil" }}" }
            // Lignes hautes et espacées (item_remote_device) plutôt que le
            // simple_spinner_dropdown_item natif, trop serré pour viser au doigt la
            // bonne session quand plusieurs appareils sont listés.
            binding.spDevice.adapter = ArrayAdapter(this, R.layout.item_remote_device, labels).apply {
                setDropDownViewResource(R.layout.item_remote_device)
            }
            val idx = devices.indexOfFirst { it.deviceId == targetId }
            if (idx >= 0) binding.spDevice.setSelection(idx, false)
        } else {
            binding.spDevice.visibility = View.GONE
            binding.tvTarget.visibility = View.VISIBLE
            binding.tvTarget.text = "${target.device.ifBlank { "Appareil" }} — ${target.title.ifBlank { "Accueil" }}"
        }

        val watching = target.kind == "watching"
        binding.rowPlayer.visibility = if (watching) View.VISIBLE else View.GONE
        binding.rowPlayer2.visibility = if (watching) View.VISIBLE else View.GONE
        if (watching) binding.ivPlayPause.setImageResource(if (target.playing) R.drawable.ic_pwa_pause else R.drawable.ic_pwa_play)
    }

    // Volume/muet : la cible ne renvoie pas son propre niveau via la présence → simple
    // mémoire du dernier ordre envoyé depuis CE panneau (même limite que côté PWA).
    private var muted = false

    private fun send(cmd: String, value: String? = null) {
        val id = targetId ?: return
        lifecycleScope.launch {
            runCatching { app.mediaRepository.remoteCmd(id, cmd, value, app.sessionManager.bearer()) }
        }
    }
}
