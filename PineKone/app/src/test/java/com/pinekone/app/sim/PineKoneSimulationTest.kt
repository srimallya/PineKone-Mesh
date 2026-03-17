package com.pinekone.app.sim

import com.pinekone.app.data.model.DecisionReasonCode
import com.pinekone.app.data.model.GovernanceRole
import com.pinekone.app.data.model.MutationKind
import com.pinekone.app.protocol.PkOps
import com.pinekone.app.protocol.PkPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PineKoneSimulationTest {

    @Test
    fun multiHopDeliveryPrefersRelationallyCloserRelay() {
        val sim = PineKoneSimulation()
            .addNode(SimNode("A"))
            .addNode(SimNode("B"))
            .addNode(SimNode("C"))
            .addNode(SimNode("D"))
            .connectBidirectional("A", "B", quality = 0.75)
            .connectBidirectional("A", "C", quality = 0.45)
            .connectBidirectional("B", "D", quality = 0.85)
            .connectBidirectional("C", "D", quality = 0.4)
            .invite("root", "A", "ctx:3047")
            .invite("root", "B", "ctx:3047")
            .invite("branch", "C", "ctx:9999")
            .bindAlias("B", "D", "ctx:3047:target", relationDistance = 1)
            .bindAlias("C", "D", "ctx:3047:other", relationDistance = 3)
            .grantRole("B", GovernanceRole.RELAY, "root")
            .grantRole("C", GovernanceRole.RELAY, "branch")

        val result = sim.run(
            handle = sim.sendMessage("A", "D", "ctx:3047:target"),
            maxTicks = 4
        )

        assertTrue(result.delivered, result.events.joinToString("\n"))
        assertEquals(1, result.deliveryTick)
        assertTrue(result.events.any { it.peerId == "B" && it.decision == com.pinekone.app.data.model.RoutingDecision.FORWARD_NOW }, result.events.joinToString("\n"))
        assertTrue(result.events.any { it.mutation == MutationKind.ALIAS_ROTATE })
    }

    @Test
    fun storeCarryDeliversAfterTransportRecovery() {
        val sim = PineKoneSimulation()
            .addNode(SimNode("A"))
            .addNode(SimNode("B"))
            .connectBidirectional("A", "B", quality = 0.8)
            .grantRole("B", GovernanceRole.RELAY, "root")
            .bindAlias("B", "B", "ctx:3047:recovery", relationDistance = 0)

        sim.setLinkActive("A", "B", active = false)
        sim.setLinkActive("B", "A", active = false)

        val result = sim.run(
            handle = sim.sendMessage("A", "B", "ctx:3047:recovery"),
            maxTicks = 5,
            beforeTick = { tick, live ->
                if (tick == 2) {
                    live.setLinkActive("A", "B", active = true)
                    live.setLinkActive("B", "A", active = true)
                }
            }
        )

        assertTrue(result.delivered, result.events.joinToString("\n"))
        assertTrue(result.events.any { it.decision == com.pinekone.app.data.model.RoutingDecision.STORE_CARRY })
        assertTrue(result.events.any { it.decision == com.pinekone.app.data.model.RoutingDecision.DELIVERY_CONFIRMED })
    }

    @Test
    fun shadowCustodyDeliversWhenWeakRouteExists() {
        val sim = PineKoneSimulation()
            .addNode(SimNode("A", webMailboxAvailable = true))
            .addNode(SimNode("B"))
            .addNode(SimNode("D", canFetchCustody = true))
            .connectBidirectional("A", "B", quality = 0.15)
            .grantRole("B", GovernanceRole.RELAY, "root")

        val result = sim.run(
            handle = sim.sendMessage("A", "D", "ctx:3047:weak", ttl = 4),
            maxTicks = 4
        )

        assertTrue(result.delivered, result.events.joinToString("\n"))
        assertTrue(result.deliveredViaCustody)
        assertTrue(result.events.any { it.type == SimEventType.CUSTODY && it.reason == DecisionReasonCode.CUSTODY_TICKET_ISSUED })
    }

    @Test
    fun revocationAndScopeQuarantineBypassCompromisedRelay() {
        val sim = PineKoneSimulation()
            .addNode(SimNode("A"))
            .addNode(SimNode("B"))
            .addNode(SimNode("C"))
            .addNode(SimNode("D"))
            .connectBidirectional("A", "B", quality = 0.95)
            .connectBidirectional("A", "C", quality = 0.7)
            .connectBidirectional("C", "D", quality = 0.8)
            .grantRole("B", GovernanceRole.RELAY, "root")
            .grantRole("C", GovernanceRole.RELAY, "root")
            .bindAlias("B", "D", "ctx:3047:quarantine", relationDistance = 1)
            .bindAlias("C", "D", "ctx:3047:quarantine", relationDistance = 2)
            .revoke("B", "root", "scope:ctx:3047:quarantine")

        val result = sim.run(
            handle = sim.sendMessage("A", "D", "ctx:3047:quarantine"),
            maxTicks = 5
        )

        assertTrue(result.delivered, result.events.joinToString("\n"))
        assertFalse(result.events.any { it.peerId == "B" && it.decision == com.pinekone.app.data.model.RoutingDecision.FORWARD_NOW })
        assertTrue(result.events.any { it.peerId == "C" && it.decision == com.pinekone.app.data.model.RoutingDecision.FORWARD_NOW })
    }

    @Test
    fun expiredRelayRoleRemovesPeerFromEligibility() {
        val sim = PineKoneSimulation()
            .addNode(SimNode("A"))
            .addNode(SimNode("B"))
            .addNode(SimNode("C"))
            .addNode(SimNode("D"))
            .connectBidirectional("A", "B", quality = 0.95)
            .connectBidirectional("A", "C", quality = 0.6)
            .connectBidirectional("C", "D", quality = 0.85)
            .grantRole("B", GovernanceRole.RELAY, "root", expiresAtTick = -1)
            .grantRole("C", GovernanceRole.RELAY, "root")
            .bindAlias("C", "D", "ctx:3047:expiry", relationDistance = 2)

        val result = sim.run(
            handle = sim.sendMessage("A", "D", "ctx:3047:expiry"),
            maxTicks = 5,
            beforeTick = { _, _ -> }
        )

        assertTrue(result.delivered)
        assertFalse(result.events.any { it.peerId == "B" && it.decision == com.pinekone.app.data.model.RoutingDecision.FORWARD_NOW })
        assertTrue(result.events.any { it.peerId == "C" && it.decision == com.pinekone.app.data.model.RoutingDecision.FORWARD_NOW })
    }

    @Test
    fun retryLimitProducesFailureWithoutCustody() {
        val sim = PineKoneSimulation()
            .addNode(SimNode("A", custodyEligible = false, webMailboxAvailable = false))
            .addNode(SimNode("B"))
            .connectBidirectional("A", "B", quality = 0.5)
            .revoke("B", "root", "lineage_sever compromised relay")

        val result = sim.run(
            handle = sim.sendMessage(
                "A",
                "D",
                "ctx:3047:dead",
                policy = PkPolicy(maxFanout = 2, retryLimit = 2, minBattPct = 15),
                ops = PkOps(storeCarry = true, requireAck = true, e2eAckPath = true),
                ttl = 3
            ),
            maxTicks = 4
        )

        assertFalse(result.delivered, result.events.joinToString("\n"))
        assertTrue(result.events.any { it.reason == DecisionReasonCode.RETRY_LIMIT_EXCEEDED || it.reason == DecisionReasonCode.NO_VIABLE_PATH }, result.events.joinToString("\n"))
    }

    @Test
    fun learnedRouteEdgesAccumulateAcrossSimulation() {
        val sim = PineKoneSimulation()
            .addNode(SimNode("A"))
            .addNode(SimNode("B"))
            .addNode(SimNode("D"))
            .connectBidirectional("A", "B", quality = 0.8)
            .connectBidirectional("B", "D", quality = 0.9)
            .grantRole("B", GovernanceRole.RELAY, "root")
            .bindAlias("B", "D", "ctx:3047:learn", relationDistance = 1)

        val result = sim.run(
            handle = sim.sendMessage("A", "D", "ctx:3047:learn"),
            maxTicks = 4
        )

        assertTrue(result.delivered)
        val edge = result.routeEdges["A"]?.values?.firstOrNull { it.peerId == "B" }
        assertNotNull(edge)
        assertTrue(edge.attemptCount >= 1)
        assertTrue(edge.edgeWeight > 0.0)
    }

    @Test
    fun seededStressSimulationKeepsTrustedPairMostlyDeliveringUnderChurn() {
        val (report, results) = PineKoneStressScenario(seed = 17, nodeCount = 16)
            .runStress(rounds = 18, maxTicksPerMessage = 10)

        println(report.render())

        assertEquals(18, results.size)
        assertTrue(report.deliveryRate >= 0.70, report.render())
        assertTrue(report.trustedPairSuccessRate >= 0.70, report.render())
        assertTrue(report.totalEvents > 0, report.render())
    }

    @Test
    fun harsherSeedStillFindsSomePathButExposesWeaknesses() {
        val (report, _) = PineKoneStressScenario(seed = 29, nodeCount = 22)
            .runStress(rounds = 20, maxTicksPerMessage = 10)

        println(report.render())

        assertTrue(report.deliveryRate >= 0.55, report.render())
        assertTrue(
            report.failedMessages >= 1 ||
                report.custodyDeliveries >= 1 ||
                report.failureReasons.containsKey(DecisionReasonCode.NO_VIABLE_PATH) ||
                report.failureReasons.containsKey(DecisionReasonCode.RETRY_LIMIT_EXCEEDED) ||
                report.failureReasons.containsKey(DecisionReasonCode.RELATIONAL_UNRESOLVED),
            report.render()
        )
    }

    @Test
    fun monteCarloStressShowsRobustButImperfectBehavior() {
        val report = PineKoneMonteCarloScenario(
            seeds = 11..30,
            nodeCountForSeed = { seed -> 10 + ((seed * 7) % 16) },
            rounds = 18,
            maxTicksPerMessage = 10
        ).run()

        println(report.render())

        assertEquals(20, report.totalRuns)
        assertTrue(report.averageDeliveryRate >= 0.75, report.render())
        assertTrue(report.p50DeliveryRate >= 0.80, report.render())
        assertTrue(report.p10DeliveryRate >= 0.55, report.render())
        assertTrue(report.minDeliveryRate >= 0.45, report.render())
        assertTrue(report.worstRuns.isNotEmpty(), report.render())
    }
}
