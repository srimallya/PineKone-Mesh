package com.pinekone.app.data

import com.pinekone.app.data.db.DecisionEventEntity
import com.pinekone.app.data.db.MutationEventEntity
import com.pinekone.app.data.db.RouteContextEdgeEntity
import com.pinekone.app.data.db.RoutingTelemetryDao
import com.pinekone.app.data.model.DecisionEvent
import com.pinekone.app.data.model.DecisionReasonCode
import com.pinekone.app.data.model.MutationEvent
import com.pinekone.app.data.model.MutationKind
import com.pinekone.app.data.model.RouteContextEdge
import com.pinekone.app.data.model.RouteContextEdgeSample
import com.pinekone.app.data.model.RouteContextEdgeScore
import com.pinekone.app.data.model.RoutingDecision
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoutingTelemetryRepository(
    private val dao: RoutingTelemetryDao
) {
    val decisionEvents: Flow<List<DecisionEvent>> = dao.observeAllDecisionEvents().map { events ->
        events.map { it.toDomain() }
    }

    val mutationEvents: Flow<List<MutationEvent>> = dao.observeAllMutationEvents().map { events ->
        events.map { it.toDomain() }
    }

    val routeContextEdges: Flow<List<RouteContextEdge>> = dao.observeRouteContextEdges().map { edges ->
        edges.map { it.toDomain() }
    }

    val routeContextEdgeScores: Flow<List<RouteContextEdgeScore>> = dao.observeRouteContextEdges().map { edges ->
        edges.map { it.toDomain().score() }
            .sortedByDescending { it.score }
    }

    fun decisionEventsFor(msgId: String): Flow<List<DecisionEvent>> =
        dao.observeDecisionEvents(msgId).map { events ->
            events.map { it.toDomain() }
        }

    fun mutationEventsFor(msgId: String): Flow<List<MutationEvent>> =
        dao.observeMutationEvents(msgId).map { events ->
            events.map { it.toDomain() }
        }

    suspend fun routeEdgesForContext(contextKey: String): Map<String, RouteContextEdge> =
        dao.findRouteContextEdges(contextKey)
            .map { it.toDomain() }
            .associateBy { it.peerId }

    suspend fun getRouteContextEdge(peerId: String, contextKey: String): RouteContextEdge? =
        dao.getRouteContextEdge(peerId, contextKey)?.toDomain()

    fun observeRouteContextEdgeScores(contextKey: String): Flow<List<RouteContextEdgeScore>> =
        dao.observeRouteContextEdges().map { edges ->
            edges.asSequence()
                .map { it.toDomain() }
                .filter { it.contextKey == contextKey }
                .map { it.score() }
                .sortedByDescending { it.score }
                .toList()
        }

    suspend fun rankRouteContextEdges(contextKey: String): List<RouteContextEdgeScore> =
        dao.findRouteContextEdges(contextKey)
            .asSequence()
            .map { it.toDomain() }
            .map { it.score() }
            .sortedByDescending { it.score }
            .toList()

    suspend fun recordRouteAttempt(peerId: String, contextKey: String, transport: String?) {
        val now = System.currentTimeMillis()
        val current = dao.getRouteContextEdge(peerId, contextKey)
        dao.upsertRouteContextEdge(
            RouteContextEdgeEntity(
                id = current?.id ?: 0,
                peerId = peerId,
                contextKey = contextKey,
                successCount = current?.successCount ?: 0,
                failureCount = current?.failureCount ?: 0,
                custodyCount = current?.custodyCount ?: 0,
                attemptCount = (current?.attemptCount ?: 0) + 1,
                edgeWeight = current?.edgeWeight ?: 0.0,
                lastTransport = transport ?: current?.lastTransport,
                lastReasonCode = current?.lastReasonCode,
                lastLatencyMs = current?.lastLatencyMs,
                updatedAtEpochMillis = now
            )
        )
    }

    suspend fun recordRouteContextEdgeSample(sample: RouteContextEdgeSample): RouteContextEdge {
        val current = dao.getRouteContextEdge(sample.peerId, sample.contextKey)
        val currentDomain = current?.toDomain()
        val nextDomain = if (currentDomain == null) {
            RouteContextEdge(
                id = 0,
                peerId = sample.peerId,
                contextKey = sample.contextKey,
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
        } else {
            currentDomain.withSample(sample)
        }

        dao.upsertRouteContextEdge(nextDomain.toEntity(current?.id ?: nextDomain.id))
        return nextDomain
    }

    suspend fun recordRouteOutcome(
        peerId: String,
        contextKey: String,
        success: Boolean,
        custodyAccepted: Boolean = false,
        latencyMs: Long? = null,
        transport: String?,
        reasonCode: DecisionReasonCode?
    ) {
        val sample = RouteContextEdgeSample(
            peerId = peerId,
            contextKey = contextKey,
            decision = when {
                custodyAccepted -> RoutingDecision.ACCEPT_CUSTODY
                success -> RoutingDecision.FORWARD_NOW
                else -> RoutingDecision.DELIVERY_FAILED
            },
            reasonCode = reasonCode ?: when {
                custodyAccepted -> DecisionReasonCode.CUSTODY_TICKET_ISSUED
                success -> DecisionReasonCode.DELIVERY_ACK_RECEIVED
                else -> DecisionReasonCode.NO_VIABLE_PATH
            },
            lastTransport = transport,
            lastLatencyMs = latencyMs,
            attemptCountDelta = 0
        )
        recordRouteContextEdgeSample(sample)
    }

    suspend fun recordRouteMutationImpact(
        peerId: String,
        contextKey: String,
        mutationKind: MutationKind,
        transport: String?,
        reasonCode: DecisionReasonCode?
    ) {
        val sample = when (mutationKind) {
            MutationKind.EDGE_REWEIGHT -> RouteContextEdgeSample(
                peerId = peerId,
                contextKey = contextKey,
                decision = RoutingDecision.FORWARD_NOW,
                reasonCode = reasonCode ?: DecisionReasonCode.CONDENSE_PROGRESS,
                lastTransport = transport,
                attemptCountDelta = 0,
                successCountDelta = 0,
                weightDeltaOverride = 0.55
            )
            MutationKind.ALIAS_ROTATE -> RouteContextEdgeSample(
                peerId = peerId,
                contextKey = contextKey,
                decision = RoutingDecision.STORE_CARRY,
                reasonCode = reasonCode ?: DecisionReasonCode.CONDENSE_PROGRESS,
                lastTransport = transport,
                attemptCountDelta = 0,
                successCountDelta = 0,
                weightDeltaOverride = -0.08
            )
            MutationKind.CUSTODY_ROLE_SHIFT -> RouteContextEdgeSample(
                peerId = peerId,
                contextKey = contextKey,
                decision = RoutingDecision.ACCEPT_CUSTODY,
                reasonCode = reasonCode ?: DecisionReasonCode.CUSTODY_TICKET_ISSUED,
                lastTransport = transport,
                attemptCountDelta = 0,
                successCountDelta = 0,
                weightDeltaOverride = 0.35
            )
            MutationKind.HINT_TIER_SHIFT -> RouteContextEdgeSample(
                peerId = peerId,
                contextKey = contextKey,
                decision = RoutingDecision.STORE_CARRY,
                reasonCode = reasonCode ?: DecisionReasonCode.RELATIONAL_UNRESOLVED,
                lastTransport = transport,
                attemptCountDelta = 0,
                successCountDelta = 0,
                weightDeltaOverride = 0.18
            )
            MutationKind.DELIVERY_PATH_CONFIRMED -> RouteContextEdgeSample(
                peerId = peerId,
                contextKey = contextKey,
                decision = RoutingDecision.DELIVERY_CONFIRMED,
                reasonCode = reasonCode ?: DecisionReasonCode.DELIVERY_ACK_RECEIVED,
                lastTransport = transport,
                attemptCountDelta = 0,
                successCountDelta = 0,
                weightDeltaOverride = 0.65
            )
        }
        recordRouteContextEdgeSample(sample)
    }

    suspend fun recordDecision(
        msgId: String,
        contactId: String?,
        decision: RoutingDecision,
        reasonCode: DecisionReasonCode,
        transport: String?,
        peerId: String?,
        detail: String?
    ) {
        dao.insertDecisionEvent(
            DecisionEventEntity(
                msgId = msgId,
                contactId = contactId,
                decision = decision.name,
                reasonCode = reasonCode.name,
                transport = transport,
                peerId = peerId,
                detail = detail,
                createdAtEpochMillis = System.currentTimeMillis()
            )
        )
    }

    suspend fun recordMutation(
        msgId: String,
        mutationKind: MutationKind,
        peerId: String?,
        detail: String?
    ) {
        dao.insertMutationEvent(
            MutationEventEntity(
                msgId = msgId,
                mutationKind = mutationKind.name,
                peerId = peerId,
                detail = detail,
                createdAtEpochMillis = System.currentTimeMillis()
            )
        )
    }

    private fun DecisionEventEntity.toDomain(): DecisionEvent =
        DecisionEvent(
            id = id,
            msgId = msgId,
            contactId = contactId,
            decision = RoutingDecision.valueOf(decision),
            reasonCode = DecisionReasonCode.valueOf(reasonCode),
            transport = transport,
            peerId = peerId,
            detail = detail,
            createdAt = Instant.ofEpochMilli(createdAtEpochMillis)
        )

    private fun MutationEventEntity.toDomain(): MutationEvent =
        MutationEvent(
            id = id,
            msgId = msgId,
            mutationKind = MutationKind.valueOf(mutationKind),
            peerId = peerId,
            detail = detail,
            createdAt = Instant.ofEpochMilli(createdAtEpochMillis)
        )

    private fun RouteContextEdgeEntity.toDomain(): RouteContextEdge =
        RouteContextEdge(
            id = id,
            peerId = peerId,
            contextKey = contextKey,
            successCount = successCount,
            failureCount = failureCount,
            custodyCount = custodyCount,
            attemptCount = attemptCount,
            edgeWeight = edgeWeight,
            lastTransport = lastTransport,
            lastReasonCode = lastReasonCode,
            lastLatencyMs = lastLatencyMs,
            updatedAt = Instant.ofEpochMilli(updatedAtEpochMillis)
        )

    private fun RouteContextEdge.toEntity(id: Long = this.id): RouteContextEdgeEntity =
        RouteContextEdgeEntity(
            id = id,
            peerId = peerId,
            contextKey = contextKey,
            successCount = successCount,
            failureCount = failureCount,
            custodyCount = custodyCount,
            attemptCount = attemptCount,
            edgeWeight = edgeWeight,
            lastTransport = lastTransport,
            lastReasonCode = lastReasonCode,
            lastLatencyMs = lastLatencyMs,
            updatedAtEpochMillis = updatedAt.toEpochMilli()
        )
}
