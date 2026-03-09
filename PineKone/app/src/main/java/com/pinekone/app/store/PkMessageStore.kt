package com.pinekone.app.store

import com.pinekone.app.protocol.PkEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID

data class EnvelopeRecord(
    val id: String = UUID.randomUUID().toString(),
    val envelope: PkEnvelope,
    val createdAt: Instant = Instant.now(),
    val status: EnvelopeStatus = EnvelopeStatus.PENDING,
    val debugNote: String? = null
)

enum class EnvelopeStatus {
    PENDING,
    IN_FLIGHT,
    DELIVERED,
    FAILED
}

class PkMessageStore(
    private val scope: CoroutineScope
) {
    private val _records = MutableStateFlow<List<EnvelopeRecord>>(emptyList())
    val records: StateFlow<List<EnvelopeRecord>> = _records

    fun upsert(record: EnvelopeRecord) {
        scope.launch {
            val list = _records.value.toMutableList()
            val index = list.indexOfFirst { it.id == record.id }
            if (index >= 0) {
                list[index] = record
            } else {
                list.add(0, record)
            }
            _records.emit(list)
        }
    }

    fun updateStatus(msgId: ByteArray, status: EnvelopeStatus) {
        scope.launch {
            val list = _records.value.toMutableList()
            val index = list.indexOfFirst { it.envelope.msgId.contentEquals(msgId) }
            if (index >= 0) {
                list[index] = list[index].copy(status = status)
                _records.emit(list)
            }
        }
    }

    fun observeByMsgId(msgIdHex: String): EnvelopeRecord? =
        _records.value.firstOrNull { it.envelope.msgId.joinToString("") { b -> "%02x".format(b) } == msgIdHex }
}
