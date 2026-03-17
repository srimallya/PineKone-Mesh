package com.pinekone.app.data

import com.pinekone.app.data.db.AliasEpochEntity
import com.pinekone.app.data.db.CustodyRecordEntity
import com.pinekone.app.data.db.CustodyReceiptEntity
import com.pinekone.app.data.db.DecisionReceiptEntity
import com.pinekone.app.data.db.ProtocolStateDao
import com.pinekone.app.data.db.RelayEventEntity
import com.pinekone.app.data.db.ReplayNonceEntity
import com.pinekone.app.data.db.ReplayWindowEntity
import kotlinx.coroutines.flow.Flow

class ProtocolStateRepository(
    private val dao: ProtocolStateDao
) {
    val decisionReceipts: Flow<List<DecisionReceiptEntity>> = dao.observeDecisionReceipts()
    val custodyReceipts: Flow<List<CustodyReceiptEntity>> = dao.observeCustodyReceipts()
    val custodyRecords: Flow<List<CustodyRecordEntity>> = dao.observeCustodyRecords()
    val aliasEpochs: Flow<List<AliasEpochEntity>> = dao.observeAliasEpochs()
    val relayEvents: Flow<List<RelayEventEntity>> = dao.observeRelayEvents()
    val replayWindows: Flow<List<ReplayWindowEntity>> = dao.observeReplayWindows()
    val replayNonces: Flow<List<ReplayNonceEntity>> = dao.observeReplayNonces()

    fun decisionReceiptsForMsg(msgId: String): Flow<List<DecisionReceiptEntity>> =
        dao.observeDecisionReceiptsForMsg(msgId)

    fun custodyReceiptsForMsg(msgId: String): Flow<List<CustodyReceiptEntity>> =
        dao.observeCustodyReceiptsForMsg(msgId)

    fun custodyRecordsForMsg(msgId: String): Flow<List<CustodyRecordEntity>> =
        dao.observeCustodyRecordsForMsg(msgId)

    fun aliasEpochsForNode(nodeId: String): Flow<List<AliasEpochEntity>> =
        dao.observeAliasEpochsForNode(nodeId)

    fun relayEventsForMsg(msgId: String): Flow<List<RelayEventEntity>> =
        dao.observeRelayEventsForMsg(msgId)

    fun replayNoncesForSubject(subjectKey: String): Flow<List<ReplayNonceEntity>> =
        dao.observeReplayNoncesForSubject(subjectKey)

    suspend fun saveDecisionReceipt(receipt: DecisionReceiptEntity) {
        dao.upsertDecisionReceipt(receipt)
    }

    suspend fun saveCustodyReceipt(receipt: CustodyReceiptEntity) {
        dao.upsertCustodyReceipt(receipt)
    }

    suspend fun saveCustodyRecord(record: CustodyRecordEntity) {
        dao.upsertCustodyRecord(record)
    }

    suspend fun saveAliasEpoch(epoch: AliasEpochEntity) {
        dao.upsertAliasEpoch(epoch)
    }

    suspend fun saveRelayEvent(event: RelayEventEntity) {
        dao.upsertRelayEvent(event)
    }

    suspend fun saveReplayWindow(window: ReplayWindowEntity) {
        dao.upsertReplayWindow(window)
    }

    suspend fun saveReplayNonce(nonce: ReplayNonceEntity): Long =
        dao.insertReplayNonce(nonce)

    suspend fun pruneExpiredReplayNonces(cutoffEpochMillis: Long) {
        dao.pruneExpiredReplayNonces(cutoffEpochMillis)
    }
}
