package com.heyheyon.armbandbot

import android.content.SharedPreferences

/** Per-filter action keys follow the shared `<prefix>_...` convention used by every other filter. */
internal const val REPEAT_ACTION_PREFIX = "repeat_spam"
internal const val REPEAT_CUSTOM_ACTION_KEY = "repeat_spam_use_custom_action_config"
internal const val REPEAT_PROCESS_MODE_KEY = "repeat_spam_block_process_mode"
internal const val REPEAT_DELETE_ONLY_KEY = "repeat_spam_delete_only_mode"
internal const val REPEAT_DURATION_KEY = "repeat_spam_block_duration_hours"
internal const val REPEAT_DELETE_POST_KEY = "repeat_spam_delete_post_on_block"
internal const val REPEAT_REASON_KEY = "repeat_spam_block_reason_text"

/**
 * Up to 1.7.0-beta1 repeat spam could only delete (or hold with "감지 기록만"). Missing keys keep exactly
 * that behaviour: the per-filter setting is on, and the mode is DELETE or, for observe-only users, HOLD.
 * Unlike other filters it must not silently fall back to the default action, which may be BLOCK.
 */
internal fun repeatUsesCustomAction(p: SharedPreferences): Boolean = p.getBoolean(REPEAT_CUSTOM_ACTION_KEY, true)

internal fun repeatCustomProcessMode(p: SharedPreferences): String =
    p.getString(REPEAT_PROCESS_MODE_KEY, null)?.takeIf { it in setOf("BLOCK", "DELETE", "HOLD") }
        ?: if (p.getBoolean(REPEAT_OBSERVE_KEY, false)) "HOLD" else "DELETE"

/** Same rule as BotService.botPrefCompatProcessMode for the bot's 차단 기본 설정. */
internal fun defaultProcessMode(p: SharedPreferences): String = when (p.getString("block_process_mode", null)) {
    "HOLD" -> "HOLD"
    "DELETE" -> "DELETE"
    "BLOCK" -> "BLOCK"
    else -> if (p.getBoolean("delete_only_mode", false)) "DELETE" else "BLOCK"
}

internal data class RepeatActionView(val mode: String, val durationHours: Int, val deletePost: Boolean, val followsDefault: Boolean)

/** What the bot will actually do when repeat spam is found (used by the screen summary and policy revision). */
internal fun repeatActionView(p: SharedPreferences): RepeatActionView {
    val baseDuration = p.getInt("block_duration_hours", 6)
    val baseDeletePost = p.getBoolean("delete_post_on_block", true)
    return if (repeatUsesCustomAction(p)) RepeatActionView(
        mode = repeatCustomProcessMode(p),
        durationHours = if (p.contains(REPEAT_DURATION_KEY)) p.getInt(REPEAT_DURATION_KEY, baseDuration) else baseDuration,
        deletePost = if (p.contains(REPEAT_DELETE_POST_KEY)) p.getBoolean(REPEAT_DELETE_POST_KEY, true) else baseDeletePost,
        followsDefault = false,
    ) else RepeatActionView(defaultProcessMode(p), baseDuration, baseDeletePost, followsDefault = true)
}

internal fun repeatSpamSettings(p: SharedPreferences) = RepeatSpamSettings(
    enabled = p.getBoolean(REPEAT_ENABLED_KEY, false),
    windowMinutes = p.getInt(REPEAT_WINDOW_KEY, 10), count = p.getInt(REPEAT_COUNT_KEY, 4),
    similarityPercent = p.getInt(REPEAT_SIMILARITY_KEY, 85),
    minimumLength = p.getInt(REPEAT_MIN_LENGTH_KEY, REPEAT_DEFAULT_MIN_LENGTH),
    // HOLD keeps the earlier "감지 기록만" semantics: another filter that acts on the same post wins.
    observeOnly = repeatActionView(p).mode == "HOLD",
    yudong = p.getBoolean(REPEAT_YUDONG_KEY, true), kkang = p.getBoolean(REPEAT_KKANG_KEY, true),
    contentEnabled = p.getBoolean(REPEAT_CONTENT_ENABLED_KEY, true), postingRate = postingRateSettings(p),
).bounded()

internal fun repeatSpamPolicyRevision(p: SharedPreferences): String = policyHash(listOf(
    repeatSpamSettings(p).toString(), p.getLong(REPEAT_REVISION_KEY, 0).toString(),
    repeatActionView(p).toString(), postingRateActionView(p).toString(),
    p.getString(RATE_REASON_KEY, "").orEmpty(), p.getString(REPEAT_REASON_KEY, "").orEmpty(),
    p.getString("target_urls", "").orEmpty(), userFilterPolicyRevision(p),
    effectiveRemoteLists(p)?.revision.orEmpty(), p.getString("kkang_detection_mode", "separate").orEmpty(),
    p.getInt("kkang_post_min", 5).toString(), p.getInt("kkang_comment_min", 10).toString(),
    p.getInt("kkang_total_min", 15).toString(),
    loadOrderedMultilineValues(p, "nickname_whitelist").distinct().sorted().joinToString("\n"),
    p.getBoolean("is_nickname_filter_mode", false).toString(),
).joinToString("|"))

internal fun SharedPreferences.Editor.repeatSpamChanged(): SharedPreferences.Editor =
    putLong(REPEAT_REVISION_KEY, System.nanoTime())
