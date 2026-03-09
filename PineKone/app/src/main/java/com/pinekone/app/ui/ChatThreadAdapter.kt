package com.pinekone.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.pinekone.app.R
import com.pinekone.app.data.model.Contact
import java.time.format.DateTimeFormatter
import java.time.ZoneId

class ChatThreadAdapter(
    private val onClick: (Contact) -> Unit,
    private val onLongPress: (Contact) -> Unit,
    private val onOptions: (View, Contact) -> Unit
) : ListAdapter<Contact, ChatThreadAdapter.ThreadViewHolder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ThreadViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_chat_thread, parent, false)
        return ThreadViewHolder(view as ViewGroup, onClick, onLongPress, onOptions)
    }

    override fun onBindViewHolder(holder: ThreadViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    private object Diff : DiffUtil.ItemCallback<Contact>() {
        override fun areItemsTheSame(oldItem: Contact, newItem: Contact): Boolean =
            oldItem.nodeId == newItem.nodeId

        override fun areContentsTheSame(oldItem: Contact, newItem: Contact): Boolean =
            oldItem == newItem
    }

    class ThreadViewHolder(
        itemView: ViewGroup,
        private val onClick: (Contact) -> Unit,
        private val onLongPress: (Contact) -> Unit,
        private val onOptions: (View, Contact) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val name: TextView = itemView.findViewById(R.id.threadName)
        private val snippet: TextView = itemView.findViewById(R.id.threadSnippet)
        private val options: ImageButton = itemView.findViewById(R.id.contactOptions)

        private var boundContact: Contact? = null

        init {
            itemView.setOnClickListener {
                boundContact?.let(onClick)
            }
            itemView.setOnLongClickListener {
                boundContact?.let(onLongPress)
                true
            }
            options.setOnClickListener { view ->
                boundContact?.let { onOptions(view, it) }
            }
        }

        fun bind(contact: Contact) {
            boundContact = contact
            name.text = contact.displayName
            val snippetText = buildString {
                contact.lastMessageSnippet?.let { append(it) }
                contact.lastMessageTimestamp?.let {
                    if (isNotEmpty()) append(" • ")
                    append(timeFormatter.format(it))
                }
            }
            snippet.text = if (snippetText.isNotEmpty()) snippetText else "No messages yet"
        }

        companion object {
            private val timeFormatter: DateTimeFormatter =
                DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
        }
    }
}
