package com.heyheyon.armbandbot

import org.json.JSONObject

internal const val BOT_SETTINGS_CURRENT_SCHEMA_VERSION = 4
private const val BOT_SETTINGS_MIN_SUPPORTED_SCHEMA_VERSION = 1

internal data class BotSettingsImportEnvelope(
    val schemaVersion: Int,
    val exportVersion: Int,
    val exportedByAppVersion: String,
    val botName: String,
    val strings: Map<String, String>,
    val booleans: Map<String, Boolean>,
    val ints: Map<String, Int>,
    val floats: Map<String, Float>,
    val stringSets: Map<String, List<String>>,
    val pumProcessModePresent: Boolean,
)

internal fun parseAndMigrateBotSettingsExport(json: JSONObject): BotSettingsExport {
    val envelope = parseBotSettingsImportEnvelope(json)
    validateSupportedSchemaVersion(envelope.schemaVersion)
    return migrateBotSettingsEnvelopeToCurrent(envelope)
}

private fun parseBotSettingsImportEnvelope(json: JSONObject): BotSettingsImportEnvelope {
    val rawSchemaVersion = when {
        json.has("schemaVersion") -> json.optInt("schemaVersion", -1)
        json.has("exportVersion") -> 1
        else -> 1
    }

    require(rawSchemaVersion > 0) { "설정 파일 schemaVersion 값이 올바르지 않습니다." }

    val stringsJson = json.optJSONObject("strings")
    if (stringsJson?.has(RUN_SCHEDULE_WINDOWS_JSON_KEY) == true) {
        val raw = stringsJson.get(RUN_SCHEDULE_WINDOWS_JSON_KEY)
        require(raw is String) { "작동 시간대 JSON은 문자열이어야 합니다." }
        decodeBotRunWindows(raw)
        val booleans = json.optJSONObject("booleans")
        require(booleans?.has("run_schedule_enabled") != true || booleans.get("run_schedule_enabled") is Boolean)
    }
    return BotSettingsImportEnvelope(
        schemaVersion = rawSchemaVersion,
        exportVersion = json.optInt("exportVersion", 1),
        exportedByAppVersion = json.optString("exportedByAppVersion", json.optString("appVersion", "")),
        botName = json.optString("botName", "가져온 봇"),
        strings = stringsJson.toStringMap(EXPORTABLE_STRING_KEYS),
        booleans = json.optJSONObject("booleans").toBooleanMap(EXPORTABLE_BOOLEAN_KEYS),
        ints = json.optJSONObject("ints").toIntMap(EXPORTABLE_INT_KEYS),
        floats = json.optJSONObject("floats").toFloatMap(EXPORTABLE_FLOAT_KEYS),
        stringSets = json.optJSONObject("stringSets").toStringListMap(EXPORTABLE_STRING_SET_KEYS),
        pumProcessModePresent = stringsJson?.has("pum_block_process_mode") == true,
    )
}

private fun validateSupportedSchemaVersion(schemaVersion: Int) {
    require(schemaVersion >= BOT_SETTINGS_MIN_SUPPORTED_SCHEMA_VERSION) {
        "지원하지 않는 구형 설정 파일 형식입니다."
    }
    require(schemaVersion <= BOT_SETTINGS_CURRENT_SCHEMA_VERSION) {
        "더 최신 앱에서 내보낸 설정 파일입니다. 현재 앱은 schemaVersion $BOT_SETTINGS_CURRENT_SCHEMA_VERSION 까지만 불러올 수 있습니다."
    }
}

private fun migrateBotSettingsEnvelopeToCurrent(envelope: BotSettingsImportEnvelope): BotSettingsExport = when (envelope.schemaVersion) {
    1, 2, 3, BOT_SETTINGS_CURRENT_SCHEMA_VERSION -> migrateSupportedSchemaToCurrent(envelope)
    else -> error("schemaVersion ${envelope.schemaVersion} 마이그레이션이 아직 구현되지 않았습니다.")
}

private fun migrateSupportedSchemaToCurrent(envelope: BotSettingsImportEnvelope): BotSettingsExport {
    val normalizedStrings = envelope.strings.toMutableMap()
    val normalizedStringSets = envelope.stringSets.toMutableMap()
    ORDERED_MULTILINE_SETTING_KEYS.forEach { key ->
        val resolved = resolveOrderedMultilineText(
            savedText = envelope.strings[orderedMultilineTextKey(key)],
            legacyValues = LinkedHashSet(envelope.stringSets[key].orEmpty()),
        )
        normalizedStrings[orderedMultilineTextKey(key)] = resolved.text
        normalizedStringSets[key] = resolved.lines
    }

    val normalizedPum = normalizePumSettings(
        processMode = envelope.strings["pum_block_process_mode"].takeIf { envelope.pumProcessModePresent },
        blockDurationHours = envelope.ints["pum_block_duration_hours"],
        legacyDeleteOnly = envelope.booleans["pum_delete_only_mode"] == true,
        processModePresent = envelope.pumProcessModePresent,
    )
    val scheduleResult = loadBotRunSchedule(envelope.strings + envelope.booleans + envelope.ints)
    require(scheduleResult is BotRunScheduleLoadResult.Valid) { "작동 시간대 설정이 올바르지 않습니다." }
    val normalizedSchedule = scheduleResult.schedule
    if (scheduleResult.legacyEditor == null) {
        normalizedStrings[RUN_SCHEDULE_WINDOWS_JSON_KEY] = encodeBotRunWindows(normalizedSchedule.windows)
    }
    return BotSettingsExport(
        schemaVersion = BOT_SETTINGS_CURRENT_SCHEMA_VERSION,
        exportVersion = envelope.exportVersion,
        exportedByAppVersion = envelope.exportedByAppVersion.ifBlank { "unknown" },
        botName = envelope.botName,
        strings = normalizedStrings + ("pum_block_process_mode" to normalizedPum.processMode),
        booleans = envelope.booleans + ("run_schedule_enabled" to normalizedSchedule.enabled),
        ints = envelope.ints + mapOf(
            "pum_block_duration_hours" to normalizedPum.blockDurationHours,
            "run_schedule_start_minute" to (scheduleResult.legacyEditor?.startMinute ?: normalizedSchedule.startMinuteOfDay),
            "run_schedule_end_minute" to (scheduleResult.legacyEditor?.endMinute ?: normalizedSchedule.endMinuteOfDay),
        ),
        floats = envelope.floats,
        stringSets = normalizedStringSets,
    )
}

internal data class NormalizedRunScheduleSettings(
    val enabled: Boolean,
    val startMinute: Int,
    val endMinute: Int,
)

internal fun normalizeRunScheduleSettings(
    enabled: Boolean,
    startMinute: Int,
    endMinute: Int,
): NormalizedRunScheduleSettings {
    val normalizedStart = startMinute.takeIf { it in 0..1439 } ?: 0
    val normalizedEnd = endMinute.takeIf { it in 0..1439 } ?: 1439
    val valid = startMinute in 0..1439 && endMinute in 0..1439 && startMinute != endMinute
    return NormalizedRunScheduleSettings(
        enabled = enabled && valid,
        startMinute = normalizedStart,
        endMinute = normalizedEnd,
    )
}
