package com.pinekone.app.sim

import com.pinekone.app.data.model.AliasBinding
import com.pinekone.app.data.model.DecisionReasonCode
import com.pinekone.app.data.model.GovernanceRole
import com.pinekone.app.data.model.InviteAttestation
import com.pinekone.app.data.model.MutationKind
import com.pinekone.app.data.model.Revocation
import com.pinekone.app.data.model.RoleAttestation
import com.pinekone.app.data.model.RouteContextEdge
import com.pinekone.app.data.model.RouteContextEdgeSample
import com.pinekone.app.data.model.RoutingDecision
import com.pinekone.app.data.model.RoutingPeerProfile
import com.pinekone.app.engine.CapGovernor
import com.pinekone.app.engine.DeviceCaps
import com.pinekone.app.engine.DeviceStatus
import com.pinekone.app.engine.ForwardDecision
import com.pinekone.app.protocol.PkOps
import com.pinekone.app.protocol.PkPolicy
import java.time.Instant
import java.util.UUID
import kotlin.random.Random

enum class SimTransport {
    BLE,
    WIFI,
    WEB
}

enum class SimEventType {
    DECISION,
    MUTATION,
    DELIVERY,
    CUSTODY
}

data class SimNode(
    val id: String,
    val displayName: String = id,
    var batteryPct: Int = 100,
    var queueSlack: Double = 1.0,
    var transmitBudget: Double = 1.0,
    var maxFanout: Int = 2,
    var minBatteryPct: Int = 15,
    var custodyEligible: Boolean = true,
    var webMailboxAvailable: Boolean = false,
    var canFetchCustody: Boolean = false
)

data class SimLink(
    val from: String,
    val to: String,
    val transport: SimTransport,
    var quality: Double,
    var active: Boolean = true
)

data class SimEvent(
    val tick: Int,
    val type: SimEventType,
    val nodeId: String,
    val msgId: String,
    val decision: RoutingDecision? = null,
    val reason: DecisionReasonCode? = null,
    val mutation: MutationKind? = null,
    val peerId: String? = null,
    val detail: String
)

data class SimResult(
    val delivered: Boolean,
    val deliveredViaCustody: Boolean,
    val deliveryTick: Int?,
    val events: List<SimEvent>,
    val holders: Set<String>,
    val routeEdges: Map<String, Map<String, RouteContextEdge>>
)

data class SimStressMessageResult(
    val from: String,
    val to: String,
    val contextKey: String,
    val delivered: Boolean,
    val deliveredViaCustody: Boolean,
    val deliveryTick: Int?,
    val failureReasons: List<DecisionReasonCode>,
    val eventCount: Int
)

data class SimStressReport(
    val seed: Int,
    val nodeCount: Int,
    val totalMessages: Int,
    val deliveredMessages: Int,
    val custodyDeliveries: Int,
    val failedMessages: Int,
    val deliveryRate: Double,
    val custodyRate: Double,
    val failureReasons: Map<DecisionReasonCode, Int>,
    val trustedPairSuccessRate: Double,
    val totalEvents: Int
) {
    fun render(): String =
        buildString {
            append("seed=")
            append(seed)
            append(" nodes=")
            append(nodeCount)
            append(" messages=")
            append(totalMessages)
            append(" delivered=")
            append(deliveredMessages)
            append(" custody=")
            append(custodyDeliveries)
            append(" failed=")
            append(failedMessages)
            append(" deliveryRate=")
            append("%.2f".format(deliveryRate))
            append(" custodyRate=")
            append("%.2f".format(custodyRate))
            append(" trustedPairSuccessRate=")
            append("%.2f".format(trustedPairSuccessRate))
            append(" totalEvents=")
            append(totalEvents)
            if (failureReasons.isNotEmpty()) {
                append(" failureReasons=")
                append(
                    failureReasons.entries
                        .sortedByDescending { it.value }
                        .joinToString(",") { "${it.key.name}:${it.value}" }
                )
            }
        }
}

private data class SimPathScore(
    val peerId: String,
    val distance: Int,
    val score: Double,
    val profile: RoutingPeerProfile,
    val explanation: String
)

internal data class MessageCopy(
    val nodeId: String,
    var ttlRemaining: Int,
    var attempts: Int = 0
)

internal data class SimMessage(
    val id: String,
    val originId: String,
    val targetId: String,
    val contextKey: String,
    val policy: PkPolicy,
    val ops: PkOps,
    val copies: MutableList<MessageCopy>,
    var custodyTick: Int? = null,
    var delivered: Boolean = false,
    var deliveredViaCustody: Boolean = false,
    var deliveryTick: Int? = null
)

class PineKoneSimulation(
    startTime: Instant = Instant.parse("2026-03-18T00:00:00Z")
) {
    private val startTime = startTime
    private val nodes = linkedMapOf<String, SimNode>()
    private val links = mutableListOf<SimLink>()
    private val aliasBindings = mutableListOf<AliasBinding>()
    private val inviteAttestations = mutableListOf<InviteAttestation>()
    private val roleAttestations = mutableListOf<RoleAttestation>()
    private val revocations = mutableListOf<Revocation>()
    private val routeEdges = mutableMapOf<String, MutableMap<String, RouteContextEdge>>()
    private val events = mutableListOf<SimEvent>()

    private var tick: Int = 0
    private var aliasIdCounter = 0L
    private var inviteIdCounter = 0L
    private var roleIdCounter = 0L
    private var revocationIdCounter = 0L

    fun addNode(node: SimNode): PineKoneSimulation {
        nodes[node.id] = node
        return this
    }

    fun connectBidirectional(a: String, b: String, quality: Double, transport: SimTransport = SimTransport.BLE): PineKoneSimulation {
        links += SimLink(a, b, transport, quality)
        links += SimLink(b, a, transport, quality)
        return this
    }

    fun setLinkActive(from: String, to: String, active: Boolean) {
        links.firstOrNull { it.from == from && it.to == to }?.active = active
    }

    fun setLinkQuality(from: String, to: String, quality: Double) {
        links.firstOrNull { it.from == from && it.to == to }?.quality = quality.coerceIn(0.05, 1.0)
    }

    fun updateNode(
        nodeId: String,
        batteryPct: Int? = null,
        queueSlack: Double? = null,
        transmitBudget: Double? = null,
        custodyEligible: Boolean? = null,
        webMailboxAvailable: Boolean? = null,
        canFetchCustody: Boolean? = null
    ) {
        val node = nodes[nodeId] ?: return
        batteryPct?.let { node.batteryPct = it.coerceIn(0, 100) }
        queueSlack?.let { node.queueSlack = it.coerceIn(0.0, 1.0) }
        transmitBudget?.let { node.transmitBudget = it.coerceIn(0.0, 1.0) }
        custodyEligible?.let { node.custodyEligible = it }
        webMailboxAvailable?.let { node.webMailboxAvailable = it }
        canFetchCustody?.let { node.canFetchCustody = it }
    }

    fun nodeIds(): List<String> = nodes.keys.toList()

    fun linkPairs(): List<Pair<String, String>> = links.map { it.from to it.to }

    fun currentTick(): Int = tick

    fun bindAlias(nodeId: String, contactId: String, scope: String, relationDistance: Int): PineKoneSimulation {
        aliasIdCounter += 1
        aliasBindings += AliasBinding(
            id = aliasIdCounter,
            nodeId = nodeId,
            contactId = contactId,
            aliasId = "alias-$aliasIdCounter",
            scope = scope,
            epoch = 1,
            relationDistance = relationDistance,
            createdAt = now()
        )
        return this
    }

    fun invite(inviterNodeId: String, memberNodeId: String, scope: String): PineKoneSimulation {
        inviteIdCounter += 1
        inviteAttestations += InviteAttestation(
            id = inviteIdCounter,
            attestationRef = "invite-$inviteIdCounter",
            inviterNodeId = inviterNodeId,
            memberNodeId = memberNodeId,
            inviterDisplayName = inviterNodeId,
            memberDisplayName = memberNodeId,
            scope = scope,
            createdAt = now()
        )
        return this
    }

    fun grantRole(nodeId: String, role: GovernanceRole, grantedBy: String, expiresAtTick: Int? = null): PineKoneSimulation {
        roleIdCounter += 1
        roleAttestations += RoleAttestation(
            id = roleIdCounter,
            nodeId = nodeId,
            role = role,
            grantedBy = grantedBy,
            attestationRef = "role-$roleIdCounter",
            expiresAt = expiresAtTick?.let { startTime.plusSeconds(it * 60L) },
            createdAt = now()
        )
        return this
    }

    fun revoke(nodeId: String, revokedBy: String, reason: String): PineKoneSimulation {
        revocationIdCounter += 1
        revocations += Revocation(
            id = revocationIdCounter,
            nodeId = nodeId,
            revokedBy = revokedBy,
            reason = reason,
            createdAt = now()
        )
        return this
    }

    fun sendMessage(
        originId: String,
        targetId: String,
        contextKey: String,
        policy: PkPolicy = PkPolicy(maxFanout = 2, retryLimit = 3, minBattPct = 15),
        ops: PkOps = PkOps(storeCarry = true, requireAck = true, e2eAckPath = true),
        ttl: Int = 6
    ): SimMessageHandle {
        val id = UUID.randomUUID().toString().take(8)
        val message = SimMessage(
            id = id,
            originId = originId,
            targetId = targetId,
            contextKey = contextKey,
            policy = policy,
            ops = ops,
            copies = mutableListOf(MessageCopy(originId, ttlRemaining = ttl))
        )
        return SimMessageHandle(message)
    }

    fun run(handle: SimMessageHandle, maxTicks: Int, beforeTick: ((tick: Int, sim: PineKoneSimulation) -> Unit)? = null): SimResult {
        val message = handle.message
        repeat(maxTicks) {
            beforeTick?.invoke(tick, this)
            step(message)
            if (message.delivered) {
                return resultFor(message)
            }
            tick += 1
        }
        return resultFor(message)
    }

    fun eventsFor(msgId: String): List<SimEvent> = events.filter { it.msgId == msgId }

    fun learnedEdge(ownerNodeId: String, peerId: String, contextKey: String): RouteContextEdge? =
        routeEdges[ownerNodeId]?.get(routeKey(peerId, contextKey))

    private fun step(message: SimMessage) {
        if (message.delivered) return

        if (message.custodyTick != null && nodes[message.targetId]?.canFetchCustody == true && tick > message.custodyTick!!) {
            message.delivered = true
            message.deliveredViaCustody = true
            message.deliveryTick = tick
            events += SimEvent(
                tick = tick,
                type = SimEventType.DELIVERY,
                nodeId = message.targetId,
                msgId = message.id,
                detail = "delivered via custody fetch"
            )
            return
        }

        val snapshot = message.copies.toList()
        for (copy in snapshot) {
            if (message.delivered) break
            processCopy(message, copy)
        }
    }

    private fun processCopy(message: SimMessage, copy: MessageCopy) {
        val node = nodes[copy.nodeId] ?: return
        if (copy.nodeId == message.targetId) {
            message.delivered = true
            message.deliveryTick = tick
            events += SimEvent(
                tick = tick,
                type = SimEventType.DELIVERY,
                nodeId = copy.nodeId,
                msgId = message.id,
                detail = "message resolved at destination"
            )
            return
        }

        if (copy.ttlRemaining <= 0) {
            events += SimEvent(
                tick = tick,
                type = SimEventType.DECISION,
                nodeId = copy.nodeId,
                msgId = message.id,
                decision = RoutingDecision.DELIVERY_FAILED,
                reason = DecisionReasonCode.RETRY_LIMIT_EXCEEDED,
                detail = "ttl exhausted"
            )
            message.copies.removeIf { it.nodeId == copy.nodeId }
            return
        }

        val decision = capGovernorFor(node).evaluate(
            policy = message.policy,
            status = DeviceStatus(node.batteryPct, node.queueSlack),
            storeCarryRequested = message.ops.storeCarry
        )
        val scoredPeer = bestPeer(copy.nodeId, message)
        when {
            decision is ForwardDecision.Allowed && scoredPeer != null -> forward(message, copy, node, scoredPeer)
            decision is ForwardDecision.Allowed && message.ops.storeCarry -> storeCarry(message, copy, node, null)
            decision == ForwardDecision.StoreCarry -> storeCarry(message, copy, node, scoredPeer)
            decision == ForwardDecision.DeclinedBattery -> failCopy(message, copy, node, DecisionReasonCode.BATTERY_BELOW_FLOOR, "battery below floor")
            else -> failCopy(message, copy, node, DecisionReasonCode.NO_VIABLE_PATH, "no viable path")
        }
    }

    private fun forward(message: SimMessage, copy: MessageCopy, node: SimNode, score: SimPathScore) {
        copy.attempts += 1
        recordAttempt(node.id, score.peerId, message.contextKey)
        events += SimEvent(
            tick = tick,
            type = SimEventType.DECISION,
            nodeId = node.id,
            msgId = message.id,
            decision = RoutingDecision.FORWARD_NOW,
            reason = DecisionReasonCode.CONDENSE_PROGRESS,
            peerId = score.peerId,
            detail = score.explanation
        )
        recordMutation(node.id, message.id, MutationKind.EDGE_REWEIGHT, score.peerId, message.contextKey, DecisionReasonCode.CONDENSE_PROGRESS, "forwarded")
        recordMutation(node.id, message.id, MutationKind.ALIAS_ROTATE, score.peerId, message.contextKey, DecisionReasonCode.CONDENSE_PROGRESS, "alias rotated")

        if (shouldUseShadowCustody(node, score)) {
            message.custodyTick = tick
            events += SimEvent(
                tick = tick,
                type = SimEventType.CUSTODY,
                nodeId = node.id,
                msgId = message.id,
                decision = RoutingDecision.ACCEPT_CUSTODY,
                reason = DecisionReasonCode.CUSTODY_TICKET_ISSUED,
                detail = "shadow custody due to low-confidence route"
            )
        }

        message.copies.removeIf { it.nodeId == copy.nodeId }
        val nextCopy = message.copies.firstOrNull { it.nodeId == score.peerId }
        if (nextCopy == null) {
            message.copies += MessageCopy(nodeId = score.peerId, ttlRemaining = copy.ttlRemaining - 1)
        } else {
            nextCopy.ttlRemaining = maxOf(nextCopy.ttlRemaining, copy.ttlRemaining - 1)
        }

        if (score.peerId == message.targetId) {
            message.delivered = true
            message.deliveryTick = tick
            recordOutcome(node.id, score.peerId, message.contextKey, success = true, reason = DecisionReasonCode.DELIVERY_ACK_RECEIVED)
            recordMutation(node.id, message.id, MutationKind.DELIVERY_PATH_CONFIRMED, score.peerId, message.contextKey, DecisionReasonCode.DELIVERY_ACK_RECEIVED, "delivery confirmed")
            events += SimEvent(
                tick = tick,
                type = SimEventType.DELIVERY,
                nodeId = score.peerId,
                msgId = message.id,
                decision = RoutingDecision.DELIVERY_CONFIRMED,
                reason = DecisionReasonCode.DELIVERY_ACK_RECEIVED,
                peerId = node.id,
                detail = "delivered over ${score.explanation}"
            )
        } else {
            recordOutcome(node.id, score.peerId, message.contextKey, success = true, reason = DecisionReasonCode.CONDENSE_PROGRESS)
        }
    }

    private fun storeCarry(message: SimMessage, copy: MessageCopy, node: SimNode, score: SimPathScore?) {
        copy.attempts += 1
        events += SimEvent(
            tick = tick,
            type = SimEventType.DECISION,
            nodeId = node.id,
            msgId = message.id,
            decision = RoutingDecision.STORE_CARRY,
            reason = if (score == null) DecisionReasonCode.NO_VIABLE_PATH else DecisionReasonCode.RELATIONAL_UNRESOLVED,
            peerId = score?.peerId,
            detail = score?.explanation ?: "no governance-eligible route"
        )
        if (score != null) {
            recordMutation(node.id, message.id, MutationKind.HINT_TIER_SHIFT, score.peerId, message.contextKey, DecisionReasonCode.RELATIONAL_UNRESOLVED, "store-carry waiting")
        }
        if (node.custodyEligible && node.webMailboxAvailable && message.custodyTick == null) {
            message.custodyTick = tick
            events += SimEvent(
                tick = tick,
                type = SimEventType.CUSTODY,
                nodeId = node.id,
                msgId = message.id,
                decision = RoutingDecision.ACCEPT_CUSTODY,
                reason = DecisionReasonCode.CUSTODY_TICKET_ISSUED,
                detail = "custody fallback accepted"
            )
        }
        if (copy.attempts > message.policy.retryLimit && message.custodyTick == null) {
            failCopy(message, copy, node, DecisionReasonCode.RETRY_LIMIT_EXCEEDED, "retry limit exceeded")
        }
    }

    private fun failCopy(message: SimMessage, copy: MessageCopy, node: SimNode, reason: DecisionReasonCode, detail: String) {
        events += SimEvent(
            tick = tick,
            type = SimEventType.DECISION,
            nodeId = node.id,
            msgId = message.id,
            decision = RoutingDecision.DELIVERY_FAILED,
            reason = reason,
            detail = detail
        )
        message.copies.removeIf { it.nodeId == copy.nodeId }
    }

    private fun bestPeer(nodeId: String, message: SimMessage): SimPathScore? {
        val availableLinks = links.filter { it.from == nodeId && it.active }
        if (availableLinks.isEmpty()) return null
        return availableLinks
            .mapNotNull { link ->
                val peer = nodes[link.to] ?: return@mapNotNull null
                val profile = routingPeerProfile(message.targetId, peer.id, message.contextKey)
                val distance = profile.contextualDistance
                val learnedEdge = routeEdges[nodeId]?.get(routeKey(peer.id, message.contextKey))
                val directMatchBonus = if (peer.id == message.targetId) 1.5 else 0.0
                val transportBonus = if (link.transport == SimTransport.WEB) 0.1 else 0.35
                val qualityScore = link.quality.coerceIn(0.0, 1.0)
                val distanceScore = (5 - distance).coerceAtLeast(0) * 0.8
                val learnedBias = learnedEdge?.edgeWeight ?: 0.0
                val successBonus = ((learnedEdge?.successRate ?: 0.5) - 0.5) * 1.2
                val custodyBonus = (learnedEdge?.custodyCount ?: 0).coerceAtMost(3) * 0.05
                val score = distanceScore + qualityScore + transportBonus + directMatchBonus + learnedBias + successBonus + custodyBonus + profile.trustScore
                if (!profile.eligible || score <= -1.0 || distance >= 5) {
                    null
                } else {
                    SimPathScore(
                        peerId = peer.id,
                        distance = distance,
                        score = score,
                        profile = profile,
                        explanation = "distance=$distance quality=${"%.2f".format(qualityScore)} trust=${"%.2f".format(profile.trustScore)}"
                    )
                }
            }
            .maxByOrNull { it.score }
    }

    private fun routingPeerProfile(targetNodeId: String, candidateNodeId: String, contextKey: String?): RoutingPeerProfile {
        val relationDistance = relationDistance(targetNodeId, candidateNodeId)
        val candidateRevocations = revocations.filter { it.nodeId == candidateNodeId }
        val isRevoked = candidateRevocations.isNotEmpty()
        val candidateAliases = aliasBindings.filter { it.nodeId == candidateNodeId || it.contactId == candidateNodeId }
        val now = now()
        val hasRelayRole = roleAttestations.any {
            val expiresAt = it.expiresAt
            it.nodeId == candidateNodeId &&
                it.role == GovernanceRole.RELAY &&
                (expiresAt == null || !expiresAt.isBefore(now))
        }
        val targetInvites = inviteAttestations.filter { it.inviterNodeId == targetNodeId || it.memberNodeId == targetNodeId }
        val candidateInvites = inviteAttestations.filter { it.inviterNodeId == candidateNodeId || it.memberNodeId == candidateNodeId }
        val targetRoots = targetInvites.flatMap { listOf(it.inviterNodeId, it.memberNodeId) }.toSet()
        val candidateRoots = candidateInvites.flatMap { listOf(it.inviterNodeId, it.memberNodeId) }.toSet()
        val sharedLineage = targetRoots.intersect(candidateRoots).isNotEmpty()
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

    private fun relationDistance(targetNodeId: String, candidateNodeId: String): Int {
        if (targetNodeId == candidateNodeId) return 0
        if (revocations.any { it.nodeId == candidateNodeId }) return 6

        val candidateAliases = aliasBindings.filter { it.nodeId == candidateNodeId || it.contactId == candidateNodeId }
        if (candidateAliases.any { it.contactId == targetNodeId || it.nodeId == targetNodeId }) {
            return 1
        }

        val targetInvites = inviteAttestations.filter { it.inviterNodeId == targetNodeId || it.memberNodeId == targetNodeId }
        val candidateInvites = inviteAttestations.filter { it.inviterNodeId == candidateNodeId || it.memberNodeId == candidateNodeId }
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

    private fun shouldUseShadowCustody(node: SimNode, score: SimPathScore): Boolean =
        node.webMailboxAvailable && (score.score < 3.1 || score.distance >= 3)

    private fun recordAttempt(ownerNodeId: String, peerId: String, contextKey: String) {
        val existing = routeEdges.getOrPut(ownerNodeId) { mutableMapOf() }[routeKey(peerId, contextKey)]
        val next = if (existing == null) {
            RouteContextEdge(
                id = 0,
                peerId = peerId,
                contextKey = contextKey,
                successCount = 0,
                failureCount = 0,
                custodyCount = 0,
                attemptCount = 1,
                edgeWeight = 0.0,
                lastTransport = null,
                lastReasonCode = null,
                lastLatencyMs = null,
                updatedAt = now()
            )
        } else {
            existing.copy(attemptCount = existing.attemptCount + 1, updatedAt = now())
        }
        routeEdges.getOrPut(ownerNodeId) { mutableMapOf() }[routeKey(peerId, contextKey)] = next
    }

    private fun recordOutcome(ownerNodeId: String, peerId: String, contextKey: String, success: Boolean, reason: DecisionReasonCode) {
        updateRouteEdge(
            ownerNodeId = ownerNodeId,
            peerId = peerId,
            contextKey = contextKey,
            sample = RouteContextEdgeSample(
                peerId = peerId,
                contextKey = contextKey,
                decision = if (success) RoutingDecision.DELIVERY_CONFIRMED else RoutingDecision.DELIVERY_FAILED,
                reasonCode = reason,
                attemptCountDelta = 0,
                observedAt = now()
            )
        )
    }

    private fun recordMutation(
        ownerNodeId: String,
        msgId: String,
        mutationKind: MutationKind,
        peerId: String,
        contextKey: String,
        reason: DecisionReasonCode,
        detail: String
    ) {
        events += SimEvent(
            tick = tick,
            type = SimEventType.MUTATION,
            nodeId = ownerNodeId,
            msgId = msgId,
            mutation = mutationKind,
            peerId = peerId,
            detail = detail
        )
        val sample = when (mutationKind) {
            MutationKind.EDGE_REWEIGHT -> RouteContextEdgeSample(
                peerId = peerId,
                contextKey = contextKey,
                decision = RoutingDecision.FORWARD_NOW,
                reasonCode = reason,
                attemptCountDelta = 0,
                successCountDelta = 0,
                weightDeltaOverride = 0.55,
                observedAt = now()
            )
            MutationKind.ALIAS_ROTATE -> RouteContextEdgeSample(
                peerId = peerId,
                contextKey = contextKey,
                decision = RoutingDecision.STORE_CARRY,
                reasonCode = reason,
                attemptCountDelta = 0,
                successCountDelta = 0,
                weightDeltaOverride = -0.08,
                observedAt = now()
            )
            MutationKind.CUSTODY_ROLE_SHIFT -> RouteContextEdgeSample(
                peerId = peerId,
                contextKey = contextKey,
                decision = RoutingDecision.ACCEPT_CUSTODY,
                reasonCode = DecisionReasonCode.CUSTODY_TICKET_ISSUED,
                attemptCountDelta = 0,
                successCountDelta = 0,
                weightDeltaOverride = 0.35,
                observedAt = now()
            )
            MutationKind.HINT_TIER_SHIFT -> RouteContextEdgeSample(
                peerId = peerId,
                contextKey = contextKey,
                decision = RoutingDecision.STORE_CARRY,
                reasonCode = DecisionReasonCode.RELATIONAL_UNRESOLVED,
                attemptCountDelta = 0,
                successCountDelta = 0,
                weightDeltaOverride = 0.18,
                observedAt = now()
            )
            MutationKind.DELIVERY_PATH_CONFIRMED -> RouteContextEdgeSample(
                peerId = peerId,
                contextKey = contextKey,
                decision = RoutingDecision.DELIVERY_CONFIRMED,
                reasonCode = DecisionReasonCode.DELIVERY_ACK_RECEIVED,
                attemptCountDelta = 0,
                successCountDelta = 0,
                weightDeltaOverride = 0.65,
                observedAt = now()
            )
        }
        updateRouteEdge(ownerNodeId, peerId, contextKey, sample)
    }

    private fun updateRouteEdge(ownerNodeId: String, peerId: String, contextKey: String, sample: RouteContextEdgeSample) {
        val edges = routeEdges.getOrPut(ownerNodeId) { mutableMapOf() }
        val key = routeKey(peerId, contextKey)
        val updated = edges[key]?.withSample(sample) ?: RouteContextEdge(
            id = 0,
            peerId = peerId,
            contextKey = contextKey,
            successCount = sample.successCountDelta,
            failureCount = sample.failureCountDelta,
            custodyCount = sample.custodyCountDelta,
            attemptCount = sample.attemptCountDelta,
            edgeWeight = sample.weightDelta().coerceIn(-20.0, 20.0),
            lastTransport = sample.lastTransport,
            lastReasonCode = sample.reasonCode.name,
            lastLatencyMs = sample.lastLatencyMs,
            updatedAt = sample.observedAt
        )
        edges[key] = updated
    }

    private fun capGovernorFor(node: SimNode): CapGovernor =
        CapGovernor(
            DeviceCaps(
                maxFanoutDevice = node.maxFanout,
                transmitBudget = node.transmitBudget,
                minBatteryPct = node.minBatteryPct
            )
        )

    private fun now(): Instant = startTime.plusSeconds(tick * 60L)

    private fun routeKey(peerId: String, contextKey: String): String = "$peerId|$contextKey"

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

    private fun resultFor(message: SimMessage): SimResult =
        SimResult(
            delivered = message.delivered,
            deliveredViaCustody = message.deliveredViaCustody,
            deliveryTick = message.deliveryTick,
            events = eventsFor(message.id),
            holders = message.copies.map { it.nodeId }.toSet(),
            routeEdges = routeEdges.mapValues { entry -> entry.value.toMap() }
        )
}

class SimMessageHandle internal constructor(
    internal val message: SimMessage
)

class PineKoneStressScenario(
    private val seed: Int,
    private val nodeCount: Int = 16
) {
    init {
        require(nodeCount in 10..25) { "nodeCount must be 10..25" }
    }

    fun build(): Pair<PineKoneSimulation, Pair<String, String>> {
        val rng = Random(seed)
        val sim = PineKoneSimulation()
        val trustedA = "N00"
        val trustedB = "N01"
        val relayNodes = (2 until nodeCount - 2).map { "N${it.toString().padStart(2, '0')}" }
        val edgeNodes = listOf("N${(nodeCount - 2).toString().padStart(2, '0')}", "N${(nodeCount - 1).toString().padStart(2, '0')}")
        val harshNetwork = nodeCount > 18

        (0 until nodeCount).forEach { index ->
            val nodeId = "N${index.toString().padStart(2, '0')}"
            val isTrusted = nodeId == trustedA || nodeId == trustedB
            val isEdge = nodeId in edgeNodes
            sim.addNode(
                SimNode(
                    id = nodeId,
                    batteryPct = if (isTrusted) 90 else 55 + rng.nextInt(40),
                    queueSlack = if (isTrusted) 0.9 else 0.35 + rng.nextDouble() * 0.55,
                    transmitBudget = if (isTrusted) 0.95 else 0.3 + rng.nextDouble() * 0.6,
                    maxFanout = if (isTrusted) 2 else 1 + rng.nextInt(3),
                    minBatteryPct = if (isTrusted) 10 else 12 + rng.nextInt(18),
                    custodyEligible = isTrusted || rng.nextDouble() > 0.25,
                    webMailboxAvailable = isTrusted || rng.nextDouble() > 0.55,
                    canFetchCustody = isTrusted || isEdge
                )
            )
        }

        // Stable backbone plus noisy shortcuts.
        val allNodes = sim.nodeIds()
        allNodes.zipWithNext().forEach { (a, b) ->
            sim.connectBidirectional(a, b, quality = 0.35 + rng.nextDouble() * 0.25, transport = if (rng.nextBoolean()) SimTransport.BLE else SimTransport.WIFI)
        }
        sim.connectBidirectional(trustedA, relayNodes.first(), quality = if (harshNetwork) 0.42 else 0.5, transport = SimTransport.WIFI)
        sim.connectBidirectional(relayNodes.last(), trustedB, quality = if (harshNetwork) 0.45 else 0.52, transport = SimTransport.WIFI)
        repeat(nodeCount) {
            val a = allNodes[rng.nextInt(allNodes.size)]
            val b = allNodes[rng.nextInt(allNodes.size)]
            if (a != b) {
                sim.connectBidirectional(a, b, quality = 0.12 + rng.nextDouble() * 0.45, transport = if (rng.nextDouble() > 0.3) SimTransport.BLE else SimTransport.WIFI)
            }
        }

        // Shared lineage and relay grants.
        sim.invite("root", trustedA, "ctx:3047")
        sim.invite("root", trustedB, "ctx:3047")
        relayNodes.forEachIndexed { index, relay ->
            sim.invite(if (index % 3 == 0) "root" else "branch${index % 3}", relay, "ctx:3047")
            sim.grantRole(relay, GovernanceRole.RELAY, "root", expiresAtTick = if (index % 5 == 0) 20 + index else null)
            sim.bindAlias(relay, trustedB, "ctx:3047:trusted", relationDistance = 2 + (index % 2))
        }
        edgeNodes.forEach { edge ->
            sim.invite("outer", edge, "ctx:9999")
            sim.bindAlias(edge, trustedB, "ctx:3047:trusted", relationDistance = 4)
        }

        // A compromised relay should be bypassed for the trusted context.
        relayNodes.getOrNull(1)?.let { compromised ->
            sim.revoke(compromised, "root", "scope:ctx:3047:trusted")
        }

        return sim to (trustedA to trustedB)
    }

    fun applyFluctuations(sim: PineKoneSimulation, tick: Int) {
        val rng = Random(seed * 997 + tick)
        val harshNetwork = nodeCount > 18
        sim.nodeIds().forEachIndexed { index, nodeId ->
            val custodyWindow = ((tick + index) % 7) != 0
            val isTrusted = index < 2
            sim.updateNode(
                nodeId = nodeId,
                batteryPct = if (isTrusted) {
                    22 + ((rng.nextInt(30) + tick + index) % 45)
                } else {
                    18 + ((rng.nextInt(45) + tick + index) % 55)
                },
                queueSlack = if (isTrusted) 0.12 + rng.nextDouble() * 0.45 else 0.1 + rng.nextDouble() * 0.65,
                transmitBudget = if (isTrusted) 0.18 + rng.nextDouble() * 0.5 else 0.08 + rng.nextDouble() * 0.75,
                custodyEligible = isTrusted || custodyWindow,
                webMailboxAvailable = isTrusted || rng.nextDouble() > if (harshNetwork) 0.35 else 0.5,
                canFetchCustody = isTrusted || rng.nextDouble() > if (harshNetwork) 0.55 else 0.65
            )
        }

        sim.linkPairs().forEach { (from, to) ->
            val adjacentToTrusted = from in setOf("N00", "N01") || to in setOf("N00", "N01")
            val activeThreshold = when {
                harshNetwork && adjacentToTrusted -> 0.48
                adjacentToTrusted -> 0.36
                harshNetwork -> 0.32
                else -> 0.22
            }
            val active = rng.nextDouble() > activeThreshold
            sim.setLinkActive(from, to, active)
            sim.setLinkQuality(
                from,
                to,
                if (adjacentToTrusted) 0.08 + rng.nextDouble() * 0.45 else 0.1 + rng.nextDouble() * 0.6
            )
        }
    }

    fun runStress(
        rounds: Int = 18,
        maxTicksPerMessage: Int = 10
    ): Pair<SimStressReport, List<SimStressMessageResult>> {
        val (sim, trustedPair) = build()
        val results = mutableListOf<SimStressMessageResult>()

        repeat(rounds) { round ->
            val from = if (round % 2 == 0) trustedPair.first else trustedPair.second
            val to = if (round % 2 == 0) trustedPair.second else trustedPair.first
            val handle = sim.sendMessage(
                originId = from,
                targetId = to,
                contextKey = "ctx:3047:trusted",
                policy = PkPolicy(maxFanout = 2, retryLimit = 3, minBattPct = 15),
                ops = PkOps(storeCarry = true, requireAck = true, e2eAckPath = true),
                ttl = 8
            )
            val result = sim.run(
                handle = handle,
                maxTicks = maxTicksPerMessage,
                beforeTick = { tick, live -> applyFluctuations(live, tick + round * maxTicksPerMessage) }
            )
            val failureReasons = result.events.mapNotNull { it.reason }
                .filter { it != DecisionReasonCode.CONDENSE_PROGRESS && it != DecisionReasonCode.DELIVERY_ACK_RECEIVED && it != DecisionReasonCode.CUSTODY_TICKET_ISSUED }
            results += SimStressMessageResult(
                from = from,
                to = to,
                contextKey = "ctx:3047:trusted",
                delivered = result.delivered,
                deliveredViaCustody = result.deliveredViaCustody,
                deliveryTick = result.deliveryTick,
                failureReasons = failureReasons,
                eventCount = result.events.size
            )
        }

        val deliveredMessages = results.count { it.delivered }
        val custodyDeliveries = results.count { it.deliveredViaCustody }
        val failedMessages = results.count { !it.delivered }
        val failureReasons = results.flatMap { it.failureReasons }.groupingBy { it }.eachCount()
        val report = SimStressReport(
            seed = seed,
            nodeCount = nodeCount,
            totalMessages = results.size,
            deliveredMessages = deliveredMessages,
            custodyDeliveries = custodyDeliveries,
            failedMessages = failedMessages,
            deliveryRate = deliveredMessages.toDouble() / results.size,
            custodyRate = custodyDeliveries.toDouble() / results.size,
            failureReasons = failureReasons,
            trustedPairSuccessRate = deliveredMessages.toDouble() / results.size,
            totalEvents = results.sumOf { it.eventCount }
        )
        return report to results
    }
}
