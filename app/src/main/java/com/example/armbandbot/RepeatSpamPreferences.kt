package com.heyheyon.armbandbot

import android.content.SharedPreferences

internal fun repeatSpamSettings(p: SharedPreferences) = RepeatSpamSettings(
    enabled = p.getBoolean(REPEAT_ENABLED_KEY, false),
    windowMinutes = p.getInt(REPEAT_WINDOW_KEY, 10), count = p.getInt(REPEAT_COUNT_KEY, 4),
    similarityPercent = p.getInt(REPEAT_SIMILARITY_KEY, 85), observeOnly = p.getBoolean(REPEAT_OBSERVE_KEY, false),
    yudong = p.getBoolean(REPEAT_YUDONG_KEY, true), kkang = p.getBoolean(REPEAT_KKANG_KEY, true),
).bounded()

internal fun repeatSpamPolicyRevision(p: SharedPreferences): String = policyHash(listOf(
    repeatSpamSettings(p).toString(), p.getLong(REPEAT_REVISION_KEY, 0).toString(),
    p.getString("target_urls", "").orEmpty(), userFilterPolicyRevision(p),
    effectiveRemoteLists(p)?.revision.orEmpty(), p.getString("kkang_detection_mode", "separate").orEmpty(),
    p.getInt("kkang_post_min", 5).toString(), p.getInt("kkang_comment_min", 10).toString(),
    p.getInt("kkang_total_min", 15).toString(),
    loadOrderedMultilineValues(p, "nickname_whitelist").distinct().sorted().joinToString("\n"),
    p.getBoolean("is_nickname_filter_mode", false).toString(),
).joinToString("|"))

internal fun SharedPreferences.Editor.repeatSpamChanged(): SharedPreferences.Editor =
    putLong(REPEAT_REVISION_KEY, System.nanoTime())
