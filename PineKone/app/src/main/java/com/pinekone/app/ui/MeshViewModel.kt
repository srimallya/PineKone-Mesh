package com.pinekone.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.pinekone.app.PineKoneApp
import com.pinekone.app.data.model.ChatMessage
import com.pinekone.app.data.model.Contact
import com.pinekone.app.data.model.DecisionReasonCode
import com.pinekone.app.data.model.MessageDirection
import com.pinekone.app.data.model.MessageStatus
import com.pinekone.app.data.model.PublicChatMessage
import com.pinekone.app.engine.CustodyTicket
import com.pinekone.app.engine.PkEngine
import com.pinekone.app.engine.PkPeer
import com.pinekone.app.engine.RadioMode
import com.pinekone.app.identity.PkIdentity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MeshViewModel(application: Application) : AndroidViewModel(application) {
    private val app: PineKoneApp = application as PineKoneApp
    private val engine: PkEngine = app.engine
    private val contactRepository = app.contactRepository
    private val messageRepository = app.messageRepository
    private val routingTelemetryRepository = app.routingTelemetryRepository
    private val governanceRepository = app.governanceRepository

    val identity: StateFlow<PkIdentity?> = engine.identity.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = null
    )

    val peers: StateFlow<List<PkPeer>> = engine.peers
    val transportAvailability = engine.transportAvailability
    val contacts: StateFlow<List<Contact>> = engine.contacts
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val envelopes = engine.envelopes
    val publicMessages: StateFlow<List<PublicChatMessage>> =
        engine.publicMessages.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val custodyTickets: StateFlow<CustodyTicket?> = engine.custodyTickets
        .stateIn(viewModelScope, SharingStarted.Lazily, null)
    val radioMode: StateFlow<RadioMode> = engine.radioMode

    val sendEvents = engine.sendEvents
    val pingEvents = engine.pingEvents
    val governanceSummary = governanceRepository.governanceSummary
        .stateIn(viewModelScope, SharingStarted.Eagerly, com.pinekone.app.data.model.GovernanceSummary(0, 0, 0, 0))
    val governanceEvents = governanceRepository.governanceEvents
        .map { rows -> rows.map { FeedRow(it.id, it.title, it.subtitle) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val networkFeed = combine(
        governanceRepository.governanceEvents,
        routingTelemetryRepository.mutationEvents
    ) { governanceRows, mutationRows ->
        val governanceFeed = governanceRows.map { FeedRow(it.id, it.title, it.subtitle) }
        val mutationFeed = mutationRows.take(50).map {
            FeedRow(
                id = "mutation:${it.id}",
                title = it.mutationKind.name.replace('_', ' '),
                subtitle = listOfNotNull(it.msgId.take(10), it.peerId?.take(10), it.detail).joinToString(" • ")
            )
        }
        mutationFeed + governanceFeed
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val custodyFeed = routingTelemetryRepository.decisionEvents
        .map { events ->
            events.filter {
                it.reasonCode == DecisionReasonCode.CUSTODY_TICKET_ISSUED || it.reasonCode == DecisionReasonCode.CUSTODY_REJECTED
            }.map {
                FeedRow(
                    id = "custody:${it.id}",
                    title = it.decision.name.replace('_', ' '),
                    subtitle = listOfNotNull(it.msgId.take(10), it.reasonCode.name.lowercase(), it.detail).joinToString(" • ")
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun sendMessage(contactId: String, message: String) {
        viewModelScope.launch {
            engine.sendMessage(contactId, message)
        }
    }

    fun sendPublicMessage(message: String) {
        viewModelScope.launch {
            engine.postPublicMessage(message)
        }
    }

    fun pingPeer(peerId: String) {
        viewModelScope.launch {
            engine.pingPeer(peerId)
        }
    }

    fun setRadioMode(mode: RadioMode) {
        engine.setRadioMode(mode)
    }

    fun resendMessage(contactId: String, message: ChatMessage) {
        if (message.direction != MessageDirection.OUTGOING || message.status != MessageStatus.FAILED) return
        viewModelScope.launch {
            engine.resendMessage(contactId, message.msgId)
        }
    }

    fun messagesFor(contactId: String): StateFlow<List<ChatMessage>> =
        engine.messagesFor(contactId)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun messageTraceSummaries(contactId: String): Flow<Map<String, MessageTraceSummary>> =
        messagesFor(contactId).flatMapLatest { messages ->
            if (messages.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(
                    messages.map { message ->
                        combine(
                            routingTelemetryRepository.decisionEventsFor(message.msgId),
                            routingTelemetryRepository.mutationEventsFor(message.msgId)
                        ) { decisions, mutations ->
                            val latestDecision = decisions.lastOrNull()
                            message.msgId to MessageTraceSummary(
                                msgId = message.msgId,
                                latestDecision = latestDecision?.decision?.name?.replace('_', ' ') ?: "No trace yet",
                                latestReason = latestDecision?.reasonCode?.name?.lowercase()?.replace('_', ' ') ?: "pending",
                                mutationCount = mutations.size,
                                latestDetail = latestDecision?.detail
                            )
                        }
                    }
                ) { pairs ->
                    pairs.toMap()
                }
            }
        }

    fun renameContact(contactId: String, newName: String) {
        viewModelScope.launch {
            contactRepository.renameContact(contactId, newName)
        }
    }

    fun deleteContact(contactId: String) {
        viewModelScope.launch {
            contactRepository.deleteContact(contactId)
        }
    }

    fun deleteConversation(contactId: String) {
        viewModelScope.launch {
            messageRepository.deleteConversation(contactId)
        }
    }

    fun contactFlow(contactId: String): StateFlow<Contact?> =
        contacts.map { list -> list.firstOrNull { it.nodeId == contactId } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.AndroidViewModelFactory(application) {}
    }
}
