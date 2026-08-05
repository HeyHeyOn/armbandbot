package com.heyheyon.armbandbot

import android.content.SharedPreferences

internal val ORDERED_MULTILINE_SETTING_KEYS: Set<String> = linkedSetOf(
    "normal",
    "bypass",
    "search_keywords",
    "block_exempt_post_numbers",
    "url_whitelist",
    "user_blacklist",
    "user_whitelist",
    "nickname_blacklist",
    "nickname_bypass_blacklist",
    "nickname_whitelist",
    "voice_blacklist",
)

internal data class OrderedMultilineText(
    val lines: List<String>,
    val text: String,
    val values: Set<String>,
    val removedDuplicateCount: Int,
)

internal fun orderedMultilineTextKey(key: String): String = "${key}_text"

internal fun normalizeOrderedMultilineText(rawText: String): OrderedMultilineText {
    val normalizedLines = rawText
        .lines()
        .map(String::trim)
        .filter(String::isNotEmpty)
    val distinctLines = normalizedLines.distinct()

    return OrderedMultilineText(
        lines = distinctLines,
        text = distinctLines.joinToString("\n"),
        values = LinkedHashSet(distinctLines),
        removedDuplicateCount = normalizedLines.size - distinctLines.size,
    )
}

internal fun resolveOrderedMultilineText(
    savedText: String?,
    legacyValues: Set<String>?,
): OrderedMultilineText {
    val sourceText = savedText ?: legacyValues.orEmpty().joinToString("\n")
    return normalizeOrderedMultilineText(sourceText)
}

internal fun loadOrderedMultilineText(
    preferences: SharedPreferences,
    key: String,
): String = resolveOrderedMultilineText(
    savedText = preferences.getString(orderedMultilineTextKey(key), null),
    legacyValues = preferences.getStringSet(key, emptySet()),
).text

internal fun persistOrderedMultilineText(
    preferences: SharedPreferences,
    key: String,
    rawText: String,
): OrderedMultilineText {
    require(key in ORDERED_MULTILINE_SETTING_KEYS) { "Unsupported ordered multiline key: $key" }
    val normalized = normalizeOrderedMultilineText(rawText)
    preferences.edit()
        .putString(orderedMultilineTextKey(key), normalized.text)
        .putStringSet(key, LinkedHashSet(normalized.values))
        .apply()
    return normalized
}
