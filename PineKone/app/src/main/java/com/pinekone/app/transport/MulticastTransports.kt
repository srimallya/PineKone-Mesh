package com.pinekone.app.transport

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.pinekone.app.identity.IdentityRepository
import com.pinekone.app.protocol.PkFormats
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

class WifiAwareTransport(
    context: Context,
    scope: CoroutineScope,
    identityRepository: IdentityRepository
) : Transport {
    private val delegate = MulticastMeshTransport(
        context = context,
        scope = scope,
        identityRepository = identityRepository,
        kind = RadioKind.WIFI_AWARE,
        quality = 0.75,
        config = MulticastMeshTransport.Config(
            groupAddress = "239.120.67.21",
            port = 30471,
            beaconIntervalMs = 6_000L
        ),
        availabilityCheck = {
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)
        }
    )

    override val kind: RadioKind get() = delegate.kind
    override fun startDiscovery() = delegate.startDiscovery()
    override fun stopDiscovery() = delegate.stopDiscovery()
    override fun advertise(on: Boolean) = delegate.advertise(on)
    override fun peers(): Flow<PeerEvent> = delegate.peers()
    override fun open(peer: RadioPeer): Flow<TransportFrame> = delegate.open(peer)
    override suspend fun send(peer: RadioPeer, frame: ByteArray): Boolean = delegate.send(peer, frame)
    override fun isAvailable(): Boolean = delegate.isAvailable()
}

class LanMulticastTransport(
    context: Context,
    scope: CoroutineScope,
    identityRepository: IdentityRepository
) : Transport {
    private val delegate = MulticastMeshTransport(
        context = context,
        scope = scope,
        identityRepository = identityRepository,
        kind = RadioKind.LAN,
        quality = 0.6,
        config = MulticastMeshTransport.Config(
            groupAddress = "239.120.67.11",
            port = 30470,
            beaconIntervalMs = 5_000L
        ),
        availabilityCheck = {
            val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
            wifi?.isWifiEnabled == true
        }
    )

    override val kind: RadioKind get() = delegate.kind
    override fun startDiscovery() = delegate.startDiscovery()
    override fun stopDiscovery() = delegate.stopDiscovery()
    override fun advertise(on: Boolean) = delegate.advertise(on)
    override fun peers(): Flow<PeerEvent> = delegate.peers()
    override fun open(peer: RadioPeer): Flow<TransportFrame> = delegate.open(peer)
    override suspend fun send(peer: RadioPeer, frame: ByteArray): Boolean = delegate.send(peer, frame)
    override fun isAvailable(): Boolean = delegate.isAvailable()
}

private class MulticastMeshTransport(
    private val context: Context,
    private val scope: CoroutineScope,
    private val identityRepository: IdentityRepository,
    override val kind: RadioKind,
    private val quality: Double,
    private val config: Config,
    private val availabilityCheck: () -> Boolean
) : Transport {

    data class Config(
        val groupAddress: String,
        val port: Int,
        val beaconIntervalMs: Long
    )

    private val peerEvents = MutableSharedFlow<PeerEvent>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val peerFlows = ConcurrentHashMap<String, MutableSharedFlow<TransportFrame>>()
    private val peerAddresses = ConcurrentHashMap<String, InetSocketAddress>()
    private val addressToPeer = ConcurrentHashMap<String, String>()
    private val peerMutex = Mutex()

    private val discoveryActive = AtomicBoolean(false)
    private val advertiseActive = AtomicBoolean(false)
    private val identityReady = AtomicBoolean(false)

    private var receiverJob: Job? = null
    private var beaconJob: Job? = null
    private var socket: MulticastSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)

    private val identityMutex = Mutex()
    private lateinit var localNodeId: String
    private lateinit var handshakeBytes: ByteArray

    override fun startDiscovery() {
        scope.launch(Dispatchers.IO) {
            if (!ensureIdentity()) return@launch
            if (!discoveryActive.compareAndSet(false, true)) return@launch
            startReceiver()
        }
    }

    override fun stopDiscovery() {
        if (!discoveryActive.compareAndSet(true, false)) return
        receiverJob?.cancel()
        receiverJob = null
        runCatching {
            socket?.leaveGroup(InetAddress.getByName(config.groupAddress))
        }
        runCatching { socket?.close() }
        socket = null
        multicastLock?.let {
            if (it.isHeld) it.release()
        }
        multicastLock = null
        scope.launch {
            val removed = peerMutex.withLock {
                val ids = peerAddresses.keys.toList()
                peerAddresses.clear()
                addressToPeer.clear()
                peerFlows.clear()
                ids
            }
            removed.forEach { id ->
                peerEvents.emit(PeerEvent.Removed(id, kind))
            }
        }
    }

    override fun advertise(on: Boolean) {
        if (on) {
            scope.launch(Dispatchers.IO) {
                if (!ensureIdentity()) return@launch
                if (!advertiseActive.compareAndSet(false, true)) return@launch
                sendHandshake()
                beaconJob = launch {
                    while (isActive) {
                        delay(config.beaconIntervalMs)
                        sendHandshake()
                    }
                }
            }
        } else {
            advertiseActive.set(false)
            beaconJob?.cancel()
            beaconJob = null
        }
    }

    override fun peers(): Flow<PeerEvent> = peerEvents.asSharedFlow()

    override fun open(peer: RadioPeer): Flow<TransportFrame> =
        peerFlows.getOrPut(peer.id) {
            MutableSharedFlow(
                replay = 0,
                extraBufferCapacity = 16,
                onBufferOverflow = BufferOverflow.DROP_OLDEST
            )
        }.asSharedFlow()

    override suspend fun send(peer: RadioPeer, frame: ByteArray): Boolean {
        if (!ensureIdentity()) return false
        val target = peerAddresses[peer.id] ?: return false
        return withContext(Dispatchers.IO) {
            runCatching {
                DatagramSocket().use { socket ->
                    val packet = DatagramPacket(frame, frame.size, target)
                    socket.send(packet)
                }
                true
            }.onFailure {
                Log.e(TAG, "Failed to send frame to ${peer.id}", it)
            }.getOrDefault(false)
        }
    }

    override fun isAvailable(): Boolean = availabilityCheck()

    private suspend fun startReceiver() {
        val group = InetAddress.getByName(config.groupAddress)
        val socket = MulticastSocket(config.port).apply {
            reuseAddress = true
            joinGroup(group)
        }
        this.socket = socket
        if (hasMulticastPermission()) {
            multicastLock = wifiManager?.createMulticastLock("pinekone-mesh-${kind.name.lowercase()}")?.apply {
                setReferenceCounted(true)
                runCatching { acquire() }
                    .onFailure { error ->
                        Log.e(TAG, "Failed to acquire multicast lock for $kind", error)
                    }
            }
        } else {
            Log.w(TAG, "Missing CHANGE_WIFI_MULTICAST_STATE; skipping multicast lock for $kind")
        }
        receiverJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(64 * 1024)
            while (isActive) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                    val length = packet.length
                    if (length <= 0) continue
                    val payload = packet.data.copyOfRange(packet.offset, packet.offset + length)
                    handleIncoming(packet.address, packet.port, payload)
                } catch (ex: SocketException) {
                    if (socket.isClosed) break
                    Log.w(TAG, "Socket closed for $kind", ex)
                } catch (t: Throwable) {
                    Log.e(TAG, "Receiver loop error for $kind", t)
                }
            }
        }
    }

    private fun hasMulticastPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CHANGE_WIFI_MULTICAST_STATE
        ) == PackageManager.PERMISSION_GRANTED

    private suspend fun handleIncoming(address: InetAddress, port: Int, bytes: ByteArray) {
        if (!ensureIdentity()) return
        val payload = bytes.decodeToString()
        val packet = runCatching {
            PkFormats.json.decodeFromString(MeshPacket.serializer(), payload)
        }.getOrElse {
            Log.w(TAG, "Failed to decode packet on $kind", it)
            return
        }
        val addressKey = keyFor(address, port)
        when (packet) {
            is MeshPacket.Handshake -> handleHandshake(packet, address, port, addressKey)
            is MeshPacket.EnvelopePacket -> emitFrame(addressKey, bytes, TransportFrame.FrameType.PAYLOAD)
            is MeshPacket.ControlPacket -> emitFrame(addressKey, bytes, TransportFrame.FrameType.CONTROL)
        }
    }

    private suspend fun handleHandshake(
        packet: MeshPacket.Handshake,
        address: InetAddress,
        port: Int,
        addressKey: String
    ) {
        if (packet.nodeId == localNodeId) return
        val socketAddress = InetSocketAddress(address, port)
        val peer = RadioPeer(
            id = packet.nodeId,
            displayName = packet.displayName,
            fingerprint = packet.fingerprint,
            publicKey = packet.publicKey,
            kind = kind,
            metadata = mapOf(
                "quality" to quality,
                "maxFanout" to packet.capabilities.maxFanout,
                "minBatteryPct" to packet.capabilities.minBatteryPct
            )
        )
        val isNew = peerMutex.withLock {
            val previouslyKnown = peerAddresses.containsKey(peer.id)
            peerAddresses[peer.id] = socketAddress
            addressToPeer[addressKey] = peer.id
            peerFlows.getOrPut(peer.id) {
                MutableSharedFlow(
                    replay = 0,
                    extraBufferCapacity = 16,
                    onBufferOverflow = BufferOverflow.DROP_OLDEST
                )
            }
            !previouslyKnown
        }
        peerEvents.emit(PeerEvent.Upsert(peer))
        if (isNew || advertiseActive.get()) {
            sendHandshake(socketAddress)
        }
    }

    private suspend fun emitFrame(addressKey: String, payload: ByteArray, type: TransportFrame.FrameType) {
        val peerId = addressToPeer[addressKey] ?: return
        val flow = peerFlows[peerId] ?: return
        flow.emit(TransportFrame(bytes = payload, type = type))
    }

    private suspend fun ensureIdentity(): Boolean {
        if (identityReady.get()) return true
        return identityMutex.withLock {
            if (identityReady.get()) return true
            runCatching {
                val identity = identityRepository.getIdentity()
                localNodeId = identity.nodeId
                val handshake = MeshPacket.Handshake(
                    nodeId = identity.nodeId,
                    displayName = identity.displayName,
                    publicKey = identity.publicKey,
                    fingerprint = identity.fingerprint
                )
                handshakeBytes = PkFormats.json
                    .encodeToString(MeshPacket.serializer(), handshake)
                    .encodeToByteArray()
                identityReady.set(true)
            }.onFailure {
                Log.e(TAG, "Failed to load identity for $kind", it)
            }.isSuccess
        }
    }

    private suspend fun sendHandshake(target: InetSocketAddress? = null) {
        if (!identityReady.get()) return
        withContext(Dispatchers.IO) {
            val address = target?.address ?: InetAddress.getByName(config.groupAddress)
            val port = target?.port ?: config.port
            runCatching {
                DatagramSocket().use { socket ->
                    val packet = DatagramPacket(handshakeBytes, handshakeBytes.size, address, port)
                    socket.send(packet)
                }
            }.onFailure {
                Log.e(TAG, "Failed to broadcast handshake for $kind", it)
            }
        }
    }

    private fun keyFor(address: InetAddress, port: Int): String =
        "${address.hostAddress}:$port"

    companion object {
        private const val TAG = "MulticastTransport"
    }
}
