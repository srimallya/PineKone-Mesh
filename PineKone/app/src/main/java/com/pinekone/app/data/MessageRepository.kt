package com.pinekone.app.data

import com.pinekone.app.data.db.ContactDao
import com.pinekone.app.data.db.MessageDao
import com.pinekone.app.data.db.MessageEntity
import com.pinekone.app.data.model.ChatMessage
import com.pinekone.app.data.model.MessageContentType
import com.pinekone.app.data.model.MessageDirection
import com.pinekone.app.data.model.MessageStatus
import com.pinekone.app.data.model.MessageTransport
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class MessageRepository(
    private val messageDao: MessageDao,
    private val contactDao: ContactDao
) {

    fun messagesFor(contactId: String): Flow<List<ChatMessage>> =
        messageDao.observeMessages(contactId).map { entities ->
            entities.map { it.toDomain() }
        }

    suspend fun recordOutgoing(
        contactId: String,
        msgIdHex: String,
        senderFingerprint: String,
        payload: String,
        contentType: MessageContentType,
        transport: MessageTransport,
        status: MessageStatus,
        localUri: String? = null,
        mimeType: String? = null,
        fileName: String? = null,
        byteSize: Long? = null,
        durationMs: Long? = null,
        thumbnailUri: String? = null
    ) {
        val now = System.currentTimeMillis()
        val entity = MessageEntity(
            msgId = msgIdHex,
            contactId = contactId,
            senderFingerprint = senderFingerprint,
            payload = payload,
            contentType = contentType.name,
            localUri = localUri,
            mimeType = mimeType,
            fileName = fileName,
            byteSize = byteSize,
            durationMs = durationMs,
            thumbnailUri = thumbnailUri,
            timestampEpochMillis = now,
            direction = MessageDirection.OUTGOING.name,
            transport = transport.name,
            status = status.name,
            deliveredAt = null
        )
        messageDao.insert(entity)
        contactDao.updateLastMessage(contactId, snippetFor(contentType, payload, fileName), now)
        contactDao.updateLastSeen(contactId, now)
    }

    suspend fun recordIncoming(
        contactId: String,
        msgIdHex: String,
        senderFingerprint: String,
        payload: String,
        transport: MessageTransport,
        contentType: MessageContentType = MessageContentType.TEXT,
        localUri: String? = null,
        mimeType: String? = null,
        fileName: String? = null,
        byteSize: Long? = null,
        durationMs: Long? = null,
        thumbnailUri: String? = null
    ) {
        val now = System.currentTimeMillis()
        val entity = MessageEntity(
            msgId = msgIdHex,
            contactId = contactId,
            senderFingerprint = senderFingerprint,
            payload = payload,
            contentType = contentType.name,
            localUri = localUri,
            mimeType = mimeType,
            fileName = fileName,
            byteSize = byteSize,
            durationMs = durationMs,
            thumbnailUri = thumbnailUri,
            timestampEpochMillis = now,
            direction = MessageDirection.INCOMING.name,
            transport = transport.name,
            status = MessageStatus.DELIVERED.name,
            deliveredAt = now
        )
        messageDao.insert(entity)
        contactDao.updateLastMessage(contactId, snippetFor(contentType, payload, fileName), now)
        contactDao.updateLastSeen(contactId, now)
    }

    suspend fun markStatus(contactId: String, msgIdHex: String, status: MessageStatus) {
        val deliveredAt = if (status == MessageStatus.DELIVERED) System.currentTimeMillis() else null
        messageDao.updateStatus(contactId, msgIdHex, status.name, deliveredAt)
    }

    suspend fun getMessage(contactId: String, msgIdHex: String): ChatMessage? =
        messageDao.getMessage(contactId, msgIdHex)?.toDomain()

    suspend fun deleteConversation(contactId: String) {
        messageDao.deleteForContact(contactId)
        contactDao.clearConversationMetadata(contactId)
    }

    private fun MessageEntity.toDomain(): ChatMessage =
        ChatMessage(
            id = id,
            msgId = msgId,
            contactId = contactId,
            senderFingerprint = senderFingerprint,
            payload = payload,
            contentType = MessageContentType.valueOf(contentType),
            localUri = localUri,
            mimeType = mimeType,
            fileName = fileName,
            byteSize = byteSize,
            durationMs = durationMs,
            thumbnailUri = thumbnailUri,
            timestamp = Instant.ofEpochMilli(timestampEpochMillis),
            direction = MessageDirection.valueOf(direction),
            status = MessageStatus.valueOf(status),
            transport = MessageTransport.valueOf(transport),
            deliveredAt = deliveredAt?.let { Instant.ofEpochMilli(it) }
        )

    private fun snippetFor(contentType: MessageContentType, payload: String, fileName: String?): String =
        when (contentType) {
            MessageContentType.TEXT -> payload.take(120)
            MessageContentType.IMAGE -> "Image${fileName?.let { ": $it" } ?: ""}"
            MessageContentType.VOICE_NOTE -> "Voice note"
        }
}
