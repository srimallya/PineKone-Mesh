@file:UseSerializers(HexByteArraySerializer::class)

package com.pinekone.app.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

@Serializable
data class PkEnvelope(
    val ver: Int = CURRENT_PROTOCOL_VERSION,
    @SerialName("msg_id") val msgId: ByteArray,
    @SerialName("alias_ctx") val aliasCtx: String,
    @SerialName("trace_id") val traceId: String? = null,
    @SerialName("deadline_ms") val deadlineMs: Long,
    @SerialName("created_at_ms") val createdAtMs: Long,
    @SerialName("ctx_commitment") val ctxCommitment: ByteArray,
    @SerialName("condense_depth") val condenseDepth: Int,
    @SerialName("mutation_nonce") val mutationNonce: ByteArray,
    @SerialName("hint_tier") val hintTier: Int,
    val ttl: Int,
    val policy: PkPolicy,
    val hints: PkHints? = null,
    val ops: PkOps = PkOps(),
    val web: PkWebHints? = null,
    val frag: PkFragmentHeader,
    val auth: PkAuth? = null,
    val payload: ByteArray
) {
    init {
        require(ver == CURRENT_PROTOCOL_VERSION) { "Unsupported envelope version: $ver" }
        require(ttl in 0..255) { "TTL must be 0-255" }
        require(msgId.size == 16) { "msg_id MUST be 16 bytes" }
        require(aliasCtx.isNotBlank()) { "alias_ctx must not be blank" }
        require(deadlineMs >= 0L) { "deadline_ms must be >= 0" }
        require(createdAtMs >= 0L) { "created_at_ms must be >= 0" }
        require(ctxCommitment.size in 16..32) { "ctx_commitment must be 16-32 bytes" }
        require(condenseDepth in 0..255) { "condense_depth must be 0-255" }
        require(mutationNonce.size in 8..32) { "mutation_nonce must be 8-32 bytes" }
        require(hintTier in 0..2) { "hint_tier must be 0..2" }
        require(deadlineMs >= createdAtMs) { "deadline_ms must be >= created_at_ms" }
    }

    fun withDecrementedTtl(): PkEnvelope = copy(ttl = (ttl - 1).coerceAtLeast(0))
}

@Serializable
data class PkPolicy(
    @SerialName("max_fanout") val maxFanout: Int = 0,
    @SerialName("k_pipe") val kPipe: Int = 20,
    @SerialName("retry_limit") val retryLimit: Int = 0,
    @SerialName("min_batt_pct") val minBattPct: Int = 0,
    @SerialName("J_threshold") val jThreshold: Int = 0,
    val weights: PkPolicyWeights = PkPolicyWeights(),
    @SerialName("ttl_floor") val ttlFloor: Int? = null,
    @SerialName("parity_block") val parityBlock: Int? = null
) {
    init {
        require(maxFanout in 0..255) { "max_fanout must be 0-255" }
        require(kPipe in 0..255) { "k_pipe must be fixed-point byte" }
        require(retryLimit in 0..255) { "retry_limit must be 0-255" }
        require(minBattPct in 0..100) { "min_batt_pct must be 0-100" }
        require(jThreshold in 0..255) { "J_threshold must be 0-255" }
        ttlFloor?.let { require(it in 0..255) { "ttl_floor must be 0-255" } }
        parityBlock?.let { require(it in 0..255) { "parity_block must be 0-255" } }
    }
}

@Serializable
data class PkPolicyWeights(
    val novelty: Int = 255,
    val coverage: Int = 204,
    val quality: Int = 102,
    @SerialName("cost_latency") val costLatency: Int = 255,
    @SerialName("cost_battery") val costBattery: Int = 170,
    @SerialName("cost_dup") val costDup: Int = 85,
    @SerialName("cost_slack") val costSlack: Int = 255
) {
    init {
        listOf(
            novelty, coverage, quality, costLatency, costBattery, costDup, costSlack
        ).forEach { require(it in 0..255) { "Weights must be 0-255" } }
    }
}

@Serializable
data class PkHints(
    @SerialName("community_id") val communityId: Int,
    @SerialName("target_hash") val targetHash: ByteArray? = null,
    val mu: ByteArray? = null,
    val priority: Int = 1
) {
    init {
        require(communityId in 0..0xFFFF) { "community_id must fit u16" }
        require(priority in 0..2) { "priority must be 0..2" }
        targetHash?.let { require(it.size == 8) { "target_hash must be 8 bytes" } }
        mu?.let { require(it.size == 8) { "mu must be 8 bytes" } }
    }
}

@Serializable
data class PkOps(
    @SerialName("store_carry") val storeCarry: Boolean = true,
    @SerialName("require_ack") val requireAck: Boolean = false,
    @SerialName("e2e_ack_path") val e2eAckPath: Boolean = false
)

@Serializable
data class PkWebHints(
    val rendezvous: String? = null,
    @SerialName("window_s") val windowSeconds: Long = 0,
    val pricing: PkWebPricing? = null,
    val privacy: PkWebPrivacy = PkWebPrivacy(),
    val topic: Long? = null
) {
    init {
        rendezvous?.let { require(it.length <= 24) { "rendezvous label too long" } }
        require(windowSeconds >= 0) { "window_s must be ≥ 0" }
    }
}

@Serializable
data class PkWebPricing(
    @SerialName("pow_bits") val powBits: Int = 0,
    val postage: Int = 0
) {
    init {
        require(powBits in 0..255) { "pow_bits must be 0-255" }
        require(postage in 0..0xFFFF) { "postage must fit u16" }
    }
}

@Serializable
data class PkWebPrivacy(
    val oblivious: Boolean = false
)

@Serializable
data class PkFragmentHeader(
    val kind: PkFragmentKind = PkFragmentKind.DATA,
    val seq: Int = 0,
    val total: Int = 0
) {
    init {
        require(seq in 0..0xFFFFFF) { "seq must fit u24" }
        require(total in 0..0xFFFFFF) { "total must fit u24" }
    }
}

@Serializable
enum class PkFragmentKind {
    DATA,
    PARITY,
    CTRL
}

@Serializable
data class PkAuth(
    @SerialName("policy_sig") val policySig: ByteArray? = null,
    @SerialName("origin_pk_fp") val originPkFingerprint: ByteArray? = null,
    @SerialName("origin_sig") val originSig: ByteArray? = null,
    @SerialName("lineage_root_fp") val lineageRootFingerprint: ByteArray? = null,
    @SerialName("scope_alias_id") val scopeAliasId: String? = null,
    @SerialName("scope_epoch") val scopeEpoch: Long? = null,
    @SerialName("signed_at_ms") val signedAtMs: Long? = null
) {
    init {
        policySig?.let { require(it.size == 64) { "policy_sig must be 64 bytes" } }
        originPkFingerprint?.let { require(it.size == 8) { "origin_pk_fp must be 8 bytes" } }
        originSig?.let { require(it.size == 64) { "origin_sig must be 64 bytes" } }
        lineageRootFingerprint?.let { require(it.size == 8) { "lineage_root_fp must be 8 bytes" } }
        scopeAliasId?.let { require(it.isNotBlank()) { "scope_alias_id must not be blank" } }
        scopeEpoch?.let { require(it >= 0L) { "scope_epoch must be >= 0" } }
        signedAtMs?.let { require(it >= 0L) { "signed_at_ms must be >= 0" } }
    }
}

const val CURRENT_PROTOCOL_VERSION: Int = 2
