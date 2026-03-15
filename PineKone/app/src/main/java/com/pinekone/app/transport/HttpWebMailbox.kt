package com.pinekone.app.transport

import android.util.Log
import com.pinekone.app.engine.CustodyResult
import com.pinekone.app.engine.CustodyTicket
import com.pinekone.app.engine.WebTransport
import com.pinekone.app.protocol.PkEnvelope
import com.pinekone.app.protocol.PkFormats
import com.pinekone.app.protocol.toHexString
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class HttpWebMailbox(
    baseUrl: String,
    private val client: OkHttpClient = defaultClient()
) : WebTransport {

    private val custodyFlow = MutableSharedFlow<CustodyTicket>(extraBufferCapacity = 8)
    override val custodyTickets: Flow<CustodyTicket> = custodyFlow.asSharedFlow()
    override val isConfigured: Boolean = true

    private val uploadUrl = if (baseUrl.endsWith("/")) {
        "${baseUrl}envelopes"
    } else {
        "$baseUrl/envelopes"
    }
    private val started = AtomicBoolean(false)

    override suspend fun start() {
        started.set(true)
    }

    override suspend fun stop() {
        started.set(false)
    }

    override suspend fun upload(envelope: PkEnvelope): CustodyResult = withContext(Dispatchers.IO) {
        if (!started.get()) {
            Log.w(TAG, "Mailbox upload attempted before start()")
        }
        val json = PkFormats.json.encodeToString(PkEnvelope.serializer(), envelope)
        val request = Request.Builder()
            .url(uploadUrl)
            .post(json.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@use CustodyResult.Rejected(response.code.toString(), response.message)
                }
                val bodyText = response.body?.string()
                val expiryHeader = response.header(HEADER_CUSTODY_EXPIRY)?.toLongOrNull()
                val expiryFromBody = bodyText?.let(::parseExpiry)
                val expiry = expiryHeader
                    ?: expiryFromBody
                    ?: Instant.now().plusSeconds(DEFAULT_TICKET_TTL_SECONDS).epochSecond
                val ticket = CustodyTicket(
                    msgIdHex = envelope.msgId.toHexString(),
                    expiryEpochSeconds = expiry
                )
                custodyFlow.emit(ticket)
                CustodyResult.Accepted(ticket)
            }
        }.getOrElse { error ->
            Log.e(TAG, "Mailbox upload failed", error)
            CustodyResult.Rejected("network_error", error.message ?: "Mailbox upload failed")
        }
    }

    private fun parseExpiry(body: String): Long? =
        runCatching {
            val ack = PkFormats.json.decodeFromString<MailboxAck>(body)
            ack.expiryEpochSeconds ?: ack.expiry ?: ack.ttlSeconds?.let { ttl ->
                Instant.now().plusSeconds(ttl).epochSecond
            }
        }.getOrNull()

    @Serializable
    private data class MailboxAck(
        val expiryEpochSeconds: Long? = null,
        val expiry: Long? = null,
        val ttlSeconds: Long? = null
    )

    companion object {
        private const val TAG = "HttpWebMailbox"
        private const val HEADER_CUSTODY_EXPIRY = "X-Custody-Expiry"
        private const val DEFAULT_TICKET_TTL_SECONDS = 3600L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            .build()
    }
}
