@file:UseSerializers(HexByteArraySerializer::class)

package com.pinekone.app.transport

import com.pinekone.app.protocol.HexByteArraySerializer
import com.pinekone.app.protocol.PkControlFrame
import com.pinekone.app.protocol.PkEnvelope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

@Serializable
sealed interface MeshPacket {
    @SerialName("handshake")
    @Serializable
    data class Handshake(
        val nodeId: String,
        val displayName: String,
        val publicKey: ByteArray,
        val fingerprint: ByteArray,
        val capabilities: Capabilities = Capabilities()
    ) : MeshPacket {
        @Serializable
        data class Capabilities(
            val maxFanout: Int = 2,
            val minBatteryPct: Int = 15
        )
    }

    @SerialName("envelope")
    @Serializable
    data class EnvelopePacket(
        val envelope: PkEnvelope
    ) : MeshPacket

    @SerialName("control")
    @Serializable
    data class ControlPacket(
        val frame: PkControlFrame
    ) : MeshPacket
}
