package com.pinekone.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.pinekone.app.R
import com.pinekone.app.engine.PkPeer
import com.pinekone.app.engine.TransportKind
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class PingStatus {
    IDLE,
    PENDING,
    SUCCESS,
    FAILURE
}

data class PingUiState(
    val status: PingStatus,
    val latencyMs: Long? = null
)

class PeerAdapter(
    private val onPing: ((PkPeer) -> Unit)? = null
) : ListAdapter<PkPeer, PeerAdapter.PeerViewHolder>(Diff) {

    private var pingStates: Map<String, PingUiState> = emptyMap()

    fun updatePingStates(states: Map<String, PingUiState>) {
        pingStates = states
        if (onPing != null) {
            notifyDataSetChanged()
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PeerViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_peer, parent, false)
        return PeerViewHolder(view)
    }

    override fun onBindViewHolder(holder: PeerViewHolder, position: Int) {
        val peer = getItem(position)
        holder.bind(peer, pingStates[peer.id], onPing)
    }

    class PeerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val relationBadge: Chip = itemView.findViewById(R.id.peerRelationBadge)
        private val name: TextView = itemView.findViewById(R.id.peerName)
        private val trustChip: Chip = itemView.findViewById(R.id.peerTrustChip)
        private val transportChip: Chip = itemView.findViewById(R.id.peerTransportChip)
        private val meta: TextView = itemView.findViewById(R.id.peerMeta)
        private val pingButton: MaterialButton? = itemView.findViewById(R.id.peerPing)
        private val pingStatusView: TextView? = itemView.findViewById(R.id.peerPingStatus)

        fun bind(peer: PkPeer, state: PingUiState?, onPing: ((PkPeer) -> Unit)?) {
            name.text = peer.displayName
            val timestamp = formatter.format(peer.lastSeen)
            relationBadge.text = itemView.context.getString(R.string.nearby_relation_l1)
            tintChip(relationBadge, R.color.pk_chip_success_bg, R.color.pk_chip_success_fg)
            trustChip.text = itemView.context.getString(R.string.nearby_trust_unverified)
            tintChip(trustChip, R.color.pk_chip_neutral_bg, R.color.pk_chip_neutral_fg)
            transportChip.text = if (peer.transport == TransportKind.MESH) {
                itemView.context.getString(R.string.nearby_transport_mesh_chip)
            } else {
                itemView.context.getString(R.string.nearby_transport_web_chip)
            }
            tintChip(
                transportChip,
                if (peer.transport == TransportKind.MESH) R.color.pk_chip_info_bg else R.color.pk_chip_warn_bg,
                if (peer.transport == TransportKind.MESH) R.color.pk_chip_info_fg else R.color.pk_chip_warn_fg
            )
            val battery = peer.batteryPct?.let { "battery=$it%" }
            val quality = itemView.context.getString(
                R.string.nearby_quality,
                (peer.quality * 100).toInt().coerceIn(0, 100)
            )
            meta.text = listOfNotNull(
                itemView.context.getString(R.string.nearby_relation_direct),
                itemView.context.getString(R.string.nearby_seen_time, timestamp),
                battery,
                quality
            ).joinToString(" • ")

            if (onPing == null || pingButton == null || pingStatusView == null) {
                pingButton?.visibility = View.GONE
                pingStatusView?.visibility = View.GONE
                return
            }

            val context = itemView.context
            val status = state ?: PingUiState(PingStatus.IDLE)
            pingButton.visibility = View.VISIBLE
            when (status.status) {
                PingStatus.IDLE -> {
                    pingButton.text = context.getString(R.string.nearby_ping)
                    pingButton.isEnabled = true
                    pingStatusView.visibility = View.GONE
                }
                PingStatus.PENDING -> {
                    pingButton.text = context.getString(R.string.nearby_ping_pending)
                    pingButton.isEnabled = false
                    pingStatusView.visibility = View.GONE
                }
                PingStatus.SUCCESS -> {
                    pingButton.text = context.getString(R.string.nearby_ping)
                    pingButton.isEnabled = true
                    pingStatusView.visibility = View.VISIBLE
                    val latency = status.latencyMs?.coerceAtLeast(0) ?: 0
                    pingStatusView.text = context.getString(R.string.nearby_ping_latency, latency)
                    pingStatusView.setTextColor(ContextCompat.getColor(context, R.color.pk_chip_info_fg))
                }
                PingStatus.FAILURE -> {
                    pingButton.text = context.getString(R.string.nearby_ping_retry)
                    pingButton.isEnabled = true
                    pingStatusView.visibility = View.VISIBLE
                    pingStatusView.text = context.getString(R.string.nearby_ping_failed)
                    pingStatusView.setTextColor(ContextCompat.getColor(context, R.color.md_theme_error))
                }
            }

            pingButton.setOnClickListener {
                onPing(peer)
            }
        }

        private fun tintChip(chip: Chip, backgroundRes: Int, foregroundRes: Int) {
            chip.chipBackgroundColor = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(itemView.context, backgroundRes)
            )
            chip.setTextColor(ContextCompat.getColor(itemView.context, foregroundRes))
        }

        companion object {
            private val formatter: DateTimeFormatter =
                DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
        }
    }

    private object Diff : DiffUtil.ItemCallback<PkPeer>() {
        override fun areItemsTheSame(oldItem: PkPeer, newItem: PkPeer): Boolean = oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: PkPeer, newItem: PkPeer): Boolean = oldItem == newItem
    }
}
