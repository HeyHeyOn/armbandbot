package com.heyheyon.armbandbot

import org.json.JSONObject

internal const val BOT_SETTINGS_CURRENT_SCHEMA_VERSION = 1
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
    val stringSets: Map<String, List<String>>
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

    return BotSettingsImportEnvelope(
        schemaVersion = rawSchemaVersion,
        exportVersion = json.optInt("exportVersion", 1),
        exportedByAppVersion = json.optString("exportedByAppVersion", json.optString("appVersion", "")),
        botName = json.optString("botName", "가져온 봇"),
        strings = json.optJSONObject("strings").toStringMap(EXPORTABLE_STRING_KEYS),
        booleans = json.optJSONObject("booleans").toBooleanMap(EXPORTABLE_BOOLEAN_KEYS),
        ints = json.optJSONObject("ints").toIntMap(EXPORTABLE_INT_KEYS),
        floats = json.optJSONObject("floats").toFloatMap(EXPORTABLE_FLOAT_KEYS),
        stringSets = json.optJSONObject("stringSets").toStringListMap(EXPORTABLE_STRING_SET_KEYS)
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
    1 -> migrateSchemaV1ToCurrent(envelope)
    else -> error("schemaVersion ${envelope.schemaVersion} 마이그레이션이 아직 구현되지 않았습니다.")
}

private fun migrateSchemaV1ToCurrent(envelope: BotSettingsImportEnvelope): BotSettingsExport = BotSettingsExport(
    schemaVersion = BOT_SETTINGS_CURRENT_SCHEMA_VERSION,
    exportVersion = envelope.exportVersion,
    exportedByAppVersion = envelope.exportedByAppVersion.ifBlank { "unknown" },
    botName = envelope.botName,
    strings = envelope.strings,
    booleans = envelope.booleans,
    ints = envelope.ints,
    floats = envelope.floats,
    stringSets = envelope.stringSets
)
