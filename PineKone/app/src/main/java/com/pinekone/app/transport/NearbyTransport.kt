package com.pinekone.app.transport

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.pinekone.app.identity.IdentityRepository
import com.pinekone.app.protocol.PkFormats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

private const val SERVICE_ID = "com.pinekone.mesh"
private const val TAG = "NearbyTransport"

class NearbyTransport(
    private val context: Context,
    private val scope: CoroutineScope,
    private val identityRepository: IdentityRepository
) : Transport {

    override val kind: RadioKind = RadioKind.NEARBY

    private val connectionsClient = Nearby.getConnectionsClient(context)
    private val peerEvents = MutableSharedFlow<PeerEvent>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val peerFrames = ConcurrentHashMap<String, MutableSharedFlow<TransportFrame>>()
    private val endpointToPeer = ConcurrentHashMap<String, RadioPeer>()
    private val peerIdToEndpoint = ConcurrentHashMap<String, String>()

    private val isAdvertising = AtomicBoolean(false)
    private val isDiscovering = AtomicBoolean(false)

    private var identityLoaded = false
    private lateinit var localNodeId: String
    private lateinit var localDisplayName: String
    private lateinit var localPublicKey: ByteArray
    private lateinit var localFingerprint: ByteArray

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            Log.d(TAG, "Connection initiated from $endpointId (${info.endpointName})")
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            when (result.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    Log.i(TAG, "Connected to $endpointId")
                    scope.launch { sendHandshake(endpointId) }
                }
                else -> {
                    Log.w(TAG, "Connection failed for $endpointId: ${result.status}")
                    removePeer(endpointId)
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            Log.i(TAG, "Disconnected from $endpointId")
            removePeer(endpointId)
        }
    }

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            scope.launch {
                if (!ensureIdentityLoaded()) return@launch
                Log.d(TAG, "Endpoint found: $endpointId (${info.endpointName})")
                runCatching {
                    connectionsClient.requestConnection(localDisplayName, endpointId, connectionLifecycleCallback)
                        .await()
                }.onFailure { error ->
                    Log.e(TAG, "Failed to request connection", error)
                }
            }
        }

        override fun onEndpointLost(endpointId: String) {
            Log.d(TAG, "Endpoint lost: $endpointId")
            removePeer(endpointId)
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) {
                payload.asBytes()?.let { bytes ->
                    scope.launch { handlePayload(endpointId, bytes) }
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // no-op
        }
    }

    override fun startDiscovery() {
        scope.launch {
            if (!ensureIdentityLoaded()) return@launch
            if (!hasNearbyPermissions()) {
                Log.w(TAG, "Missing Nearby permissions, skipping discovery")
                return@launch
            }
            if (!isDiscovering.compareAndSet(false, true)) {
                Log.d(TAG, "Discovery already running")
                return@launch
            }
            runCatching {
                connectionsClient.startDiscovery(
                    SERVICE_ID,
                    discoveryCallback,
                    com.google.android.gms.nearby.connection.DiscoveryOptions.Builder()
                        .setStrategy(Strategy.P2P_CLUSTER)
                        .build()
                ).await()
            }.onFailure { error ->
                Log.e(TAG, "Failed to start discovery", error)
                isDiscovering.set(false)
            }
        }
    }

    override fun stopDiscovery() {
        if (!isDiscovering.compareAndSet(true, false)) return
        connectionsClient.stopDiscovery()
    }

    override fun advertise(on: Boolean) {
        scope.launch {
            if (!ensureIdentityLoaded()) return@launch
            if (on) {
                if (!hasNearbyPermissions()) {
                    Log.w(TAG, "Missing Nearby permissions, skipping advertising")
                    return@launch
                }
                if (!isAdvertising.compareAndSet(false, true)) {
                    Log.d(TAG, "Advertising already running")
                    return@launch
                }
                runCatching {
                    connectionsClient.startAdvertising(
                        localDisplayName,
                        SERVICE_ID,
                        connectionLifecycleCallback,
                        com.google.android.gms.nearby.connection.AdvertisingOptions.Builder()
                            .setStrategy(Strategy.P2P_CLUSTER)
                            .build()
                    ).await()
                }.onFailure { error ->
                    Log.e(TAG, "Failed to start advertising", error)
                    isAdvertising.set(false)
                }
            } else {
                if (!isAdvertising.compareAndSet(true, false)) return@launch
                connectionsClient.stopAdvertising()
            }
        }
    }

    override fun peers(): Flow<PeerEvent> = peerEvents.asSharedFlow()

    override fun open(peer: RadioPeer): Flow<TransportFrame> {
        val stream = peerFrames.getOrPut(peer.id) {
            MutableSharedFlow(
                replay = 1,
                extraBufferCapacity = 16,
                onBufferOverflow = BufferOverflow.DROP_OLDEST
            )
        }
        return stream.asSharedFlow()
    }

    override suspend fun send(peer: RadioPeer, frame: ByteArray): Boolean {
        val endpoint = peerIdToEndpoint[peer.id] ?: return false
        return runCatching {
            connectionsClient.sendPayload(endpoint, Payload.fromBytes(frame)).await()
            true
        }.onFailure { error ->
            Log.e(TAG, "Failed to send payload to ${peer.displayName}", error)
        }.getOrDefault(false)
    }

    override fun isAvailable(): Boolean {
        val pm = context.packageManager
        val hasNearby = pm.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT) ||
            pm.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)
        return hasNearby
    }

    private suspend fun ensureIdentityLoaded(): Boolean {
        if (identityLoaded) return true
        return runCatching {
            val identity = identityRepository.getIdentity()
            localNodeId = identity.nodeId
            localDisplayName = identity.displayName
            localPublicKey = identity.publicKey
            localFingerprint = identity.fingerprint
            identityLoaded = true
        }.onFailure { error ->
            Log.e(TAG, "Failed to load local identity", error)
        }.isSuccess
    }

    private suspend fun sendHandshake(endpointId: String) {
        if (!ensureIdentityLoaded()) return
        val packet = MeshPacket.Handshake(
            nodeId = localNodeId,
            displayName = localDisplayName,
            publicKey = localPublicKey,
            fingerprint = localFingerprint
        )
        val bytes = PkFormats.json.encodeToString(MeshPacket.serializer(), packet).encodeToByteArray()
        runCatching {
            connectionsClient.sendPayload(endpointId, Payload.fromBytes(bytes)).await()
        }.onFailure { error ->
            Log.e(TAG, "Failed to send handshake", error)
        }
    }

    private suspend fun handlePayload(endpointId: String, bytes: ByteArray) {
        val packet = runCatching {
            PkFormats.json.decodeFromString(MeshPacket.serializer(), bytes.decodeToString())
        }.getOrElse { error ->
            Log.e(TAG, "Failed to decode Nearby payload", error)
            return
        }

        when (packet) {
            is MeshPacket.Handshake -> {
                val peer = RadioPeer(
                    id = packet.nodeId,
                    displayName = packet.displayName,
                    fingerprint = packet.fingerprint,
                    publicKey = packet.publicKey,
                    kind = kind,
                    metadata = mapOf(
                        "quality" to 0.5,
                        "maxFanout" to packet.capabilities.maxFanout,
                        "minBatteryPct" to packet.capabilities.minBatteryPct
                    )
                )
                endpointToPeer[endpointId] = peer
                peerIdToEndpoint[peer.id] = endpointId

                peerFrames.getOrPut(peer.id) {
                    MutableSharedFlow(
                        replay = 1,
                        extraBufferCapacity = 16,
                        onBufferOverflow = BufferOverflow.DROP_OLDEST
                    )
                }

                peerEvents.emit(PeerEvent.Upsert(peer))

                if (packet.nodeId != localNodeId) {
                    sendHandshake(endpointId)
                }
            }
            is MeshPacket.EnvelopePacket -> {
                val peer = endpointToPeer[endpointId] ?: return
                peerFrames[peer.id]?.emit(
                    TransportFrame(
                        bytes = bytes,
                        type = TransportFrame.FrameType.PAYLOAD
                    )
                )
            }
            is MeshPacket.ControlPacket -> {
                val peer = endpointToPeer[endpointId] ?: return
                peerFrames[peer.id]?.emit(
                    TransportFrame(
                        bytes = bytes,
                        type = TransportFrame.FrameType.CONTROL
                    )
                )
            }
        }
    }

    private fun removePeer(endpointId: String) {
        val peer = endpointToPeer.remove(endpointId) ?: return
        peerIdToEndpoint.remove(peer.id)
        peerFrames.remove(peer.id)
        scope.launch {
            peerEvents.emit(PeerEvent.Removed(peer.id, kind))
        }
    }

    private fun hasNearbyPermissions(): Boolean {
        val required = mutableListOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            required += Manifest.permission.NEARBY_WIFI_DEVICES
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            required += Manifest.permission.ACCESS_FINE_LOCATION
        }

        return required.all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }
}
