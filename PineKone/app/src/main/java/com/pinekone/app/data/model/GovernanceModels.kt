package com.pinekone.app.data.model

import java.time.Instant

enum class GovernanceRole {
    RELAY,
    CUSTODY,
    ADMIN
}

enum class GovernanceEventType {
    INVITE,
    ALIAS_BINDING,
    ROLE_GRANT,
    REVOCATION
}

data class AliasBinding(
    val id: Long,
    val nodeId: String,
    val contactId: String,
    val aliasId: String,
    val scope: String,
    val epoch: Long,
    val relationDistance: Int,
    val createdAt: Instant
)

data class InviteAttestation(
    val id: Long,
    val attestationRef: String,
    val inviterNodeId: String,
    val memberNodeId: String,
    val inviterDisplayName: String?,
    val memberDisplayName: String?,
    val scope: String,
    val createdAt: Instant
)

data class RoleAttestation(
    val id: Long,
    val nodeId: String,
    val role: GovernanceRole,
    val grantedBy: String,
    val attestationRef: String,
    val expiresAt: Instant?,
    val createdAt: Instant
)

data class Revocation(
    val id: Long,
    val nodeId: String,
    val revokedBy: String,
    val reason: String,
    val createdAt: Instant
)

data class GovernanceEvent(
    val id: String,
    val type: GovernanceEventType,
    val title: String,
    val subtitle: String,
    val createdAt: Instant
)

data class GovernanceSummary(
    val aliasCount: Int,
    val inviteCount: Int,
    val roleGrantCount: Int,
    val revocationCount: Int
)

data class RoutingPeerProfile(
    val candidateNodeId: String,
    val relationDistance: Int,
    val contextualDistance: Int,
    val isRevoked: Boolean,
    val scopeQuarantined: Boolean,
    val lineageSevered: Boolean,
    val sharedLineage: Boolean,
    val hasRelayRole: Boolean,
    val exactScopeMatch: Boolean,
    val communityMatch: Boolean,
    val eligible: Boolean,
    val trustScore: Double
)
