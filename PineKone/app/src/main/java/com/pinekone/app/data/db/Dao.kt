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
