package com.pinekone.app.engine

import com.pinekone.app.protocol.PkEnvelope
import com.pinekone.app.transport.RadioKind
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant

data class PkPeer(
    val id: String,
    val displayName: String,
    val lastSeen: Instant,
    val batteryPct: Int?,
    val quality: Double,
    val transport: TransportKind,
    val fingerprintHex: String?,
    val publicKey: ByteArray?
)

enum class TransportKind {
    MESH,
    WEB
}

enum class RadioMode {
    BT_ONLY,
    FULL
}

sealed interface InboundFrame {
    data class EnvelopeFrame(val envelope: PkEnvelope, val via: PkPeer?) : InboundFrame
    data class Control(val frame: com.pinekone.app.protocol.PkControlFrame, val peer: PkPeer?) : InboundFrame
}

interface MeshTransport {
    val peers: StateFlow<List<PkPeer>>
    val inbound: kotlinx.coroutines.flow.Flow<InboundFrame>
    val availability: StateFlow<List<TransportState>>
    val radioMode: StateFlow<RadioMode>

    suspend fun start()
    suspend fun stop()
    suspend fun setRadioMode(mode: RadioMode)
    suspend fun send(envelope: PkEnvelope, peerId: String? = null)
    suspend fun sendControl(frame: com.pinekone.app.protocol.PkControlFrame, peerId: String? = null)
}

data class TransportState(
    val kind: TransportKind,
    val radio: RadioKind,
    val available: Boolean,
    val peerCount: Int
)
