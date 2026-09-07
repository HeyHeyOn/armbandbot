package com.heyheyon.armbandbot

/**
 * Produces preferences for a distinct bot identity without inheriting runtime state or a
 * source bot's private scan scope. Schedule and other migrated settings remain portable.
 */
internal fun prepareCopiedBotSettingsSnapshot(
    source: Map<String, Any?>,
    newBotName: String,
): Map<String, Any> = migrateBotSettingsSnapshot(source)
    .toMutableMap()
    .apply {
        this["bot_name"] = newBotName.trim().ifBlank { "복사된 봇" }
        this["independent_scan_state_enabled"] = false
        this["is_running"] = false
        this["should_restore_after_restart"] = false
    }
