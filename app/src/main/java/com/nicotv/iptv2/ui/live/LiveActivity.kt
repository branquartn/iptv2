package com.nicotv.iptv2.ui.live

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.LinearLayoutManager
import com.nicotv.iptv2.R
import com.nicotv.iptv2.databinding.ActivityLiveBinding
import com.nicotv.iptv2.player.PlayerActivity
import com.nicotv.iptv2.ui.common.BaseActivity
import com.nicotv.iptv2.ui.common.CategorySidebarAdapter

@UnstableApi
class LiveActivity : BaseActivity() {

    private lateinit var binding: ActivityLiveBinding
    private lateinit var viewModel: LiveViewModel
    private lateinit var channelAdapter: ChannelAdapter
    private lateinit var categoryAdapter: CategorySidebarAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLiveBinding.inflate(layoutInflater)
        setContentView(binding.root)
        viewModel = ViewModelProvider(this)[LiveViewModel::class.java]

        binding.btnBack.setOnClickListener { finish() }
        applyRing(binding.btnBack, binding.btnBackRing, 1.25f)
        applyRing(binding.btnFavoritesFilter, binding.btnFavoritesFilterRing, 1.2f)
        applyRing(binding.btnFrenchFilter, binding.btnFrenchFilterRing, 1.2f)

        channelAdapter = ChannelAdapter(
            onClick = { channel ->
                startActivity(Intent(this, PlayerActivity::class.java).apply {
                    putExtra(PlayerActivity.EXTRA_STREAM_URL, channel.streamUrl)
                    putExtra(PlayerActivity.EXTRA_TITLE, channel.name)
                })
            },
            onToggleFavorite = { channel -> viewModel.toggleFavorite(channel) },
            epgScope = lifecycleScope,
            fetchEpg = { channel -> viewModel.getShortEpg(channel) }
        )
        binding.rvChannels.layoutManager = LinearLayoutManager(this)
        binding.rvChannels.adapter = channelAdapter

        categoryAdapter = CategorySidebarAdapter(getString(R.string.category_all)) { category -> viewModel.selectedCategory.value = category }
        binding.rvCategories.layoutManager = LinearLayoutManager(this)
        binding.rvCategories.adapter = categoryAdapter

        binding.btnFavoritesFilter.setOnClickListener {
            val on = viewModel.favoritesOnly.value != true
            viewModel.favoritesOnly.value = on
            binding.ivFavoritesFilter.imageTintList = ContextCompat.getColorStateList(
                this, if (on) R.color.favorite_yellow else R.color.text_primary
            )
        }

        binding.btnFrenchFilter.setOnClickListener {
            val on = viewModel.frenchOnly.value != true
            viewModel.frenchOnly.value = on
            binding.tvFrenchFilter.setTextColor(
                ContextCompat.getColor(this, if (on) R.color.accent else R.color.text_primary)
            )
        }

        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) { viewModel.searchQuery.value = s?.toString() ?: "" }
        })
        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { hideKeyboard(); true } else false
        }

        viewModel.categories.observe(this) { cats -> categoryAdapter.submitList(cats) }

        binding.progressLoading.visibility = View.VISIBLE
        viewModel.filteredChannels.observe(this) { channels ->
            binding.progressLoading.visibility = View.GONE
            channelAdapter.submitList(channels)
            binding.tvEmpty.visibility = if (channels.isEmpty()) View.VISIBLE else View.GONE
            binding.rvChannels.visibility = if (channels.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val view = currentFocus ?: binding.root
        imm.hideSoftInputFromWindow(view.windowToken, 0)
        binding.etSearch.clearFocus()
    }

    private fun applyRing(target: View, ring: com.nicotv.iptv2.ui.common.RotatingBorderView, scale: Float) {
        target.setOnFocusChangeListener { v, hasFocus ->
            v.animate().scaleX(if (hasFocus) scale else 1f).scaleY(if (hasFocus) scale else 1f).setDuration(150).start()
            ring.visibility = if (hasFocus) View.VISIBLE else View.INVISIBLE
            if (hasFocus) ring.startAnim() else ring.stopAnim()
        }
    }
}
