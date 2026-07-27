package com.heyheyon.armbandbot

fun shouldRecheckPost(
    savedCommentCount: Int,
    currentCommentCount: Int,
    savedTitle: String?,
    currentTitle: String,
    isPumSourceFilterMode: Boolean = false,
    pumBlockAllPosts: Boolean = false,
    pumRecheckEveryCycle: Boolean = false,
    hasPumListMarker: Boolean = false,
    snapshotBackfillRequired: Boolean = false,
): Boolean {
    if (savedCommentCount == -1) return true
    if (savedCommentCount != currentCommentCount) return true
    if (normalizePostTitle(savedTitle) != normalizePostTitle(currentTitle)) return true
    if (snapshotBackfillRequired) return true
    return isPumSourceFilterMode && hasPumListMarker &&
        (pumBlockAllPosts || pumRecheckEveryCycle)
}

/** Skip only block-all work forced for an unchanged row whose effective terminal action is already held. */
fun shouldSkipPumHoldPreflight(
    isPumSourceFilterMode: Boolean,
    pumBlockAllPosts: Boolean,
    hasPumListMarker: Boolean,
    rowUnchanged: Boolean,
    effectiveActionIsHold: Boolean,
    alreadyHeld: Boolean,
): Boolean = isPumSourceFilterMode && pumBlockAllPosts && hasPumListMarker && rowUnchanged &&
    effectiveActionIsHold && alreadyHeld

private fun normalizePostTitle(title: String?): String = title.orEmpty().trim()
