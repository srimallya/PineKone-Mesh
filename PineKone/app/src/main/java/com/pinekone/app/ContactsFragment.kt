package com.pinekone.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pinekone.app.data.model.Contact
import com.pinekone.app.databinding.DialogContactNameBinding
import com.pinekone.app.databinding.FragmentContactsBinding
import com.pinekone.app.ui.ChatThreadAdapter
import com.pinekone.app.ui.MeshViewModel
import kotlinx.coroutines.launch

class ContactsFragment : Fragment() {

    private var _binding: FragmentContactsBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: MeshViewModel
    private lateinit var adapter: ChatThreadAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentContactsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(requireActivity(), MeshViewModel.factory(requireActivity().application))[MeshViewModel::class.java]

        adapter = ChatThreadAdapter(
            onClick = { contact ->
                startActivity(ChatActivity.intent(requireContext(), contact.nodeId, contact.displayName))
            },
            onLongPress = { contact ->
                showDeleteThreadDialog(contact)
            },
            onOptions = { anchor, contact ->
                showContactOptions(anchor, contact)
            }
        )

        binding.contactList.layoutManager = LinearLayoutManager(requireContext())
        binding.contactList.adapter = adapter
        binding.emptyContactsAction.setOnClickListener {
            startActivity(Intent(requireContext(), InviteActivity::class.java))
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                viewModel.contacts.collect { contacts ->
                    val items = contacts.filterNot { it.isSelf }
                    adapter.submitList(items)
                    binding.emptyContactsCard.isVisible = items.isEmpty()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun showContactOptions(anchor: View, contact: Contact) {
        PopupMenu(requireContext(), anchor).apply {
            menuInflater.inflate(R.menu.menu_contact_options, menu)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_rename_contact -> {
                        promptRename(contact)
                        true
                    }

                    R.id.action_delete_contact -> {
                        confirmDeleteContact(contact)
                        true
                    }

                    else -> false
                }
            }
        }.show()
    }

    private fun promptRename(contact: Contact) {
        val dialogBinding = DialogContactNameBinding.inflate(LayoutInflater.from(requireContext()))
        dialogBinding.contactNameInput.setText(contact.displayName)
        dialogBinding.contactNameInput.setSelection(dialogBinding.contactNameInput.text?.length ?: 0)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.contact_rename_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.common_save) { _, _ ->
                val newName = dialogBinding.contactNameInput.text?.toString()?.trim().orEmpty()
                if (newName.isNotEmpty() && newName != contact.displayName) {
                    viewModel.renameContact(contact.nodeId, newName)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteContact(contact: Contact) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.contact_delete_title)
            .setMessage(getString(R.string.contact_delete_message, contact.displayName))
            .setPositiveButton(R.string.contact_delete_confirm) { _, _ ->
                viewModel.deleteContact(contact.nodeId)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showDeleteThreadDialog(contact: Contact) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.contact_delete_thread_title)
            .setMessage(getString(R.string.contact_delete_thread_message, contact.displayName))
            .setPositiveButton(R.string.contact_delete_thread_confirm) { _, _ ->
                viewModel.deleteConversation(contact.nodeId)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
