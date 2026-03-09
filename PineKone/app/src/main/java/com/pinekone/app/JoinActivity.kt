package com.pinekone.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.pinekone.app.databinding.ActivityJoinBinding
import com.pinekone.app.databinding.DialogContactNameBinding
import com.pinekone.app.data.model.GovernanceRole
import com.pinekone.app.ui.MeshViewModel
import com.pinekone.app.ui.PeerAdapter
import java.time.Instant
import kotlinx.coroutines.launch

class JoinActivity : AppCompatActivity() {

    private lateinit var binding: ActivityJoinBinding
    private lateinit var viewModel: MeshViewModel
    private val peerAdapter = PeerAdapter()

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            handleInvite(result.contents)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityJoinBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        viewModel = ViewModelProvider(this, MeshViewModel.factory(application))[MeshViewModel::class.java]

        binding.nearbyDevicesList.layoutManager = LinearLayoutManager(this)
        binding.nearbyDevicesList.adapter = peerAdapter

        binding.scanQrButton.setOnClickListener {
            val options = ScanOptions().apply {
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                setPrompt(getString(R.string.join_scan_qr))
                setCameraId(0)
                setBeepEnabled(false)
            }
            scanLauncher.launch(options)
        }
        binding.showMyCodeButton.setOnClickListener {
            startActivity(Intent(this, InviteActivity::class.java))
        }

        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                viewModel.peers.collect { peers ->
                    peerAdapter.submitList(peers)
                }
            }
        }
    }

    private fun handleInvite(contents: String) {
        val uri = runCatching { Uri.parse(contents) }.getOrNull()
        if (uri == null || uri.scheme != "pk") {
            Snackbar.make(binding.root, getString(R.string.invite_invalid), Snackbar.LENGTH_SHORT).show()
            return
        }
        val nodeId = uri.host ?: uri.path?.removePrefix("/")
        val fingerprint = uri.getQueryParameter("fp")
        val displayName = uri.getQueryParameter("name") ?: "Relay ${nodeId?.take(4) ?: ""}"
        val publicKey = uri.getQueryParameter("pk")
        val inviterNodeId = uri.getQueryParameter("inviter") ?: nodeId
        val attestationRef = uri.getQueryParameter("att")
        val aliasId = uri.getQueryParameter("alias")
        val epoch = uri.getQueryParameter("epoch")?.toLongOrNull() ?: (Instant.now().epochSecond / 86_400)
        val role = uri.getQueryParameter("role")
        if (nodeId.isNullOrBlank() || fingerprint.isNullOrBlank() || publicKey.isNullOrBlank()) {
            Snackbar.make(binding.root, getString(R.string.invite_invalid), Snackbar.LENGTH_SHORT).show()
            return
        }

        val connectionMode = if (role.equals("relay", ignoreCase = true)) {
            getString(R.string.join_review_connection_relay)
        } else {
            getString(R.string.join_review_connection_direct)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.join_review_title)
            .setMessage(
                buildString {
                    append(getString(R.string.join_review_message, displayName))
                    append("\n\n")
                    append(
                        getString(
                            R.string.join_review_details,
                            connectionMode,
                            epoch.toString(),
                            fingerprint.take(16)
                        )
                    )
                }
            )
            .setNeutralButton(R.string.join_review_advanced) { _, _ ->
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.join_review_advanced)
                    .setMessage(contents)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.join_review_add_contact) { _, _ ->
                promptContactName(displayName) { chosenName ->
                    val app = application as PineKoneApp
                    lifecycleScope.launch {
                        app.contactRepository.upsertContact(nodeId, chosenName, fingerprint, publicKey)
                        val self = viewModel.identity.value
                        app.governanceRepository.recordInviteAttestation(
                            attestationRef = attestationRef ?: "invite:$nodeId:${System.currentTimeMillis()}",
                            inviterNodeId = inviterNodeId.orEmpty(),
                            memberNodeId = nodeId,
                            inviterDisplayName = displayName,
                            memberDisplayName = chosenName,
                            scope = "direct"
                        )
                        app.governanceRepository.bindAlias(
                            nodeId = self?.nodeId ?: "local",
                            contactId = nodeId,
                            aliasId = aliasId ?: fingerprint.take(12),
                            scope = "direct",
                            epoch = epoch,
                            relationDistance = 1
                        )
                        if (role.equals("relay", ignoreCase = true)) {
                            app.governanceRepository.grantRole(
                                nodeId = nodeId,
                                role = GovernanceRole.RELAY,
                                grantedBy = inviterNodeId ?: nodeId,
                                attestationRef = "role:${attestationRef ?: nodeId}:relay",
                                expiresAtEpochMillis = null
                            )
                        }
                        Snackbar.make(
                            binding.root,
                            getString(R.string.invite_accept, chosenName),
                            Snackbar.LENGTH_LONG
                        ).show()
                        startActivity(ChatActivity.intent(this@JoinActivity, nodeId, chosenName))
                        finish()
                    }
                }
            }
            .show()
    }

    private fun promptContactName(defaultName: String, onConfirmed: (String) -> Unit) {
        val dialogBinding = DialogContactNameBinding.inflate(LayoutInflater.from(this))
        dialogBinding.contactNameInput.setText(defaultName)
        dialogBinding.contactNameInput.setSelection(dialogBinding.contactNameInput.text?.length ?: 0)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.join_contact_name_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.common_save) { _, _ ->
                val name = dialogBinding.contactNameInput.text?.toString()?.ifBlank { defaultName } ?: defaultName
                onConfirmed(name)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
