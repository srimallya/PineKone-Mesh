package com.pinekone.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.pinekone.app.R
import com.pinekone.app.data.model.ChatMessage
import com.pinekone.app.data.model.MessageDirection
import com.pinekone.app.data.model.MessageStatus
import com.pinekone.app.data.model.MessageTransport
import java.time.Duration
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MessageAdapter(
    private val onResend: (ChatMessage) -> Unit
) : ListAdapter<ChatMessage, MessageAdapter.MessageViewHolder>(Diff) {
    private var traces: Map<String, MessageTraceSummary> = emptyMap()

    fun updateTraces(value: Map<String, MessageTraceSummary>) {
        traces = value
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position).direction) {
            MessageDirection.OUTGOING -> VIEW_TYPE_OUTGOING
            MessageDirection.INCOMING -> VIEW_TYPE_INCOMING
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val layout = if (viewType == VIEW_TYPE_OUTGOING) {
            R.layout.item_message_outgoing
        } else {
            R.layout.item_message_incoming
        }
        val view = LayoutInflater.from(parent.context).inflate(layout, parent, false) as ViewGroup
        val bodyView = view.findViewById<TextView>(R.id.messageBody)
        val metaView = view.findViewById<TextView>(R.id.messageMeta)
        val traceView = view.findViewById<TextView>(R.id.messageTrace)
        val statusChip = view.findViewById<Chip>(R.id.messageStatusChip)
        val retryView = view.findViewById<TextView>(R.id.messageRetry)
        return MessageViewHolder(view, bodyView, metaView, traceView, statusChip, retryView, onResend)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val item = getItem(position)
        holder.bind(item, traces[item.msgId])
    }

    class MessageViewHolder(
        private val container: ViewGroup,
        private val body: TextView,
        private val meta: TextView,
        private val trace: TextView,
        private val statusChip: Chip,
        private val retry: TextView?,
        private val onResend: (ChatMessage) -> Unit
    ) : RecyclerView.ViewHolder(container) {
        fun bind(message: ChatMessage, traceSummary: MessageTraceSummary?) {
            body.text = message.payload
            meta.text = formatMeta(message)
            renderStatusChip(message)
            renderTrace(traceSummary)
            if (message.direction == MessageDirection.OUTGOING && message.status == MessageStatus.FAILED) {
                retry?.visibility = View.VISIBLE
                retry?.setOnClickListener { onResend(message) }
            } else {
                retry?.visibility = View.GONE
                retry?.setOnClickListener(null)
            }
        }

        private fun formatMeta(message: ChatMessage): String {
            val context = container.context
            val timeLabel = formatter.format(message.timestamp)
            val transportSection = when (message.transport) {
                MessageTransport.MESH -> context.getString(R.string.message_state_trace_mesh, timeLabel)
                MessageTransport.WEB -> context.getString(R.string.message_state_trace_web, timeLabel)
            }

            val statusSection = when {
                message.direction == MessageDirection.OUTGOING &&
                    message.status == MessageStatus.DELIVERED &&
                    message.deliveredAt != null -> {
                    val durationMillis = Duration.between(message.timestamp, message.deliveredAt).toMillis()
                    val durationLabel = formatDuration(context, durationMillis)
                    context.getString(R.string.message_meta_delivered_in, durationLabel)
                }

                message.direction == MessageDirection.OUTGOING -> {
                    statusLabel(context, message.status)
                }

                else -> context.getString(R.string.message_status_received)
            }

            return listOf(statusSection, transportSection).joinToString(" • ")
        }

        private fun renderTrace(traceSummary: MessageTraceSummary?) {
            if (traceSummary == null) {
                trace.visibility = View.GONE
                trace.text = ""
                return
            }
            trace.visibility = View.VISIBLE
            trace.text = listOfNotNull(
                traceSummary.latestDecision.lowercase(),
                traceSummary.latestReason,
                traceSummary.latestDetail,
                "mutations=${traceSummary.mutationCount}"
            ).joinToString(" • ")
        }

        private fun renderStatusChip(message: ChatMessage) {
            val context = container.context
            val (labelRes, backgroundRes, foregroundRes) = when {
                message.direction == MessageDirection.INCOMING -> Triple(
                    R.string.message_status_received,
                    R.color.pk_chip_info_bg,
                    R.color.pk_chip_info_fg
                )
                message.status == MessageStatus.DELIVERED -> Triple(
                    R.string.message_status_delivered,
                    R.color.pk_chip_success_bg,
                    R.color.pk_chip_success_fg
                )
                message.status == MessageStatus.FAILED -> Triple(
                    R.string.message_status_failed,
                    R.color.pk_chip_error_bg,
                    R.color.pk_chip_error_fg
                )
                message.status == MessageStatus.SENT -> Triple(
                    R.string.message_status_sent,
                    R.color.pk_chip_info_bg,
                    R.color.pk_chip_info_fg
                )
                else -> Triple(
                    R.string.message_status_pending,
                    R.color.pk_chip_warn_bg,
                    R.color.pk_chip_warn_fg
                )
            }
            statusChip.text = context.getString(labelRes)
            statusChip.chipBackgroundColor = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(context, backgroundRes)
            )
            statusChip.setTextColor(ContextCompat.getColor(context, foregroundRes))
        }

        private fun statusLabel(context: android.content.Context, status: MessageStatus): String = when (status) {
            MessageStatus.PENDING -> context.getString(R.string.message_status_pending)
            MessageStatus.SENT -> context.getString(R.string.message_status_sent)
            MessageStatus.DELIVERED -> context.getString(R.string.message_status_delivered)
            MessageStatus.FAILED -> context.getString(R.string.message_status_failed)
        }

        private fun formatDuration(context: android.content.Context, durationMillis: Long): String {
            val totalSeconds = (durationMillis / 1000).coerceAtLeast(1)
            val minutes = totalSeconds / 60
            val seconds = (totalSeconds % 60).toInt()
            return when {
                minutes >= 1 -> {
                    if (minutes >= 10) {
                        context.getString(R.string.message_delivery_duration_minutes, minutes.toInt())
                    } else {
                        context.getString(R.string.message_delivery_duration_minutes_seconds, minutes.toInt(), seconds)
                    }
                }
                else -> context.getString(R.string.message_delivery_duration_seconds, seconds)
            }
        }

        companion object {
            private val formatter: DateTimeFormatter =
                DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
        }
    }

    private object Diff : DiffUtil.ItemCallback<ChatMessage>() {
        override fun areItemsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean =
            oldItem == newItem
    }

    companion object {
        private const val VIEW_TYPE_OUTGOING = 1
        private const val VIEW_TYPE_INCOMING = 2
    }
}
