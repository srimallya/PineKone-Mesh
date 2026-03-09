package com.pinekone.app.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PkEnvelopeTest {

    @Test
    fun `json round trip`() {
        val envelope = sampleEnvelope()
        val json = envelope.toJson()
        val decoded = json.toEnvelopeFromJson()
        assertEnvelopeEquals(envelope, decoded)
    }

    @Test
    fun `cbor round trip`() {
        val envelope = sampleEnvelope()
        val cbor = envelope.toCbor()
        val decoded = cbor.toEnvelopeFromCbor()
        assertEnvelopeEquals(envelope, decoded)
    }

    private fun assertEnvelopeEquals(expected: PkEnvelope, actual: PkEnvelope) {
        assertEquals(expected.ver, actual.ver)
        assertTrue(expected.msgId.contentEquals(actual.msgId))
        assertEquals(expected.ttl, actual.ttl)
        assertEquals(expected.policy, actual.policy)
        assertEquals(expected.hints, actual.hints)
        assertEquals(expected.ops, actual.ops)
        assertEquals(expected.web, actual.web)
        assertEquals(expected.frag, actual.frag)
        val expectedAuth = expected.auth?.originPkFingerprint
        val actualAuth = actual.auth?.originPkFingerprint
        if (expectedAuth != null && actualAuth != null) {
            assertTrue(expectedAuth.contentEquals(actualAuth))
        } else {
            assertEquals(expected.auth, actual.auth)
        }
        assertTrue(expected.payload.contentEquals(actual.payload))
    }

    private fun sampleEnvelope(): PkEnvelope {
        val msgId = ByteArray(16) { it.toByte() }
        val payload = ByteArray(32) { (255 - it).toByte() }
        return PkEnvelope(
            ver = 1,
            msgId = msgId,
            ttl = 10,
            policy = PkPolicy(
                maxFanout = 2,
                kPipe = 20,
                retryLimit = 3,
                minBattPct = 15,
                jThreshold = 5,
                weights = PkPolicyWeights()
            ),
            hints = PkHints(communityId = 0x3047, priority = 1),
            ops = PkOps(storeCarry = true, requireAck = true, e2eAckPath = true),
            web = PkWebHints(
                rendezvous = "h:pk.one/mbx/47",
                windowSeconds = 3600,
                pricing = PkWebPricing(powBits = 18, postage = 1),
                privacy = PkWebPrivacy(oblivious = true),
                topic = 0x304701
            ),
            frag = PkFragmentHeader(kind = PkFragmentKind.DATA, seq = 0, total = 64),
            auth = PkAuth(originPkFingerprint = ByteArray(8) { 0xAB.toByte() }),
            payload = payload
        )
    }
}
