package com.pinekone.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.pinekone.app.data.model.AutoDownloadImages
import com.pinekone.app.data.model.AutoPlayVoiceNotes
import com.pinekone.app.data.model.DefaultPrivacyMode
import com.pinekone.app.data.model.MapVisibilityDefault
import com.pinekone.app.databinding.ActivitySettingsBinding
import com.pinekone.app.ui.MeshViewModel
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var viewModel: MeshViewModel
    private var suppressCallbacks = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        viewModel = ViewModelProvider(this, MeshViewModel.factory(application))[MeshViewModel::class.java]

        setSupportActionBar(binding.settingsToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.settingsToolbar.setNavigationOnClickListener { finish() }

        bindControls()
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                viewModel.settings.collect { settings ->
                    suppressCallbacks = true
                    binding.mapVisibilityGroup.check(
                        when (settings.mapVisibilityDefault) {
                            MapVisibilityDefault.ALL_DISCOVERED -> binding.mapVisibilityAll.id
                            MapVisibilityDefault.CONTACTS_ONLY -> binding.mapVisibilityContacts.id
                            MapVisibilityDefault.TRUSTED_ONLY -> binding.mapVisibilityTrusted.id
                        }
                    )
                    binding.showUnverifiedSwitch.isChecked = settings.showUnverifiedPeers
                    binding.showDiagnosticsSwitch.isChecked = settings.showDiagnostics
                    binding.privacyModeGroup.check(
                        when (settings.defaultPrivacyMode) {
                            DefaultPrivacyMode.BALANCED -> binding.privacyBalanced.id
                            DefaultPrivacyMode.STRICT -> binding.privacyStrict.id
                        }
                    )
                    binding.autoDownloadGroup.check(
                        when (settings.autoDownloadImages) {
                            AutoDownloadImages.WIFI_ONLY -> binding.autoDownloadWifi.id
                            AutoDownloadImages.ALWAYS -> binding.autoDownloadAlways.id
                            AutoDownloadImages.NEVER -> binding.autoDownloadNever.id
                        }
                    )
                    binding.autoPlayVoiceGroup.check(
                        when (settings.autoPlayVoiceNotes) {
                            AutoPlayVoiceNotes.NEVER -> binding.voicePlayNever.id
                            AutoPlayVoiceNotes.MANUAL_ONLY -> binding.voicePlayManual.id
                            AutoPlayVoiceNotes.TRUSTED_ONLY -> binding.voicePlayTrusted.id
                        }
                    )
                    suppressCallbacks = false
                }
            }
        }
    }

    private fun bindControls() {
        binding.mapVisibilityGroup.setOnCheckedChangeListener { _, checkedId ->
            if (suppressCallbacks) return@setOnCheckedChangeListener
            viewModel.setMapVisibility(
                when (checkedId) {
                    binding.mapVisibilityContacts.id -> MapVisibilityDefault.CONTACTS_ONLY
                    binding.mapVisibilityTrusted.id -> MapVisibilityDefault.TRUSTED_ONLY
                    else -> MapVisibilityDefault.ALL_DISCOVERED
                }
            )
        }
        binding.showUnverifiedSwitch.setOnCheckedChangeListener { _, checked ->
            if (!suppressCallbacks) {
                viewModel.setShowUnverifiedPeers(checked)
            }
        }
        binding.showDiagnosticsSwitch.setOnCheckedChangeListener { _, checked ->
            if (!suppressCallbacks) {
                viewModel.setShowDiagnostics(checked)
            }
        }
        binding.privacyModeGroup.setOnCheckedChangeListener { _, checkedId ->
            if (suppressCallbacks) return@setOnCheckedChangeListener
            viewModel.setDefaultPrivacyMode(
                if (checkedId == binding.privacyStrict.id) DefaultPrivacyMode.STRICT else DefaultPrivacyMode.BALANCED
            )
        }
        binding.autoDownloadGroup.setOnCheckedChangeListener { _, checkedId ->
            if (suppressCallbacks) return@setOnCheckedChangeListener
            viewModel.setAutoDownloadImages(
                when (checkedId) {
                    binding.autoDownloadAlways.id -> AutoDownloadImages.ALWAYS
                    binding.autoDownloadNever.id -> AutoDownloadImages.NEVER
                    else -> AutoDownloadImages.WIFI_ONLY
                }
            )
        }
        binding.autoPlayVoiceGroup.setOnCheckedChangeListener { _, checkedId ->
            if (suppressCallbacks) return@setOnCheckedChangeListener
            viewModel.setAutoPlayVoiceNotes(
                when (checkedId) {
                    binding.voicePlayNever.id -> AutoPlayVoiceNotes.NEVER
                    binding.voicePlayTrusted.id -> AutoPlayVoiceNotes.TRUSTED_ONLY
                    else -> AutoPlayVoiceNotes.MANUAL_ONLY
                }
            )
        }
    }
}
