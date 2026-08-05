package com.heyheyon.armbandbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BotSettingsTransferTest {
    @Test
    fun legacySchemaOneImportBackfillsOrderedTextFromJsonArrayOrder() {
        val legacy = BotSettingsExport(
            schemaVersion = 1,
            botName = "구형 순서 봇",
            strings = emptyMap(),
            booleans = emptyMap(),
            ints = emptyMap(),
            floats = emptyMap(),
            stringSets = mapOf(
                "user_blacklist" to listOf("second # memo", "first", "second # memo"),
            ),
        )

        val imported = parseAndMigrateBotSettingsExport(legacy.toJson())

        assertEquals("second # memo\nfirst", imported.strings["user_blacklist_text"])
        assertEquals(listOf("second # memo", "first"), imported.stringSets["user_blacklist"])
        assertEquals(BOT_SETTINGS_CURRENT_SCHEMA_VERSION, imported.schemaVersion)
    }

    @Test
    fun orderedMultilineTextSurvivesJsonRoundTrip() {
        ORDERED_MULTILINE_SETTING_KEYS.forEach { key ->
            assertTrue(orderedMultilineTextKey(key) in EXPORTABLE_STRING_KEYS)
        }
        val original = BotSettingsExport(
            botName = "순서 보존 봇",
            strings = mapOf("user_blacklist_text" to "second # memo\nfirst"),
            booleans = emptyMap(),
            ints = emptyMap(),
            floats = emptyMap(),
            stringSets = mapOf("user_blacklist" to listOf("first", "second # memo")),
        )

        val imported = parseAndMigrateBotSettingsExport(original.toJson())

        assertEquals("second # memo\nfirst", imported.strings["user_blacklist_text"])
        assertEquals(listOf("second # memo", "first"), imported.stringSets["user_blacklist"])
    }

    @Test
    fun explicitEmptyOrderedTextClearsStaleSetOnImport() {
        val original = BotSettingsExport(
            botName = "비운 목록 봇",
            strings = mapOf("user_blacklist_text" to ""),
            booleans = emptyMap(),
            ints = emptyMap(),
            floats = emptyMap(),
            stringSets = mapOf("user_blacklist" to listOf("stale-user")),
        )

        val imported = parseAndMigrateBotSettingsExport(original.toJson())

        assertTrue(imported.strings.containsKey("user_blacklist_text"))
        assertEquals("", imported.strings["user_blacklist_text"])
        assertEquals(emptyList<String>(), imported.stringSets["user_blacklist"])
    }

    @Test
    fun explicitEmptyUrlWhitelistRemainsEmptyAcrossTransferNormalization() {
        assertEquals(
            defaultBotUrlWhitelist().toList(),
            exportStringSetValues("url_whitelist", storedValues = null),
        )
        assertEquals(
            emptyList<String>(),
            exportStringSetValues("url_whitelist", storedValues = emptySet()),
        )

        val original = BotSettingsExport(
            botName = "URL 목록을 비운 봇",
            strings = mapOf("url_whitelist_text" to ""),
            booleans = emptyMap(),
            ints = emptyMap(),
            floats = emptyMap(),
            stringSets = mapOf("url_whitelist" to emptyList()),
        )
        val imported = parseAndMigrateBotSettingsExport(original.toJson())

        assertEquals("", imported.strings["url_whitelist_text"])
        assertEquals(emptyList<String>(), imported.stringSets["url_whitelist"])
    }

    @Test
    fun pumSettingsRoundTripWithBothValues() {
        listOf(false, true).forEach { sourceEnabled ->
            listOf(false, true).forEach { recheckEveryCycle ->
                val original = BotSettingsExport(
                    botName = "PUM 설정 봇",
                    strings = emptyMap(),
                    booleans = mapOf(
                        "is_pum_source_filter_mode" to sourceEnabled,
                        "pum_recheck_every_cycle" to recheckEveryCycle,
                    ),
                    ints = emptyMap(),
                    floats = emptyMap(),
                    stringSets = emptyMap(),
                )

                val imported = parseAndMigrateBotSettingsExport(original.toJson())

                assertEquals(sourceEnabled, imported.booleans["is_pum_source_filter_mode"])
                assertEquals(recheckEveryCycle, imported.booleans["pum_recheck_every_cycle"])
            }
        }
    }

    @Test
    fun legacyTransferDefaultsPumSettingsToFalse() {
        val imported = parseAndMigrateBotSettingsExport(
            BotSettingsExport(
                botName = "구형 봇",
                strings = emptyMap(),
                booleans = emptyMap(),
                ints = emptyMap(),
                floats = emptyMap(),
                stringSets = emptyMap(),
            ).toJson()
        )

        assertEquals(false, imported.booleans["is_pum_source_filter_mode"])
        assertEquals(false, imported.booleans["pum_recheck_every_cycle"])
        assertEquals(false, imported.booleans["pum_block_all_posts"])
        assertEquals(false, imported.booleans["pum_use_custom_action_config"])
        assertEquals(false, imported.booleans["pum_delete_only_mode"])
        assertEquals(true, imported.booleans["pum_delete_post_on_block"])
        assertEquals("BLOCK", imported.strings["pum_block_process_mode"])
        assertEquals(6, imported.ints["pum_block_duration_hours"])
        assertFalse(imported.strings.containsKey("pum_block_reason_text"))
    }

    @Test
    fun pumActionSettingsSurviveJsonRoundTrip() {
        val original = BotSettingsExport(
            botName = "PUM 조치 설정 봇",
            strings = mapOf(
                "pum_block_process_mode" to "HOLD",
                "pum_block_reason_text" to "펌 출처 위반",
            ),
            booleans = mapOf(
                "pum_block_all_posts" to true,
                "pum_use_custom_action_config" to true,
                "pum_delete_only_mode" to true,
                "pum_delete_post_on_block" to false,
            ),
            ints = mapOf("pum_block_duration_hours" to 24),
            floats = emptyMap(),
            stringSets = emptyMap(),
        )

        val imported = parseAndMigrateBotSettingsExport(original.toJson())

        assertEquals("HOLD", imported.strings["pum_block_process_mode"])
        assertEquals("펌 출처 위반", imported.strings["pum_block_reason_text"])
        assertEquals(true, imported.booleans["pum_block_all_posts"])
        assertEquals(true, imported.booleans["pum_use_custom_action_config"])
        assertEquals(true, imported.booleans["pum_delete_only_mode"])
        assertEquals(false, imported.booleans["pum_delete_post_on_block"])
        assertEquals(24, imported.ints["pum_block_duration_hours"])
    }

    @Test
    fun importedPumActionSettingsAreNormalizedAndRoundTripSafely() {
        val unsafe = BotSettingsExport(
            botName = "unsafe",
            strings = mapOf("pum_block_process_mode" to "hold"),
            booleans = emptyMap(),
            ints = mapOf("pum_block_duration_hours" to 99_999),
            floats = emptyMap(),
            stringSets = emptyMap(),
        )

        val imported = parseAndMigrateBotSettingsExport(unsafe.toJson())
        val roundTripped = parseAndMigrateBotSettingsExport(imported.toJson())

        assertEquals("BLOCK", imported.strings["pum_block_process_mode"])
        assertEquals(744, imported.ints["pum_block_duration_hours"])
        assertEquals("BLOCK", roundTripped.strings["pum_block_process_mode"])
        assertEquals(744, roundTripped.ints["pum_block_duration_hours"])
    }

    @Test
    fun legacyImportUsesExplicitDeleteOnlyWhenPumProcessModeIsAbsent() {
        val json = BotSettingsExport(
            botName = "legacy",
            strings = emptyMap(),
            booleans = mapOf("pum_delete_only_mode" to true),
            ints = emptyMap(),
            floats = emptyMap(),
            stringSets = emptyMap(),
        ).toJson()
        json.getJSONObject("strings").remove("pum_block_process_mode")

        val imported = parseAndMigrateBotSettingsExport(json)

        assertEquals("DELETE", imported.strings["pum_block_process_mode"])
        assertEquals(6, imported.ints["pum_block_duration_hours"])
    }

    @Test
    fun importFailsSafeWhenExplicitProcessModeIsInvalidDespiteLegacyDeleteOnly() {
        val unsafe = BotSettingsExport(
            botName = "unsafe",
            strings = mapOf("pum_block_process_mode" to "invalid"),
            booleans = mapOf("pum_delete_only_mode" to true),
            ints = emptyMap(),
            floats = emptyMap(),
            stringSets = emptyMap(),
        )

        val imported = parseAndMigrateBotSettingsExport(unsafe.toJson())

        assertEquals("BLOCK", imported.strings["pum_block_process_mode"])
    }
}
