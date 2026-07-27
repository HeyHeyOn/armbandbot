package com.heyheyon.armbandbot

internal object SnapshotLogPolicy {
    fun shouldLogPerformance(blockedTs: String?, blockedCommentNo: String?): Boolean =
        blockedTs == null && blockedCommentNo == null
}