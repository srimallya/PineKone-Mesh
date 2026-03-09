package com.pinekone.app.engine

import com.pinekone.app.protocol.PkEnvelope
import kotlinx.coroutines.flow.Flow

interface WebTransport {
    val custodyTickets: Flow<CustodyTicket>

    suspend fun start()
    suspend fun stop()
    suspend fun upload(envelope: PkEnvelope): CustodyResult
}

data class CustodyTicket(
    val msgIdHex: String,
    val expiryEpochSeconds: Long
)

sealed interface CustodyResult {
    data class Accepted(val ticket: CustodyTicket) : CustodyResult
    data class Rejected(val code: String, val message: String) : CustodyResult
}
