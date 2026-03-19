@file:UseSerializers(HexByteArraySerializer::class)

package com.pinekone.app.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

@Serializable
sealed interface PkControlFrame {
    val msgId: ByteArray
    val issuedAtMs: Long?
}

@Serializable
@SerialName("hack")
data class AckFrame(
    @SerialName("msg_id") override val msgId: ByteArray,
    @SerialName("highest_contig_seq") val highestContiguousSeq: Int,
    @SerialName("acked_seqs") val ackedSeqs: List<Int> = emptyList(),
    @SerialName("issued_at_ms") override val issuedAtMs: Long? = null
) : PkControlFrame {
    init {
        requireControlMsgId(msgId)
        require(highestContiguousSeq in 0..0xFFFFFF) { "seq must fit u24" }
        ackedSeqs.forEach { require(it in 0..0xFFFFFF) { "acked seq $it must fit u24" } }
        issuedAtMs?.let(::requireNonNegativeTimestamp)
    }
}

@Serializable
@SerialName("ack")
data class CompatAckFrame(
    @SerialName("msg_id") override val msgId: ByteArray,
    @SerialName("highest_contig_seq") val highestContiguousSeq: Int,
    @SerialName("acked_seqs") val ackedSeqs: List<Int> = emptyList(),
    @SerialName("issued_at_ms") override val issuedAtMs: Long? = null
) : PkControlFrame {
    init {
        requireControlMsgId(msgId)
        require(highestContiguousSeq in 0..0xFFFFFF) { "seq must fit u24" }
        ackedSeqs.forEach { require(it in 0..0xFFFFFF) { "acked seq $it must fit u24" } }
        issuedAtMs?.let(::requireNonNegativeTimestamp)
    }
}

@Serializable
@SerialName("nack")
data class NackFrame(
    @SerialName("msg_id") override val msgId: ByteArray,
    val holes: List<Int>,
    @SerialName("highest_contig_seq") val highestContiguousSeq: Int? = null,
    @SerialName("issued_at_ms") override val issuedAtMs: Long? = null
) : PkControlFrame {
    init {
        requireControlMsgId(msgId)
        holes.forEach { require(it in 0..0xFFFFFF) { "hole $it must fit u24" } }
        highestContiguousSeq?.let { require(it in 0..0xFFFFFF) { "seq must fit u24" } }
        issuedAtMs?.let(::requireNonNegativeTimestamp)
    }
}

@Serializable
@SerialName("claim")
data class ClaimFrame(
    @SerialName("msg_id") override val msgId: ByteArray,
    @SerialName("receipt_id") val receiptId: String? = null,
    @SerialName("highest_contig_seq") val highestContiguousSeq: Int,
    val proof: ByteArray,
    @SerialName("issued_at_ms") override val issuedAtMs: Long? = null
) : PkControlFrame {
    init {
        requireControlMsgId(msgId)
        receiptId?.let { require(it.isNotBlank()) { "receipt_id must not be blank" } }
        require(highestContiguousSeq in 0..0xFFFFFF) { "seq must fit u24" }
        require(proof.size in 16..96) { "proof must be 16-96 bytes" }
        issuedAtMs?.let(::requireNonNegativeTimestamp)
    }
}

@Serializable
@SerialName("custody_offer")
data class CustodyOfferFrame(
    @SerialName("msg_id") override val msgId: ByteArray,
    @SerialName("receipt_id") val receiptId: String? = null,
    @SerialName("expires_at_ms") val expiresAtMs: Long,
    @SerialName("fetch_token") val fetchToken: String? = null,
    val proof: ByteArray? = null,
    @SerialName("issued_at_ms") override val issuedAtMs: Long? = null
) : PkControlFrame {
    init {
        requireControlMsgId(msgId)
        receiptId?.let { require(it.isNotBlank()) { "receipt_id must not be blank" } }
        require(expiresAtMs >= 0L) { "expires_at_ms must be >= 0" }
        fetchToken?.let { require(it.isNotBlank()) { "fetch_token must not be blank" } }
        proof?.let { require(it.size in 8..96) { "proof must be 8-96 bytes" } }
        issuedAtMs?.let(::requireNonNegativeTimestamp)
    }
}

@Serializable
@SerialName("custody_accept")
data class CustodyAcceptFrame(
    @SerialName("msg_id") override val msgId: ByteArray,
    @SerialName("receipt_id") val receiptId: String,
    @SerialName("accepted_at_ms") val acceptedAtMs: Long,
    @SerialName("expiry_at_ms") val expiryAtMs: Long,
    @SerialName("fetch_token") val fetchToken: String? = null,
    val proof: ByteArray? = null,
    @SerialName("issued_at_ms") override val issuedAtMs: Long? = null
) : PkControlFrame {
    init {
        requireControlMsgId(msgId)
        require(receiptId.isNotBlank()) { "receipt_id must not be blank" }
        require(acceptedAtMs >= 0L) { "accepted_at_ms must be >= 0" }
        require(expiryAtMs >= acceptedAtMs) { "expiry_at_ms must be >= accepted_at_ms" }
        fetchToken?.let { require(it.isNotBlank()) { "fetch_token must not be blank" } }
        proof?.let { require(it.size in 8..96) { "proof must be 8-96 bytes" } }
        issuedAtMs?.let(::requireNonNegativeTimestamp)
    }
}

@Serializable
@SerialName("custody_reject")
data class CustodyRejectFrame(
    @SerialName("msg_id") override val msgId: ByteArray,
    @SerialName("reason_code") val reasonCode: String,
    @SerialName("receipt_id") val receiptId: String? = null,
    @SerialName("issued_at_ms") override val issuedAtMs: Long? = null
) : PkControlFrame {
    init {
        requireControlMsgId(msgId)
        require(reasonCode.isNotBlank()) { "reason_code must not be blank" }
        receiptId?.let { require(it.isNotBlank()) { "receipt_id must not be blank" } }
        issuedAtMs?.let(::requireNonNegativeTimestamp)
    }
}

@Serializable
@SerialName("custody_release")
data class CustodyReleaseFrame(
    @SerialName("msg_id") override val msgId: ByteArray,
    @SerialName("receipt_id") val receiptId: String,
    @SerialName("terminal_reason") val terminalReason: String,
    @SerialName("issued_at_ms") override val issuedAtMs: Long? = null
) : PkControlFrame {
    init {
        requireControlMsgId(msgId)
        require(receiptId.isNotBlank()) { "receipt_id must not be blank" }
        require(terminalReason.isNotBlank()) { "terminal_reason must not be blank" }
        issuedAtMs?.let(::requireNonNegativeTimestamp)
    }
}

@Serializable
@SerialName("alias_rotate")
data class AliasRotateFrame(
    @SerialName("msg_id") override val msgId: ByteArray,
    @SerialName("alias_ctx") val aliasCtx: String,
    @SerialName("alias_id") val aliasId: String,
    @SerialName("alias_epoch") val aliasEpoch: Long,
    @SerialName("grace_until_ms") val graceUntilMs: Long? = null,
    val proof: ByteArray? = null,
    @SerialName("issued_at_ms") override val issuedAtMs: Long? = null
) : PkControlFrame {
    init {
        requireControlMsgId(msgId)
        require(aliasCtx.isNotBlank()) { "alias_ctx must not be blank" }
        require(aliasId.isNotBlank()) { "alias_id must not be blank" }
        require(aliasEpoch >= 0) { "alias_epoch must be >= 0" }
        graceUntilMs?.let(::requireNonNegativeTimestamp)
        proof?.let { require(it.size in 8..96) { "proof must be 8-96 bytes" } }
        issuedAtMs?.let(::requireNonNegativeTimestamp)
    }
}

@Serializable
@SerialName("lineage_sever")
data class LineageSeverFrame(
    @SerialName("msg_id") override val msgId: ByteArray,
    @SerialName("attestation_ref") val attestationRef: String,
    @SerialName("lineage_root_fp") val lineageRootFingerprint: ByteArray,
    @SerialName("scope") val scope: String,
    @SerialName("reason_code") val reasonCode: String,
    @SerialName("issued_at_ms") override val issuedAtMs: Long? = null
) : PkControlFrame {
    init {
        requireControlMsgId(msgId)
        require(attestationRef.isNotBlank()) { "attestation_ref must not be blank" }
        require(lineageRootFingerprint.size == 8) { "lineage_root_fp must be 8 bytes" }
        require(scope.isNotBlank()) { "scope must not be blank" }
        require(reasonCode.isNotBlank()) { "reason_code must not be blank" }
        issuedAtMs?.let(::requireNonNegativeTimestamp)
    }
}

@Serializable
@SerialName("condense_proof")
data class CondenseProofFrame(
    @SerialName("msg_id") override val msgId: ByteArray,
    @SerialName("condense_depth") val condenseDepth: Int,
    @SerialName("proof_type") val proofType: String = "relational",
    val proof: ByteArray,
    @SerialName("issued_at_ms") override val issuedAtMs: Long? = null
) : PkControlFrame {
    init {
        requireControlMsgId(msgId)
        require(condenseDepth in 0..255) { "condense_depth must be 0-255" }
        require(proofType.isNotBlank()) { "proof_type must not be blank" }
        require(proof.size in 8..128) { "proof must be 8-128 bytes" }
        issuedAtMs?.let(::requireNonNegativeTimestamp)
    }
}

@Serializable
@SerialName("decision_receipt")
data class DecisionReceiptFrame(
    @SerialName("msg_id") override val msgId: ByteArray,
    val receipt: DecisionReceipt,
    @SerialName("issued_at_ms") override val issuedAtMs: Long? = null
) : PkControlFrame {
    init {
        requireControlMsgId(msgId)
        require(receipt.msgId.contentEquals(msgId)) { "receipt msg_id must match frame msg_id" }
        issuedAtMs?.let(::requireNonNegativeTimestamp)
    }
}

@Serializable
@SerialName("custody_receipt")
data class CustodyReceiptFrame(
    @SerialName("msg_id") override val msgId: ByteArray,
    val receipt: CustodyReceiptV2,
    @SerialName("issued_at_ms") override val issuedAtMs: Long? = null
) : PkControlFrame {
    init {
        requireControlMsgId(msgId)
        require(receipt.msgId.contentEquals(msgId)) { "receipt msg_id must match frame msg_id" }
        issuedAtMs?.let(::requireNonNegativeTimestamp)
    }
}

@Serializable
@SerialName("ping")
data class PingFrame(
    @SerialName("msg_id") override val msgId: ByteArray,
    @SerialName("sent_at_ms") val sentAtMs: Long,
    @SerialName("issued_at_ms") override val issuedAtMs: Long? = sentAtMs
) : PkControlFrame {
    init {
        requireControlMsgId(msgId)
        require(sentAtMs >= 0L) { "sent_at_ms must be >= 0" }
        issuedAtMs?.let(::requireNonNegativeTimestamp)
    }
}

@Serializable
@SerialName("pong")
data class PongFrame(
    @SerialName("msg_id") override val msgId: ByteArray,
    @SerialName("received_at_ms") val receivedAtMs: Long,
    @SerialName("issued_at_ms") override val issuedAtMs: Long? = receivedAtMs
) : PkControlFrame {
    init {
        requireControlMsgId(msgId)
        require(receivedAtMs >= 0L) { "received_at_ms must be >= 0" }
        issuedAtMs?.let(::requireNonNegativeTimestamp)
    }
}

private fun requireControlMsgId(msgId: ByteArray) {
    require(msgId.size == 16) { "msg_id must be 16 bytes" }
}

private fun requireNonNegativeTimestamp(timestampMs: Long) {
    require(timestampMs >= 0L) { "timestamp must be >= 0" }
}
