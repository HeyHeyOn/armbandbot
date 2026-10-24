package com.heyheyon.armbandbot

import android.content.SharedPreferences

internal const val RATE_ACTION_PREFIX = "posting_rate"
internal const val RATE_CUSTOM_KEY = "posting_rate_use_custom_action_config"
internal const val RATE_MODE_KEY = "posting_rate_block_process_mode"
internal const val RATE_DURATION_KEY = "posting_rate_block_duration_hours"
internal const val RATE_DELETE_KEY = "posting_rate_delete_post_on_block"
internal const val RATE_REASON_KEY = "posting_rate_block_reason_text"

internal fun postingRateCustomMode(p: SharedPreferences): String =
    p.getString(RATE_MODE_KEY, null)?.takeIf { it in setOf("BLOCK", "DELETE", "HOLD") } ?: "HOLD"

internal fun postingRateActionView(p: SharedPreferences): RepeatActionView {
    val custom = p.getBoolean(RATE_CUSTOM_KEY, true)
    val hours = p.getInt("block_duration_hours", 6)
    val delete = p.getBoolean("delete_post_on_block", true)
    return if (custom) RepeatActionView(postingRateCustomMode(p), p.getInt(RATE_DURATION_KEY, hours),
        p.getBoolean(RATE_DELETE_KEY, delete), false)
    else RepeatActionView(defaultProcessMode(p), hours, delete, true)
}

internal fun postingRateSettings(p: SharedPreferences) = PostingRateSettings(
    enabled = p.getBoolean(RATE_ENABLED_KEY, false),
    yudong = p.getBoolean(RATE_YUDONG_KEY, true), kkang = p.getBoolean(RATE_KKANG_KEY, true),
    ipSeconds = p.getInt(RATE_IP_SECONDS_KEY, 60), ipCount = p.getInt(RATE_IP_COUNT_KEY, 10),
    uidSeconds = p.getInt(RATE_UID_SECONDS_KEY, 60), uidCount = p.getInt(RATE_UID_COUNT_KEY, 5),
    observeOnly = postingRateActionView(p).mode == "HOLD",
).bounded()
