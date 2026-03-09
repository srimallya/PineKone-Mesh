package com.pinekone.app.ui

data class MessageTraceSummary(
    val msgId: String,
    val latestDecision: String,
    val latestReason: String,
    val mutationCount: Int,
    val latestDetail: String?
)

data class FeedRow(
    val id: String,
    val title: String,
    val subtitle: String
)
