package com.heyheyon.armbandbot

internal fun parseGallogCounts(raw: String): Pair<Int, Int>? {
    val match = Regex("""^\s*([0-9]+)\s*,\s*([0-9]+)\s*$""").matchEntire(raw) ?: return null
    val postCount = match.groupValues[1].toIntOrNull() ?: return null
    val commentCount = match.groupValues[2].toIntOrNull() ?: return null
    return postCount to commentCount
}
