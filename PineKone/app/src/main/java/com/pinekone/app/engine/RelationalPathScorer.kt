package com.pinekone.app.engine

import com.pinekone.app.data.GovernanceRepository
import com.pinekone.app.data.model.Contact

data class PathScore(
    val peer: PkPeer,
    val distance: Int,
    val score: Double,
    val explanation: String
)

class RelationalPathScorer(
    private val governanceRepository: GovernanceRepository
) {
    suspend fun selectBestPeer(target: Contact, peers: List<PkPeer>): PathScore? {
        return peers
            .map { peer ->
                val distance = governanceRepository.relationDistance(target.nodeId, peer.id)
                val directMatchBonus = if (peer.id == target.nodeId || peer.fingerprintHex == target.fingerprint) 1.5 else 0.0
                val transportBonus = if (peer.transport == TransportKind.MESH) 0.35 else 0.1
                val qualityScore = peer.quality.coerceIn(0.0, 1.0)
                val distanceScore = (5 - distance).coerceAtLeast(0) * 0.8
                val score = distanceScore + qualityScore + transportBonus + directMatchBonus
                PathScore(
                    peer = peer,
                    distance = distance,
                    score = score,
                    explanation = "distance=$distance quality=${"%.2f".format(qualityScore)} transport=${peer.transport.name.lowercase()}"
                )
            }
            .filter { it.distance < 5 }
            .maxByOrNull { it.score }
    }
}
