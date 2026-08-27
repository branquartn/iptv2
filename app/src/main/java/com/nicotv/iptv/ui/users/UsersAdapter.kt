package com.nicotv.iptv.ui.users

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.nicotv.iptv.data.network.ApiUser
import com.nicotv.iptv.databinding.ItemUserBinding

class UsersAdapter(
    private val onReset: (ApiUser) -> Unit,
    private val onDelete: (ApiUser) -> Unit
) : ListAdapter<ApiUser, UsersAdapter.UserViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UserViewHolder {
        val binding = ItemUserBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return UserViewHolder(binding)
    }

    override fun onBindViewHolder(holder: UserViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class UserViewHolder(private val binding: ItemUserBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(user: ApiUser) {
            binding.tvUsername.text = user.username
            binding.tvAdminBadge.visibility = if (user.is_admin) View.VISIBLE else View.GONE
            binding.btnReset.setOnClickListener { onReset(user) }
            if (user.username.equals(ADMIN_USERNAME, ignoreCase = true)) {
                binding.btnDelete.visibility = View.GONE
            } else {
                binding.btnDelete.visibility = View.VISIBLE
                binding.btnDelete.setOnClickListener { onDelete(user) }
            }
        }
    }

    companion object {
        private const val ADMIN_USERNAME = "admin"

        private val DIFF = object : DiffUtil.ItemCallback<ApiUser>() {
            override fun areItemsTheSame(a: ApiUser, b: ApiUser) = a.id == b.id
            override fun areContentsTheSame(a: ApiUser, b: ApiUser) = a == b
        }
    }
}
