package com.pinekone.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.pinekone.app.R

class FeedAdapter : ListAdapter<FeedRow, FeedAdapter.FeedViewHolder>(Diff) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FeedViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_feed_row, parent, false)
        return FeedViewHolder(view)
    }

    override fun onBindViewHolder(holder: FeedViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class FeedViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.feedTitle)
        private val subtitle: TextView = itemView.findViewById(R.id.feedSubtitle)

        fun bind(row: FeedRow) {
            title.text = row.title
            subtitle.text = row.subtitle
        }
    }

    private object Diff : DiffUtil.ItemCallback<FeedRow>() {
        override fun areItemsTheSame(oldItem: FeedRow, newItem: FeedRow): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: FeedRow, newItem: FeedRow): Boolean = oldItem == newItem
    }
}
