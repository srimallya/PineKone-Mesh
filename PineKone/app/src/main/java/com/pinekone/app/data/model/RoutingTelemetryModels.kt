package com.pinekone.app.data.model

import java.time.Instant
import kotlin.math.max
import kotlin.math.min

enum class RoutingDecision {
    FORWARD_NOW,
    STORE_CARRY,
    ACCEPT_CUSTODY,
    DELIVERY_CONFIRMED,
    DELIVERY_FAILED
}

enum class DecisionReasonCode {
    BATTERY_BELOW_FLOOR,
    NO_VIABLE_PATH,
    POLICY_CLAMPED_FANOUT,
    RELATIONAL_UNRESOLVED,
    CONDENSE_PROGRESS,
    CUSTODY_TICKET_ISSUED,
    CUSTODY_REJECTED,
    PAYLOAD_TOO_LARGE,
    MEDIA_CAPTURE_CANCELLED,
    DELIVERY_ACK_RECEIVED,
    RETRY_LIMIT_EXCEEDED
}

enum class MutationKind {
    EDGE_REWEIGHT,
    ALIAS_ROTATE,
    CUSTODY_ROLE_SHIFT,
    HINT_TIER_SHIFT,
    DELIVERY_PATH_CONFIRMED
}

data class DecisionEvent(
    val id: Long,
    val msgId: String,
    val contactId: String?,
    val decision: RoutingDecision,
    val reasonCode: DecisionReasonCode,
    val transport: String?,
    val peerId: String?,
    val detail: String?,
    val createdAt: Instant
)

data class MutationEvent(
    val id: Long,
    val msgId: String,
    val mutationKind: MutationKind,
    val peerId: String?,
    val detail: String?,
    val createdAt: Instant
)

data class RouteContextEdge(
    val id: Long,
    val peerId: String,
    val contextKey: String,
    val successCount: Int,
    val failureCount: Int,
    val custodyCount: Int,
    val attemptCount: Int,
    val edgeWeight: Double,
    val lastTransport: String?,
    val lastReasonCode: String?,
    val lastLatencyMs: Long?,
    val updatedAt: Instant
) {
    val successRate: Double
        get() = (successCount + 1.0) / (successCount + failureCount + 2.0)

    fun score(now: Instant = Instant.now()): RouteContextEdgeScore {
        val ageMinutes = max(0L, now.toEpochMilli() - updatedAt.toEpochMilli()) / 60_000.0
        val freshnessBias = when {
            ageMinutes <= 15.0 -> 0.75
            ageMinutes <= 120.0 -> 0.25
            ageMinutes <= 1_440.0 -> 0.0
            else -> -0.5
        }
        val successBias = successCount * 0.9
        val failureBias = failureCount * -1.1
        val custodyBias = custodyCount * 0.4
        val attemptBias = min(attemptCount.toDouble(), 20.0) * 0.05
        val reliabilityBias = (successRate - 0.5) * 2.0
        val score = (edgeWeight + successBias + failureBias + custodyBias + attemptBias + reliabilityBias + freshnessBias)
            .coerceIn(-50.0, 50.0)
        return RouteContextEdgeScore(
            edge = this,
            score = score,
            explanation = "weight=${"%.2f".format(edgeWeight)} success=${successCount} failure=${failureCount} custody=${custodyCount} age=${"%.1f".format(ageMinutes)}m"
        )
    }

    fun withSample(sample: RouteContextEdgeSample): RouteContextEdge {
        require(peerId == sample.peerId) { "sample peer_id must match edge peer_id" }
        require(contextKey == sample.contextKey) { "sample context_key must match edge context_key" }

        val nextSuccessCount = successCount + sample.successCountDelta
        val nextFailureCount = failureCount + sample.failureCountDelta
        val nextCustodyCount = custodyCount + sample.custodyCountDelta
        val nextAttemptCount = attemptCount + sample.attemptCountDelta
        val adjustedWeight = ((edgeWeight * 0.85) + sample.weightDelta()).coerceIn(-20.0, 20.0)
        return copy(
            successCount = nextSuccessCount,
            failureCount = nextFailureCount,
            custodyCount = nextCustodyCount,
            attemptCount = nextAttemptCount,
            edgeWeight = adjustedWeight,
            lastTransport = sample.lastTransport ?: lastTransport,
            lastReasonCode = sample.reasonCode.name,
            lastLatencyMs = sample.lastLatencyMs ?: lastLatencyMs,
            updatedAt = sample.observedAt
        )
    }
}

data class RouteContextEdgeSample(
    val peerId: String,
    val contextKey: String,
    val decision: RoutingDecision,
    val reasonCode: DecisionReasonCode,
    val lastTransport: String? = null,
    val lastLatencyMs: Long? = null,
    val successCountDelta: Int = if (decision.isPositiveOutcome()) 1 else 0,
    val failureCountDelta: Int = if (decision == RoutingDecision.DELIVERY_FAILED) 1 else 0,
    val custodyCountDelta: Int = if (decision == RoutingDecision.ACCEPT_CUSTODY) 1 else 0,
    val attemptCountDelta: Int = 1,
    val weightDeltaOverride: Double? = null,
    val observedAt: Instant = Instant.now()
) {
    init {
        require(peerId.isNotBlank()) { "peerId must not be blank" }
        require(contextKey.isNotBlank()) { "contextKey must not be blank" }
        require(successCountDelta >= 0) { "successCountDelta must be >= 0" }
        require(failureCountDelta >= 0) { "failureCountDelta must be >= 0" }
        require(custodyCountDelta >= 0) { "custodyCountDelta must be >= 0" }
        require(attemptCountDelta >= 0) { "attemptCountDelta must be >= 0" }
    }

    fun weightDelta(): Double = weightDeltaOverride ?: when (decision) {
        RoutingDecision.FORWARD_NOW -> 1.2
        RoutingDecision.STORE_CARRY -> 0.7
        RoutingDecision.ACCEPT_CUSTODY -> 1.4
        RoutingDecision.DELIVERY_CONFIRMED -> 2.6
        RoutingDecision.DELIVERY_FAILED -> -2.2
    } + reasonAdjustment(reasonCode)

    private fun reasonAdjustment(reasonCode: DecisionReasonCode): Double =
        when (reasonCode) {
            DecisionReasonCode.DELIVERY_ACK_RECEIVED -> 0.8
            DecisionReasonCode.CONDENSE_PROGRESS -> 0.6
            DecisionReasonCode.CUSTODY_TICKET_ISSUED -> 0.4
            DecisionReasonCode.CUSTODY_REJECTED -> -0.9
            DecisionReasonCode.BATTERY_BELOW_FLOOR -> -0.7
            DecisionReasonCode.NO_VIABLE_PATH -> -0.6
            DecisionReasonCode.POLICY_CLAMPED_FANOUT -> -0.3
            DecisionReasonCode.RELATIONAL_UNRESOLVED -> -0.4
            DecisionReasonCode.RETRY_LIMIT_EXCEEDED -> -1.0
            DecisionReasonCode.PAYLOAD_TOO_LARGE -> -0.8
            DecisionReasonCode.MEDIA_CAPTURE_CANCELLED -> -0.2
        }
}

data class RouteContextEdgeScore(
    val edge: RouteContextEdge,
    val score: Double,
    val explanation: String
)

private fun RoutingDecision.isPositiveOutcome(): Boolean =
    when (this) {
        RoutingDecision.FORWARD_NOW,
        RoutingDecision.STORE_CARRY,
        RoutingDecision.ACCEPT_CUSTODY,
        RoutingDecision.DELIVERY_CONFIRMED -> true

        RoutingDecision.DELIVERY_FAILED -> false
    }
