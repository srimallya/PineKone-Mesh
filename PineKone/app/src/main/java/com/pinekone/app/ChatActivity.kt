package com.pinekone.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.pinekone.app.data.model.AutoPlayVoiceNotes
import com.pinekone.app.data.model.ChatMessage
import com.pinekone.app.data.model.MessageContentType
import com.pinekone.app.databinding.ActivityChatBinding
import com.pinekone.app.engine.SendLifecycleEvent
import com.pinekone.app.ui.MessageAdapter
import com.pinekone.app.ui.MeshViewModel
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class ChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatBinding
    private lateinit var viewModel: MeshViewModel
    private lateinit var adapter: MessageAdapter

    private val contactId: String by lazy { intent.getStringExtra(EXTRA_CONTACT_ID).orEmpty() }
    private val initialContactName: String by lazy { intent.getStringExtra(EXTRA_CONTACT_NAME).orEmpty() }
    private val attachmentRepository get() = (application as PineKoneApp).attachmentRepository

    private var mediaRecorder: MediaRecorder? = null
    private var mediaPlayer: MediaPlayer? = null
    private var recordingFile: File? = null
    private var recordingStartedAtMs: Long = 0L
    private var recordingDurationMs: Long = 0L
    private var recordingTicker: Job? = null
    private var latestSettingsAutoPlay: AutoPlayVoiceNotes = AutoPlayVoiceNotes.MANUAL_ONLY
    private var lastAutoPlayedMsgId: String? = null

    private val imagePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        viewModel.sendImageMessage(contactId, uri)
        Snackbar.make(binding.root, R.string.chat_image_queued, Snackbar.LENGTH_SHORT).show()
    }

    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            startVoiceRecording()
        } else {
            Snackbar.make(binding.root, R.string.chat_record_permission_required, Snackbar.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = ViewModelProvider(this, MeshViewModel.factory(application))[MeshViewModel::class.java]
        adapter = MessageAdapter(
            onResend = { message -> viewModel.resendMessage(contactId, message) },
            onOpenImage = { message -> openImage(message) },
            onPlayVoice = { message -> playVoiceMessage(message) }
        )

        setSupportActionBar(binding.chatToolbar)
        supportActionBar?.title = initialContactName
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.chatToolbar.setNavigationOnClickListener { finish() }

        binding.messageList.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        binding.messageList.adapter = adapter

        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                viewModel.messagesFor(contactId).collect { messages ->
                    adapter.submitList(messages) {
                        binding.messageList.scrollToPosition(maxOf(adapter.itemCount - 1, 0))
                    }
                    maybeAutoPlay(messages)
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                viewModel.messageTraceSummaries(contactId).collect { traces ->
                    adapter.updateTraces(traces)
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                viewModel.settings.collect { settings ->
                    latestSettingsAutoPlay = settings.autoPlayVoiceNotes
                    adapter.setShowDiagnostics(settings.showDiagnostics)
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                viewModel.contactFlow(contactId).collect { contact ->
                    contact?.let {
                        supportActionBar?.title = it.displayName
                        binding.chatIdentitySubtitle.text = getString(
                            R.string.chat_identity_meta,
                            it.fingerprint.take(10)
                        )
                    }
                }
            }
        }

        binding.chatIdentitySubtitle.text = getString(R.string.chat_identity_meta_pending)

        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                viewModel.mediaErrors.collect { message ->
                    Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                viewModel.sendEvents.collect { event ->
                    if (event.contactId != contactId) return@collect
                    when (event) {
                        is SendLifecycleEvent.RetryScheduled -> {
                            val message = getString(
                                R.string.message_retrying,
                                event.attempt,
                                event.maxAttempts
                            )
                            Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
                        }

                        is SendLifecycleEvent.Failed -> {
                            Snackbar.make(
                                binding.root,
                                R.string.message_delivery_unavailable,
                                Snackbar.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }
        }

        binding.messageSendButton.isEnabled = false
        binding.messageInput.doAfterTextChanged { text ->
            binding.messageSendButton.isEnabled = !text.isNullOrBlank() && binding.messageInput.isEnabled
        }
        binding.messageSendButton.setOnClickListener {
            val text = binding.messageInput.text?.toString().orEmpty().trim()
            if (text.isEmpty()) {
                Toast.makeText(this, R.string.chat_type_message, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            viewModel.sendMessage(contactId, text)
            binding.messageInput.setText("")
        }
        binding.messageAttachButton.setOnClickListener { imagePicker.launch("image/*") }
        binding.messageMicButton.setOnClickListener { requestVoiceRecording() }
        binding.recordingStopButton.setOnClickListener { stopRecordingForReview() }
        binding.recordingCancelButton.setOnClickListener { cancelRecording() }
        binding.recordingDiscardButton.setOnClickListener { discardRecordingReview() }
        binding.recordingSendButton.setOnClickListener { sendReviewedRecording() }
    }

    override fun onDestroy() {
        recordingTicker?.cancel()
        mediaRecorder?.release()
        mediaRecorder = null
        mediaPlayer?.release()
        mediaPlayer = null
        super.onDestroy()
    }

    private fun requestVoiceRecording() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startVoiceRecording()
        } else {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    @Suppress("DEPRECATION")
    private fun startVoiceRecording() {
        if (mediaRecorder != null) return
        val outputFile = attachmentRepository.createVoiceNoteOutputFile()
        recordingFile = outputFile
        mediaRecorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setOutputFile(outputFile.absolutePath)
            prepare()
            start()
        }
        recordingStartedAtMs = SystemClock.elapsedRealtime()
        recordingDurationMs = 0L
        binding.recordingPanel.visibility = android.view.View.VISIBLE
        binding.recordingReviewPanel.visibility = android.view.View.GONE
        binding.messageInputLayout.isEnabled = false
        binding.messageInput.isEnabled = false
        binding.messageSendButton.isEnabled = false
        binding.messageAttachButton.isEnabled = false
        binding.messageMicButton.isEnabled = false
        recordingTicker?.cancel()
        recordingTicker = lifecycleScope.launch {
            while (true) {
                recordingDurationMs = SystemClock.elapsedRealtime() - recordingStartedAtMs
                binding.recordingStatus.text = getString(R.string.chat_recording_now, formatDuration(recordingDurationMs))
                if (recordingDurationMs >= 120_000L) {
                    stopRecordingForReview()
                    break
                }
                kotlinx.coroutines.delay(250L)
            }
        }
    }

    private fun stopRecordingForReview() {
        val recorder = mediaRecorder ?: return
        runCatching { recorder.stop() }
        recorder.release()
        mediaRecorder = null
        recordingTicker?.cancel()
        binding.recordingPanel.visibility = android.view.View.GONE
        binding.recordingReviewPanel.visibility = android.view.View.VISIBLE
        binding.recordingReviewText.text = getString(R.string.chat_recording_ready, formatDuration(recordingDurationMs))
        restoreComposer(enabled = false)
    }

    private fun cancelRecording() {
        mediaRecorder?.let {
            runCatching { it.stop() }
            it.release()
        }
        mediaRecorder = null
        recordingTicker?.cancel()
        recordingFile?.delete()
        recordingFile = null
        recordingDurationMs = 0L
        binding.recordingPanel.visibility = android.view.View.GONE
        restoreComposer(enabled = true)
    }

    private fun discardRecordingReview() {
        recordingFile?.delete()
        recordingFile = null
        recordingDurationMs = 0L
        binding.recordingReviewPanel.visibility = android.view.View.GONE
        restoreComposer(enabled = true)
    }

    private fun sendReviewedRecording() {
        val file = recordingFile ?: return
        viewModel.sendVoiceNote(contactId, file.absolutePath, recordingDurationMs)
        Snackbar.make(binding.root, R.string.chat_voice_queued, Snackbar.LENGTH_SHORT).show()
        binding.recordingReviewPanel.visibility = android.view.View.GONE
        recordingFile = null
        recordingDurationMs = 0L
        restoreComposer(enabled = true)
    }

    private fun restoreComposer(enabled: Boolean) {
        binding.messageInputLayout.isEnabled = enabled
        binding.messageInput.isEnabled = enabled
        binding.messageSendButton.isEnabled = enabled
        binding.messageAttachButton.isEnabled = enabled
        binding.messageMicButton.isEnabled = enabled
    }

    private fun openImage(message: ChatMessage) {
        val uri = secureUriFor(message.localUri ?: return)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, message.mimeType ?: "image/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Snackbar.make(binding.root, R.string.chat_media_open_failed, Snackbar.LENGTH_LONG).show()
        }
    }

    private fun playVoiceMessage(message: ChatMessage) {
        val uri = Uri.parse(message.localUri ?: return)
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            setDataSource(this@ChatActivity, uri)
            setOnCompletionListener { player ->
                player.release()
                if (mediaPlayer === player) {
                    mediaPlayer = null
                }
            }
            prepare()
            start()
        }
    }

    private fun maybeAutoPlay(messages: List<ChatMessage>) {
        if (latestSettingsAutoPlay != AutoPlayVoiceNotes.TRUSTED_ONLY) return
        val latest = messages.lastOrNull() ?: return
        if (latest.msgId == lastAutoPlayedMsgId) return
        if (latest.direction != com.pinekone.app.data.model.MessageDirection.INCOMING) return
        if (latest.contentType != MessageContentType.VOICE_NOTE) return
        lastAutoPlayedMsgId = latest.msgId
        playVoiceMessage(latest)
    }

    private fun secureUriFor(raw: String): Uri {
        val uri = Uri.parse(raw)
        if (uri.scheme != "file") return uri
        val file = File(requireNotNull(uri.path))
        return FileProvider.getUriForFile(this, "$packageName.files", file)
    }

    private fun formatDuration(durationMs: Long): String {
        val totalSeconds = (durationMs / 1000L).toInt().coerceAtLeast(1)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%d:%02d".format(minutes, seconds)
    }

    companion object {
        private const val EXTRA_CONTACT_ID = "contact_id"
        private const val EXTRA_CONTACT_NAME = "contact_name"

        fun intent(context: Context, contactId: String, contactName: String): Intent =
            Intent(context, ChatActivity::class.java).apply {
                putExtra(EXTRA_CONTACT_ID, contactId)
                putExtra(EXTRA_CONTACT_NAME, contactName)
            }
    }
}
