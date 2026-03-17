package com.pinekone.app.engine

import com.pinekone.app.data.GovernanceRepository
import com.pinekone.app.data.RoutingTelemetryRepository
import com.pinekone.app.data.model.Contact

data class PathScore(
    val peer: PkPeer,
    val distance: Int,
    val score: Double,
    val explanation: String,
    val eligible: Boolean
)

class RelationalPathScorer(
    private val governanceRepository: GovernanceRepository,
    private val routingTelemetryRepository: RoutingTelemetryRepository
) {
    suspend fun selectBestPeer(target: Contact, peers: List<PkPeer>, contextKey: String): PathScore? {
        val learnedEdges = routingTelemetryRepository.routeEdgesForContext(contextKey)
        return peers
            .map { peer ->
                val profile = governanceRepository.routingPeerProfile(target.nodeId, peer.id, contextKey)
                val distance = profile.contextualDistance
                val learnedEdge = learnedEdges[peer.id]
                val directMatchBonus = if (peer.id == target.nodeId || peer.fingerprintHex == target.fingerprint) 1.5 else 0.0
                val transportBonus = if (peer.transport == TransportKind.MESH) 0.35 else 0.1
                val qualityScore = peer.quality.coerceIn(0.0, 1.0)
                val distanceScore = (5 - distance).coerceAtLeast(0) * 0.8
                val learnedBias = learnedEdge?.edgeWeight ?: 0.0
                val successBonus = ((learnedEdge?.successRate ?: 0.5) - 0.5) * 1.2
                val custodyBonus = (learnedEdge?.custodyCount ?: 0).coerceAtMost(3) * 0.05
                val score = distanceScore + qualityScore + transportBonus + directMatchBonus + learnedBias + successBonus + custodyBonus + profile.trustScore
                PathScore(
                    peer = peer,
                    distance = distance,
                    score = score,
                    explanation = buildString {
                        append("distance=")
                        append(distance)
                        append(" quality=")
                        append("%.2f".format(qualityScore))
                        append(" transport=")
                        append(peer.transport.name.lowercase())
                        append(" trust=")
                        append("%.2f".format(profile.trustScore))
                        if (profile.hasRelayRole) append(" relay")
                        if (profile.sharedLineage) append(" lineage")
                        if (profile.exactScopeMatch) append(" scope")
                        else if (profile.communityMatch) append(" community")
                        learnedEdge?.let {
                            append(" learned=")
                            append("%.2f".format(it.edgeWeight))
                            append(" success=")
                            append("%.0f%%".format(it.successRate * 100.0))
                        }
                    },
                    eligible = profile.eligible
                )
            }
            .filter { it.distance < 5 && it.score > -1.0 && it.eligible }
            .maxByOrNull { it.score }
    }
}
