package com.pinekone.app

import android.content.Intent
import android.content.Context
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
import com.pinekone.app.databinding.FragmentNearbyBinding
import com.pinekone.app.engine.PingEvent
import com.pinekone.app.engine.PkPeer
import com.pinekone.app.protocol.toHexString
import com.pinekone.app.ui.MeshViewModel
import com.pinekone.app.ui.PeerAdapter
import com.pinekone.app.ui.PeerPresentation
import com.pinekone.app.ui.PingStatus
import com.pinekone.app.ui.PingUiState
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import androidx.core.content.ContextCompat

class NearbyFragment : Fragment() {

    private var _binding: FragmentNearbyBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: MeshViewModel
    private lateinit var mapView: MapView
    private lateinit var peerAdapter: PeerAdapter

    private val markers = mutableMapOf<String, Marker>()
    private val latestPeers = mutableMapOf<String, PeerPresentation>()
    private val pingStates = mutableMapOf<String, PingUiState>()
    private val pingResetJobs = mutableMapOf<String, Job>()

    private var selfMarker: Marker? = null
    private var myPosition: GeoPoint? = null
    private var hasCenteredOnSelf = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentNearbyBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(
            requireActivity(),
            MeshViewModel.factory(requireActivity().application)
        )[MeshViewModel::class.java]

        peerAdapter = PeerAdapter { peer ->
            setPingState(peer.peer.id, PingUiState(PingStatus.PENDING))
            viewModel.pingPeer(peer.peer.id)
        }

        binding.nearbyList.layoutManager = LinearLayoutManager(requireContext())
        binding.nearbyList.adapter = peerAdapter

        configureMapView(requireContext())

        binding.viewModeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            when (checkedId) {
                binding.viewModeList.id -> showListView()
                binding.viewModeMap.id -> showMapView()
            }
        }
        binding.viewModeToggle.check(binding.viewModeList.id)
        showListView()

        binding.mapPingButton.setOnClickListener { pingAllPeers() }
        binding.nearbyActionButton.setOnClickListener {
            startActivity(Intent(requireContext(), JoinActivity::class.java))
        }

        collectIdentity()
        collectPeers()
        collectPingEvents()
        collectTransportAvailability()
    }

    private fun collectTransportAvailability() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                viewModel.transportAvailability.collect { states ->
                    updateTransportStatus(states)
                }
            }
        }
    }

    private fun updateTransportStatus(states: List<com.pinekone.app.engine.TransportState>) {
        val statusView = binding.nearbyTransportStatus
        if (states.isEmpty()) {
            statusView.text = getString(R.string.nearby_transport_scanning)
            return
        }
        val hasAvailable = states.any { it.available }
        val anyPeers = states.any { it.peerCount > 0 }
        val webOnly = states.all { !it.available } && !anyPeers

        val text = when {
            webOnly -> getString(R.string.nearby_transport_web_only)
            hasAvailable && anyPeers -> getString(R.string.nearby_transport_ready)
            else -> getString(R.string.nearby_transport_scanning)
        }
        statusView.text = text
    }

    private fun collectIdentity() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                viewModel.identity.collect { identity ->
                    identity?.let {
                        myPosition = generatePositionFromKey(it.fingerprint.toHexString())
                        updateSelfMarker()
                    }
                }
            }
        }
    }

    private fun collectPeers() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                viewModel.visiblePeers.collect { peers ->
                    val settings = viewModel.settings.value
                    binding.nearbyStatus.text = if (peers.isEmpty()) {
                        getString(R.string.nearby_summary_title_idle)
                    } else {
                        getString(R.string.nearby_summary_title_active, peers.size)
                    }
                    binding.nearbyFilter.text = when (settings.mapVisibilityDefault) {
                        com.pinekone.app.data.model.MapVisibilityDefault.ALL_DISCOVERED -> getString(R.string.settings_map_all)
                        com.pinekone.app.data.model.MapVisibilityDefault.CONTACTS_ONLY -> getString(R.string.settings_map_contacts)
                        com.pinekone.app.data.model.MapVisibilityDefault.TRUSTED_ONLY -> getString(R.string.settings_map_trusted)
                    }
                    binding.nearbyHint.text = if (peers.isEmpty()) {
                        getString(
                            R.string.nearby_summary_empty_filtered,
                            binding.nearbyFilter.text
                        )
                    } else {
                        getString(R.string.nearby_summary_ready)
                    }

                    binding.nearbyEmpty.isVisible = false
                    binding.mapPingButton.isEnabled = peers.isNotEmpty()
                    binding.mapPingButton.visibility =
                        if (binding.mapContainer.isVisible) View.VISIBLE else View.GONE
                    binding.viewModeMap.isEnabled = peers.isNotEmpty()
                    if (peers.isEmpty() && binding.mapContainer.isVisible) {
                        binding.viewModeToggle.check(binding.viewModeList.id)
                        showListView()
                    }

                    peerAdapter.submitList(peers)
                    trimPingStates(peers)
                    peerAdapter.updatePingStates(pingStates.toMap())
                    updateMarkers(peers)
                }
            }
        }
    }

    private fun collectPingEvents() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                viewModel.pingEvents.collect { event ->
                    when (event) {
                        is PingEvent.Started -> setPingState(event.peerId, PingUiState(PingStatus.PENDING))
                        is PingEvent.Success -> setPingState(
                            event.peerId,
                            PingUiState(PingStatus.SUCCESS, event.latencyMs)
                        )
                        is PingEvent.Timeout -> setPingState(event.peerId, PingUiState(PingStatus.FAILURE))
                    }
                }
            }
        }
    }

    private fun showListView() {
        binding.nearbyList.isVisible = true
        binding.nearbyEmpty.isVisible = false
        binding.mapContainer.isVisible = false
        binding.mapPingButton.visibility = View.GONE
        if (this::mapView.isInitialized) {
            mapView.onPause()
        }
    }

    private fun showMapView() {
        if (peerAdapter.itemCount == 0) {
            showListView()
            return
        }
        binding.nearbyList.isVisible = false
        binding.nearbyEmpty.isVisible = false
        binding.mapContainer.isVisible = true
        binding.mapPingButton.visibility = View.VISIBLE
        if (this::mapView.isInitialized) {
            mapView.onResume()
        }
        centerOnSelfIfAvailable()
    }

    private fun pingAllPeers() {
        val peers = viewModel.visiblePeers.value
        if (peers.isEmpty()) return
        peers.forEach { peer ->
            setPingState(peer.peer.id, PingUiState(PingStatus.PENDING))
            viewModel.pingPeer(peer.peer.id)
        }
    }

    private fun trimPingStates(peers: List<PeerPresentation>) {
        val activeIds = peers.map { it.peer.id }.toSet()
        val removed = pingStates.keys - activeIds
        if (removed.isEmpty()) return
        removed.forEach { id ->
            pingStates.remove(id)
            pingResetJobs.remove(id)?.cancel()
        }
    }

    private fun updateMarkers(peers: List<PeerPresentation>) {
        val activeIds = peers.map { it.peer.id }.toSet()
        val removed = markers.keys - activeIds
        removed.forEach { id ->
            markers.remove(id)?.let { mapView.overlays.remove(it) }
            latestPeers.remove(id)
        }

        peers.forEach { presentation ->
            val peer = presentation.peer
            latestPeers[peer.id] = presentation
            val marker = markers[peer.id] ?: Marker(mapView).apply {
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                markers[peer.id] = this
                mapView.overlays.add(this)
            }
            marker.position = generatePosition(peer)
            marker.title = "${peer.displayName} • ${presentation.visibilityLabel}"
            val state = pingStates[peer.id] ?: PingUiState(PingStatus.IDLE)
            applyMarkerState(peer.id, state, invalidate = false)
        }

        if (binding.mapContainer.isVisible) {
            mapView.invalidate()
        }
    }

    private fun setPingState(peerId: String, state: PingUiState) {
        if (state.status == PingStatus.IDLE) {
            pingStates.remove(peerId)
        } else {
            pingStates[peerId] = state
        }
        peerAdapter.updatePingStates(pingStates.toMap())
        applyMarkerState(peerId, state)
        pingResetJobs.remove(peerId)?.cancel()

        if (state.status == PingStatus.SUCCESS || state.status == PingStatus.FAILURE) {
            pingResetJobs[peerId] = viewLifecycleOwner.lifecycleScope.launch {
                delay(PING_HIGHLIGHT_DURATION_MS)
                if (pingStates[peerId]?.status == state.status) {
                    setPingState(peerId, PingUiState(PingStatus.IDLE))
                }
            }
        }
    }

    private fun applyMarkerState(peerId: String, state: PingUiState, invalidate: Boolean = true) {
        val marker = markers[peerId] ?: return
        val drawableRes = when (state.status) {
            PingStatus.PENDING -> R.drawable.ic_ping_marker_pending
            PingStatus.SUCCESS -> R.drawable.ic_ping_marker_success
            PingStatus.FAILURE -> R.drawable.ic_ping_marker_failed
            PingStatus.IDLE -> R.drawable.ic_ping_marker_default
        }
        marker.icon = ContextCompat.getDrawable(requireContext(), drawableRes)
        val baseName = latestPeers[peerId]?.peer?.displayName ?: marker.title ?: ""
        marker.title = when (state.status) {
            PingStatus.SUCCESS -> {
                val latency = state.latencyMs?.coerceAtLeast(0) ?: 0
                "$baseName • ${getString(R.string.nearby_ping_latency, latency)}"
            }
            PingStatus.FAILURE -> "$baseName • ${getString(R.string.nearby_ping_failed)}"
            PingStatus.PENDING -> "$baseName • ${getString(R.string.nearby_ping_pending)}"
            else -> baseName
        }
        if (invalidate && binding.mapContainer.isVisible) {
            mapView.invalidate()
        }
    }

    private fun generatePosition(peer: PkPeer): GeoPoint {
        val basis = peer.fingerprintHex?.takeIf { it.isNotBlank() } ?: peer.id
        return generatePositionFromKey(basis)
    }

    private fun generatePositionFromKey(key: String): GeoPoint {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.encodeToByteArray())
        val latSeed = ByteBuffer.wrap(digest.copyOfRange(0, 4)).int
        val lngSeed = ByteBuffer.wrap(digest.copyOfRange(4, 8)).int
        val lat = (latSeed / Int.MAX_VALUE.toDouble()) * 40
        val lng = (lngSeed / Int.MAX_VALUE.toDouble()) * 80
        return GeoPoint(lat, lng)
    }

    private fun configureMapView(context: Context) {
        Configuration.getInstance().load(context, context.getSharedPreferences(OSM_PREFS_NAME, Context.MODE_PRIVATE))
        if (Configuration.getInstance().userAgentValue.isNullOrBlank()) {
            Configuration.getInstance().userAgentValue = context.packageName
        }
        mapView = binding.mapView
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(DEFAULT_ZOOM)
        mapView.controller.setCenter(GeoPoint(0.0, 0.0))
    }

    private fun updateSelfMarker() {
        val position = myPosition ?: return
        val marker = selfMarker ?: Marker(mapView).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_ping_marker_self)
            title = getString(R.string.nearby_self_marker)
            mapView.overlays.add(this)
            selfMarker = this
        }
        marker.position = position
        centerOnSelfIfAvailable(force = !hasCenteredOnSelf)
    }

    private fun centerOnSelfIfAvailable(force: Boolean = false) {
        val position = myPosition ?: return
        if (!force && hasCenteredOnSelf) return
        mapView.controller.setZoom(DEFAULT_ZOOM)
        mapView.controller.setCenter(position)
        hasCenteredOnSelf = true
    }

    override fun onResume() {
        super.onResume()
        if (this::mapView.isInitialized) {
            mapView.onResume()
        }
    }

    override fun onPause() {
        if (this::mapView.isInitialized) {
            mapView.onPause()
        }
        super.onPause()
    }

    override fun onDestroyView() {
        pingResetJobs.values.forEach { it.cancel() }
        pingResetJobs.clear()
        if (this::mapView.isInitialized) {
            mapView.onDetach()
        }
        hasCenteredOnSelf = false
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val OSM_PREFS_NAME = "osmdroid"
        private const val DEFAULT_ZOOM = 5.5
        private const val PING_HIGHLIGHT_DURATION_MS = 3_000L
    }
}
