package com.pinekone.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.pinekone.app.PineKoneApp
import com.pinekone.app.data.model.AppSettings
import com.pinekone.app.data.model.AutoDownloadImages
import com.pinekone.app.data.model.AutoPlayVoiceNotes
import com.pinekone.app.data.model.ChatMessage
import com.pinekone.app.data.model.Contact
import com.pinekone.app.data.model.DefaultPrivacyMode
import com.pinekone.app.data.model.DecisionReasonCode
import com.pinekone.app.data.model.MapVisibilityDefault
import com.pinekone.app.data.model.MessageDirection
import com.pinekone.app.data.model.MessageStatus
import com.pinekone.app.data.model.PublicChatMessage
import com.pinekone.app.engine.CustodyTicket
import com.pinekone.app.engine.PkEngine
import com.pinekone.app.engine.PkPeer
import com.pinekone.app.engine.RadioMode
import com.pinekone.app.identity.PkIdentity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
    private val settingsRepository = app.settingsRepository
    private val attachmentRepository = app.attachmentRepository

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
    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val custodyTickets: StateFlow<CustodyTicket?> = engine.custodyTickets
        .stateIn(viewModelScope, SharingStarted.Lazily, null)
    val radioMode: StateFlow<RadioMode> = engine.radioMode

    val sendEvents = engine.sendEvents
    val pingEvents = engine.pingEvents
    private val mediaErrorsMutable = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val mediaErrors = mediaErrorsMutable.asSharedFlow()
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
    val visiblePeers: StateFlow<List<PeerPresentation>> = combine(
        engine.peers,
        contacts,
        governanceRepository.aliasBindings,
        governanceRepository.revocations,
        settings
    ) { peers, contactsNow, aliases, revocations, appSettings ->
        peers.mapNotNull { peer ->
            val matchingContact = contactsNow.firstOrNull {
                it.nodeId == peer.id || (peer.fingerprintHex != null && it.fingerprint == peer.fingerprintHex)
            }
            val relationDistance = aliases
                .filter { it.nodeId == peer.id || it.contactId == peer.id || matchingContact?.nodeId == it.contactId }
                .minOfOrNull { it.relationDistance }
                ?: if (matchingContact != null) 1 else 4
            val isRevoked = revocations.any { it.nodeId == peer.id }
            val isTrusted = !isRevoked && relationDistance <= 2
            val isContact = matchingContact != null

            if (!appSettings.showUnverifiedPeers && !isTrusted && !isContact) {
                null
            } else {
                val visible = when (appSettings.mapVisibilityDefault) {
                    MapVisibilityDefault.ALL_DISCOVERED -> true
                    MapVisibilityDefault.CONTACTS_ONLY -> isContact
                    MapVisibilityDefault.TRUSTED_ONLY -> isTrusted
                }
                if (!visible) {
                    null
                } else {
                    PeerPresentation(
                        peer = peer,
                        isContact = isContact,
                        isTrusted = isTrusted,
                        isRevoked = isRevoked,
                        relationDistance = relationDistance,
                        visibilityLabel = when (appSettings.mapVisibilityDefault) {
                            MapVisibilityDefault.ALL_DISCOVERED -> "All discovered"
                            MapVisibilityDefault.CONTACTS_ONLY -> "Contacts only"
                            MapVisibilityDefault.TRUSTED_ONLY -> "Trusted only"
                        }
                    )
                }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun sendMessage(contactId: String, message: String) {
        viewModelScope.launch {
            engine.sendMessage(contactId, message)
        }
    }

    fun sendImageMessage(contactId: String, sourceUri: Uri, caption: String = "") {
        viewModelScope.launch {
            runCatching {
                val stored = attachmentRepository.importImage(sourceUri, maxBytes = 3L * 1024L * 1024L)
                engine.sendImageMessage(
                    contactId = contactId,
                    payloadText = caption,
                    localUri = stored.localUri,
                    mimeType = stored.mimeType,
                    fileName = stored.fileName,
                    byteSize = stored.byteSize,
                    bytes = stored.bytes,
                    thumbnailUri = stored.thumbnailUri
                )
            }.onFailure { mediaErrorsMutable.tryEmit(it.message ?: "Image could not be prepared.") }
        }
    }

    fun sendVoiceNote(contactId: String, filePath: String, durationMs: Long) {
        viewModelScope.launch {
            runCatching {
                val stored = attachmentRepository.finalizeVoiceNote(
                    file = java.io.File(filePath),
                    maxBytes = 3L * 1024L * 1024L,
                    durationOverrideMs = durationMs
                )
                engine.sendVoiceNote(
                    contactId = contactId,
                    localUri = stored.localUri,
                    fileName = stored.fileName,
                    byteSize = stored.byteSize,
                    durationMs = stored.durationMs,
                    bytes = stored.bytes
                )
            }.onFailure { mediaErrorsMutable.tryEmit(it.message ?: "Voice note could not be prepared.") }
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

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
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

    fun setMapVisibility(mode: MapVisibilityDefault) {
        viewModelScope.launch { settingsRepository.setMapVisibility(mode) }
    }

    fun setShowDiagnostics(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setShowDiagnostics(enabled) }
    }

    fun setShowUnverifiedPeers(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setShowUnverifiedPeers(enabled) }
    }

    fun setAutoDownloadImages(mode: AutoDownloadImages) {
        viewModelScope.launch { settingsRepository.setAutoDownloadImages(mode) }
    }

    fun setAutoPlayVoiceNotes(mode: AutoPlayVoiceNotes) {
        viewModelScope.launch { settingsRepository.setAutoPlayVoiceNotes(mode) }
    }

    fun setDefaultPrivacyMode(mode: DefaultPrivacyMode) {
        viewModelScope.launch { settingsRepository.setDefaultPrivacyMode(mode) }
    }

    fun contactFlow(contactId: String): StateFlow<Contact?> =
        contacts.map { list -> list.firstOrNull { it.nodeId == contactId } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.AndroidViewModelFactory(application) {}
    }
}
