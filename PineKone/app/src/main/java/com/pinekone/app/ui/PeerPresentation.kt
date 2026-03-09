package com.pinekone.app.ui

import com.pinekone.app.engine.PkPeer

data class PeerPresentation(
    val peer: PkPeer,
    val isContact: Boolean,
    val isTrusted: Boolean,
    val isRevoked: Boolean,
    val relationDistance: Int,
    val visibilityLabel: String
)
