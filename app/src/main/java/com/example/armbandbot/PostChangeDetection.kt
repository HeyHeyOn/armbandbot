package com.heyheyon.armbandbot

fun shouldRecheckPost(
    savedCommentCount: Int,
    currentCommentCount: Int,
    savedTitle: String?,
    currentTitle: String
): Boolean {
    if (savedCommentCount == -1) return true
    if (savedCommentCount != currentCommentCount) return true
    return normalizePostTitle(savedTitle) != normalizePostTitle(currentTitle)
}

private fun normalizePostTitle(title: String?): String = title.orEmpty().trim()
