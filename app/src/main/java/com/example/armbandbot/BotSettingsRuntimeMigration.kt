package com.heyheyon.armbandbot

import android.content.Context
import android.content.SharedPreferences

internal const val BOT_PREF_SCHEMA_VERSION_KEY = "bot_settings_schema_version"
internal const val BOT_PREF_APP_VERSION_KEY = "bot_settings_app_version"

private val BOOLEAN_PREF_DEFAULTS: Map<String, Boolean> = mapOf(
    "ai_delete_only_mode" to false,
    "ai_delete_post_on_block" to true,
    "ai_filter_use_custom_endpoint" to false,
    "ai_filter_use_custom_model" to false,
    "ai_use_custom_action_config" to false,
    "auto_login_enabled" to false,
    "delete_only_mode" to false,
    "delete_post_on_block" to true,
    "dccon_delete_only_mode" to false,
    "dccon_delete_post_on_block" to true,
    "dccon_use_custom_action_config" to false,
    "dev_mode_unlocked" to false,
    "gallery_setting_image_block_all" to false,
    "gallery_setting_image_block_mobile" to false,
    "gallery_setting_image_block_proxy" to true,
    "gallery_setting_image_block_use" to false,
    "gallery_setting_mobile_ips_use" to false,
    "gallery_setting_mobile_use" to false,
    "gallery_setting_proxy_use" to false,
    "gallery_setting_refresh_enabled" to false,
    "image_delete_only_mode" to false,
    "image_delete_post_on_block" to true,
    "image_use_custom_action_config" to false,
    "is_ai_filter_mode" to false,
    "is_dccon_filter_mode" to false,
    "is_debug_mode" to false,
    "is_expert_mode" to false,
    "is_image_filter_mode" to false,
    "is_kkang_comment_block" to false,
    "is_kkang_filter_mode" to false,
    "is_kkang_image_block" to false,
    "is_kkang_post_block" to false,
    "is_kkang_voice_block" to false,
    "is_nickname_filter_mode" to false,
    "is_overseas_ip_comment_block" to true,
    "is_overseas_ip_filter_mode" to false,
    "is_overseas_ip_post_block" to true,
    "is_running" to false,
    "is_search_mode" to false,
    "is_snapshot_all" to false,
    "is_snapshot_blocked" to true,
    "is_spam_burst_protection_enabled" to false,
    "is_spam_code_filter_mode" to false,
    "is_url_filter_mode" to false,
    "is_user_filter_mode" to false,
    "is_voice_filter_mode" to false,
    "is_yudong_comment_block" to false,
    "is_yudong_image_block" to false,
    "is_yudong_post_block" to false,
    "is_yudong_voice_block" to false,
    "keyword_apply_kkang_only" to false,
    "keyword_apply_yudong_only" to false,
    "keyword_delete_only_mode" to false,
    "keyword_delete_post_on_block" to true,
    "keyword_use_custom_action_config" to false,
    "kkang_delete_only_mode" to false,
    "kkang_delete_post_on_block" to true,
    "kkang_use_custom_action_config" to false,
    "nickname_delete_only_mode" to false,
    "nickname_delete_post_on_block" to true,
    "nickname_use_custom_action_config" to false,
    "noti_ai" to true,
    "noti_image" to true,
    "noti_keyword" to true,
    "noti_kkang" to true,
    "noti_master" to true,
    "noti_nickname" to true,
    "noti_spam" to true,
    "noti_url" to true,
    "noti_user" to true,
    "noti_voice" to true,
    "noti_yudong" to true,
    "overseas_ip_delete_only_mode" to false,
    "overseas_ip_delete_post_on_block" to true,
    "overseas_ip_use_custom_action_config" to false,
    "session_webview_fallback_pending" to false,
    "should_restore_after_restart" to false,
    "spam_burst_target_kkang" to true,
    "spam_burst_target_yudong" to true,
    "spam_delete_only_mode" to false,
    "spam_delete_post_on_block" to true,
    "spam_use_custom_action_config" to false,
    "url_delete_only_mode" to false,
    "url_delete_post_on_block" to true,
    "url_use_custom_action_config" to false,
    "user_delete_only_mode" to false,
    "user_delete_post_on_block" to true,
    "user_use_custom_action_config" to false,
    "voice_delete_only_mode" to false,
    "voice_delete_post_on_block" to true,
    "voice_use_custom_action_config" to false,
    "yudong_delete_only_mode" to false,
    "yudong_delete_post_on_block" to true,
    "yudong_use_custom_action_config" to false
)

private val INT_PREF_DEFAULTS: Map<String, Int> = mapOf(
    "ai_block_duration_hours" to -1,
    "auto_login_failure_count" to 0,
    "block_duration_hours" to 6,
    "dccon_block_duration_hours" to 6,
    "gallery_setting_image_block_time_minutes" to 2880,
    "gallery_setting_mobile_ips_time_minutes" to 1440,
    "gallery_setting_mobile_time_minutes" to 720,
    "gallery_setting_proxy_time_minutes" to 2880,
    "gallery_setting_refresh_interval_minutes" to 30,
    "image_block_duration_hours" to 6,
    "image_filter_threshold" to 80,
    "keyword_block_duration_hours" to 6,
    "kkang_block_duration_hours" to 6,
    "kkang_comment_min" to 10,
    "kkang_post_min" to 5,
    "kkang_total_min" to 15,
    "nickname_block_duration_hours" to 6,
    "overseas_ip_block_duration_hours" to 6,
    "scan_page_count" to 1,
    "snapshot_keep_days" to 7,
    "spam_block_duration_hours" to 6,
    "spam_burst_duration_minutes" to 10,
    "spam_burst_window_minutes" to 3,
    "spam_burst_yudong_threshold" to 10,
    "spam_code_length" to 6,
    "url_block_duration_hours" to 6,
    "user_block_duration_hours" to 6,
    "voice_block_duration_hours" to 6,
    "yudong_block_duration_hours" to 6
)

private val FLOAT_PREF_DEFAULTS: Map<String, Float> = mapOf(
    "delay_cycle_max_sec" to 90.0f,
    "delay_cycle_min_sec" to 45.0f,
    "delay_page_max_sec" to 4.0f,
    "delay_page_min_sec" to 2.0f,
    "delay_post_max_sec" to 2.5f,
    "delay_post_min_sec" to 1.0f
)

private val STRING_PREF_DEFAULTS: Map<String, String> = mapOf(
    "ai_block_process_mode" to "BLOCK",
    "ai_filter_model" to "gpt-4o-mini",
    "ai_filter_provider" to "openai_compatible",
    "block_process_mode" to "BLOCK",
    "block_reason_text" to "커뮤니티 규칙 위반",
    "dccon_block_process_mode" to "BLOCK",
    "kkang_detection_mode" to "separate",
    "search_type" to "search_subject_memo",
    "target_urls" to ""
)

private val STRING_SET_PREF_DEFAULTS: Map<String, Set<String>> = mapOf(
    "activity_log_selected_filters" to emptySet(),
    "block_exempt_post_numbers" to emptySet(),
    "bypass" to emptySet(),
    "image_alt_blacklist" to emptySet(),
    "dccon_blacklist" to emptySet(),
    "nickname_blacklist" to emptySet(),
    "nickname_whitelist" to emptySet(),
    "normal" to emptySet(),
    "search_keywords" to emptySet(),
    "url_whitelist" to defaultBotUrlWhitelist(),
    "user_blacklist" to emptySet(),
    "user_whitelist" to emptySet(),
    "voice_blacklist" to emptySet()
)

fun migrateAllBotSettingsToCurrentVersion(context: Context): Int {
    val masterPref = context.getSharedPreferences("bot_master", Context.MODE_PRIVATE)
    val botIds = normalizeBotIdList(masterPref)
    return botIds.count { botId ->
        migrateBotSettingsToCurrentVersion(
            context.getSharedPreferences("bot_prefs_$botId", Context.MODE_PRIVATE)
        )
    }
}

fun migrateBotSettingsToCurrentVersion(botPref: SharedPreferences): Boolean {
    val before = botPref.all
    val migrated = migrateBotSettingsSnapshot(before)
    if (migrated == before) return false

    val editor = botPref.edit()
    migrated.forEach { (key, value) ->
        when (value) {
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Float -> editor.putFloat(key, value)
            is Long -> editor.putLong(key, value)
            is String -> editor.putString(key, value)
            is Set<*> -> @Suppress("UNCHECKED_CAST") editor.putStringSet(key, value as Set<String>)
        }
    }
    editor.apply()
    return true
}

internal fun migrateBotSettingsSnapshot(values: Map<String, Any?>): Map<String, Any> {
    val migrated = values
        .filterValues { it != null }
        .mapValues { it.value!! }
        .toMutableMap()

    BOOLEAN_PREF_DEFAULTS.forEach { (key, default) ->
        migrated[key] = coerceBoolean(migrated[key], default)
    }
    INT_PREF_DEFAULTS.forEach { (key, default) ->
        migrated[key] = coerceInt(migrated[key], default)
    }
    FLOAT_PREF_DEFAULTS.forEach { (key, default) ->
        migrated[key] = coerceFloat(migrated[key], default)
    }
    STRING_PREF_DEFAULTS.forEach { (key, default) ->
        migrated[key] = coerceString(migrated[key], default)
    }
    STRING_SET_PREF_DEFAULTS.forEach { (key, default) ->
        migrated[key] = coerceStringSet(migrated[key], default)
    }

    migrated[BOT_PREF_SCHEMA_VERSION_KEY] = BOT_SETTINGS_CURRENT_SCHEMA_VERSION
    migrated[BOT_PREF_APP_VERSION_KEY] = ARMBANDBOT_APP_VERSION
    return migrated
}

private fun normalizeBotIdList(masterPref: SharedPreferences): List<String> {
    val existing = masterPref.getString("bot_ids_list", null)
    val ids = if (existing != null) {
        existing.split(",")
    } else {
        masterPref.getStringSet("bot_ids", emptySet()).orEmpty()
    }
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()

    masterPref.edit().putString("bot_ids_list", ids.joinToString(",")).apply()
    return ids
}

private fun coerceBoolean(value: Any?, default: Boolean): Boolean = when (value) {
    is Boolean -> value
    is String -> when (value.trim().lowercase()) {
        "true", "1", "y", "yes", "on" -> true
        "false", "0", "n", "no", "off" -> false
        else -> default
    }
    is Number -> value.toInt() != 0
    else -> default
}

private fun coerceInt(value: Any?, default: Int): Int = when (value) {
    is Int -> value
    is Long -> value.toInt()
    is Float -> value.toInt()
    is Double -> value.toInt()
    is String -> value.trim().toIntOrNull() ?: value.trim().toFloatOrNull()?.toInt() ?: default
    else -> default
}

private fun coerceFloat(value: Any?, default: Float): Float = when (value) {
    is Float -> value
    is Int -> value.toFloat()
    is Long -> value.toFloat()
    is Double -> value.toFloat()
    is String -> value.trim().toFloatOrNull() ?: default
    else -> default
}

private fun coerceString(value: Any?, default: String): String = when (value) {
    is String -> value
    null -> default
    else -> value.toString()
}

private fun coerceStringSet(value: Any?, default: Set<String>): Set<String> = when (value) {
    is Set<*> -> value.mapNotNull { it?.toString()?.trim() }.filter { it.isNotEmpty() }.toSet()
    is String -> value.split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    is Iterable<*> -> value.mapNotNull { it?.toString()?.trim() }.filter { it.isNotEmpty() }.toSet()
    else -> default
}.let { if (it.isEmpty() && default.isNotEmpty()) default else it }
