package com.pinekone.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.pinekone.app.databinding.ActivityChatBinding
import com.pinekone.app.engine.SendLifecycleEvent
import com.pinekone.app.ui.MessageAdapter
import com.pinekone.app.ui.MeshViewModel
import kotlinx.coroutines.launch

class ChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatBinding
    private lateinit var viewModel: MeshViewModel
    private lateinit var adapter: MessageAdapter

    private val contactId: String by lazy { intent.getStringExtra(EXTRA_CONTACT_ID).orEmpty() }
    private val initialContactName: String by lazy { intent.getStringExtra(EXTRA_CONTACT_NAME).orEmpty() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = ViewModelProvider(this, MeshViewModel.factory(application))[MeshViewModel::class.java]
        adapter = MessageAdapter { message ->
            viewModel.resendMessage(contactId, message)
        }

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
                                R.string.message_delivery_failed,
                                Snackbar.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }
        }

        binding.messageSendButton.setOnClickListener {
            val text = binding.messageInput.text?.toString().orEmpty().trim()
            if (text.isEmpty()) {
                Toast.makeText(this, "Type a message", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            viewModel.sendMessage(contactId, text)
            binding.messageInput.setText("")
        }
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
