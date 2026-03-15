package com.pinekone.app.transport

import com.pinekone.app.engine.CustodyResult
import com.pinekone.app.engine.CustodyTicket
import com.pinekone.app.engine.WebTransport
import com.pinekone.app.protocol.PkEnvelope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.time.Instant

class NoopWebMailbox : WebTransport {
    private val custodyFlow = MutableSharedFlow<CustodyTicket>(extraBufferCapacity = 8)
    override val custodyTickets: Flow<CustodyTicket> = custodyFlow.asSharedFlow()
    override val isConfigured: Boolean = false

    override suspend fun start() {
        // nothing to do
    }

    override suspend fun stop() {
        // nothing to do
    }

    override suspend fun upload(envelope: PkEnvelope): CustodyResult {
        return CustodyResult.Rejected(
            code = "unconfigured",
            message = "Web mailbox is not configured"
        )
    }
}
