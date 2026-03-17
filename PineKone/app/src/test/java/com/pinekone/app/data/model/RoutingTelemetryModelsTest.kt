package com.pinekone.app.data.model

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoutingTelemetryModelsTest {

    @Test
    fun scorePrefersRecentSuccessfulEdge() {
        val now = Instant.parse("2026-03-18T00:00:00Z")
        val recentEdge = RouteContextEdge(
            id = 1,
            peerId = "peer-a",
            contextKey = "ctx:alpha",
            successCount = 5,
            failureCount = 0,
            custodyCount = 1,
            attemptCount = 6,
            edgeWeight = 1.6,
            lastTransport = "BLE",
            lastReasonCode = DecisionReasonCode.DELIVERY_ACK_RECEIVED.name,
            lastLatencyMs = 800L,
            updatedAt = now.minusSeconds(60)
        )
        val staleEdge = recentEdge.copy(
            peerId = "peer-b",
            successCount = 1,
            failureCount = 3,
            edgeWeight = -0.8,
            updatedAt = now.minusSeconds(60 * 60 * 48)
        )

        val recentScore = recentEdge.score(now)
        val staleScore = staleEdge.score(now)

        assertTrue(recentScore.score > staleScore.score)
        assertTrue(recentScore.explanation.contains("success=5"))
    }

    @Test
    fun sampleUpdatesEdgeStateWithoutDroppingExistingLatency() {
        val base = RouteContextEdge(
            id = 7,
            peerId = "peer-a",
            contextKey = "ctx:alpha",
            successCount = 2,
            failureCount = 1,
            custodyCount = 0,
            attemptCount = 3,
            edgeWeight = 0.5,
            lastTransport = "WEB",
            lastReasonCode = DecisionReasonCode.NO_VIABLE_PATH.name,
            lastLatencyMs = 1200L,
            updatedAt = Instant.parse("2026-03-17T23:30:00Z")
        )
        val sample = RouteContextEdgeSample(
            peerId = "peer-a",
            contextKey = "ctx:alpha",
            decision = RoutingDecision.FORWARD_NOW,
            reasonCode = DecisionReasonCode.CONDENSE_PROGRESS,
            lastTransport = "BLE",
            observedAt = Instant.parse("2026-03-18T00:00:00Z")
        )

        val updated = base.withSample(sample)

        assertEquals(3, updated.successCount)
        assertEquals(1, updated.failureCount)
        assertEquals(0, updated.custodyCount)
        assertEquals(4, updated.attemptCount)
        assertTrue(updated.edgeWeight > base.edgeWeight)
        assertEquals(1200L, updated.lastLatencyMs)
        assertEquals("BLE", updated.lastTransport)
    }

    @Test
    fun outcomeSampleCanAvoidDoubleCountingAttempts() {
        val base = RouteContextEdge(
            id = 9,
            peerId = "peer-c",
            contextKey = "ctx:beta",
            successCount = 1,
            failureCount = 0,
            custodyCount = 0,
            attemptCount = 2,
            edgeWeight = 0.2,
            lastTransport = "BLE",
            lastReasonCode = DecisionReasonCode.CONDENSE_PROGRESS.name,
            lastLatencyMs = 900L,
            updatedAt = Instant.parse("2026-03-17T23:59:00Z")
        )
        val sample = RouteContextEdgeSample(
            peerId = "peer-c",
            contextKey = "ctx:beta",
            decision = RoutingDecision.DELIVERY_CONFIRMED,
            reasonCode = DecisionReasonCode.DELIVERY_ACK_RECEIVED,
            lastTransport = "BLE",
            attemptCountDelta = 0,
            observedAt = Instant.parse("2026-03-18T00:00:00Z")
        )

        val updated = base.withSample(sample)

        assertEquals(2, updated.attemptCount)
        assertEquals(2, updated.successCount)
        assertTrue(updated.edgeWeight > base.edgeWeight)
    }
}
