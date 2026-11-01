package com.nicotv.iptv2.ui.add

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import coil.load
import com.nicotv.iptv2.AppConfig
import com.nicotv.iptv2.IptvApplication
import com.nicotv.iptv2.R
import com.nicotv.iptv2.data.tmdb.TmdbWork
import com.nicotv.iptv2.databinding.ActivityAddBinding
import com.nicotv.iptv2.databinding.ItemAddResultBinding
import com.nicotv.iptv2.ui.common.BaseActivity
import kotlinx.coroutines.launch

/**
 * « + » de l'accueil, comme dans l'app iptv : recherche TMDb d'un film ou d'une série, puis demande d'ajout à la
 * bibliothèque du serveur (file de téléchargement). Le titre apparaît ensuite tout seul dans « Mon serveur » (temps réel).
 */
class AddActivity : BaseActivity() {

    private lateinit var binding: ActivityAddBinding
    private val app get() = application as IptvApplication
    private var results: List<TmdbWork> = emptyList()

    private val adapter = object : BaseAdapter() {
        override fun getCount() = results.size
        override fun getItem(position: Int) = results[position]
        override fun getItemId(position: Int) = results[position].id.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val b = if (convertView == null) ItemAddResultBinding.inflate(LayoutInflater.from(parent.context), parent, false)
                    else ItemAddResultBinding.bind(convertView)
            val w = results[position]
            b.tvAddTitle.text = w.title
            b.tvAddMeta.text = listOf(if (w.isTv) "Série" else "Film", w.year).filter { it.isNotBlank() }.joinToString(" · ")
            b.ivAddPoster.load(w.posterPath?.let { AppConfig.Tmdb.IMAGE_BASE_W185 + it })
            return b.root
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnAddBack.setOnClickListener { finish() }
        binding.lvAddResults.adapter = adapter
        binding.lvAddResults.setOnItemClickListener { _, _, pos, _ -> confirmAdd(results[pos]) }
        binding.etAddSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { search(); true } else false
        }
        binding.etAddSearch.requestFocus()
    }

    private fun search() {
        val q = binding.etAddSearch.text.toString().trim()
        if (q.isBlank()) return
        binding.tvAddEmpty.visibility = View.VISIBLE
        binding.tvAddEmpty.text = "Recherche…"
        lifecycleScope.launch {
            results = app.playlistRepository.searchForAdd(q)
            adapter.notifyDataSetChanged()
            binding.tvAddEmpty.visibility = if (results.isEmpty()) View.VISIBLE else View.GONE
            if (results.isEmpty()) binding.tvAddEmpty.text = "Aucun résultat pour « $q »."
            else binding.lvAddResults.requestFocus()
        }
    }

    private fun confirmAdd(w: TmdbWork) {
        val dialog = AlertDialog.Builder(this)
            .setTitle("Ajouter « ${w.title} »${if (w.year.isNotBlank()) " (${w.year})" else ""} ?")
            .setMessage("Le titre sera recherché et téléchargé sur ton serveur, puis apparaîtra dans « Mon serveur ».")
            .setPositiveButton("Ajouter") { _, _ ->
                lifecycleScope.launch {
                    val (ok, msg) = app.libraryApi.addTitle(w.id, w.isTv, w.title, w.year)
                    Toast.makeText(this@AddActivity, msg, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Annuler", null)
            .create()
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(R.drawable.bg_dialog)
    }
}
