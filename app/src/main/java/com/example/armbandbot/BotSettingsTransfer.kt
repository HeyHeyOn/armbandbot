package com.heyheyon.armbandbot

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID

private const val BOT_SETTINGS_EXPORT_VERSION = 1
private val BOT_SETTINGS_APP_VERSION: String
    get() = ARMBANDBOT_APP_VERSION
private const val BOT_SETTINGS_FILE_TYPE = "armbandbot_bot_settings"
private val DEFAULT_URL_WHITELIST = setOf("dcinside.com", "dcinside.kr", "youtube.com", "youtu.be")

internal val EXPORTABLE_STRING_KEYS = listOf(
    "target_urls",
    "search_type",
    "block_reason_text",
    "kkang_detection_mode",
    "pum_block_process_mode",
    "pum_block_reason_text"
) + ORDERED_MULTILINE_SETTING_KEYS.map(::orderedMultilineTextKey)

internal val EXPORTABLE_BOOLEAN_KEYS = listOf(
    "noti_master", "noti_keyword", "noti_user", "noti_nickname", "noti_yudong", "noti_kkang",
    "noti_url", "noti_image", "noti_voice", "noti_spam", "delete_post_on_block",
    "is_search_mode", "is_user_filter_mode", "is_nickname_filter_mode",
    "is_yudong_post_block", "is_yudong_comment_block", "is_yudong_image_block", "is_yudong_voice_block",
    "is_kkang_filter_mode", "is_kkang_post_block", "is_kkang_comment_block", "is_kkang_image_block", "is_kkang_voice_block",
    "is_url_filter_mode", "is_image_filter_mode", "is_dccon_filter_mode", "is_voice_filter_mode", "is_spam_code_filter_mode", "is_special_char_filter_mode",
    "is_pum_source_filter_mode", "pum_recheck_every_cycle",
    "pum_block_all_posts", "pum_use_custom_action_config", "pum_delete_only_mode", "pum_delete_post_on_block",
    "bypass_ignore_case_enabled", "bypass_unicode_normalization_enabled",
    "is_debug_mode", "is_expert_mode", "is_snapshot_blocked", "is_snapshot_all"
)

internal val EXPORTABLE_INT_KEYS = listOf(
    "block_duration_hours", "kkang_post_min", "kkang_comment_min", "kkang_total_min", "spam_code_length",
    "image_filter_threshold", "scan_page_count", "snapshot_keep_days", "pum_block_duration_hours"
)

internal val EXPORTABLE_FLOAT_KEYS = listOf(
    "delay_post_min_sec", "delay_post_max_sec", "delay_page_min_sec",
    "delay_page_max_sec", "delay_cycle_min_sec", "delay_cycle_max_sec"
)

internal val EXPORTABLE_STRING_SET_KEYS = listOf(
    "normal", "bypass", "search_keywords", "user_blacklist", "user_whitelist",
    "nickname_blacklist", "nickname_bypass_blacklist", "nickname_whitelist", "url_whitelist", "block_exempt_post_numbers", "image_alt_blacklist", "dccon_blacklist", "voice_blacklist", "special_char_whitelist"
)

data class BotSettingsExport(
    val schemaVersion: Int = BOT_SETTINGS_CURRENT_SCHEMA_VERSION,
    val exportVersion: Int = BOT_SETTINGS_EXPORT_VERSION,
    val exportedByAppVersion: String = BOT_SETTINGS_APP_VERSION,
    val botName: String,
    val strings: Map<String, String>,
    val booleans: Map<String, Boolean>,
    val ints: Map<String, Int>,
    val floats: Map<String, Float>,
    val stringSets: Map<String, List<String>>
)

fun defaultBotUrlWhitelist(): Set<String> = DEFAULT_URL_WHITELIST

internal fun exportStringSetValues(key: String, storedValues: Set<String>?): List<String> {
    val values = storedValues ?: if (key == "url_whitelist") DEFAULT_URL_WHITELIST else emptySet()
    return values
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
}

fun exportBotSettings(context: Context, botId: String): BotSettingsExport {
    val botPref = context.getSharedPreferences("bot_prefs_$botId", Context.MODE_PRIVATE)
    migrateBotSettingsToCurrentVersion(botPref)
    return BotSettingsExport(
        botName = botPref.getString("bot_name", "이름 없는 봇") ?: "이름 없는 봇",
        strings = EXPORTABLE_STRING_KEYS.associateWithNotNull { key -> botPref.getString(key, null) },
        booleans = EXPORTABLE_BOOLEAN_KEYS.associateWith { key -> botPref.getBoolean(key, defaultBooleanValue(key)) },
        ints = EXPORTABLE_INT_KEYS.associateWith { key -> botPref.getInt(key, defaultIntValue(key)) },
        floats = EXPORTABLE_FLOAT_KEYS.associateWith { key -> botPref.getFloat(key, defaultFloatValue(key)) },
        stringSets = EXPORTABLE_STRING_SET_KEYS.associateWith { key ->
            exportStringSetValues(key, botPref.getStringSet(key, null))
        }
    )
}

fun writeBotSettingsJson(context: Context, uriString: String, export: BotSettingsExport) {
    val uri = android.net.Uri.parse(uriString)
    val json = export.toJson().toString(2)
    context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { writer ->
        writer.write(json)
    } ?: error("파일을 열 수 없습니다.")
}

fun importBotSettingsAsNewBot(context: Context, uriString: String): String {
    val uri = android.net.Uri.parse(uriString)
    val jsonText = context.contentResolver.openInputStream(uri)?.use { input ->
        BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText()
    } ?: error("파일을 읽을 수 없습니다.")

    val json = JSONObject(jsonText)
    validateBotSettingsFileType(json)
    val imported = parseAndMigrateBotSettingsExport(json)
    val newBotId = "bot_${UUID.randomUUID()}"
    val botPref = context.getSharedPreferences("bot_prefs_$newBotId", Context.MODE_PRIVATE)
    applyImportedSettings(botPref, imported)

    val masterPref = context.getSharedPreferences("bot_master", Context.MODE_PRIVATE)
    val botIds = (masterPref.getString("bot_ids_list", "") ?: "")
        .split(",")
        .filter { it.isNotBlank() }
        .toMutableList()
    botIds.add(newBotId)
    masterPref.edit().putString("bot_ids_list", botIds.joinToString(",")).apply()

    return newBotId
}

private fun applyImportedSettings(botPref: SharedPreferences, imported: BotSettingsExport) {
    val editor = botPref.edit()
    editor.clear()
    editor.putString("bot_name", imported.botName.trim().ifBlank { "가져온 봇" })

    imported.strings.forEach { (key, value) -> editor.putString(key, value) }
    imported.booleans.forEach { (key, value) -> editor.putBoolean(key, value) }
    imported.ints.forEach { (key, value) -> editor.putInt(key, value) }
    imported.floats.forEach { (key, value) -> editor.putFloat(key, value) }
    imported.stringSets.forEach { (key, value) ->
        editor.putStringSet(key, value.map { it.trim() }.filter { it.isNotEmpty() }.toSet())
    }

    editor.putBoolean("is_running", false)
    editor.putBoolean("should_restore_after_restart", false)
    editor.putInt(BOT_PREF_SCHEMA_VERSION_KEY, BOT_SETTINGS_CURRENT_SCHEMA_VERSION)
    editor.putString(BOT_PREF_APP_VERSION_KEY, ARMBANDBOT_APP_VERSION)
    editor.apply()
}

internal fun BotSettingsExport.toJson(): JSONObject = JSONObject().apply {
    put("type", BOT_SETTINGS_FILE_TYPE)
    put("schemaVersion", schemaVersion)
    put("exportVersion", exportVersion)
    put("exportedByAppVersion", exportedByAppVersion)
    put("appVersion", exportedByAppVersion)
    put("botName", botName)
    put("strings", JSONObject(strings))
    put("booleans", JSONObject(booleans))
    put("ints", JSONObject(ints))
    put("floats", JSONObject(floats.mapValues { it.value.toDouble() }))
    put("stringSets", JSONObject(stringSets.mapValues { JSONArray(it.value) }))
}

private fun validateBotSettingsFileType(json: JSONObject) {
    val type = json.optString("type", "")
    require(type == BOT_SETTINGS_FILE_TYPE) { "완장봇 설정 파일이 아닙니다." }
}

internal fun JSONObject?.toStringMap(keys: List<String>): Map<String, String> =
    keys.mapNotNull { key ->
        val obj = this
        val value = if (obj != null && obj.has(key) && !obj.isNull(key)) {
            val trimmed = obj.optString(key).trim()
            val isOrderedTextKey = ORDERED_MULTILINE_SETTING_KEYS.any {
                orderedMultilineTextKey(it) == key
            }
            trimmed.takeIf { it.isNotEmpty() || isOrderedTextKey }
        } else {
            null
        }
        value?.let { key to it } ?: defaultStringValue(key)?.let { key to it }
    }.toMap()

internal fun JSONObject?.toBooleanMap(keys: List<String>): Map<String, Boolean> =
    keys.associateWith { key -> this?.optBoolean(key, defaultBooleanValue(key)) ?: defaultBooleanValue(key) }

internal fun JSONObject?.toIntMap(keys: List<String>): Map<String, Int> =
    keys.associateWith { key -> this?.optInt(key, defaultIntValue(key)) ?: defaultIntValue(key) }

internal fun JSONObject?.toFloatMap(keys: List<String>): Map<String, Float> =
    keys.associateWith { key -> (this?.optDouble(key, defaultFloatValue(key).toDouble()) ?: defaultFloatValue(key).toDouble()).toFloat() }

internal fun JSONObject?.toStringListMap(keys: List<String>): Map<String, List<String>> =
    keys.associateWith { key ->
        val array = this?.optJSONArray(key)
        if (array == null) {
            if (key == "url_whitelist") DEFAULT_URL_WHITELIST.toList() else emptyList()
        } else {
            buildList {
                for (i in 0 until array.length()) {
                    val value = array.optString(i).trim()
                    if (value.isNotEmpty()) add(value)
                }
            }.distinct()
        }
    }

private inline fun <T> Iterable<String>.associateWithNotNull(valueSelector: (String) -> T?): Map<String, T> =
    buildMap {
        for (key in this@associateWithNotNull) {
            val value = valueSelector(key) ?: continue
            put(key, value)
        }
    }

internal fun defaultBooleanValue(key: String): Boolean = when (key) {
    "noti_master", "noti_keyword", "noti_user", "noti_nickname", "noti_yudong", "noti_kkang",
    "noti_url", "noti_image", "noti_voice", "noti_spam", "delete_post_on_block", "is_snapshot_blocked",
    "pum_delete_post_on_block" -> true
    else -> false
}

internal fun defaultStringValue(key: String): String? = when (key) {
    "pum_block_process_mode" -> "BLOCK"
    else -> null
}

internal fun defaultIntValue(key: String): Int = when (key) {
    "block_duration_hours" -> 6
    "kkang_post_min" -> 5
    "kkang_comment_min" -> 10
    "kkang_total_min" -> 15
    "spam_code_length" -> 6
    "image_filter_threshold" -> 80
    "pum_block_duration_hours" -> 6
    "scan_page_count" -> 1
    "snapshot_keep_days" -> 7
    else -> 0
}

internal fun defaultFloatValue(key: String): Float = when (key) {
    "delay_post_min_sec" -> 1.0f
    "delay_post_max_sec" -> 2.5f
    "delay_page_min_sec" -> 2.0f
    "delay_page_max_sec" -> 4.0f
    "delay_cycle_min_sec" -> 45.0f
    "delay_cycle_max_sec" -> 90.0f
    else -> 0f
}
