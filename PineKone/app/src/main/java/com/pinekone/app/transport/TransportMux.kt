package com.pinekone.app.transport

import android.util.Log
import com.pinekone.app.engine.InboundFrame
import com.pinekone.app.engine.MeshTransport
import com.pinekone.app.engine.PkPeer
import com.pinekone.app.engine.RadioMode
import com.pinekone.app.engine.TransportKind
import com.pinekone.app.engine.TransportState
import com.pinekone.app.protocol.PkControlFrame
import com.pinekone.app.protocol.PkEnvelope
import com.pinekone.app.protocol.PkFormats
import com.pinekone.app.protocol.toHexString
import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

private const val TAG = "TransportMux"

class TransportMux(
    private val scope: CoroutineScope,
    private val transports: List<Transport>,
    private val clock: Clock = Clock.systemUTC()
) : MeshTransport {

    private data class PeerKey(val kind: RadioKind, val id: String)

    private data class PeerEntry(
        val transport: Transport,
        var radioPeer: RadioPeer,
        var pkPeer: PkPeer
    )

    private val started = AtomicBoolean(false)
    private val peersState = MutableStateFlow<List<PkPeer>>(emptyList())
    private val inboundFlow = MutableSharedFlow<InboundFrame>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val availabilityState = MutableStateFlow<List<TransportState>>(emptyList())
    private val modeState = MutableStateFlow(RadioMode.FULL)

    private val peerMutex = Mutex()
    private val peerEntries = mutableMapOf<PeerKey, PeerEntry>()
    private val peerStreams = mutableMapOf<PeerKey, Job>()
    private val peerRoutes = mutableMapOf<String, PeerKey>()
    private val transportCollectors = mutableMapOf<Transport, Job>()

    override val peers: StateFlow<List<PkPeer>> = peersState
    override val inbound = inboundFlow.asSharedFlow()
    override val availability: StateFlow<List<TransportState>> = availabilityState
    override val radioMode: StateFlow<RadioMode> = modeState

    override suspend fun start() {
        if (!started.compareAndSet(false, true)) return
        applyMode(modeState.value)
    }

    override suspend fun stop() {
        if (!started.compareAndSet(true, false)) return
        transports.forEach { transport -> deactivateTransport(transport) }
        peerMutex.withLock {
            peerEntries.clear()
            peerRoutes.clear()
            peersState.value = emptyList()
            publishAvailabilityLocked()
        }
    }

    override suspend fun setRadioMode(mode: RadioMode) {
        if (modeState.value == mode) return
        modeState.value = mode
        if (started.get()) {
            applyMode(mode)
        }
    }

    private suspend fun applyMode(mode: RadioMode) {
        val allowedKinds = allowedKindsFor(mode)
        transports.forEach { transport ->
            if (transport.kind in allowedKinds) {
                activateTransport(transport)
            } else {
                deactivateTransport(transport)
            }
        }
        publishAvailability()
    }

    private suspend fun activateTransport(transport: Transport) {
        if (transportCollectors.containsKey(transport)) {
            if (!transport.isAvailable()) {
                deactivateTransport(transport)
            }
            return
        }
        if (!transport.isAvailable()) {
            return
        }
        transport.advertise(true)
        transport.startDiscovery()
        val job = scope.launch {
            transport.peers().collect { event ->
                when (event) {
                    is PeerEvent.Upsert -> handlePeerUpsert(transport, event.peer)
                    is PeerEvent.Removed -> handlePeerRemoval(PeerKey(event.kind, event.peerId))
                }
            }
        }
        transportCollectors[transport] = job
    }

    private suspend fun deactivateTransport(transport: Transport) {
        transportCollectors.remove(transport)?.cancel()
        runCatching { transport.stopDiscovery() }
        runCatching { transport.advertise(false) }
        peerMutex.withLock {
            val keysToRemove = peerEntries.keys.filter { it.kind == transport.kind }
            keysToRemove.forEach { key ->
                peerEntries.remove(key)
                peerRoutes.entries.removeIf { it.value == key }
                peerStreams.remove(key)?.cancel()
            }
            publishPeersLocked()
            publishAvailabilityLocked()
        }
    }

    private fun allowedKindsFor(mode: RadioMode): Set<RadioKind> =
        when (mode) {
            RadioMode.BT_ONLY -> setOf(RadioKind.BLE)
            RadioMode.FULL -> transports.map { it.kind }.toSet()
        }

    override suspend fun send(envelope: PkEnvelope, peerId: String?) {
        val packet = MeshPacket.EnvelopePacket(envelope)
        val payload = PkFormats.json.encodeToString(MeshPacket.serializer(), packet).encodeToByteArray()
        sendBytes(payload, peerId)
    }

    override suspend fun sendControl(frame: PkControlFrame, peerId: String?) {
        val packet = MeshPacket.ControlPacket(frame)
        val payload = PkFormats.json.encodeToString(MeshPacket.serializer(), packet).encodeToByteArray()
        sendBytes(payload, peerId)
    }

    private suspend fun sendBytes(bytes: ByteArray, peerId: String?) {
        if (peerId == null) {
            val candidateGroups = peerMutex.withLock {
                peerEntries
                    .toList()
                    .groupBy { (_, entry) -> logicalPeerKey(entry.pkPeer) }
                    .values
                    .map { group -> prioritize(group.map { it.first }) }
            }
            candidateGroups.forEach { keys ->
                sendViaCandidateKeys(bytes, keys)
            }
            return
        }

        val candidateKeys = peerMutex.withLock { candidateRouteKeysLocked(peerId) }
        if (candidateKeys.isEmpty()) return
        sendViaCandidateKeys(bytes, candidateKeys)
    }

    private suspend fun handlePeerUpsert(transport: Transport, peer: RadioPeer) {
        var streamToStart: Pair<PeerKey, RadioPeer>? = null
        peerMutex.withLock {
            val key = PeerKey(transport.kind, peer.id)
            val pkPeer = buildPkPeer(peer)
            val existing = peerEntries[key]
            if (existing == null) {
                peerEntries[key] = PeerEntry(transport, peer, pkPeer)
                if (!peerStreams.containsKey(key)) {
                    streamToStart = key to peer
                }
            } else {
                existing.radioPeer = peer
                existing.pkPeer = pkPeer
                if (!peerStreams.containsKey(key)) {
                    streamToStart = key to peer
                }
            }
            rebuildRoutesLocked()
            publishPeersLocked()
            publishAvailabilityLocked()
        }

        streamToStart?.let { (key, radioPeer) ->
            val flow = transport.open(radioPeer)
            val job = scope.launch {
                flow.collect { frame ->
                    handleFrame(key, transport, frame)
                }
            }
            peerMutex.withLock { peerStreams[key] = job }
        }
    }

    private suspend fun handlePeerRemoval(key: PeerKey) {
        val job: Job? = peerMutex.withLock {
            if (peerEntries.remove(key) == null) return@withLock null
            rebuildRoutesLocked()
            publishPeersLocked()
            publishAvailabilityLocked()
            peerStreams.remove(key)
        }
        job?.cancel()
    }

    private suspend fun handleFrame(key: PeerKey, transport: Transport, frame: TransportFrame) {
        val peerSnapshot = peerMutex.withLock {
            val entry = peerEntries[key] ?: return
            val refreshed = entry.pkPeer.copy(lastSeen = Instant.now(clock))
            entry.pkPeer = refreshed
            publishPeersLocked()
            refreshed
        }

        val packet = runCatching {
            PkFormats.json.decodeFromString(MeshPacket.serializer(), frame.bytes.decodeToString())
        }.getOrElse {
            Log.e(TAG, "Failed to decode frame from ${transport.kind}", it)
            return
        }

        when (packet) {
            is MeshPacket.Handshake -> {
                val updatedPeer = RadioPeer(
                    id = packet.nodeId,
                    displayName = packet.displayName,
                    fingerprint = packet.fingerprint,
                    publicKey = packet.publicKey,
                    kind = transport.kind,
                    metadata = mapOf(
                        "maxFanout" to packet.capabilities.maxFanout,
                        "minBatteryPct" to packet.capabilities.minBatteryPct
                    )
                )
                handlePeerUpsert(transport, updatedPeer)
            }
            is MeshPacket.EnvelopePacket -> inboundFlow.emit(
                InboundFrame.EnvelopeFrame(packet.envelope, peerSnapshot)
            )
            is MeshPacket.ControlPacket -> inboundFlow.emit(
                InboundFrame.Control(packet.frame, peerSnapshot)
            )
        }
    }

    private fun buildPkPeer(peer: RadioPeer): PkPeer {
        val battery = peer.metadata["batteryPct"] as? Int
        val quality = (peer.metadata["quality"] as? Double) ?: DEFAULT_QUALITY
        val transportKind = if (peer.kind == RadioKind.WEB) TransportKind.WEB else TransportKind.MESH
        return PkPeer(
            id = peer.id,
            displayName = peer.displayName,
            lastSeen = Instant.now(clock),
            batteryPct = battery,
            quality = quality,
            transport = transportKind,
            fingerprintHex = peer.fingerprint?.toHexString(),
            publicKey = peer.publicKey
        )
    }

    private fun rebuildRoutesLocked() {
        peerRoutes.clear()
        peerEntries.forEach { (key, entry) ->
            val current = peerRoutes[entry.pkPeer.id]
            if (current == null) {
                peerRoutes[entry.pkPeer.id] = key
            } else if (priorityFor(key.kind) < priorityFor(current.kind)) {
                peerRoutes[entry.pkPeer.id] = key
            }
        }
    }

    private fun publishPeersLocked() {
        val peers = peerEntries.values
            .groupBy { logicalPeerKey(it.pkPeer) }
            .values
            .map(::mergePeerEntries)
            .sortedBy { it.displayName.lowercase() }
        peersState.value = peers
    }

    private fun publishAvailability() {
        scope.launch {
            peerMutex.withLock {
                publishAvailabilityLocked()
            }
        }
    }

    private fun publishAvailabilityLocked() {
        val counts = mutableMapOf<RadioKind, Int>()
        peerEntries.forEach { (key, _) ->
            counts.merge(key.kind, 1, Int::plus)
        }
        val states = transports.map { transport ->
            val active = transportCollectors.containsKey(transport)
            TransportState(
                kind = if (transport.kind == RadioKind.WEB) TransportKind.WEB else TransportKind.MESH,
                radio = transport.kind,
                available = active && transport.isAvailable(),
                peerCount = if (active) counts[transport.kind] ?: 0 else 0
            )
        }
        availabilityState.value = states
    }

    private fun priorityFor(kind: RadioKind): Int =
        when (kind) {
            RadioKind.WIFI_P2P -> 0
            RadioKind.WIFI_AWARE -> 1
            RadioKind.LAN -> 2
            RadioKind.BLE -> 3
            RadioKind.NEARBY -> 4
            RadioKind.WEB -> 5
        }

    private suspend fun sendViaCandidateKeys(bytes: ByteArray, candidateKeys: List<PeerKey>) {
        for (key in candidateKeys) {
            val entry = peerMutex.withLock { peerEntries[key] } ?: continue
            val success = runCatching { entry.transport.send(entry.radioPeer, bytes) }
                .onFailure { Log.e(TAG, "Failed to send frame to ${entry.radioPeer.id} via ${entry.transport.kind}", it) }
                .getOrDefault(false)
            if (success) {
                return
            }
            Log.w(TAG, "Transport ${entry.transport.kind} rejected send to ${entry.radioPeer.id}; trying next route")
        }
    }

    private fun candidateRouteKeysLocked(peerId: String): List<PeerKey> {
        val exactMatches = peerEntries
            .filter { (_, entry) -> entry.pkPeer.id == peerId }
            .keys
            .toList()
        return when {
            exactMatches.isNotEmpty() -> prioritize(exactMatches)
            peerRoutes[peerId] != null -> listOf(peerRoutes.getValue(peerId))
            else -> emptyList()
        }
    }

    private fun prioritize(keys: List<PeerKey>): List<PeerKey> =
        keys.sortedBy { priorityFor(it.kind) }

    private fun logicalPeerKey(peer: PkPeer): String =
        peer.fingerprintHex?.takeIf { it.isNotBlank() } ?: peer.id

    private fun mergePeerEntries(entries: List<PeerEntry>): PkPeer {
        val preferred = entries.minWithOrNull(
            compareBy<PeerEntry> { priorityFor(it.radioPeer.kind) }
                .thenByDescending { it.pkPeer.quality }
                .thenByDescending { it.pkPeer.lastSeen }
        ) ?: entries.first()
        val newestLastSeen = entries.maxOf { it.pkPeer.lastSeen }
        val strongestQuality = entries.maxOf { it.pkPeer.quality }
        val battery = entries.mapNotNull { it.pkPeer.batteryPct }.maxOrNull()
        val publicKey = preferred.pkPeer.publicKey ?: entries.firstNotNullOfOrNull { it.pkPeer.publicKey }
        val fingerprint = preferred.pkPeer.fingerprintHex ?: entries.firstNotNullOfOrNull { it.pkPeer.fingerprintHex }
        return preferred.pkPeer.copy(
            lastSeen = newestLastSeen,
            quality = strongestQuality,
            batteryPct = battery,
            publicKey = publicKey,
            fingerprintHex = fingerprint
        )
    }

    companion object {
        private const val DEFAULT_QUALITY = 0.5
    }
}
