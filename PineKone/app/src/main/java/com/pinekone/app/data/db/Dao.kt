package com.pinekone.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts WHERE is_self = 0 ORDER BY CASE WHEN last_message_timestamp IS NULL THEN 1 ELSE 0 END, last_message_timestamp DESC")
    fun observeContacts(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE is_self = 1 LIMIT 1")
    fun observeSelf(): Flow<ContactEntity?>

    @Query("SELECT * FROM contacts WHERE node_id = :nodeId LIMIT 1")
    suspend fun getByNodeId(nodeId: String): ContactEntity?

    @Query("SELECT * FROM contacts WHERE fingerprint = :fingerprint LIMIT 1")
    suspend fun getByFingerprint(fingerprint: String): ContactEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(contact: ContactEntity)

    @Query("UPDATE contacts SET last_seen = :lastSeen WHERE node_id = :nodeId")
    suspend fun updateLastSeen(nodeId: String, lastSeen: Long)

    @Query("UPDATE contacts SET last_message_snippet = :snippet, last_message_timestamp = :timestamp WHERE node_id = :nodeId")
    suspend fun updateLastMessage(nodeId: String, snippet: String?, timestamp: Long?)

    @Query("DELETE FROM contacts WHERE node_id = :nodeId")
    suspend fun deleteByNodeId(nodeId: String)

    @Query("UPDATE contacts SET display_name = :displayName WHERE node_id = :nodeId")
    suspend fun rename(nodeId: String, displayName: String)

    @Query("UPDATE contacts SET last_message_snippet = NULL, last_message_timestamp = NULL WHERE node_id = :nodeId")
    suspend fun clearConversationMetadata(nodeId: String)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE contact_id = :contactId ORDER BY timestamp ASC")
    fun observeMessages(contactId: String): Flow<List<MessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity)

    @Query("UPDATE messages SET status = :status, delivered_at = :deliveredAt WHERE contact_id = :contactId AND msg_id = :msgId")
    suspend fun updateStatus(contactId: String, msgId: String, status: String, deliveredAt: Long?)

    @Query("SELECT * FROM messages WHERE contact_id = :contactId AND msg_id = :msgId LIMIT 1")
    suspend fun getMessage(contactId: String, msgId: String): MessageEntity?

    @Query("DELETE FROM messages WHERE contact_id = :contactId")
    suspend fun deleteForContact(contactId: String)
}

@Dao
interface PublicMessageDao {
    @Query("SELECT * FROM public_messages ORDER BY timestamp ASC")
    fun observeMessages(): Flow<List<PublicMessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: PublicMessageEntity)

    @Query("DELETE FROM public_messages")
    suspend fun clearAll()
}

@Dao
interface RoutingTelemetryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDecisionEvent(event: DecisionEventEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMutationEvent(event: MutationEventEntity)

    @Query("SELECT * FROM decision_events WHERE msg_id = :msgId ORDER BY created_at ASC")
    fun observeDecisionEvents(msgId: String): Flow<List<DecisionEventEntity>>

    @Query("SELECT * FROM mutation_events WHERE msg_id = :msgId ORDER BY created_at ASC")
    fun observeMutationEvents(msgId: String): Flow<List<MutationEventEntity>>

    @Query("SELECT * FROM decision_events ORDER BY created_at DESC")
    fun observeAllDecisionEvents(): Flow<List<DecisionEventEntity>>

    @Query("SELECT * FROM mutation_events ORDER BY created_at DESC")
    fun observeAllMutationEvents(): Flow<List<MutationEventEntity>>
}

@Dao
interface GovernanceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAliasBinding(binding: AliasBindingEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertInviteAttestation(attestation: InviteAttestationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRoleAttestation(attestation: RoleAttestationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRevocation(revocation: RevocationEntity)

    @Query("SELECT * FROM alias_bindings ORDER BY created_at DESC")
    fun observeAliasBindings(): Flow<List<AliasBindingEntity>>

    @Query("SELECT * FROM invite_attestations ORDER BY created_at DESC")
    fun observeInviteAttestations(): Flow<List<InviteAttestationEntity>>

    @Query("SELECT * FROM role_attestations ORDER BY created_at DESC")
    fun observeRoleAttestations(): Flow<List<RoleAttestationEntity>>

    @Query("SELECT * FROM revocations ORDER BY created_at DESC")
    fun observeRevocations(): Flow<List<RevocationEntity>>

    @Query("SELECT * FROM alias_bindings WHERE node_id = :nodeId OR contact_id = :nodeId")
    suspend fun findAliasBindingsForNode(nodeId: String): List<AliasBindingEntity>

    @Query("SELECT * FROM invite_attestations WHERE inviter_node_id = :nodeId OR member_node_id = :nodeId")
    suspend fun findInviteAttestationsForNode(nodeId: String): List<InviteAttestationEntity>

    @Query("SELECT COUNT(*) FROM revocations WHERE node_id = :nodeId")
    suspend fun revocationCount(nodeId: String): Int
}

@Dao
interface ProtocolStateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDecisionReceipt(receipt: DecisionReceiptEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCustodyReceipt(receipt: CustodyReceiptEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCustodyRecord(record: CustodyRecordEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAliasEpoch(epoch: AliasEpochEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRelayEvent(event: RelayEventEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReplayWindow(window: ReplayWindowEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertReplayNonce(nonce: ReplayNonceEntity): Long

    @Query("SELECT * FROM decision_receipts ORDER BY created_at DESC")
    fun observeDecisionReceipts(): Flow<List<DecisionReceiptEntity>>

    @Query("SELECT * FROM decision_receipts WHERE msg_id = :msgId ORDER BY created_at ASC")
    fun observeDecisionReceiptsForMsg(msgId: String): Flow<List<DecisionReceiptEntity>>

    @Query("SELECT * FROM decision_receipts WHERE receipt_id = :receiptId LIMIT 1")
    suspend fun getDecisionReceipt(receiptId: String): DecisionReceiptEntity?

    @Query("SELECT * FROM custody_receipts ORDER BY created_at DESC")
    fun observeCustodyReceipts(): Flow<List<CustodyReceiptEntity>>

    @Query("SELECT * FROM custody_receipts WHERE msg_id = :msgId ORDER BY created_at ASC")
    fun observeCustodyReceiptsForMsg(msgId: String): Flow<List<CustodyReceiptEntity>>

    @Query("SELECT * FROM custody_receipts WHERE receipt_id = :receiptId LIMIT 1")
    suspend fun getCustodyReceipt(receiptId: String): CustodyReceiptEntity?

    @Query("SELECT * FROM custody_records ORDER BY updated_at DESC")
    fun observeCustodyRecords(): Flow<List<CustodyRecordEntity>>

    @Query("SELECT * FROM custody_records WHERE msg_id = :msgId ORDER BY updated_at ASC")
    fun observeCustodyRecordsForMsg(msgId: String): Flow<List<CustodyRecordEntity>>

    @Query("SELECT * FROM custody_records WHERE state = 'ACTIVE' ORDER BY updated_at DESC")
    fun observeActiveCustodyRecords(): Flow<List<CustodyRecordEntity>>

    @Query("UPDATE custody_records SET state = :state, reason = :reason, released_at = :releasedAtEpochMillis, transferred_from = :transferredFromNodeId, updated_at = :updatedAtEpochMillis WHERE id = :id")
    suspend fun updateCustodyRecord(
        id: Long,
        state: String,
        reason: String?,
        releasedAtEpochMillis: Long?,
        transferredFromNodeId: String?,
        updatedAtEpochMillis: Long
    )

    @Query("SELECT * FROM alias_epochs ORDER BY created_at DESC")
    fun observeAliasEpochs(): Flow<List<AliasEpochEntity>>

    @Query("SELECT * FROM alias_epochs WHERE node_id = :nodeId ORDER BY epoch DESC")
    fun observeAliasEpochsForNode(nodeId: String): Flow<List<AliasEpochEntity>>

    @Query("SELECT * FROM alias_epochs WHERE retired_at IS NULL ORDER BY active_from DESC")
    fun observeActiveAliasEpochs(): Flow<List<AliasEpochEntity>>

    @Query("SELECT * FROM alias_epochs WHERE alias_id = :aliasId LIMIT 1")
    suspend fun getAliasEpochByAliasId(aliasId: String): AliasEpochEntity?

    @Query("SELECT * FROM relay_events ORDER BY created_at DESC")
    fun observeRelayEvents(): Flow<List<RelayEventEntity>>

    @Query("SELECT * FROM relay_events WHERE msg_id = :msgId ORDER BY hop_index ASC, created_at ASC")
    fun observeRelayEventsForMsg(msgId: String): Flow<List<RelayEventEntity>>

    @Query("SELECT * FROM relay_events WHERE event_id = :eventId LIMIT 1")
    suspend fun getRelayEvent(eventId: String): RelayEventEntity?

    @Query("SELECT * FROM replay_windows ORDER BY updated_at DESC")
    fun observeReplayWindows(): Flow<List<ReplayWindowEntity>>

    @Query("SELECT * FROM replay_windows WHERE subject_key = :subjectKey LIMIT 1")
    suspend fun getReplayWindow(subjectKey: String): ReplayWindowEntity?

    @Query("SELECT * FROM replay_nonces WHERE subject_key = :subjectKey ORDER BY seen_at DESC")
    fun observeReplayNoncesForSubject(subjectKey: String): Flow<List<ReplayNonceEntity>>

    @Query("SELECT * FROM replay_nonces ORDER BY seen_at DESC")
    fun observeReplayNonces(): Flow<List<ReplayNonceEntity>>

    @Query("DELETE FROM replay_nonces WHERE expires_at < :cutoffEpochMillis")
    suspend fun pruneExpiredReplayNonces(cutoffEpochMillis: Long)
}
