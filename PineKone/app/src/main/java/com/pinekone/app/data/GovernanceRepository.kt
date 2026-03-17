package com.pinekone.app.data

import com.pinekone.app.data.db.AliasBindingEntity
import com.pinekone.app.data.db.GovernanceDao
import com.pinekone.app.data.db.InviteAttestationEntity
import com.pinekone.app.data.db.RevocationEntity
import com.pinekone.app.data.db.RoleAttestationEntity
import com.pinekone.app.data.model.AliasBinding
import com.pinekone.app.data.model.GovernanceEvent
import com.pinekone.app.data.model.GovernanceEventType
import com.pinekone.app.data.model.GovernanceRole
import com.pinekone.app.data.model.GovernanceSummary
import com.pinekone.app.data.model.InviteAttestation
import com.pinekone.app.data.model.Revocation
import com.pinekone.app.data.model.RoutingPeerProfile
import com.pinekone.app.data.model.RoleAttestation
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

class GovernanceRepository(
    private val dao: GovernanceDao
) {
    val aliasBindings: Flow<List<AliasBinding>> = dao.observeAliasBindings().map { rows ->
        rows.map { it.toDomain() }
    }

    val inviteAttestations: Flow<List<InviteAttestation>> = dao.observeInviteAttestations().map { rows ->
        rows.map { it.toDomain() }
    }

    val roleAttestations: Flow<List<RoleAttestation>> = dao.observeRoleAttestations().map { rows ->
        rows.map { it.toDomain() }
    }

    val revocations: Flow<List<Revocation>> = dao.observeRevocations().map { rows ->
        rows.map { it.toDomain() }
    }

    val governanceEvents: Flow<List<GovernanceEvent>> = combine(
        aliasBindings,
        inviteAttestations,
        roleAttestations,
        revocations
    ) { aliases, invites, roles, revokes ->
        buildList {
            addAll(invites.map {
                GovernanceEvent(
                    id = "invite:${it.id}",
                    type = GovernanceEventType.INVITE,
                    title = "Invite accepted",
                    subtitle = "${it.inviterDisplayName ?: it.inviterNodeId} -> ${it.memberDisplayName ?: it.memberNodeId}",
                    createdAt = it.createdAt
                )
            })
            addAll(aliases.map {
                GovernanceEvent(
                    id = "alias:${it.id}",
                    type = GovernanceEventType.ALIAS_BINDING,
                    title = "Alias bound",
                    subtitle = "${it.aliasId.take(10)} • distance ${it.relationDistance} • ${it.scope}",
                    createdAt = it.createdAt
                )
            })
            addAll(roles.map {
                GovernanceEvent(
                    id = "role:${it.id}",
                    type = GovernanceEventType.ROLE_GRANT,
                    title = "Role grant",
                    subtitle = "${it.role.name.lowercase()} for ${it.nodeId.take(10)} by ${it.grantedBy.take(10)}",
                    createdAt = it.createdAt
                )
            })
            addAll(revokes.map {
                GovernanceEvent(
                    id = "revoke:${it.id}",
                    type = GovernanceEventType.REVOCATION,
                    title = "Revocation",
                    subtitle = "${it.nodeId.take(10)} • ${it.reason}",
                    createdAt = it.createdAt
                )
            })
        }.sortedByDescending { it.createdAt }
    }

    val governanceSummary: Flow<GovernanceSummary> = combine(
        aliasBindings,
        inviteAttestations,
        roleAttestations,
        revocations
    ) { aliases, invites, roles, revokes ->
        GovernanceSummary(
            aliasCount = aliases.size,
            inviteCount = invites.size,
            roleGrantCount = roles.size,
            revocationCount = revokes.size
        )
    }

    suspend fun bindAlias(
        nodeId: String,
        contactId: String,
        aliasId: String,
        scope: String,
        epoch: Long,
        relationDistance: Int
    ) {
        dao.upsertAliasBinding(
            AliasBindingEntity(
                nodeId = nodeId,
                contactId = contactId,
                aliasId = aliasId,
                scope = scope,
                epoch = epoch,
                relationDistance = relationDistance,
                createdAtEpochMillis = System.currentTimeMillis()
            )
        )
    }

    suspend fun recordInviteAttestation(
        attestationRef: String,
        inviterNodeId: String,
        memberNodeId: String,
        inviterDisplayName: String?,
        memberDisplayName: String?,
        scope: String
    ) {
        dao.upsertInviteAttestation(
            InviteAttestationEntity(
                attestationRef = attestationRef,
                inviterNodeId = inviterNodeId,
                memberNodeId = memberNodeId,
                inviterDisplayName = inviterDisplayName,
                memberDisplayName = memberDisplayName,
                scope = scope,
                createdAtEpochMillis = System.currentTimeMillis()
            )
        )
    }

    suspend fun grantRole(
        nodeId: String,
        role: GovernanceRole,
        grantedBy: String,
        attestationRef: String,
        expiresAtEpochMillis: Long?
    ) {
        dao.upsertRoleAttestation(
            RoleAttestationEntity(
                nodeId = nodeId,
                role = role.name,
                grantedBy = grantedBy,
                attestationRef = attestationRef,
                expiresAtEpochMillis = expiresAtEpochMillis,
                createdAtEpochMillis = System.currentTimeMillis()
            )
        )
    }

    suspend fun revoke(nodeId: String, revokedBy: String, reason: String) {
        dao.insertRevocation(
            RevocationEntity(
                nodeId = nodeId,
                revokedBy = revokedBy,
                reason = reason,
                createdAtEpochMillis = System.currentTimeMillis()
            )
        )
    }

    suspend fun relationDistance(targetNodeId: String, candidateNodeId: String): Int {
        if (targetNodeId == candidateNodeId) return 0
        if (dao.revocationCount(candidateNodeId) > 0) return 6

        val candidateAliases = dao.findAliasBindingsForNode(candidateNodeId)
        if (candidateAliases.any { it.contactId == targetNodeId || it.nodeId == targetNodeId }) {
            return 1
        }

        val targetInvites = dao.findInviteAttestationsForNode(targetNodeId)
        val candidateInvites = dao.findInviteAttestationsForNode(candidateNodeId)

        if (targetInvites.any { it.inviterNodeId == candidateNodeId || it.memberNodeId == candidateNodeId }) {
            return 1
        }

        val targetRoots = targetInvites.flatMap { listOf(it.inviterNodeId, it.memberNodeId) }.toSet()
        val candidateRoots = candidateInvites.flatMap { listOf(it.inviterNodeId, it.memberNodeId) }.toSet()
        if (targetRoots.intersect(candidateRoots).isNotEmpty()) {
            return 2
        }

        if (candidateInvites.isNotEmpty()) {
            return 3
        }
        return 4
    }

    suspend fun routingPeerProfile(
        targetNodeId: String,
        candidateNodeId: String,
        contextKey: String? = null
    ): RoutingPeerProfile {
        val relationDistance = relationDistance(targetNodeId, candidateNodeId)
        val candidateRevocations = dao.findRevocationsForNode(candidateNodeId)
        val isRevoked = candidateRevocations.isNotEmpty()
        val now = System.currentTimeMillis()
        val candidateRoles = dao.findRoleAttestationsForNode(candidateNodeId)
        val hasRelayRole = candidateRoles.any {
            it.role == GovernanceRole.RELAY.name &&
                (it.expiresAtEpochMillis == null || it.expiresAtEpochMillis >= now)
        }
        val targetInvites = dao.findInviteAttestationsForNode(targetNodeId)
        val candidateInvites = dao.findInviteAttestationsForNode(candidateNodeId)
        val targetRoots = targetInvites.flatMap { listOf(it.inviterNodeId, it.memberNodeId) }.toSet()
        val candidateRoots = candidateInvites.flatMap { listOf(it.inviterNodeId, it.memberNodeId) }.toSet()
        val sharedLineage = targetRoots.intersect(candidateRoots).isNotEmpty()
        val candidateAliases = dao.findAliasBindingsForNode(candidateNodeId)
        val exactScopeMatch = contextKey != null && candidateAliases.any { it.scope == contextKey }
        val communityMatch = contextKey != null && candidateAliases.any { isSameCommunityScope(it.scope, contextKey) }
        val scopeQuarantined = contextKey != null && candidateRevocations.any { isScopeRelevant(it.reason, contextKey) }
        val lineageSevered = candidateRevocations.any {
            val normalized = it.reason.lowercase()
            normalized.contains("lineage_sever") || normalized.contains("lineage sever")
        }
        val aliasDistanceHint = candidateAliases
            .filter { it.contactId == targetNodeId || it.nodeId == targetNodeId || (contextKey != null && isScopeRelevant(it.scope, contextKey)) }
            .minOfOrNull { it.relationDistance }
        val contextualDistance = listOfNotNull(
            relationDistance,
            aliasDistanceHint,
            relationDistance - if (exactScopeMatch) 1 else 0,
            relationDistance - if (!exactScopeMatch && communityMatch) 1 else 0
        ).minOrNull()?.coerceAtLeast(0) ?: relationDistance

        val eligible = !isRevoked &&
            !scopeQuarantined &&
            !lineageSevered &&
            contextualDistance < 5 &&
            (contextualDistance <= 2 || hasRelayRole || sharedLineage || exactScopeMatch)
        val trustScore = when {
            isRevoked -> -1.0
            scopeQuarantined || lineageSevered -> -0.8
            contextualDistance == 0 -> 2.5
            contextualDistance == 1 -> 1.4
            contextualDistance == 2 -> 0.9
            contextualDistance == 3 -> 0.2
            else -> -0.4
        } + if (sharedLineage) 0.45 else 0.0
            + if (hasRelayRole) 0.35 else 0.0
            + if (exactScopeMatch) 0.55 else 0.0
            + if (!exactScopeMatch && communityMatch) 0.2 else 0.0

        return RoutingPeerProfile(
            candidateNodeId = candidateNodeId,
            relationDistance = relationDistance,
            contextualDistance = contextualDistance,
            isRevoked = isRevoked,
            scopeQuarantined = scopeQuarantined,
            lineageSevered = lineageSevered,
            sharedLineage = sharedLineage,
            hasRelayRole = hasRelayRole,
            exactScopeMatch = exactScopeMatch,
            communityMatch = communityMatch,
            eligible = eligible,
            trustScore = trustScore
        )
    }

    private fun isScopeRelevant(candidateScope: String, contextKey: String): Boolean {
        val normalized = candidateScope.substringAfter("scope:", candidateScope)
        return normalized == contextKey || isSameCommunityScope(normalized, contextKey)
    }

    private fun isSameCommunityScope(left: String, right: String): Boolean =
        scopeCommunity(left) != null && scopeCommunity(left) == scopeCommunity(right)

    private fun scopeCommunity(scope: String): String? =
        scope.split(':').let { parts ->
            when {
                parts.size >= 2 && parts.first() == "ctx" -> parts[1]
                parts.isNotEmpty() -> parts.firstOrNull()
                else -> null
            }
        }

    private fun AliasBindingEntity.toDomain(): AliasBinding =
        AliasBinding(
            id = id,
            nodeId = nodeId,
            contactId = contactId,
            aliasId = aliasId,
            scope = scope,
            epoch = epoch,
            relationDistance = relationDistance,
            createdAt = Instant.ofEpochMilli(createdAtEpochMillis)
        )

    private fun InviteAttestationEntity.toDomain(): InviteAttestation =
        InviteAttestation(
            id = id,
            attestationRef = attestationRef,
            inviterNodeId = inviterNodeId,
            memberNodeId = memberNodeId,
            inviterDisplayName = inviterDisplayName,
            memberDisplayName = memberDisplayName,
            scope = scope,
            createdAt = Instant.ofEpochMilli(createdAtEpochMillis)
        )

    private fun RoleAttestationEntity.toDomain(): RoleAttestation =
        RoleAttestation(
            id = id,
            nodeId = nodeId,
            role = GovernanceRole.valueOf(role),
            grantedBy = grantedBy,
            attestationRef = attestationRef,
            expiresAt = expiresAtEpochMillis?.let { Instant.ofEpochMilli(it) },
            createdAt = Instant.ofEpochMilli(createdAtEpochMillis)
        )

    private fun RevocationEntity.toDomain(): Revocation =
        Revocation(
            id = id,
            nodeId = nodeId,
            revokedBy = revokedBy,
            reason = reason,
            createdAt = Instant.ofEpochMilli(createdAtEpochMillis)
        )
}
