package com.pinekone.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.Gravity
import android.widget.LinearLayout
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.pinekone.app.data.model.PublicChatMessage
import com.pinekone.app.databinding.ItemPublicMessageBinding
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class PublicChatAdapter(
    private val isSelf: (PublicChatMessage) -> Boolean
) : ListAdapter<PublicChatMessage, PublicChatAdapter.PublicMessageViewHolder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PublicMessageViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val binding = ItemPublicMessageBinding.inflate(inflater, parent, false)
        return PublicMessageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PublicMessageViewHolder, position: Int) {
        holder.bind(getItem(position), isSelf(getItem(position)))
    }

    class PublicMessageViewHolder(
        private val binding: ItemPublicMessageBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(message: PublicChatMessage, isSelf: Boolean) {
            val context = binding.root.context
            val authorLabel = if (isSelf) {
                context.getString(com.pinekone.app.R.string.public_chat_author_you)
            } else {
                message.authorName
            }
            binding.publicMessageAuthor.text = authorLabel
            binding.publicMessageBody.text = message.payload
            binding.publicMessageMeta.text = formatter.format(message.timestamp)

            val gravity = if (isSelf) Gravity.END else Gravity.START
            (binding.root as? LinearLayout)?.gravity = gravity
        }

        companion object {
            private val formatter: DateTimeFormatter =
                DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
        }
    }

    private object Diff : DiffUtil.ItemCallback<PublicChatMessage>() {
        override fun areItemsTheSame(oldItem: PublicChatMessage, newItem: PublicChatMessage): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: PublicChatMessage, newItem: PublicChatMessage): Boolean =
            oldItem == newItem
    }
}
