package com.pinekone.app

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayoutMediator
import com.google.android.material.switchmaterial.SwitchMaterial
import com.pinekone.app.databinding.ActivityHomeBinding
import com.pinekone.app.engine.CustodyTicket
import com.pinekone.app.engine.RadioMode
import com.pinekone.app.engine.TransportState
import java.time.Instant
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest

class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private var latestPeerCount: Int = 0
    private var latestRadioMode: RadioMode = RadioMode.FULL
    private var latestTransportStates: List<TransportState> = emptyList()
    private var latestCustodyTicket: CustodyTicket? = null

    private val engine get() = (application as PineKoneApp).engine
    private val pageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            updateFabVisibility(position)
        }
    }

    private val permissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val denied = result.filterValues { granted -> !granted }.keys
            if (denied.isNotEmpty()) {
                Snackbar.make(
                    binding.root,
                    getString(R.string.mesh_permissions_required, denied.joinToString()),
                    Snackbar.LENGTH_LONG
                ).show()
            } else {
                engine.ensureMeshTransportStarted()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.topAppBar)
        binding.topAppBar.inflateMenu(R.menu.menu_home)
        val radioMenuItem = binding.topAppBar.menu.findItem(R.id.action_radio_mode)
        val radioSwitch = radioMenuItem.actionView?.findViewById<SwitchMaterial>(R.id.radioModeSwitch)
        if (radioSwitch != null) {
            var suppressToggle = false
            val updateLabel: (Boolean) -> Unit = { checked ->
                radioSwitch.text = if (checked) {
                    getString(R.string.home_radio_bt_only)
                } else {
                    getString(R.string.home_radio_full)
                }
            }
            radioSwitch.setOnCheckedChangeListener { _, isChecked ->
                if (suppressToggle) return@setOnCheckedChangeListener
                engine.setRadioMode(if (isChecked) RadioMode.BT_ONLY else RadioMode.FULL)
            }
            lifecycleScope.launch {
                repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                    engine.radioMode.collectLatest { mode ->
                        latestRadioMode = mode
                        val checked = mode == RadioMode.BT_ONLY
                        if (radioSwitch.isChecked != checked) {
                            suppressToggle = true
                            radioSwitch.isChecked = checked
                            suppressToggle = false
                        }
                        updateLabel(checked)
                        renderHomeStatus()
                    }
                }
            }
            updateLabel(radioSwitch.isChecked)
        }
        binding.topAppBar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_scan -> {
                    startActivity(Intent(this, JoinActivity::class.java))
                    true
                }
                R.id.action_network -> {
                    startActivity(Intent(this, NetworkActivity::class.java))
                    true
                }
                R.id.action_custody -> {
                    startActivity(Intent(this, CustodyActivity::class.java))
                    true
                }
                R.id.action_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    true
                }
                R.id.action_radio_mode -> true
                else -> false
            }
        }

        val pagerAdapter = HomePagerAdapter(this)
        binding.homePager.adapter = pagerAdapter
        binding.homePager.registerOnPageChangeCallback(pageChangeCallback)
        updateFabVisibility(binding.homePager.currentItem)

        TabLayoutMediator(binding.homeTabs, binding.homePager) { tab, position ->
            tab.text = when (position) {
                0 -> getString(R.string.home_tab_private)
                1 -> getString(R.string.home_tab_public)
                else -> getString(R.string.home_tab_nearby)
            }
        }.attach()

        binding.fabInvite.setOnClickListener {
            startActivity(Intent(this, InviteActivity::class.java))
        }
        binding.homeStatusCard.setOnClickListener {
            startActivity(Intent(this, NetworkActivity::class.java))
        }
        binding.homeStatusDetails.setOnClickListener {
            startActivity(Intent(this, NetworkActivity::class.java))
        }
        binding.homeStatusCard.contentDescription = getString(R.string.home_status_open_details)

        observeStatusStrip()
        styleStatusStrip()
        requestMeshPermissions()
    }

    override fun onDestroy() {
        if (::binding.isInitialized) {
            binding.homePager.unregisterOnPageChangeCallback(pageChangeCallback)
        }
        super.onDestroy()
    }

    private fun requestMeshPermissions() {
        val permissions = buildList {
            add(android.Manifest.permission.BLUETOOTH_SCAN)
            add(android.Manifest.permission.BLUETOOTH_ADVERTISE)
            add(android.Manifest.permission.BLUETOOTH_CONNECT)
            add(android.Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(android.Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionsLauncher.launch(missing.toTypedArray())
        } else {
            engine.ensureMeshTransportStarted()
        }
    }

    private fun updateFabVisibility(@Suppress("UNUSED_PARAMETER") position: Int) {
        if (!::binding.isInitialized) return
        binding.fabInvite.hide()
    }

    private fun observeStatusStrip() {
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                launch {
                    engine.peers.collectLatest { peers ->
                        latestPeerCount = peers.size
                        renderHomeStatus()
                    }
                }
                launch {
                    engine.transportAvailability.collectLatest { states ->
                        latestTransportStates = states
                        renderHomeStatus()
                    }
                }
                launch {
                    engine.custodyTickets.collectLatest { ticket ->
                        latestCustodyTicket = ticket
                        renderHomeStatus()
                    }
                }
            }
        }
    }

    private fun styleStatusStrip() {
        styleChip(binding.homeChipDisclosure, R.color.pk_chip_info_bg, R.color.pk_chip_info_fg)
    }

    private fun renderHomeStatus() {
        if (!::binding.isInitialized) return
        val meshAvailable = latestTransportStates.any { it.available }
        val anyPeers = latestTransportStates.any { it.peerCount > 0 }
        val custodyActive = latestCustodyTicket?.expiryEpochSeconds?.let { it > Instant.now().epochSecond } == true

        binding.homeStatusHeadline.text = if (meshAvailable || anyPeers) {
            getString(R.string.home_status_title_active)
        } else {
            getString(R.string.home_status_title_scanning)
        }

        binding.homeStatusSubline.text = if (!meshAvailable && !anyPeers && latestTransportStates.isNotEmpty()) {
            getString(R.string.home_status_summary_web_only)
        } else {
            val transportLabel = if (latestRadioMode == RadioMode.BT_ONLY) {
                getString(R.string.home_radio_bt_only)
            } else {
                getString(R.string.home_radio_full)
            }
            getString(R.string.home_status_summary, latestPeerCount, transportLabel)
        }

        binding.homeChipTransport.text = if (latestRadioMode == RadioMode.BT_ONLY) {
            getString(R.string.home_status_chip_transport_bt)
        } else {
            getString(R.string.home_status_chip_transport_full)
        }
        styleChip(
            binding.homeChipTransport,
            if (meshAvailable || anyPeers) R.color.pk_chip_success_bg else R.color.pk_chip_neutral_bg,
            if (meshAvailable || anyPeers) R.color.pk_chip_success_fg else R.color.pk_chip_neutral_fg
        )

        binding.homeChipCustody.text = if (custodyActive) {
            getString(R.string.home_status_chip_custody_active)
        } else {
            getString(R.string.home_status_chip_custody_idle)
        }
        styleChip(
            binding.homeChipCustody,
            if (custodyActive) R.color.pk_chip_warn_bg else R.color.pk_chip_neutral_bg,
            if (custodyActive) R.color.pk_chip_warn_fg else R.color.pk_chip_neutral_fg
        )
        binding.homeStatusCard.contentDescription = buildString {
            append(binding.homeStatusHeadline.text)
            append(". ")
            append(binding.homeStatusSubline.text)
        }
    }

    private fun styleChip(chip: Chip, backgroundRes: Int, foregroundRes: Int) {
        chip.chipBackgroundColor = ColorStateList.valueOf(ContextCompat.getColor(this, backgroundRes))
        chip.setTextColor(ContextCompat.getColor(this, foregroundRes))
    }

    private class HomePagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {
        override fun getItemCount(): Int = 3

        override fun createFragment(position: Int): Fragment =
            when (position) {
                0 -> ContactsFragment()
                1 -> PublicChatFragment()
                else -> NearbyFragment()
            }
    }
}
