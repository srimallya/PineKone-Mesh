package com.pinekone.app.transport

import kotlinx.coroutines.flow.Flow

/**
 * Radio-agnostic descriptor for a peer discovered over a specific transport.
 */
data class RadioPeer(
    val id: String,
    val displayName: String,
    val fingerprint: ByteArray?,
    val publicKey: ByteArray?,
    val kind: RadioKind,
    val metadata: Map<String, Any?> = emptyMap()
)

enum class RadioKind {
    BLE,
    WIFI_P2P,
    WIFI_AWARE,
    LAN,
    WEB,
    NEARBY
}

/**
 * Event describing how the peer list has changed.
 */
sealed interface PeerEvent {
    data class Upsert(val peer: RadioPeer) : PeerEvent
    data class Removed(val peerId: String, val kind: RadioKind) : PeerEvent
}

/**
 * Frame transported over a radio link.
 */
data class TransportFrame(
    val bytes: ByteArray,
    val type: FrameType = FrameType.PAYLOAD
) {
    enum class FrameType { PAYLOAD, CONTROL }
}

/**
 * Abstraction for a single radio implementation.
 */
interface Transport {
    val kind: RadioKind

    fun startDiscovery()
    fun stopDiscovery()
    fun advertise(on: Boolean)
    fun peers(): Flow<PeerEvent>
    fun open(peer: RadioPeer): Flow<TransportFrame>
    suspend fun send(peer: RadioPeer, frame: ByteArray): Boolean
    fun isAvailable(): Boolean
}
