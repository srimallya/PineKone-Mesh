package com.pinekone.app.data

import com.pinekone.app.data.db.PublicMessageDao
import com.pinekone.app.data.db.PublicMessageEntity
import com.pinekone.app.data.model.PublicChatMessage
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class PublicChatRepository(
    private val dao: PublicMessageDao
) {
    fun messages(): Flow<List<PublicChatMessage>> =
        dao.observeMessages().map { entities ->
            entities.map { it.toDomain() }
        }

    suspend fun append(
        msgId: String,
        authorId: String,
        authorName: String,
        payload: String,
        timestampMillis: Long
    ) {
        val entity = PublicMessageEntity(
            msgId = msgId,
            authorId = authorId,
            authorName = authorName,
            payload = payload,
            timestampEpochMillis = timestampMillis
        )
        dao.insert(entity)
    }

    suspend fun clear() {
        dao.clearAll()
    }

    private fun PublicMessageEntity.toDomain(): PublicChatMessage =
        PublicChatMessage(
            id = id,
            authorId = authorId,
            authorName = authorName,
            payload = payload,
            timestamp = Instant.ofEpochMilli(timestampEpochMillis)
        )
}
