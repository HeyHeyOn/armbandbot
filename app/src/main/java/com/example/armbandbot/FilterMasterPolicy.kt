package com.heyheyon.armbandbot

import android.content.SharedPreferences
import java.util.UUID

internal const val WORD_FILTER_ENABLED_KEY = "is_word_filter_mode"
internal const val YUDONG_FILTER_ENABLED_KEY = "is_yudong_filter_mode"
internal const val FILTER_MASTER_REVISION_KEY = "filter_master_recheck_revision"

internal fun filterMasterEnabled(p: SharedPreferences, key: String): Boolean = p.getBoolean(key, true)
internal fun activeWordFilterValues(p: SharedPreferences, values: List<String>): List<String> =
    if (filterMasterEnabled(p, WORD_FILTER_ENABLED_KEY)) values else emptyList()
internal fun activeYudongFilterOption(p: SharedPreferences, key: String): Boolean =
    filterMasterEnabled(p, YUDONG_FILTER_ENABLED_KEY) && p.getBoolean(key, false)

internal fun setFilterMasterEnabled(p: SharedPreferences, key: String, enabled: Boolean) {
    require(key == WORD_FILTER_ENABLED_KEY || key == YUDONG_FILTER_ENABLED_KEY)
    if (filterMasterEnabled(p, key) == enabled) return
    // Invalidate the bounded scan gate when a master changes; do not erase child settings.
    p.edit().putBoolean(key, enabled).putString(FILTER_MASTER_REVISION_KEY, UUID.randomUUID().toString())
        .also { if (key == YUDONG_FILTER_ENABLED_KEY && enabled && p.getBoolean("is_yudong_dc_media_block", false))
            it.putBoolean("yudong_dc_media_recheck_pending", true) }.apply()
}
