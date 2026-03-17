package com.pinekone.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "contacts",
    indices = [
        Index(value = ["fingerprint"], unique = true)
    ]
)
data class ContactEntity(
    @PrimaryKey
    @ColumnInfo(name = "node_id")
    val nodeId: String,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    @ColumnInfo(name = "fingerprint")
    val fingerprint: String,
    @ColumnInfo(name = "public_key")
    val publicKey: String? = null,
    @ColumnInfo(name = "last_seen")
    val lastSeenEpochMillis: Long? = null,
    @ColumnInfo(name = "is_self")
    val isSelf: Boolean = false,
    @ColumnInfo(name = "last_message_snippet")
    val lastMessageSnippet: String? = null,
    @ColumnInfo(name = "last_message_timestamp")
    val lastMessageTimestamp: Long? = null
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ContactEntity::class,
            parentColumns = ["node_id"],
            childColumns = ["contact_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["contact_id"]),
        Index(value = ["msg_id"], unique = false)
    ]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "msg_id")
    val msgId: String,
    @ColumnInfo(name = "contact_id")
    val contactId: String,
    @ColumnInfo(name = "sender_fingerprint")
    val senderFingerprint: String,
    @ColumnInfo(name = "payload")
    val payload: String,
    @ColumnInfo(name = "content_type")
    val contentType: String = "TEXT",
    @ColumnInfo(name = "local_uri")
    val localUri: String? = null,
    @ColumnInfo(name = "mime_type")
    val mimeType: String? = null,
    @ColumnInfo(name = "file_name")
    val fileName: String? = null,
    @ColumnInfo(name = "byte_size")
    val byteSize: Long? = null,
    @ColumnInfo(name = "duration_ms")
    val durationMs: Long? = null,
    @ColumnInfo(name = "thumbnail_uri")
    val thumbnailUri: String? = null,
    @ColumnInfo(name = "timestamp")
    val timestampEpochMillis: Long,
    @ColumnInfo(name = "direction")
    val direction: String,
    @ColumnInfo(name = "transport")
    val transport: String,
    @ColumnInfo(name = "status")
    val status: String,
    @ColumnInfo(name = "delivered_at")
    val deliveredAt: Long? = null
)

@Entity(
    tableName = "public_messages",
    indices = [
        Index(value = ["msg_id"], unique = true),
        Index(value = ["timestamp"])
    ]
)
data class PublicMessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "msg_id")
    val msgId: String,
    @ColumnInfo(name = "author_id")
    val authorId: String,
    @ColumnInfo(name = "author_name")
    val authorName: String,
    @ColumnInfo(name = "payload")
    val payload: String,
    @ColumnInfo(name = "timestamp")
    val timestampEpochMillis: Long
)

@Entity(
    tableName = "decision_events",
    indices = [
        Index(value = ["msg_id"]),
        Index(value = ["contact_id"]),
        Index(value = ["created_at"])
    ]
)
data class DecisionEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "msg_id")
    val msgId: String,
    @ColumnInfo(name = "contact_id")
    val contactId: String? = null,
    @ColumnInfo(name = "decision")
    val decision: String,
    @ColumnInfo(name = "reason_code")
    val reasonCode: String,
    @ColumnInfo(name = "transport")
    val transport: String? = null,
    @ColumnInfo(name = "peer_id")
    val peerId: String? = null,
    @ColumnInfo(name = "detail")
    val detail: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAtEpochMillis: Long
)

@Entity(
    tableName = "mutation_events",
    indices = [
        Index(value = ["msg_id"]),
        Index(value = ["created_at"])
    ]
)
data class MutationEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "msg_id")
    val msgId: String,
    @ColumnInfo(name = "mutation_kind")
    val mutationKind: String,
    @ColumnInfo(name = "peer_id")
    val peerId: String? = null,
    @ColumnInfo(name = "detail")
    val detail: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAtEpochMillis: Long
)

@Entity(
    tableName = "alias_bindings",
    indices = [
        Index(value = ["node_id"]),
        Index(value = ["contact_id"]),
        Index(value = ["alias_id"], unique = true)
    ]
)
data class AliasBindingEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "node_id")
    val nodeId: String,
    @ColumnInfo(name = "contact_id")
    val contactId: String,
    @ColumnInfo(name = "alias_id")
    val aliasId: String,
    @ColumnInfo(name = "scope")
    val scope: String,
    @ColumnInfo(name = "epoch")
    val epoch: Long,
    @ColumnInfo(name = "relation_distance")
    val relationDistance: Int,
    @ColumnInfo(name = "created_at")
    val createdAtEpochMillis: Long
)

@Entity(
    tableName = "invite_attestations",
    indices = [
        Index(value = ["attestation_ref"], unique = true),
        Index(value = ["inviter_node_id"]),
        Index(value = ["member_node_id"])
    ]
)
data class InviteAttestationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "attestation_ref")
    val attestationRef: String,
    @ColumnInfo(name = "inviter_node_id")
    val inviterNodeId: String,
    @ColumnInfo(name = "member_node_id")
    val memberNodeId: String,
    @ColumnInfo(name = "inviter_display_name")
    val inviterDisplayName: String?,
    @ColumnInfo(name = "member_display_name")
    val memberDisplayName: String?,
    @ColumnInfo(name = "scope")
    val scope: String,
    @ColumnInfo(name = "created_at")
    val createdAtEpochMillis: Long
)

@Entity(
    tableName = "role_attestations",
    indices = [
        Index(value = ["node_id"]),
        Index(value = ["attestation_ref"], unique = true)
    ]
)
data class RoleAttestationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "node_id")
    val nodeId: String,
    @ColumnInfo(name = "role")
    val role: String,
    @ColumnInfo(name = "granted_by")
    val grantedBy: String,
    @ColumnInfo(name = "attestation_ref")
    val attestationRef: String,
    @ColumnInfo(name = "expires_at")
    val expiresAtEpochMillis: Long?,
    @ColumnInfo(name = "created_at")
    val createdAtEpochMillis: Long
)

@Entity(
    tableName = "revocations",
    indices = [
        Index(value = ["node_id"]),
        Index(value = ["created_at"])
    ]
)
data class RevocationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "node_id")
    val nodeId: String,
    @ColumnInfo(name = "revoked_by")
    val revokedBy: String,
    @ColumnInfo(name = "reason")
    val reason: String,
    @ColumnInfo(name = "created_at")
    val createdAtEpochMillis: Long
)

@Entity(
    tableName = "decision_receipts",
    indices = [
        Index(value = ["msg_id"]),
        Index(value = ["receipt_id"], unique = true),
        Index(value = ["created_at"]),
        Index(value = ["lineage_root"])
    ]
)
data class DecisionReceiptEntity(
    @PrimaryKey
    @ColumnInfo(name = "receipt_id")
    val receiptId: String,
    @ColumnInfo(name = "msg_id")
    val msgId: String,
    @ColumnInfo(name = "contact_id")
    val contactId: String?,
    @ColumnInfo(name = "decision")
    val decision: String,
    @ColumnInfo(name = "reason_code")
    val reasonCode: String,
    @ColumnInfo(name = "transport")
    val transport: String?,
    @ColumnInfo(name = "peer_id")
    val peerId: String?,
    @ColumnInfo(name = "alias_ctx")
    val aliasCtx: String?,
    @ColumnInfo(name = "alias_id")
    val aliasId: String?,
    @ColumnInfo(name = "lineage_root")
    val lineageRoot: String?,
    @ColumnInfo(name = "canonical_payload")
    val canonicalPayload: String,
    @ColumnInfo(name = "signature")
    val signature: String,
    @ColumnInfo(name = "created_at")
    val createdAtEpochMillis: Long
)

@Entity(
    tableName = "custody_receipts",
    indices = [
        Index(value = ["msg_id"]),
        Index(value = ["receipt_id"], unique = true),
        Index(value = ["custody_node_id"]),
        Index(value = ["expiry_at"])
    ]
)
data class CustodyReceiptEntity(
    @PrimaryKey
    @ColumnInfo(name = "receipt_id")
    val receiptId: String,
    @ColumnInfo(name = "msg_id")
    val msgId: String,
    @ColumnInfo(name = "custody_node_id")
    val custodyNodeId: String,
    @ColumnInfo(name = "accepted_at")
    val acceptedAtEpochMillis: Long,
    @ColumnInfo(name = "expiry_at")
    val expiryAtEpochMillis: Long,
    @ColumnInfo(name = "fetch_token")
    val fetchToken: String?,
    @ColumnInfo(name = "proof")
    val proof: String?,
    @ColumnInfo(name = "alias_ctx")
    val aliasCtx: String?,
    @ColumnInfo(name = "alias_id")
    val aliasId: String?,
    @ColumnInfo(name = "lineage_root")
    val lineageRoot: String?,
    @ColumnInfo(name = "signature")
    val signature: String,
    @ColumnInfo(name = "status")
    val status: String,
    @ColumnInfo(name = "created_at")
    val createdAtEpochMillis: Long
)

@Entity(
    tableName = "custody_records",
    indices = [
        Index(value = ["msg_id"]),
        Index(value = ["holder_node_id"]),
        Index(value = ["state"]),
        Index(value = ["expiry_at"])
    ]
)
data class CustodyRecordEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "msg_id")
    val msgId: String,
    @ColumnInfo(name = "holder_node_id")
    val holderNodeId: String,
    @ColumnInfo(name = "receipt_id")
    val receiptId: String?,
    @ColumnInfo(name = "state")
    val state: String,
    @ColumnInfo(name = "accepted_at")
    val acceptedAtEpochMillis: Long?,
    @ColumnInfo(name = "transferred_from")
    val transferredFromNodeId: String?,
    @ColumnInfo(name = "released_at")
    val releasedAtEpochMillis: Long?,
    @ColumnInfo(name = "expiry_at")
    val expiryAtEpochMillis: Long?,
    @ColumnInfo(name = "reason")
    val reason: String?,
    @ColumnInfo(name = "alias_ctx")
    val aliasCtx: String?,
    @ColumnInfo(name = "alias_id")
    val aliasId: String?,
    @ColumnInfo(name = "lineage_root")
    val lineageRoot: String?,
    @ColumnInfo(name = "updated_at")
    val updatedAtEpochMillis: Long
)

@Entity(
    tableName = "alias_epochs",
    indices = [
        Index(value = ["node_id"]),
        Index(value = ["alias_ctx"]),
        Index(value = ["alias_id"], unique = true),
        Index(value = ["lineage_root"]),
        Index(value = ["status"])
    ]
)
data class AliasEpochEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "node_id")
    val nodeId: String,
    @ColumnInfo(name = "alias_ctx")
    val aliasCtx: String,
    @ColumnInfo(name = "alias_id")
    val aliasId: String,
    @ColumnInfo(name = "lineage_root")
    val lineageRoot: String,
    @ColumnInfo(name = "scope")
    val scope: String,
    @ColumnInfo(name = "epoch")
    val epoch: Long,
    @ColumnInfo(name = "active_from")
    val activeFromEpochMillis: Long,
    @ColumnInfo(name = "grace_until")
    val graceUntilEpochMillis: Long?,
    @ColumnInfo(name = "retired_at")
    val retiredAtEpochMillis: Long?,
    @ColumnInfo(name = "status")
    val status: String,
    @ColumnInfo(name = "rotation_reason")
    val rotationReason: String?,
    @ColumnInfo(name = "signature")
    val signature: String?,
    @ColumnInfo(name = "created_at")
    val createdAtEpochMillis: Long
)

@Entity(
    tableName = "relay_events",
    indices = [
        Index(value = ["msg_id"]),
        Index(value = ["event_id"], unique = true),
        Index(value = ["from_peer_id"]),
        Index(value = ["to_peer_id"]),
        Index(value = ["created_at"])
    ]
)
data class RelayEventEntity(
    @PrimaryKey
    @ColumnInfo(name = "event_id")
    val eventId: String,
    @ColumnInfo(name = "msg_id")
    val msgId: String,
    @ColumnInfo(name = "hop_index")
    val hopIndex: Int,
    @ColumnInfo(name = "from_peer_id")
    val fromPeerId: String?,
    @ColumnInfo(name = "to_peer_id")
    val toPeerId: String?,
    @ColumnInfo(name = "transport")
    val transport: String,
    @ColumnInfo(name = "decision")
    val decision: String,
    @ColumnInfo(name = "reason_code")
    val reasonCode: String,
    @ColumnInfo(name = "mutation_kind")
    val mutationKind: String?,
    @ColumnInfo(name = "mutation_nonce")
    val mutationNonce: String?,
    @ColumnInfo(name = "alias_ctx")
    val aliasCtx: String?,
    @ColumnInfo(name = "alias_id")
    val aliasId: String?,
    @ColumnInfo(name = "detail")
    val detail: String?,
    @ColumnInfo(name = "created_at")
    val createdAtEpochMillis: Long
)

@Entity(
    tableName = "replay_windows",
    indices = [
        Index(value = ["subject_key"], unique = true),
        Index(value = ["scope"]),
        Index(value = ["updated_at"])
    ]
)
data class ReplayWindowEntity(
    @PrimaryKey
    @ColumnInfo(name = "subject_key")
    val subjectKey: String,
    @ColumnInfo(name = "scope")
    val scope: String,
    @ColumnInfo(name = "window_start")
    val windowStartEpochMillis: Long,
    @ColumnInfo(name = "window_end")
    val windowEndEpochMillis: Long,
    @ColumnInfo(name = "last_nonce")
    val lastNonce: String?,
    @ColumnInfo(name = "nonce_floor")
    val nonceFloor: Long?,
    @ColumnInfo(name = "nonce_ceiling")
    val nonceCeiling: Long?,
    @ColumnInfo(name = "updated_at")
    val updatedAtEpochMillis: Long
)

@Entity(
    tableName = "replay_nonces",
    indices = [
        Index(value = ["subject_key"]),
        Index(value = ["nonce"], unique = true),
        Index(value = ["expires_at"])
    ]
)
data class ReplayNonceEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "subject_key")
    val subjectKey: String,
    @ColumnInfo(name = "nonce")
    val nonce: String,
    @ColumnInfo(name = "msg_id")
    val msgId: String?,
    @ColumnInfo(name = "alias_ctx")
    val aliasCtx: String?,
    @ColumnInfo(name = "seen_at")
    val seenAtEpochMillis: Long,
    @ColumnInfo(name = "expires_at")
    val expiresAtEpochMillis: Long
)
