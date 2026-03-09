package com.pinekone.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.pinekone.app.databinding.FragmentPublicChatBinding
import com.pinekone.app.ui.MeshViewModel
import com.pinekone.app.ui.PublicChatAdapter
import kotlinx.coroutines.launch

class PublicChatFragment : Fragment() {

    private var _binding: FragmentPublicChatBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: MeshViewModel
    private lateinit var adapter: PublicChatAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPublicChatBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(requireActivity(), MeshViewModel.factory(requireActivity().application))[MeshViewModel::class.java]

        adapter = PublicChatAdapter { message ->
            val identity = viewModel.identity.value
            identity?.nodeId == message.authorId
        }

        binding.publicMessageList.layoutManager = LinearLayoutManager(requireContext()).apply {
            stackFromEnd = true
        }
        binding.publicMessageList.adapter = adapter

        binding.publicSendButton.setOnClickListener {
            val text = binding.publicMessageInput.text?.toString()?.trim().orEmpty()
            if (text.isNotEmpty()) {
                viewModel.sendPublicMessage(text)
                binding.publicMessageInput.setText("")
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                viewModel.publicMessages.collect { messages ->
                    adapter.submitList(messages) {
                        binding.publicMessageList.scrollToPosition(maxOf(adapter.itemCount - 1, 0))
                    }
                    binding.publicEmptyState.isVisible = messages.isEmpty()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
