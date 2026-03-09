package com.pinekone.app.data.model

import java.time.Instant

data class Contact(
    val nodeId: String,
    val displayName: String,
    val fingerprint: String,
    val publicKey: String?,
    val lastSeen: Instant?,
    val lastMessageSnippet: String?,
    val lastMessageTimestamp: Instant?,
    val isSelf: Boolean
)

enum class MessageDirection {
    INCOMING,
    OUTGOING
}

enum class MessageStatus {
    PENDING,
    SENT,
    DELIVERED,
    FAILED
}

enum class MessageTransport {
    MESH,
    WEB
}

data class ChatMessage(
    val id: Long,
    val msgId: String,
    val contactId: String,
    val senderFingerprint: String,
    val payload: String,
    val timestamp: Instant,
    val direction: MessageDirection,
    val status: MessageStatus,
    val transport: MessageTransport,
    val deliveredAt: Instant?
)

data class PublicChatMessage(
    val id: Long,
    val authorId: String,
    val authorName: String,
    val payload: String,
    val timestamp: Instant
)
