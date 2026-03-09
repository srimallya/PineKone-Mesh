package com.pinekone.app.data

import com.pinekone.app.data.db.ContactDao
import com.pinekone.app.data.db.ContactEntity
import com.pinekone.app.data.model.Contact
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ContactRepository(
    private val dao: ContactDao
) {

    val contacts: Flow<List<Contact>> = dao.observeContacts().map { entities ->
        entities.map { it.toDomain() }
    }

    val self: Flow<Contact?> = dao.observeSelf().map { it?.toDomain() }

    suspend fun ensureSelf(identityNodeId: String, displayName: String, fingerprintHex: String, publicKeyHex: String) {
        val existing = dao.getByNodeId(identityNodeId)
        if (existing == null || !existing.isSelf) {
            dao.upsert(
                ContactEntity(
                    nodeId = identityNodeId,
                    displayName = displayName,
                    fingerprint = fingerprintHex,
                    publicKey = publicKeyHex,
                    lastSeenEpochMillis = System.currentTimeMillis(),
                    isSelf = true
                )
            )
        }
    }

    suspend fun upsertContact(nodeId: String, displayName: String, fingerprintHex: String, publicKeyHex: String?) {
        val existing = dao.getByNodeId(nodeId)
        val fingerprintMatch = dao.getByFingerprint(fingerprintHex)
        if (fingerprintMatch != null && fingerprintMatch.nodeId != nodeId) {
            dao.deleteByNodeId(fingerprintMatch.nodeId)
        }
        val entity = ContactEntity(
            nodeId = nodeId,
            displayName = displayName,
            fingerprint = fingerprintHex,
            publicKey = publicKeyHex ?: existing?.publicKey,
            lastSeenEpochMillis = System.currentTimeMillis(),
            isSelf = existing?.isSelf ?: false,
            lastMessageSnippet = existing?.lastMessageSnippet,
            lastMessageTimestamp = existing?.lastMessageTimestamp
        )
        dao.upsert(entity)
    }

    suspend fun markLastSeen(nodeId: String) {
        dao.updateLastSeen(nodeId, System.currentTimeMillis())
    }

    suspend fun findByFingerprint(fingerprintHex: String): Contact? {
        return dao.getByFingerprint(fingerprintHex)?.toDomain()
    }

    suspend fun getContact(nodeId: String): Contact? {
        return dao.getByNodeId(nodeId)?.toDomain()
    }

    suspend fun ensureContactForFingerprint(
        fingerprintHex: String,
        defaultDisplayName: String,
        publicKeyHex: String?
    ): Contact {
        val existing = dao.getByFingerprint(fingerprintHex)
        if (existing != null) {
            if (publicKeyHex != null && existing.publicKey == null) {
                dao.upsert(existing.copy(publicKey = publicKeyHex))
                return existing.copy(publicKey = publicKeyHex).toDomain()
            }
            return existing.toDomain()
        }
        val generatedNodeId = fingerprintHex
        val entity = ContactEntity(
            nodeId = generatedNodeId,
            displayName = defaultDisplayName,
            fingerprint = fingerprintHex,
            publicKey = publicKeyHex,
            lastSeenEpochMillis = System.currentTimeMillis(),
            isSelf = false
        )
        dao.upsert(entity)
        return entity.toDomain()
    }

    suspend fun renameContact(nodeId: String, newDisplayName: String) {
        val existing = dao.getByNodeId(nodeId) ?: return
        if (existing.isSelf) return
        dao.rename(nodeId, newDisplayName)
    }

    suspend fun deleteContact(nodeId: String) {
        val existing = dao.getByNodeId(nodeId) ?: return
        if (existing.isSelf) return
        dao.deleteByNodeId(nodeId)
    }

    private fun ContactEntity.toDomain(): Contact =
        Contact(
            nodeId = nodeId,
            displayName = displayName,
            fingerprint = fingerprint,
            publicKey = publicKey,
            lastSeen = lastSeenEpochMillis?.let { Instant.ofEpochMilli(it) },
            lastMessageSnippet = lastMessageSnippet,
            lastMessageTimestamp = lastMessageTimestamp?.let { Instant.ofEpochMilli(it) },
            isSelf = isSelf
        )
}
