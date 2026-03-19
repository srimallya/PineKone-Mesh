package com.pinekone.app.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

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
        assertTrue(frame.toJson().contains("\"type\":\"hack\""))
    }

    @Test
    fun controlFrameJsonRoundTripAcceptsModernAckTag() {
        val json = """
            {
              "type":"ack",
              "msg_id":"000102030405060708090a0b0c0d0e0f",
              "highest_contig_seq":4,
              "acked_seqs":[1,2,3],
              "issued_at_ms":99
            }
        """.trimIndent()

        val decoded = json.toControlFrameFromJson()

        val ack = assertIs<CompatAckFrame>(decoded)
        assertEquals(4, ack.highestContiguousSeq)
        assertEquals(listOf(1, 2, 3), ack.ackedSeqs)
        assertEquals(99L, ack.issuedAtMs)
    }

    @Test
    fun envelopeJsonRoundTripAcceptsLegacySchema() {
        val json = """
            {
              "msg_id":"000102030405060708090a0b0c0d0e0f",
              "trace_id":"legacy-trace",
              "ttl":8,
              "policy":{"max_fanout":2,"retry_limit":1,"min_batt_pct":10},
              "hints":{"community_id":12359,"target_hash":"0102030405060708","priority":1},
              "ops":{"store_carry":true,"require_ack":true,"e2e_ack_path":true},
              "frag":{"kind":"DATA","seq":0,"total":1},
              "auth":{"origin_pk_fp":"0102030405060708"},
              "payload":"2a2b2c",
              "ver":1
            }
        """.trimIndent()

        val decoded = json.toEnvelopeFromJson()

        assertEquals(LEGACY_PROTOCOL_VERSION, decoded.ver)
        assertEquals("ctx:0:public", decoded.aliasCtx)
        assertEquals(0L, decoded.deadlineMs)
        assertEquals(0L, decoded.createdAtMs)
        assertContentEquals(ByteArray(16), decoded.ctxCommitment)
        assertContentEquals(byteArrayOf(42, 43, 44), decoded.payload)
    }
}
