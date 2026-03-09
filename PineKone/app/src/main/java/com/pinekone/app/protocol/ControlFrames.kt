@file:UseSerializers(HexByteArraySerializer::class)

package com.pinekone.app.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

@Serializable
sealed interface PkControlFrame {
    val msgId: ByteArray
}

@Serializable
@SerialName("hack")
data class HackFrame(
    @SerialName("msg_id") override val msgId: ByteArray,
    @SerialName("highest_contig_seq") val highestContiguousSeq: Int
) : PkControlFrame {
    init {
        require(msgId.size == 16) { "msg_id must be 16 bytes" }
        require(highestContiguousSeq in 0..0xFFFFFF) { "seq must fit u24" }
    }
}

@Serializable
@SerialName("ping")
data class PingFrame(
    @SerialName("msg_id") override val msgId: ByteArray,
    @SerialName("sent_at_ms") val sentAtMs: Long
) : PkControlFrame {
    init {
        require(msgId.size == 16) { "msg_id must be 16 bytes" }
    }
}

@Serializable
@SerialName("pong")
data class PongFrame(
    @SerialName("msg_id") override val msgId: ByteArray,
    @SerialName("received_at_ms") val receivedAtMs: Long
) : PkControlFrame {
    init {
        require(msgId.size == 16) { "msg_id must be 16 bytes" }
    }
}

@Serializable
@SerialName("claim")
data class ClaimFrame(
    @SerialName("msg_id") override val msgId: ByteArray,
    @SerialName("highest_contig_seq") val highestContiguousSeq: Int,
    val proof: ByteArray
) : PkControlFrame {
    init {
        require(msgId.size == 16) { "msg_id must be 16 bytes" }
        require(highestContiguousSeq in 0..0xFFFFFF) { "seq must fit u24" }
        require(proof.size == 16) { "proof must be 16 bytes" }
    }
}

@Serializable
@SerialName("nack")
data class NackFrame(
    @SerialName("msg_id") override val msgId: ByteArray,
    val holes: List<Int>
    ) : PkControlFrame {
    init {
        require(msgId.size == 16) { "msg_id must be 16 bytes" }
        holes.forEach { require(it in 0..0xFFFFFF) { "hole $it must fit u24" } }
    }
}

@Serializable
@SerialName("alias_rotate")
data class AliasRotateFrame(
    @SerialName("msg_id") override val msgId: ByteArray,
    @SerialName("alias_epoch") val aliasEpoch: Long,
    val proof: ByteArray? = null
) : PkControlFrame {
    init {
        require(msgId.size == 16) { "msg_id must be 16 bytes" }
        require(aliasEpoch >= 0) { "alias_epoch must be >= 0" }
        proof?.let { require(it.size in 8..32) { "proof must be 8-32 bytes" } }
    }
}

@Serializable
@SerialName("lineage_sever")
data class LineageSeverFrame(
    @SerialName("msg_id") override val msgId: ByteArray,
    @SerialName("attestation_ref") val attestationRef: String,
    @SerialName("reason_code") val reasonCode: String
) : PkControlFrame {
    init {
        require(msgId.size == 16) { "msg_id must be 16 bytes" }
        require(attestationRef.isNotBlank()) { "attestation_ref must not be blank" }
        require(reasonCode.isNotBlank()) { "reason_code must not be blank" }
    }
}

@Serializable
@SerialName("condense_proof")
data class CondenseProofFrame(
    @SerialName("msg_id") override val msgId: ByteArray,
    @SerialName("condense_depth") val condenseDepth: Int,
    val proof: ByteArray
) : PkControlFrame {
    init {
        require(msgId.size == 16) { "msg_id must be 16 bytes" }
        require(condenseDepth in 0..255) { "condense_depth must be 0-255" }
        require(proof.size in 8..64) { "proof must be 8-64 bytes" }
    }
}
