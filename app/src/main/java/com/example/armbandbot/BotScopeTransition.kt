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
        this[BUMP_ENABLED_KEY] = false
        this[MOVE_ENABLED_KEY] = false
        this[REMOTE_ENABLED_KEY] = false
        remove(FILTER_MASTER_REVISION_KEY)
        REMOTE_CHANNEL_EXPORT_BOOLEAN_KEYS.forEach { this[it] = false }
        keys.removeAll { it.startsWith("remote_channel_") && it !in REMOTE_CHANNEL_EXPORT_STRING_KEYS && it !in REMOTE_CHANNEL_EXPORT_BOOLEAN_KEYS && it !in REMOTE_CHANNEL_EXPORT_INT_KEYS }
        keys.removeAll { key ->
            key.startsWith("remote_lists_") && key !in setOf(REMOTE_ENABLED_KEY, "remote_lists_url", "remote_lists_kind", "remote_lists_interval_minutes")
        }
    }
