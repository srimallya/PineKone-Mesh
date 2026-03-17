@file:UseSerializers(HexByteArraySerializer::class)

package com.pinekone.app.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

@Serializable
enum class ReceiptKind {
    DECISION,
    CUSTODY
}

@Serializable
enum class DecisionReceiptOutcome {
    FORWARD_NOW,
    STORE_CARRY,
    ACCEPT_CUSTODY,
    REJECT_CUSTODY,
    DROP_EXPIRED,
    DROP_POLICY_VIOLATION
}

@Serializable
data class DecisionReceipt(
    @SerialName("receipt_id") val receiptId: String,
    @SerialName("msg_id") val msgId: ByteArray,
    @SerialName("trace_id") val traceId: String? = null,
    @SerialName("alias_ctx") val aliasCtx: String? = null,
    @SerialName("alias_id") val aliasId: String? = null,
    @SerialName("lineage_root_fp") val lineageRootFingerprint: ByteArray? = null,
    val outcome: DecisionReceiptOutcome,
    @SerialName("reason_code") val reasonCode: String,
    val detail: String? = null,
    @SerialName("peer_id") val peerId: String? = null,
    val transport: String? = null,
    @SerialName("created_at_ms") val createdAtMs: Long,
    @SerialName("canonical_hash") val canonicalHash: ByteArray,
    val signature: ByteArray
) {
    init {
        require(receiptId.isNotBlank()) { "receipt_id must not be blank" }
        require(msgId.size == 16) { "msg_id must be 16 bytes" }
        aliasCtx?.let { require(it.isNotBlank()) { "alias_ctx must not be blank" } }
        aliasId?.let { require(it.isNotBlank()) { "alias_id must not be blank" } }
        lineageRootFingerprint?.let { require(it.size == 8) { "lineage_root_fp must be 8 bytes" } }
        require(reasonCode.isNotBlank()) { "reason_code must not be blank" }
        peerId?.let { require(it.isNotBlank()) { "peer_id must not be blank" } }
        transport?.let { require(it.isNotBlank()) { "transport must not be blank" } }
        require(createdAtMs >= 0L) { "created_at_ms must be >= 0" }
        require(canonicalHash.size in 16..64) { "canonical_hash must be 16-64 bytes" }
        require(signature.size == 64) { "signature must be 64 bytes" }
    }
}

@Serializable
data class CustodyReceiptV2(
    @SerialName("receipt_id") val receiptId: String,
    @SerialName("msg_id") val msgId: ByteArray,
    @SerialName("trace_id") val traceId: String? = null,
    @SerialName("custody_node_id") val custodyNodeId: String,
    @SerialName("alias_ctx") val aliasCtx: String? = null,
    @SerialName("alias_id") val aliasId: String? = null,
    @SerialName("lineage_root_fp") val lineageRootFingerprint: ByteArray? = null,
    @SerialName("accepted_at_ms") val acceptedAtMs: Long,
    @SerialName("expiry_at_ms") val expiryAtMs: Long,
    @SerialName("fetch_token") val fetchToken: String? = null,
    val proof: ByteArray? = null,
    @SerialName("canonical_hash") val canonicalHash: ByteArray,
    val signature: ByteArray
) {
    init {
        require(receiptId.isNotBlank()) { "receipt_id must not be blank" }
        require(msgId.size == 16) { "msg_id must be 16 bytes" }
        require(custodyNodeId.isNotBlank()) { "custody_node_id must not be blank" }
        aliasCtx?.let { require(it.isNotBlank()) { "alias_ctx must not be blank" } }
        aliasId?.let { require(it.isNotBlank()) { "alias_id must not be blank" } }
        lineageRootFingerprint?.let { require(it.size == 8) { "lineage_root_fp must be 8 bytes" } }
        require(acceptedAtMs >= 0L) { "accepted_at_ms must be >= 0" }
        require(expiryAtMs >= acceptedAtMs) { "expiry_at_ms must be >= accepted_at_ms" }
        fetchToken?.let { require(it.isNotBlank()) { "fetch_token must not be blank" } }
        proof?.let { require(it.size in 8..128) { "proof must be 8-128 bytes" } }
        require(canonicalHash.size in 16..64) { "canonical_hash must be 16-64 bytes" }
        require(signature.size == 64) { "signature must be 64 bytes" }
    }
}
