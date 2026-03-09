package com.pinekone.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.pinekone.app.R
import com.pinekone.app.protocol.toHexString
import com.pinekone.app.store.EnvelopeRecord

class EnvelopeAdapter : ListAdapter<EnvelopeRecord, EnvelopeAdapter.EnvelopeViewHolder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EnvelopeViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_envelope, parent, false)
        return EnvelopeViewHolder(view)
    }

    override fun onBindViewHolder(holder: EnvelopeViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class EnvelopeViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.envelopeTitle)
        private val subtitle: TextView = itemView.findViewById(R.id.envelopeSubtitle)

        fun bind(record: EnvelopeRecord) {
            val msgIdHex = record.envelope.msgId.toHexString()
            val status = record.status.name.lowercase()
            title.text = record.debugNote ?: "Envelope ${msgIdHex.take(10)}"
            subtitle.text = "status=$status ttl=${record.envelope.ttl} fanout=${record.envelope.policy.maxFanout}"
        }
    }

    private object Diff : DiffUtil.ItemCallback<EnvelopeRecord>() {
        override fun areItemsTheSame(oldItem: EnvelopeRecord, newItem: EnvelopeRecord): Boolean = oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: EnvelopeRecord, newItem: EnvelopeRecord): Boolean =
            oldItem == newItem
    }
}
