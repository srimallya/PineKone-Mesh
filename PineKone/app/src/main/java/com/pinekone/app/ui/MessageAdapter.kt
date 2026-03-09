package com.pinekone.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.pinekone.app.R
import com.pinekone.app.data.model.ChatMessage
import com.pinekone.app.data.model.MessageContentType
import com.pinekone.app.data.model.MessageDirection
import com.pinekone.app.data.model.MessageStatus
import com.pinekone.app.data.model.MessageTransport
import java.time.Duration
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MessageAdapter(
    private val onResend: (ChatMessage) -> Unit,
    private val onOpenImage: (ChatMessage) -> Unit,
    private val onPlayVoice: (ChatMessage) -> Unit
) : ListAdapter<ChatMessage, MessageAdapter.MessageViewHolder>(Diff) {
    private var traces: Map<String, MessageTraceSummary> = emptyMap()
    private var showDiagnostics: Boolean = true

    fun updateTraces(value: Map<String, MessageTraceSummary>) {
        traces = value
        notifyDataSetChanged()
    }

    fun setShowDiagnostics(enabled: Boolean) {
        showDiagnostics = enabled
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
        val imageView = view.findViewById<ImageView>(R.id.messageImage)
        val voiceRow = view.findViewById<View>(R.id.messageVoiceRow)
        val voicePlay = view.findViewById<MaterialButton>(R.id.messageVoicePlay)
        val voiceDuration = view.findViewById<TextView>(R.id.messageVoiceDuration)
        return MessageViewHolder(
            view,
            bodyView,
            metaView,
            traceView,
            statusChip,
            retryView,
            imageView,
            voiceRow,
            voicePlay,
            voiceDuration,
            onResend,
            onOpenImage,
            onPlayVoice
        )
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val item = getItem(position)
        holder.bind(item, traces[item.msgId], showDiagnostics)
    }

    class MessageViewHolder(
        private val container: ViewGroup,
        private val body: TextView,
        private val meta: TextView,
        private val trace: TextView,
        private val statusChip: Chip,
        private val retry: TextView?,
        private val image: ImageView,
        private val voiceRow: View,
        private val voicePlay: MaterialButton,
        private val voiceDuration: TextView,
        private val onResend: (ChatMessage) -> Unit,
        private val onOpenImage: (ChatMessage) -> Unit,
        private val onPlayVoice: (ChatMessage) -> Unit
    ) : RecyclerView.ViewHolder(container) {
        fun bind(message: ChatMessage, traceSummary: MessageTraceSummary?, showDiagnostics: Boolean) {
            body.text = message.payload
            body.visibility = if (message.payload.isBlank() && message.contentType != MessageContentType.TEXT) {
                View.GONE
            } else {
                View.VISIBLE
            }
            renderContent(message)
            meta.text = formatMeta(message)
            renderStatusChip(message)
            renderTrace(traceSummary, showDiagnostics)
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

        private fun renderContent(message: ChatMessage) {
            when (message.contentType) {
                MessageContentType.TEXT -> {
                    image.visibility = View.GONE
                    voiceRow.visibility = View.GONE
                    image.setOnClickListener(null)
                    voicePlay.setOnClickListener(null)
                }
                MessageContentType.IMAGE -> {
                    image.visibility = View.VISIBLE
                    voiceRow.visibility = View.GONE
                    image.setImageURI((message.thumbnailUri ?: message.localUri).orEmpty().toUri())
                    image.setOnClickListener { onOpenImage(message) }
                    voicePlay.setOnClickListener(null)
                }
                MessageContentType.VOICE_NOTE -> {
                    image.visibility = View.GONE
                    voiceRow.visibility = View.VISIBLE
                    voiceDuration.text = formatVoiceDuration(message.durationMs)
                    voicePlay.setOnClickListener { onPlayVoice(message) }
                    image.setOnClickListener(null)
                }
            }
        }

        private fun renderTrace(traceSummary: MessageTraceSummary?, showDiagnostics: Boolean) {
            if (!showDiagnostics || traceSummary == null) {
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

        private fun formatVoiceDuration(durationMs: Long?): String {
            val totalSeconds = ((durationMs ?: 0L) / 1000L).toInt().coerceAtLeast(1)
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return "%d:%02d".format(minutes, seconds)
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
