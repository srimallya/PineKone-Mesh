package com.pinekone.app.engine

import android.util.Log
import com.pinekone.app.data.AttachmentRepository
import com.pinekone.app.data.ContactRepository
import com.pinekone.app.data.GovernanceRepository
import com.pinekone.app.data.MessageRepository
import com.pinekone.app.data.PublicChatRepository
import com.pinekone.app.data.ProtocolStateRepository
import com.pinekone.app.data.RoutingTelemetryRepository
import com.pinekone.app.data.db.DecisionReceiptEntity
import com.pinekone.app.data.model.DecisionReasonCode
import com.pinekone.app.data.model.MessageContentType
import com.pinekone.app.data.model.MessageDirection
import com.pinekone.app.data.model.MessageStatus
import com.pinekone.app.data.model.MessageTransport
import com.pinekone.app.data.model.MutationKind
import com.pinekone.app.data.model.RoutingDecision
import com.pinekone.app.identity.IdentityRepository
import com.pinekone.app.protocol.AckFrame
import com.pinekone.app.protocol.CompatAckFrame
import com.pinekone.app.protocol.LEGACY_PROTOCOL_VERSION
import com.pinekone.app.protocol.PkAuth
import com.pinekone.app.protocol.PkEnvelope
import com.pinekone.app.protocol.PkFormats
import com.pinekone.app.protocol.PkFragmentHeader
import com.pinekone.app.protocol.PkFragmentKind
import com.pinekone.app.protocol.PkHints
import com.pinekone.app.protocol.PkOps
import com.pinekone.app.protocol.PkPolicy
import com.pinekone.app.protocol.PkControlFrame
import com.pinekone.app.protocol.PingFrame
import com.pinekone.app.protocol.PongFrame
import com.pinekone.app.protocol.PkWebHints
import com.pinekone.app.protocol.DirectAttachmentPayload
import com.pinekone.app.protocol.DirectMessagePayload
import com.pinekone.app.protocol.contentType
import com.pinekone.app.protocol.fromBase64String
import com.pinekone.app.protocol.toBase64String
import com.pinekone.app.protocol.toDirectMessagePayloadOrNull
import com.pinekone.app.protocol.toBytes
import com.pinekone.app.protocol.toHexString
import com.pinekone.app.protocol.hexToByteArray
import com.pinekone.app.store.EnvelopeRecord
import com.pinekone.app.store.EnvelopeStatus
import com.pinekone.app.store.PkMessageStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.security.SecureRandom
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

data class SendResult(
    val envelope: PkEnvelope,
    val decision: ForwardDecision,
    val reasonCode: DecisionReasonCode
)

private const val TAG = "PkEngine"
private const val ACK_TIMEOUT_MS = 5_000L
private const val MAX_RETRY_ATTEMPTS = 3
private const val BACKOFF_MULTIPLIER = 2.0
private const val MAX_BACKOFF_MS = 20_000L
private const val PING_TIMEOUT_MS = 4_000L
private const val MAX_IMAGE_BYTES = 3L * 1024L * 1024L
private const val MAX_VOICE_BYTES = 3L * 1024L * 1024L
private const val MAX_VOICE_DURATION_MS = 120_000L
private val PUBLIC_HINT_MAGIC = "PKPUBMSG".encodeToByteArray()

sealed interface SendLifecycleEvent {
    val contactId: String
    val msgIdHex: String

    data class RetryScheduled(
        override val contactId: String,
        override val msgIdHex: String,
        val attempt: Int,
        val maxAttempts: Int
    ) : SendLifecycleEvent

    data class Failed(
        override val contactId: String,
        override val msgIdHex: String
    ) : SendLifecycleEvent
}

sealed interface PingEvent {
    val peerId: String

    data class Started(override val peerId: String) : PingEvent
    data class Success(override val peerId: String, val latencyMs: Long) : PingEvent
    data class Timeout(override val peerId: String) : PingEvent
}

class PkEngine(
    private val scope: CoroutineScope,
    private val identityRepository: IdentityRepository,
    private val meshTransport: MeshTransport,
    private val webTransport: WebTransport,
    private val messageStore: PkMessageStore,
    private val contactRepository: ContactRepository,
    private val messageRepository: MessageRepository,
    private val publicChatRepository: PublicChatRepository,
    private val routingTelemetryRepository: RoutingTelemetryRepository,
    private val protocolStateRepository: ProtocolStateRepository,
    private val governanceRepository: GovernanceRepository,
    private val attachmentRepository: AttachmentRepository,
    private val pathScorer: RelationalPathScorer,
    private val capGovernor: CapGovernor,
    private val statusProvider: DeviceStatusProvider
) {
    private val started = AtomicBoolean(false)
    private val random = SecureRandom()
    private val sendEventsMutable = MutableSharedFlow<SendLifecycleEvent>(extraBufferCapacity = 8)
    val sendEvents = sendEventsMutable.asSharedFlow()
    private val pendingAcks = ConcurrentHashMap<String, PendingAck>()
    private val pingEventsMutable = MutableSharedFlow<PingEvent>(extraBufferCapacity = 8)
    val pingEvents = pingEventsMutable.asSharedFlow()
    private val pendingPings = ConcurrentHashMap<String, PendingPing>()

    val peers: StateFlow<List<PkPeer>> = meshTransport.peers
    val envelopes = messageStore.records
    val custodyTickets = webTransport.custodyTickets
    val identity = identityRepository.identityFlow
    val contacts = contactRepository.contacts
    val publicMessages = publicChatRepository.messages()
    val transportAvailability = meshTransport.availability
    val radioMode = meshTransport.radioMode

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch { meshTransport.start() }
        scope.launch { webTransport.start() }
        scope.launch {
            identityRepository.identityFlow.collect { identity ->
                contactRepository.ensureSelf(
                    identity.nodeId,
                    identity.displayName,
                    identity.fingerprint.toHexString(),
                    identity.publicKey.toHexString()
                )
            }
        }
        scope.launch {
            meshTransport.peers.collect { peerList ->
                peerList.forEach { peer ->
                    val fingerprint = peer.fingerprintHex ?: return@forEach
                    val publicKeyHex = peer.publicKey?.toHexString()
                    contactRepository.upsertContact(peer.id, peer.displayName, fingerprint, publicKeyHex)
                }
            }
        }
        scope.launch {
            meshTransport.inbound.collect { frame ->
                when (frame) {
                    is InboundFrame.EnvelopeFrame -> {
                        if (frame.envelope.hints?.mu?.contentEquals(PUBLIC_HINT_MAGIC) == true) {
                            handlePublicBroadcast(frame)
                            return@collect
                        }
                        val msgIdHex = frame.envelope.msgId.toHexString()
                        val fingerprintHex = frame.envelope.auth?.originPkFingerprint?.toHexString()
                            ?: frame.via?.fingerprintHex
                            ?: "unknown"
                        val identity = identityRepository.getIdentity()
                        val contact = if (fingerprintHex != "unknown") {
                            val publicKeyHex = frame.via?.publicKey?.toHexString()
                            contactRepository.ensureContactForFingerprint(
                                fingerprintHex,
                                frame.via?.displayName ?: "Relay ${fingerprintHex.take(4)}",
                                publicKeyHex
                            )
                        } else {
                            null
                        }
                        contact?.let {
                            val publicKeyHex = it.publicKey
                            val decryptedBytes = if (publicKeyHex != null) {
                                com.pinekone.app.crypto.PkCrypto.decrypt(
                                    frame.envelope.payload,
                                    publicKeyHex,
                                    identity.secretKey
                                )
                            } else null
                            if (decryptedBytes != null) {
                                val transport = frame.via?.let { MessageTransport.MESH } ?: MessageTransport.WEB
                                storeIncomingMessage(
                                    contactId = it.nodeId,
                                    msgIdHex = msgIdHex,
                                    senderFingerprint = fingerprintHex,
                                    plaintext = decryptedBytes,
                                    transport = transport
                                )
                                frame.via?.let { peer ->
                                    val ack = AckFrame(
                                        msgId = frame.envelope.msgId,
                                        highestContiguousSeq = frame.envelope.frag.seq
                                    )
                                    scope.launch { meshTransport.sendControl(ack, peer.id) }
                                }
                            }
                        }
                        messageStore.upsert(
                            EnvelopeRecord(
                                envelope = frame.envelope,
                                createdAt = Instant.now(),
                                status = EnvelopeStatus.DELIVERED
                            )
                        )
                    }
                    is InboundFrame.Control -> {
                        handleControlFrame(frame.frame, frame.peer)
                    }
                }
            }
        }
    }

    fun ensureMeshTransportStarted() {
        scope.launch { meshTransport.start() }
    }

    fun setRadioMode(mode: RadioMode) {
        scope.launch { meshTransport.setRadioMode(mode) }
    }

    suspend fun stop() {
        if (!started.compareAndSet(true, false)) return
        pendingAcks.values.forEach { it.job?.cancel() }
        pendingAcks.clear()
        pendingPings.values.forEach { it.job?.cancel() }
        pendingPings.clear()
        meshTransport.stop()
        webTransport.stop()
    }

    suspend fun send(
        payload: ByteArray,
        policy: PkPolicy,
        hints: PkHints,
        ops: PkOps,
        web: PkWebHints? = null,
        ttl: Int = 10,
        debugNote: String? = null,
        peerId: String? = null,
        msgIdOverride: ByteArray? = null
    ): SendResult {
        val identity = identityRepository.getIdentity()
        val msgId = msgIdOverride?.copyOf()
            ?: ByteArray(16).apply(random::nextBytes)
        val mutationNonce = ByteArray(8).apply(random::nextBytes)
        val createdAtMs = System.currentTimeMillis()
        val deadlineMs = createdAtMs + (ttl.coerceAtLeast(0).toLong() * 1000L)
        val aliasCtx = buildString {
            append("ctx:")
            append(hints.communityId)
            append(':')
            append(hints.targetHash?.toHexString() ?: "public")
        }
        val ctxCommitment = hints.targetHash?.copyOf(16) ?: msgId.copyOf()
        val envelope = PkEnvelope(
            // Keep the mesh wire format readable by pre-upgrade peers until we negotiate versions.
            ver = LEGACY_PROTOCOL_VERSION,
            msgId = msgId,
            aliasCtx = aliasCtx,
            traceId = msgId.toHexString(),
            deadlineMs = deadlineMs,
            createdAtMs = createdAtMs,
            ctxCommitment = ctxCommitment,
            condenseDepth = if (peerId != null) 1 else 0,
            mutationNonce = mutationNonce,
            hintTier = hints.priority,
            ttl = ttl,
            policy = policy,
            hints = hints,
            ops = ops,
            web = web,
            frag = PkFragmentHeader(kind = PkFragmentKind.DATA, seq = 0, total = 1),
            auth = PkAuth(originPkFingerprint = identity.fingerprint),
            payload = payload
        )

        val deviceStatus = statusProvider.currentStatus()
        val decision = capGovernor.evaluate(policy, deviceStatus, ops.storeCarry)
        val reasonCode = reasonFor(decision, policy, deviceStatus)
        val msgIdHex = envelope.msgId.toHexString()
        val existingRecord = messageStore.observeByMsgId(msgIdHex)
        val record = if (existingRecord != null) {
            existingRecord.copy(
                envelope = envelope,
                status = EnvelopeStatus.PENDING,
                debugNote = debugNote ?: existingRecord.debugNote
            )
        } else {
            EnvelopeRecord(
                envelope = envelope,
                createdAt = Instant.now(),
                status = EnvelopeStatus.PENDING,
                debugNote = debugNote
            )
        }
        messageStore.upsert(record)

        when (decision) {
            is ForwardDecision.Allowed -> {
                meshTransport.send(envelope, peerId)
                messageStore.updateStatus(envelope.msgId, EnvelopeStatus.IN_FLIGHT)
                recordMutation(
                    msgIdHex = msgIdHex,
                    kind = MutationKind.EDGE_REWEIGHT,
                    peerId = peerId,
                    detail = "fanout=${decision.fanout}"
                )
                peerId?.let {
                    routingTelemetryRepository.recordRouteMutationImpact(
                        peerId = it,
                        contextKey = envelope.aliasCtx,
                        mutationKind = MutationKind.EDGE_REWEIGHT,
                        transport = MessageTransport.MESH.name,
                        reasonCode = DecisionReasonCode.CONDENSE_PROGRESS
                    )
                }
                recordMutation(
                    msgIdHex = msgIdHex,
                    kind = MutationKind.ALIAS_ROTATE,
                    peerId = peerId,
                    detail = "post-hop alias churn"
                )
                peerId?.let {
                    routingTelemetryRepository.recordRouteMutationImpact(
                        peerId = it,
                        contextKey = envelope.aliasCtx,
                        mutationKind = MutationKind.ALIAS_ROTATE,
                        transport = MessageTransport.MESH.name,
                        reasonCode = DecisionReasonCode.CONDENSE_PROGRESS
                    )
                }
            }
            ForwardDecision.StoreCarry -> {
                recordMutation(
                    msgIdHex = msgIdHex,
                    kind = MutationKind.HINT_TIER_SHIFT,
                    peerId = peerId,
                    detail = "queued for store-carry"
                )
                peerId?.let {
                    routingTelemetryRepository.recordRouteMutationImpact(
                        peerId = it,
                        contextKey = envelope.aliasCtx,
                        mutationKind = MutationKind.HINT_TIER_SHIFT,
                        transport = MessageTransport.MESH.name,
                        reasonCode = DecisionReasonCode.RELATIONAL_UNRESOLVED
                    )
                }
            }
            ForwardDecision.DeclinedBattery -> {
                // fallback to store-carry or web if available
                if (web != null) {
                    uploadToCustody(envelope = envelope, contactId = null, detail = "battery fallback from send()")
                }
            }
        }
        return SendResult(envelope, decision, reasonCode)
    }

    private suspend fun storeIncomingMessage(
        contactId: String,
        msgIdHex: String,
        senderFingerprint: String,
        plaintext: ByteArray,
        transport: MessageTransport
    ) {
        val wrapped = plaintext.toDirectMessagePayloadOrNull()
        if (wrapped == null) {
            messageRepository.recordIncoming(
                contactId = contactId,
                msgIdHex = msgIdHex,
                senderFingerprint = senderFingerprint,
                payload = plaintext.decodeToString(),
                transport = transport
            )
            return
        }

        val contentType = wrapped.contentType()
        val attachment = wrapped.attachment
        val stored = if (attachment != null && wrapped.dataBase64 != null && contentType != MessageContentType.TEXT) {
            attachmentRepository.persistIncomingAttachment(
                msgId = msgIdHex,
                contentType = contentType,
                mimeType = attachment.mimeType,
                fileName = attachment.fileName,
                bytes = wrapped.dataBase64.fromBase64String()
            )
        } else {
            null
        }

        messageRepository.recordIncoming(
            contactId = contactId,
            msgIdHex = msgIdHex,
            senderFingerprint = senderFingerprint,
            payload = wrapped.text.orEmpty(),
            transport = transport,
            contentType = contentType,
            localUri = stored?.localUri,
            mimeType = attachment?.mimeType ?: stored?.mimeType,
            fileName = attachment?.fileName ?: stored?.fileName,
            byteSize = attachment?.byteSize ?: stored?.byteSize,
            durationMs = attachment?.durationMs ?: stored?.durationMs,
            thumbnailUri = stored?.thumbnailUri
        )
    }

    private fun encodeDirectPayload(
        contentType: MessageContentType,
        text: String,
        attachmentBytes: ByteArray? = null,
        mimeType: String? = null,
        fileName: String? = null,
        byteSize: Long? = null,
        durationMs: Long? = null
    ): ByteArray {
        val type = when (contentType) {
            MessageContentType.TEXT -> "text"
            MessageContentType.IMAGE -> "image"
            MessageContentType.VOICE_NOTE -> "voice_note"
        }
        return DirectMessagePayload(
            type = type,
            text = text.ifBlank { null },
            attachment = if (attachmentBytes != null && mimeType != null && byteSize != null) {
                DirectAttachmentPayload(
                    mimeType = mimeType,
                    fileName = fileName,
                    byteSize = byteSize,
                    durationMs = durationMs
                )
            } else {
                null
            },
            dataBase64 = attachmentBytes?.toBase64String()
        ).toBytes()
    }

    private fun handleControlFrame(frame: PkControlFrame, peer: PkPeer?) {
        when (frame) {
            is AckFrame -> {
                handleAckFrame(frame.msgId, frame.highestContiguousSeq, peer)
            }
            is CompatAckFrame -> {
                handleAckFrame(frame.msgId, frame.highestContiguousSeq, peer)
            }
            is PingFrame -> {
                val targetPeerId = peer?.id ?: return
                scope.launch {
                    val pong = PongFrame(frame.msgId, System.currentTimeMillis())
                    try {
                        meshTransport.sendControl(pong, targetPeerId)
                    } catch (t: Exception) {
                        Log.w(TAG, "Failed to send pong to $targetPeerId", t)
                    }
                }
            }
            is PongFrame -> {
                val msgIdHex = frame.msgId.toHexString()
                scope.launch {
                    val pending = pendingPings.remove(msgIdHex) ?: return@launch
                    pending.job?.cancel()
                    val latency = System.currentTimeMillis() - pending.startedAtMs
                    pingEventsMutable.emit(PingEvent.Success(pending.peerId, latency))
                }
            }
            else -> {
                // Other control frames (Claim/Nack) not yet handled.
            }
        }
    }

    suspend fun sendMessage(contactId: String, messageText: String): SendResult? {
        return sendDirectPayload(
            contactId = contactId,
            payloadText = messageText,
            contentType = MessageContentType.TEXT
        )
    }

    suspend fun sendImageMessage(
        contactId: String,
        payloadText: String,
        localUri: String,
        mimeType: String,
        fileName: String,
        byteSize: Long,
        bytes: ByteArray,
        thumbnailUri: String?
    ): SendResult? {
        if (byteSize > MAX_IMAGE_BYTES) {
            recordMediaReject(contactId, DecisionReasonCode.PAYLOAD_TOO_LARGE, "image exceeds ${MAX_IMAGE_BYTES / 1024 / 1024}MB")
            return null
        }
        return sendDirectPayload(
            contactId = contactId,
            payloadText = payloadText,
            contentType = MessageContentType.IMAGE,
            attachmentBytes = bytes,
            localUri = localUri,
            mimeType = mimeType,
            fileName = fileName,
            byteSize = byteSize,
            thumbnailUri = thumbnailUri
        )
    }

    suspend fun sendVoiceNote(
        contactId: String,
        localUri: String,
        fileName: String,
        byteSize: Long,
        durationMs: Long?,
        bytes: ByteArray
    ): SendResult? {
        if (durationMs != null && durationMs > MAX_VOICE_DURATION_MS) {
            recordMediaReject(contactId, DecisionReasonCode.PAYLOAD_TOO_LARGE, "voice note exceeds 120s")
            return null
        }
        if (byteSize > MAX_VOICE_BYTES) {
            recordMediaReject(contactId, DecisionReasonCode.PAYLOAD_TOO_LARGE, "voice note exceeds media cap")
            return null
        }
        return sendDirectPayload(
            contactId = contactId,
            payloadText = "",
            contentType = MessageContentType.VOICE_NOTE,
            attachmentBytes = bytes,
            localUri = localUri,
            mimeType = "audio/mp4",
            fileName = fileName,
            byteSize = byteSize,
            durationMs = durationMs
        )
    }

    private suspend fun sendDirectPayload(
        contactId: String,
        payloadText: String,
        contentType: MessageContentType,
        attachmentBytes: ByteArray? = null,
        localUri: String? = null,
        mimeType: String? = null,
        fileName: String? = null,
        byteSize: Long? = null,
        durationMs: Long? = null,
        thumbnailUri: String? = null
    ): SendResult? {
        val contact = contactRepository.getContact(contactId) ?: return null
        val contactPublicKey = contact.publicKey ?: return null
        val identity = identityRepository.getIdentity()
        val payloadBytes = encodeDirectPayload(
            contentType = contentType,
            text = payloadText,
            attachmentBytes = attachmentBytes,
            mimeType = mimeType,
            fileName = fileName,
            byteSize = byteSize,
            durationMs = durationMs
        )
        val ciphertext = com.pinekone.app.crypto.PkCrypto.encrypt(
            plaintext = payloadBytes,
            recipientPublicKeyHex = contactPublicKey,
            senderSecretKey = identity.secretKey
        )
        val targetHash = try {
            contact.fingerprint.take(16).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        } catch (_: Exception) {
            null
        }
        val hints = PkHints(
            communityId = 0x3047,
            targetHash = targetHash,
            priority = 1
        )
        val policy = PkPolicy(
            maxFanout = 2,
            kPipe = 20,
            retryLimit = 3,
            minBattPct = 15,
            jThreshold = 5
        )
        val ops = PkOps(storeCarry = true, requireAck = true, e2eAckPath = true)
        val contextKey = buildContextKey(hints)

        val scoredPeer = pathScorer.selectBestPeer(contact, meshTransport.peers.value, contextKey)
        val peer = scoredPeer?.peer

        val result = send(
            payload = ciphertext,
            policy = policy,
            hints = hints,
            ops = ops,
            web = null,
            ttl = 10,
            debugNote = "chat:${contact.displayName}${scoredPeer?.let { " • ${it.explanation}" } ?: ""}",
            peerId = peer?.id
        )

        val msgIdHex = result.envelope.msgId.toHexString()
        if (peer != null) {
            routingTelemetryRepository.recordRouteAttempt(
                peerId = peer.id,
                contextKey = contextKey,
                transport = peer.transport.name
            )
        }
        val sentOverMesh = peer != null && result.decision is ForwardDecision.Allowed
        val canUseWebCustody = webTransport.isConfigured
        val transport = if (sentOverMesh) MessageTransport.MESH else if (canUseWebCustody) MessageTransport.WEB else MessageTransport.MESH
        val status = when {
            sentOverMesh -> MessageStatus.SENT
            canUseWebCustody -> MessageStatus.PENDING
            else -> MessageStatus.FAILED
        }
        val decision = when {
            sentOverMesh -> RoutingDecision.FORWARD_NOW
            canUseWebCustody -> RoutingDecision.STORE_CARRY
            else -> RoutingDecision.DELIVERY_FAILED
        }
        val decisionReason = if (!sentOverMesh && !canUseWebCustody) {
            DecisionReasonCode.NO_VIABLE_PATH
        } else {
            result.reasonCode
        }
        val governanceDetail = when {
            scoredPeer == null && meshTransport.peers.value.isNotEmpty() -> "no governance-eligible route"
            scoredPeer != null && !scoredPeer.eligible -> "peer failed governance policy"
            else -> null
        }

        messageRepository.recordOutgoing(
            contactId = contact.nodeId,
            msgIdHex = msgIdHex,
            senderFingerprint = identity.fingerprint.toHexString(),
            payload = payloadText,
            contentType = contentType,
            transport = transport,
            status = status,
            localUri = localUri,
            mimeType = mimeType,
            fileName = fileName,
            byteSize = byteSize,
            durationMs = durationMs,
            thumbnailUri = thumbnailUri
        )
        recordDecision(
            msgIdHex = msgIdHex,
            contactId = contact.nodeId,
            decision = decision,
            reason = decisionReason,
            transport = transport.name,
            peerId = peer?.id,
            detail = if (!sentOverMesh && !canUseWebCustody) {
                listOfNotNull("no direct mesh peer • web delivery is not configured", governanceDetail).joinToString(" • ")
            } else {
                scoredPeer?.explanation ?: "initial send • ${contentType.name.lowercase()}"
            }
        )

        if (transport == MessageTransport.MESH && result.decision is ForwardDecision.Allowed && peer != null) {
            scheduleAckWatch(contact.nodeId, msgIdHex, result.envelope, peer.id)
        }
        if (sentOverMesh && shouldUseShadowCustody(scoredPeer, result, canUseWebCustody)) {
            uploadToCustody(
                envelope = result.envelope,
                contactId = contact.nodeId,
                detail = "low-confidence mesh route • ${scoredPeer?.explanation ?: "no route explanation"}"
            )
        }
        if (transport == MessageTransport.WEB) {
            uploadToCustody(
                envelope = result.envelope,
                contactId = contact.nodeId,
                detail = "no direct mesh peer"
            )
        } else if (!sentOverMesh && !canUseWebCustody) {
            sendEventsMutable.emit(SendLifecycleEvent.Failed(contact.nodeId, msgIdHex))
        }

        return result
    }

    suspend fun resendMessage(contactId: String, msgIdHex: String): SendResult? {
        val message = messageRepository.getMessage(contactId, msgIdHex) ?: return null
        if (message.direction != MessageDirection.OUTGOING) return null
        val contact = contactRepository.getContact(contactId) ?: return null
        val contactPublicKey = contact.publicKey ?: return null
        val identity = identityRepository.getIdentity()
        val attachmentBytes = when (message.contentType) {
            MessageContentType.TEXT -> null
            else -> {
                val localUri = message.localUri ?: return null
                val file = java.io.File(android.net.Uri.parse(localUri).path ?: return null)
                if (!file.exists()) return null
                file.readBytes()
            }
        }
        val payloadBytes = encodeDirectPayload(
            contentType = message.contentType,
            text = message.payload,
            attachmentBytes = attachmentBytes,
            mimeType = message.mimeType,
            fileName = message.fileName,
            byteSize = message.byteSize,
            durationMs = message.durationMs
        )
        val ciphertext = com.pinekone.app.crypto.PkCrypto.encrypt(
            plaintext = payloadBytes,
            recipientPublicKeyHex = contactPublicKey,
            senderSecretKey = identity.secretKey
        )

        val targetHash = try {
            contact.fingerprint.take(16).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        } catch (_: Exception) {
            null
        }
        val hints = PkHints(
            communityId = 0x3047,
            targetHash = targetHash,
            priority = 1
        )
        val policy = PkPolicy(
            maxFanout = 2,
            kPipe = 20,
            retryLimit = 3,
            minBattPct = 15,
            jThreshold = 5
        )
        val ops = PkOps(storeCarry = true, requireAck = true, e2eAckPath = true)
        val contextKey = buildContextKey(hints)
        val scoredPeer = pathScorer.selectBestPeer(contact, meshTransport.peers.value, contextKey)
        val peer = scoredPeer?.peer

        messageRepository.markStatus(contactId, msgIdHex, MessageStatus.PENDING)
        val existingRecord = messageStore.observeByMsgId(msgIdHex)
        val debugNote = existingRecord?.debugNote ?: "chat:${contact.displayName}"
        val msgIdBytes = msgIdHex.hexToByteArray()

        val result = send(
            payload = ciphertext,
            policy = policy,
            hints = hints,
            ops = ops,
            web = null,
            ttl = 10,
            debugNote = debugNote,
            peerId = peer?.id,
            msgIdOverride = msgIdBytes
        )

        if (peer != null) {
            routingTelemetryRepository.recordRouteAttempt(
                peerId = peer.id,
                contextKey = contextKey,
                transport = peer.transport.name
            )
        }
        val sentOverMesh = peer != null && result.decision is ForwardDecision.Allowed
        val canUseWebCustody = webTransport.isConfigured
        val status = when {
            sentOverMesh -> MessageStatus.SENT
            canUseWebCustody -> MessageStatus.PENDING
            else -> MessageStatus.FAILED
        }
        messageRepository.markStatus(contactId, msgIdHex, status)
        val decision = when {
            sentOverMesh -> RoutingDecision.FORWARD_NOW
            canUseWebCustody -> RoutingDecision.STORE_CARRY
            else -> RoutingDecision.DELIVERY_FAILED
        }
        recordDecision(
            msgIdHex = msgIdHex,
            contactId = contactId,
            decision = decision,
            reason = if (!sentOverMesh && !canUseWebCustody) DecisionReasonCode.NO_VIABLE_PATH else result.reasonCode,
            transport = if (sentOverMesh) {
                MessageTransport.MESH.name
            } else if (canUseWebCustody) {
                MessageTransport.WEB.name
            } else {
                MessageTransport.MESH.name
            },
            peerId = peer?.id,
            detail = if (!sentOverMesh && !canUseWebCustody) {
                "resend blocked • no direct mesh peer • web delivery is not configured"
            } else {
                scoredPeer?.explanation ?: "resend"
            }
        )

        if (status == MessageStatus.SENT && peer != null && result.decision is ForwardDecision.Allowed) {
            scheduleAckWatch(contactId, msgIdHex, result.envelope, peer.id)
            if (shouldUseShadowCustody(scoredPeer, result, canUseWebCustody)) {
                uploadToCustody(
                    envelope = result.envelope,
                    contactId = contactId,
                    detail = "resend low-confidence mesh route • ${scoredPeer.explanation}"
                )
            }
        } else if (status == MessageStatus.PENDING && peer == null) {
            uploadToCustody(
                envelope = result.envelope,
                contactId = contactId,
                detail = "resend no direct mesh peer"
            )
        } else if (status == MessageStatus.PENDING && result.decision == ForwardDecision.DeclinedBattery) {
            uploadToCustody(
                envelope = result.envelope,
                contactId = contactId,
                detail = "resend battery fallback"
            )
        } else if (status == MessageStatus.FAILED) {
            sendEventsMutable.emit(SendLifecycleEvent.Failed(contactId, msgIdHex))
        }

        return result
    }

    private suspend fun recordMediaReject(contactId: String, reason: DecisionReasonCode, detail: String) {
        val msgIdHex = ByteArray(16).apply(random::nextBytes).toHexString()
        recordDecision(
            msgIdHex = msgIdHex,
            contactId = contactId,
            decision = RoutingDecision.STORE_CARRY,
            reason = reason,
            transport = null,
            peerId = null,
            detail = detail
        )
    }

    suspend fun postPublicMessage(content: String) {
        val identity = identityRepository.getIdentity()
        val timestamp = System.currentTimeMillis()
        val msgIdBytes = ByteArray(16).apply(random::nextBytes)
        val msgIdHex = msgIdBytes.toHexString()

        publicChatRepository.append(
            msgId = msgIdHex,
            authorId = identity.nodeId,
            authorName = identity.displayName,
            payload = content,
            timestampMillis = timestamp
        )

        val broadcast = PublicBroadcast(
            authorId = identity.nodeId,
            authorName = identity.displayName,
            body = content,
            sentAt = timestamp
        )
        val payloadBytes = PkFormats.json.encodeToString(broadcast).encodeToByteArray()
        val hints = PkHints(
            communityId = 0x3047,
            targetHash = null,
            mu = PUBLIC_HINT_MAGIC,
            priority = 0
        )
        val policy = PkPolicy(
            maxFanout = 3,
            kPipe = 20,
            retryLimit = 2,
            minBattPct = 15,
            jThreshold = 5
        )
        val ops = PkOps(storeCarry = true, requireAck = false, e2eAckPath = false)

        val result = send(
            payload = payloadBytes,
            policy = policy,
            hints = hints,
            ops = ops,
            web = null,
            ttl = 6,
            debugNote = "public",
            peerId = null,
            msgIdOverride = msgIdBytes
        )
        recordDecision(
            msgIdHex = msgIdHex,
            contactId = null,
            decision = when (result.decision) {
                is ForwardDecision.Allowed -> RoutingDecision.FORWARD_NOW
                ForwardDecision.StoreCarry -> RoutingDecision.STORE_CARRY
                ForwardDecision.DeclinedBattery -> RoutingDecision.STORE_CARRY
            },
            reason = result.reasonCode,
            transport = if (result.decision is ForwardDecision.Allowed) MessageTransport.MESH.name else MessageTransport.WEB.name,
            peerId = null,
            detail = "public broadcast"
        )
    }

    fun pingPeer(peerId: String) {
        scope.launch {
            val peersNow = peers.value
            val target = peersNow.firstOrNull { it.id == peerId } ?: run {
                pingEventsMutable.emit(PingEvent.Timeout(peerId))
                return@launch
            }
            val msgId = ByteArray(16).apply(random::nextBytes)
            val msgIdHex = msgId.toHexString()
            val startedAt = System.currentTimeMillis()
            val frame = PingFrame(msgId = msgId, sentAtMs = startedAt)
            pendingPings.remove(msgIdHex)?.job?.cancel()
            pingEventsMutable.emit(PingEvent.Started(peerId))
            try {
                meshTransport.sendControl(frame, target.id)
            } catch (t: Exception) {
                Log.w(TAG, "Ping send failed for $peerId", t)
                pingEventsMutable.emit(PingEvent.Timeout(peerId))
                return@launch
            }
            val pending = PendingPing(peerId = peerId, startedAtMs = startedAt)
            val job = launch {
                delay(PING_TIMEOUT_MS)
                if (pendingPings.remove(msgIdHex) != null) {
                    pingEventsMutable.emit(PingEvent.Timeout(peerId))
                }
            }
            pending.job = job
            pendingPings[msgIdHex] = pending
        }
    }

    fun messagesFor(contactId: String) = messageRepository.messagesFor(contactId)

    private fun scheduleAckWatch(contactId: String, msgIdHex: String, envelope: PkEnvelope, peerId: String) {
        val previous = pendingAcks.remove(msgIdHex)
        previous?.job?.cancel()

        messageStore.updateStatus(envelope.msgId, EnvelopeStatus.IN_FLIGHT)

        val pending = PendingAck(contactId, envelope, peerId)
        val job = scope.launch {
            var delayMs = ACK_TIMEOUT_MS
            while (isActive) {
                delay(delayMs)
                if (!pendingAcks.containsKey(msgIdHex)) {
                    break
                }
                if (pending.attempts >= MAX_RETRY_ATTEMPTS) {
                    handleDeliveryTimeout(msgIdHex)
                    break
                }
                pending.attempts += 1
                sendEventsMutable.emit(
                    SendLifecycleEvent.RetryScheduled(
                        contactId = contactId,
                        msgIdHex = msgIdHex,
                        attempt = pending.attempts,
                        maxAttempts = MAX_RETRY_ATTEMPTS
                    )
                )
                try {
                    meshTransport.send(envelope, peerId)
                } catch (t: Exception) {
                    Log.w(TAG, "Retry send failed for msg $msgIdHex", t)
                }
                delayMs = min((delayMs * BACKOFF_MULTIPLIER).toLong(), MAX_BACKOFF_MS)
            }
        }
        pending.job = job
        pendingAcks[msgIdHex] = pending
    }

    private suspend fun handleDeliveryTimeout(msgIdHex: String) {
        val pending = pendingAcks.remove(msgIdHex) ?: return
        messageStore.updateStatus(pending.envelope.msgId, EnvelopeStatus.FAILED)
        messageRepository.markStatus(pending.contactId, msgIdHex, MessageStatus.FAILED)
        routingTelemetryRepository.recordRouteOutcome(
            peerId = pending.peerId,
            contextKey = pending.envelope.aliasCtx,
            success = false,
            latencyMs = System.currentTimeMillis() - pending.startedAtMs,
            transport = MessageTransport.MESH.name,
            reasonCode = DecisionReasonCode.RETRY_LIMIT_EXCEEDED
        )
        recordDecision(
            msgIdHex = msgIdHex,
            contactId = pending.contactId,
            decision = RoutingDecision.DELIVERY_FAILED,
            reason = DecisionReasonCode.RETRY_LIMIT_EXCEEDED,
            transport = MessageTransport.MESH.name,
            peerId = pending.peerId,
            detail = "ack timeout after $MAX_RETRY_ATTEMPTS retries"
        )
        sendEventsMutable.emit(SendLifecycleEvent.Failed(pending.contactId, msgIdHex))
    }

    private fun clearPendingAck(msgIdHex: String) {
        pendingAcks.remove(msgIdHex)?.job?.cancel()
    }

    private fun handleAckFrame(msgId: ByteArray, highestContiguousSeq: Int, peer: PkPeer?) {
        val msgIdHex = msgId.toHexString()
        scope.launch {
            val pending = pendingAcks[msgIdHex]
            val contactId = pending?.contactId ?: peer?.id
            contactId?.let {
                messageRepository.markStatus(it, msgIdHex, MessageStatus.DELIVERED)
                recordDecision(
                    msgIdHex = msgIdHex,
                    contactId = it,
                    decision = RoutingDecision.DELIVERY_CONFIRMED,
                    reason = DecisionReasonCode.DELIVERY_ACK_RECEIVED,
                    transport = MessageTransport.MESH.name,
                    peerId = peer?.id,
                    detail = "ack highest_seq=$highestContiguousSeq"
                )
            }
            pending?.let {
                routingTelemetryRepository.recordRouteOutcome(
                    peerId = it.peerId,
                    contextKey = it.envelope.aliasCtx,
                    success = true,
                    latencyMs = System.currentTimeMillis() - it.startedAtMs,
                    transport = MessageTransport.MESH.name,
                    reasonCode = DecisionReasonCode.DELIVERY_ACK_RECEIVED
                )
            }
            messageStore.updateStatus(msgId, EnvelopeStatus.DELIVERED)
            recordMutation(
                msgIdHex = msgIdHex,
                kind = MutationKind.DELIVERY_PATH_CONFIRMED,
                peerId = peer?.id,
                detail = "delivery ack received"
            )
            pending?.let {
                routingTelemetryRepository.recordRouteMutationImpact(
                    peerId = it.peerId,
                    contextKey = it.envelope.aliasCtx,
                    mutationKind = MutationKind.DELIVERY_PATH_CONFIRMED,
                    transport = MessageTransport.MESH.name,
                    reasonCode = DecisionReasonCode.DELIVERY_ACK_RECEIVED
                )
            }
            clearPendingAck(msgIdHex)
        }
    }

    private fun reasonFor(
        decision: ForwardDecision,
        policy: PkPolicy,
        status: DeviceStatus
    ): DecisionReasonCode {
        val batteryFloor = capGovernor.batteryFloor(policy)
        return when (decision) {
            is ForwardDecision.Allowed -> DecisionReasonCode.CONDENSE_PROGRESS
            ForwardDecision.DeclinedBattery -> DecisionReasonCode.BATTERY_BELOW_FLOOR
            ForwardDecision.StoreCarry -> when {
                status.batteryPct < batteryFloor -> DecisionReasonCode.BATTERY_BELOW_FLOOR
                policy.maxFanout <= 0 -> DecisionReasonCode.POLICY_CLAMPED_FANOUT
                else -> DecisionReasonCode.RELATIONAL_UNRESOLVED
            }
        }
    }

    private suspend fun uploadToCustody(
        envelope: PkEnvelope,
        contactId: String?,
        detail: String
    ): CustodyResult {
        val result = webTransport.upload(envelope)
        val msgIdHex = envelope.msgId.toHexString()
        when (result) {
            is CustodyResult.Accepted -> {
                recordDecision(
                    msgIdHex = msgIdHex,
                    contactId = contactId,
                    decision = RoutingDecision.ACCEPT_CUSTODY,
                    reason = DecisionReasonCode.CUSTODY_TICKET_ISSUED,
                    transport = MessageTransport.WEB.name,
                    peerId = null,
                    detail = "$detail • expiry=${result.ticket.expiryEpochSeconds}"
                )
                recordMutation(
                    msgIdHex = msgIdHex,
                    kind = MutationKind.CUSTODY_ROLE_SHIFT,
                    peerId = null,
                    detail = "custody accepted"
                )
            }
            is CustodyResult.Rejected -> {
                recordDecision(
                    msgIdHex = msgIdHex,
                    contactId = contactId,
                    decision = RoutingDecision.STORE_CARRY,
                    reason = DecisionReasonCode.CUSTODY_REJECTED,
                    transport = MessageTransport.WEB.name,
                    peerId = null,
                    detail = "$detail • ${result.code}:${result.message}"
                )
            }
        }
        return result
    }

    private suspend fun recordDecision(
        msgIdHex: String,
        contactId: String?,
        decision: RoutingDecision,
        reason: DecisionReasonCode,
        transport: String?,
        peerId: String?,
        detail: String?
    ) {
        routingTelemetryRepository.recordDecision(
            msgId = msgIdHex,
            contactId = contactId,
            decision = decision,
            reasonCode = reason,
            transport = transport,
            peerId = peerId,
            detail = detail
        )
        val canonicalPayload = listOfNotNull(msgIdHex, contactId, decision.name, reason.name, transport, peerId, detail)
            .joinToString("|")
        protocolStateRepository.saveDecisionReceipt(
            DecisionReceiptEntity(
                receiptId = "dec:$msgIdHex:${System.currentTimeMillis()}",
                msgId = msgIdHex,
                contactId = contactId,
                decision = decision.name,
                reasonCode = reason.name,
                transport = transport,
                peerId = peerId,
                aliasCtx = null,
                aliasId = null,
                lineageRoot = null,
                canonicalPayload = canonicalPayload,
                signature = canonicalPayload.encodeToByteArray().toHexString(),
                createdAtEpochMillis = System.currentTimeMillis()
            )
        )
    }

    private suspend fun recordMutation(
        msgIdHex: String,
        kind: MutationKind,
        peerId: String?,
        detail: String?
    ) {
        routingTelemetryRepository.recordMutation(
            msgId = msgIdHex,
            mutationKind = kind,
            peerId = peerId,
            detail = detail
        )
    }

    private suspend fun handlePublicBroadcast(frame: InboundFrame.EnvelopeFrame) {
        val msgIdHex = frame.envelope.msgId.toHexString()
        val payloadString = runCatching { frame.envelope.payload.decodeToString() }.getOrElse {
            Log.w(TAG, "Failed to decode public payload for $msgIdHex", it)
            return
        }
        val broadcast = runCatching {
            PkFormats.json.decodeFromString<PublicBroadcast>(payloadString)
        }.getOrElse {
            Log.w(TAG, "Failed to parse public payload for $msgIdHex", it)
            return
        }

        publicChatRepository.append(
            msgId = msgIdHex,
            authorId = broadcast.authorId,
            authorName = broadcast.authorName,
            payload = broadcast.body,
            timestampMillis = broadcast.sentAt
        )
    }

    private data class PendingAck(
        val contactId: String,
        val envelope: PkEnvelope,
        val peerId: String,
        val startedAtMs: Long = System.currentTimeMillis(),
        var attempts: Int = 1,
        var job: Job? = null
    )

    private data class PendingPing(
        val peerId: String,
        val startedAtMs: Long,
        var job: Job? = null
    )

    private fun buildContextKey(hints: PkHints): String =
        buildString {
            append("ctx:")
            append(hints.communityId)
            append(':')
            append(hints.targetHash?.toHexString() ?: "public")
        }

    private fun shouldUseShadowCustody(
        scoredPeer: PathScore?,
        result: SendResult,
        canUseWebCustody: Boolean
    ): Boolean {
        if (!canUseWebCustody) return false
        if (result.decision !is ForwardDecision.Allowed) return false
        val score = scoredPeer?.score ?: return true
        return score < 3.1 || scoredPeer.distance >= 3
    }

    @Serializable
    private data class PublicBroadcast(
        val authorId: String,
        val authorName: String,
        val body: String,
        val sentAt: Long
    )
}
