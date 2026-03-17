package com.pinekone.app.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PkProtocolSerializationTest {

    @Test
    fun envelopeCborRoundTripPreservesBinaryFields() {
        val envelope = PkEnvelope(
            msgId = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15),
            aliasCtx = "community:alpha",
            traceId = "trace-1",
            deadlineMs = 1000L,
            createdAtMs = 500L,
            ctxCommitment = ByteArray(16) { (it + 1).toByte() },
            condenseDepth = 2,
            mutationNonce = byteArrayOf(9, 9, 9, 9, 9, 9, 9, 9),
            hintTier = 1,
            ttl = 8,
            policy = PkPolicy(maxFanout = 2, retryLimit = 1, minBattPct = 10),
            hints = PkHints(communityId = 0x3047, targetHash = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), priority = 1),
            frag = PkFragmentHeader(kind = PkFragmentKind.DATA, seq = 0, total = 1),
            auth = PkAuth(
                originPkFingerprint = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8),
                originSig = ByteArray(64) { 7 }
            ),
            payload = byteArrayOf(42, 43, 44)
        )

        val decoded = envelope.toCbor().toEnvelopeFromCbor()

        assertEquals(envelope.aliasCtx, decoded.aliasCtx)
        assertEquals(envelope.deadlineMs, decoded.deadlineMs)
        assertContentEquals(envelope.msgId, decoded.msgId)
        assertContentEquals(envelope.payload, decoded.payload)
        assertContentEquals(envelope.auth?.originSig, decoded.auth?.originSig)
    }

    @Test
    fun controlFrameJsonRoundTripUsesAckFrame() {
        val frame: PkControlFrame = AckFrame(
            msgId = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15),
            highestContiguousSeq = 4,
            ackedSeqs = listOf(1, 2, 3),
            issuedAtMs = 99L
        )

        val decoded = frame.toJson().toControlFrameFromJson()

        val ack = assertIs<AckFrame>(decoded)
        assertEquals(4, ack.highestContiguousSeq)
        assertEquals(listOf(1, 2, 3), ack.ackedSeqs)
        assertEquals(99L, ack.issuedAtMs)
    }
}
