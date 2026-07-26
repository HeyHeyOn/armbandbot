package com.heyheyon.armbandbot

fun shouldRecheckPost(
    savedCommentCount: Int,
    currentCommentCount: Int,
    savedTitle: String?,
    currentTitle: String,
    isPumSourceFilterMode: Boolean = false,
    pumRecheckEveryCycle: Boolean = false,
    hasPumListMarker: Boolean = false,
    snapshotBackfillRequired: Boolean = false,
): Boolean {
    if (savedCommentCount == -1) return true
    if (savedCommentCount != currentCommentCount) return true
    if (normalizePostTitle(savedTitle) != normalizePostTitle(currentTitle)) return true
    if (snapshotBackfillRequired) return true
    return isPumSourceFilterMode && pumRecheckEveryCycle && hasPumListMarker
}

private fun normalizePostTitle(title: String?): String = title.orEmpty().trim()
