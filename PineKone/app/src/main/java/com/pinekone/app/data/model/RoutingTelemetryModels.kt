package com.pinekone.app.data.model

import java.time.Instant

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
