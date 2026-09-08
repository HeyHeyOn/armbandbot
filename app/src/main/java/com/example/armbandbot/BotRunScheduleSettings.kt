package com.heyheyon.armbandbot

import android.content.SharedPreferences

internal const val RUN_SCHEDULE_WINDOWS_JSON_KEY = "run_schedule_windows_json"
internal sealed interface BotRunScheduleLoadResult {
    data class Valid(val schedule: BotRunSchedule, val legacyEditor: NormalizedRunScheduleSettings? = null) : BotRunScheduleLoadResult
    data class Error(val enabled: Boolean, val rawCanonical: Any?, val message: String) : BotRunScheduleLoadResult
}

internal fun encodeBotRunWindows(windows: List<BotRunWindow>): String {
    require(windows.size in 1..64)
    return windows.joinToString(prefix = "[", postfix = "]", separator = ",") {
        "{\"startMinuteOfDay\":${it.startMinuteOfDay},\"endMinuteOfDay\":${it.endMinuteOfDay}}"
    }
}

// This deliberately small grammar rejects org.json's lenient single quotes, comments,
// coercions, duplicate keys, missing values and trailing commas.
internal fun decodeBotRunWindows(json: String): List<BotRunWindow> {
    require(json.length <= 16384) { "작동 시간대 데이터가 너무 큽니다." }
    val ws = "[ \\t\\r\\n]*"
    val integer = "(-?(?:0|[1-9][0-9]*))"
    val field = "\"(startMinuteOfDay|endMinuteOfDay)\"$ws:$ws$integer"
    val objectPattern = Regex("\\{$ws$field$ws,$ws$field$ws\\}")
    var position = 0
    fun skipWhitespace() { while (position < json.length && json[position] in " \t\r\n") position++ }
    fun consume(c: Char) { skipWhitespace(); require(position < json.length && json[position++] == c) { "작동 시간대 JSON 형식이 올바르지 않습니다." } }
    consume('[')
    val windows = mutableListOf<BotRunWindow>()
    while (true) {
        skipWhitespace()
        val match = objectPattern.find(json, position)
        require(match != null && match.range.first == position) { "작동 시간대 항목이 올바르지 않습니다." }
        require(match.groupValues[1] != match.groupValues[3])
        val fields = mapOf(match.groupValues[1] to match.groupValues[2].toIntOrNull(), match.groupValues[3] to match.groupValues[4].toIntOrNull())
        windows += BotRunWindow(requireNotNull(fields["startMinuteOfDay"]),requireNotNull(fields["endMinuteOfDay"]))
        require(windows.size <= 64)
        position = match.range.last + 1
        skipWhitespace()
        if (position < json.length && json[position] == ']') break
        consume(',')
    }
    consume(']')
    skipWhitespace()
    require(position == json.length)
    return windows.toList()
}

internal fun loadBotRunSchedule(preferences: SharedPreferences): BotRunScheduleLoadResult = loadBotRunSchedule(preferences.all)

internal fun loadBotRunSchedule(values: Map<String, *>): BotRunScheduleLoadResult {
    val enabled = values["run_schedule_enabled"] == true
    if (values.containsKey(RUN_SCHEDULE_WINDOWS_JSON_KEY)) {
        val raw = values[RUN_SCHEDULE_WINDOWS_JSON_KEY]
        return try {
            require(!values.containsKey("run_schedule_enabled") || values["run_schedule_enabled"] is Boolean)
            require(raw is String)
            BotRunScheduleLoadResult.Valid(BotRunSchedule(enabled, decodeBotRunWindows(raw)))
        } catch (e: IllegalArgumentException) {
            BotRunScheduleLoadResult.Error(enabled, raw, e.message ?: "작동 시간대 설정이 올바르지 않습니다.")
        }
    }
    val legacy = normalizeRunScheduleSettings(enabled, values["run_schedule_start_minute"] as? Int ?: 0, values["run_schedule_end_minute"] as? Int ?: 1439)
    return if (legacy.startMinute == legacy.endMinute) {
        BotRunScheduleLoadResult.Valid(BotRunSchedule.disabled(), legacy)
    } else BotRunScheduleLoadResult.Valid(BotRunSchedule(legacy.enabled,legacy.startMinute,legacy.endMinute))
}

internal fun saveBotRunSchedule(preferences: SharedPreferences, schedule: BotRunSchedule) {
    val encoded = encodeBotRunWindows(schedule.windows)
    preferences.edit()
        .putString(RUN_SCHEDULE_WINDOWS_JSON_KEY, encoded)
        .putBoolean("run_schedule_enabled", schedule.enabled)
        .putInt("run_schedule_start_minute", schedule.startMinuteOfDay)
        .putInt("run_schedule_end_minute", schedule.endMinuteOfDay)
        .apply()
}
