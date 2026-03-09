package com.pinekone.app.transport

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.NetworkInfo
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.util.Log
import android.os.Build
import androidx.core.content.ContextCompat
import com.pinekone.app.identity.IdentityRepository
import com.pinekone.app.protocol.PkFormats
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

private const val P2P_TAG = "WifiP2pTransport"
private const val P2P_PORT = 93047

class WifiP2pTransport(
    private val context: Context,
    private val scope: CoroutineScope,
    private val identityRepository: IdentityRepository
) : Transport {

    override val kind: RadioKind = RadioKind.WIFI_P2P

    private val manager: WifiP2pManager? = context.getSystemService(WifiP2pManager::class.java)
    private val channel: WifiP2pManager.Channel? =
        manager?.initialize(context, context.mainLooper, ::onChannelDisconnected)

    private val peerEvents = MutableSharedFlow<PeerEvent>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val peerFlows = ConcurrentHashMap<String, MutableSharedFlow<TransportFrame>>()
    private val connections = mutableMapOf<String, P2pConnection>()
    private val connectionMutex = Mutex()

    private val identityLoaded = AtomicBoolean(false)
    private lateinit var localHandshakeBytes: ByteArray
    private lateinit var localNodeId: String

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    handlePeersChanged()
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val networkInfo: NetworkInfo? =
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO)
                    val infoConnected = networkInfo?.isConnected == true
                    if (infoConnected) {
                        val mgr = manager
                        val ch = channel
                        if (mgr != null && ch != null) {
                            runWithWifiPermission("request Wi-Fi Direct connection info") {
                                mgr.requestConnectionInfo(ch) { info ->
                                    scope.launch { handleConnectionInfo(info) }
                                }
                            }
                        }
                    } else {
                        scope.launch { handleAllDisconnected() }
                    }
                }
            }
        }
    }

    private var receiverRegistered = false
    private var discoveryActive = false
    private var serverJob: Job? = null
    private val pendingDevices = mutableSetOf<String>()

    @SuppressLint("MissingPermission")
    override fun startDiscovery() {
        if (!ensureReady()) return
        val mgr = manager
        val ch = channel
        if (mgr == null || ch == null) {
            Log.w(P2P_TAG, "WifiP2pManager not available")
            return
        }
        if (!hasWifiDirectPermission()) {
            Log.w(P2P_TAG, "Missing Wi-Fi Direct permission; cannot start discovery")
            return
        }
        registerReceiverIfNeeded()
        discoveryActive = true
        runWithWifiPermission("start Wi-Fi Direct discovery") {
            mgr.discoverPeers(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(P2P_TAG, "P2P discoverPeers started")
                }

                override fun onFailure(reason: Int) {
                    Log.e(P2P_TAG, "discoverPeers failed: $reason")
                }
            })
        }
    }

    @SuppressLint("MissingPermission")
    override fun stopDiscovery() {
        discoveryActive = false
        val mgr = manager
        val ch = channel
        if (mgr != null && ch != null) {
            runWithWifiPermission("stop Wi-Fi Direct discovery") {
                mgr.stopPeerDiscovery(ch, null)
            }
        }
        unregisterReceiverIfNeeded()
        scope.launch { handleAllDisconnected() }
    }

    override fun advertise(on: Boolean) {
        // Wi-Fi Direct discovery/advertising use the same APIs; nothing extra here.
        if (!on) {
            stopDiscovery()
        } else if (!discoveryActive) {
            startDiscovery()
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
        val connection = connectionMutex.withLock { connections[peer.id] } ?: return false
        return connection.send(frame)
    }

    override fun isAvailable(): Boolean =
        manager != null && hasWifiDirectPermission()

    private fun hasWifiDirectPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            val hasFine = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            val hasCoarse = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            hasFine || hasCoarse
        }

    private inline fun runWithWifiPermission(action: String, block: () -> Unit) {
        if (!hasWifiDirectPermission()) {
            Log.w(P2P_TAG, "Missing Wi-Fi Direct permission; cannot $action")
            return
        }
        try {
            block()
        } catch (error: SecurityException) {
            Log.e(P2P_TAG, "SecurityException while trying to $action", error)
        }
    }

    private fun ensureReady(): Boolean {
        if (!identityLoaded.get()) {
            runBlocking {
                runCatching {
                    val identity = identityRepository.getIdentity()
                    localNodeId = identity.nodeId
                    val packet = MeshPacket.Handshake(
                        nodeId = identity.nodeId,
                        displayName = identity.displayName,
                        publicKey = identity.publicKey,
                        fingerprint = identity.fingerprint
                    )
                    localHandshakeBytes =
                        PkFormats.json.encodeToString(MeshPacket.serializer(), packet).encodeToByteArray()
                    identityLoaded.set(true)
                }.onFailure { error ->
                    Log.e(P2P_TAG, "Failed to load identity", error)
                }
            }
        }
        return identityLoaded.get()
    }

    private fun registerReceiverIfNeeded() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        context.registerReceiver(receiver, filter)
        receiverRegistered = true
    }

    private fun unregisterReceiverIfNeeded() {
        if (!receiverRegistered) return
        context.unregisterReceiver(receiver)
        receiverRegistered = false
    }

    private fun onChannelDisconnected() {
        Log.w(P2P_TAG, "Wi-Fi P2P channel disconnected")
    }

    @SuppressLint("MissingPermission")
    private fun handlePeersChanged() {
        val mgr = manager
        val ch = channel
        if (mgr == null || ch == null) return
        runWithWifiPermission("request Wi-Fi Direct peers") {
            mgr.requestPeers(ch) { peers ->
                peers.deviceList.forEach { device ->
                    if (device.status == WifiP2pDevice.AVAILABLE && pendingDevices.add(device.deviceAddress)) {
                        connectToDevice(device)
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: WifiP2pDevice) {
        val mgr = manager
        val ch = channel
        if (mgr == null || ch == null) return
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            wps.setup = WpsInfo.PBC
        }
        runWithWifiPermission("connect to ${device.deviceAddress}") {
            mgr.connect(ch, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(P2P_TAG, "Connecting to ${device.deviceAddress}")
                }

                override fun onFailure(reason: Int) {
                    Log.e(P2P_TAG, "Connection to ${device.deviceAddress} failed: $reason")
                    pendingDevices.remove(device.deviceAddress)
                }
            })
        }
    }

    private suspend fun handleConnectionInfo(info: WifiP2pInfo) {
        if (info.groupFormed) {
            if (info.isGroupOwner) {
                startServer()
            } else {
                connectToGroupOwner(info.groupOwnerAddress)
            }
        }
    }

    private suspend fun startServer() {
        if (serverJob != null) return
        serverJob = scope.launch {
            Log.d(P2P_TAG, "Starting P2P server socket")
            val server = ServerSocket(P2P_PORT)
            try {
                while (true) {
                    val socket = server.accept()
                    handleSocket(socket, outbound = true)
                }
            } catch (t: Throwable) {
                Log.e(P2P_TAG, "Server socket closed", t)
            } finally {
                runCatching { server.close() }
            }
        }
    }

    private suspend fun connectToGroupOwner(address: InetAddress?) {
        if (address == null) return
        scope.launch {
            runCatching {
                Log.d(P2P_TAG, "Connecting to group owner $address")
                val socket = Socket(address, P2P_PORT)
                handleSocket(socket, outbound = false)
            }.onFailure { error ->
                Log.e(P2P_TAG, "Failed to connect to GO", error)
            }
        }
    }

    private suspend fun handleSocket(socket: Socket, outbound: Boolean) {
        scope.launch {
            val connection = P2pConnection(socket)
            connectionMutex.withLock {
                connections[socket.remoteSocketAddress.toString()] = connection
            }
            connection.start(outbound)
        }
    }

    private suspend fun handleHandshake(connection: P2pConnection, packet: MeshPacket.Handshake) {
        val radioPeer = RadioPeer(
            id = packet.nodeId,
            displayName = packet.displayName,
            fingerprint = packet.fingerprint,
            publicKey = packet.publicKey,
            kind = kind,
            metadata = mapOf(
                "maxFanout" to packet.capabilities.maxFanout,
                "minBatteryPct" to packet.capabilities.minBatteryPct,
                "quality" to 0.9
            )
        )
        connectionMutex.withLock {
            connections.remove(connection.key)
            connections[radioPeer.id] = connection
        }
        connection.associate(radioPeer.id)
        peerFlows.getOrPut(radioPeer.id) {
            MutableSharedFlow(
                replay = 0,
                extraBufferCapacity = 16,
                onBufferOverflow = BufferOverflow.DROP_OLDEST
            )
        }
        peerEvents.emit(PeerEvent.Upsert(radioPeer))
        connection.send(localHandshakeBytes)
    }

    private suspend fun emitFrame(connection: P2pConnection, bytes: ByteArray) {
        val peerId = connection.peerId ?: return
        peerFlows[peerId]?.emit(TransportFrame(bytes))
    }

    private suspend fun handleAllDisconnected() {
        connectionMutex.withLock {
            connections.values.forEach { it.close() }
            connections.clear()
        }
        serverJob?.cancel()
        serverJob = null
        peerFlows.clear()
    }

    private inner class P2pConnection(
        private val socket: Socket
    ) {
        private val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
        private val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
        private var readerJob: Job? = null
        private val closed = AtomicBoolean(false)
        var peerId: String? = null
            private set

        val key: String = socket.remoteSocketAddress.toString()

        fun associate(id: String) {
            peerId = id
        }

        fun start(outbound: Boolean) {
            readerJob = scope.launch {
                if (outbound) {
                    send(localHandshakeBytes)
                }
                try {
                    while (true) {
                        val length = input.readInt()
                        if (length <= 0 || length > 1_000_000) {
                            Log.w(P2P_TAG, "Invalid frame length: $length")
                            break
                        }
                        val buffer = ByteArray(length)
                        input.readFully(buffer)
                        val packetResult = runCatching {
                            val json = buffer.decodeToString()
                            PkFormats.json.decodeFromString(MeshPacket.serializer(), json)
                        }
                        if (packetResult.isFailure) {
                            Log.e(P2P_TAG, "Failed to decode P2P payload", packetResult.exceptionOrNull())
                            continue
                        }
                        val packet = packetResult.getOrNull() ?: continue
                        when (packet) {
                            is MeshPacket.Handshake -> handleHandshake(this@P2pConnection, packet)
                            is MeshPacket.EnvelopePacket -> emitFrame(this@P2pConnection, buffer)
                            is MeshPacket.ControlPacket -> emitFrame(this@P2pConnection, buffer)
                        }
                    }
                } catch (t: Throwable) {
                    Log.e(P2P_TAG, "Connection reader terminating", t)
                } finally {
                    close()
                    peerId?.let { id ->
                        peerEvents.emit(PeerEvent.Removed(id, kind))
                    }
                }
            }
        }

        suspend fun send(bytes: ByteArray): Boolean {
            return runCatching {
                synchronized(output) {
                    output.writeInt(bytes.size)
                    output.write(bytes)
                    output.flush()
                }
                true
            }.onFailure { error ->
                Log.e(P2P_TAG, "Failed to send via P2P", error)
                close()
            }.getOrDefault(false)
        }

        fun close() {
            if (!closed.compareAndSet(false, true)) return
            runCatching { socket.close() }
            readerJob?.cancel()
            scope.launch {
                val keyToRemove = peerId ?: key
                connectionMutex.withLock {
                    connections.remove(keyToRemove)
                }
                if (peerId != null) {
                    peerFlows.remove(peerId)
                }
            }
        }
    }
}
