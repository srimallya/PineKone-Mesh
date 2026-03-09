package com.pinekone.app.data

import com.pinekone.app.data.db.DecisionEventEntity
import com.pinekone.app.data.db.MutationEventEntity
import com.pinekone.app.data.db.RoutingTelemetryDao
import com.pinekone.app.data.model.DecisionEvent
import com.pinekone.app.data.model.DecisionReasonCode
import com.pinekone.app.data.model.MutationEvent
import com.pinekone.app.data.model.MutationKind
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

    fun decisionEventsFor(msgId: String): Flow<List<DecisionEvent>> =
        dao.observeDecisionEvents(msgId).map { events ->
            events.map { it.toDomain() }
        }

    fun mutationEventsFor(msgId: String): Flow<List<MutationEvent>> =
        dao.observeMutationEvents(msgId).map { events ->
            events.map { it.toDomain() }
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
}
