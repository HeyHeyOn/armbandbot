package com.heyheyon.armbandbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BotSettingsRuntimeMigrationTest {
    @Test
    fun appVersionComesFromGradleVersionName() {
        assertEquals(BuildConfig.VERSION_NAME, ARMBANDBOT_APP_VERSION)
    }

    @Test
    fun oldBotPreferencesAreCoercedAndStampedWithCurrentVersion() {
        val migrated = migrateBotSettingsSnapshot(
            mapOf(
                "bot_name" to "구형 봇",
                "is_url_filter_mode" to "true",
                "scan_page_count" to "3",
                "delay_post_min_sec" to "1.5",
                "url_whitelist" to "dcinside.com\nyoutu.be",
                "saved_cookie" to "ci_c=keep_me"
            )
        )

        assertEquals(true, migrated["is_url_filter_mode"])
        assertEquals(3, migrated["scan_page_count"])
        assertEquals(1.5f, migrated["delay_post_min_sec"])
        assertEquals(setOf("dcinside.com", "youtu.be"), migrated["url_whitelist"])
        assertEquals("ci_c=keep_me", migrated["saved_cookie"])
        assertEquals(BOT_SETTINGS_CURRENT_SCHEMA_VERSION, migrated[BOT_PREF_SCHEMA_VERSION_KEY])
        assertEquals(ARMBANDBOT_APP_VERSION, migrated[BOT_PREF_APP_VERSION_KEY])
    }

    @Test
    fun migrationBackfillsMissingCurrentDefaultsWithoutEnablingFilters() {
        val migrated = migrateBotSettingsSnapshot(mapOf("bot_name" to "최소 봇"))

        assertEquals("search_subject_memo", migrated["search_type"])
        assertEquals("separate", migrated["kkang_detection_mode"])
        assertEquals("커뮤니티 규칙 위반", migrated["block_reason_text"])
        assertEquals(1, migrated["scan_page_count"])
        assertEquals(6, migrated["block_duration_hours"])
        assertEquals(defaultBotUrlWhitelist(), migrated["url_whitelist"])
        assertEquals(emptySet<String>(), migrated["nickname_bypass_blacklist"])
        assertEquals(emptySet<String>(), migrated["special_char_whitelist"])
        assertFalse(migrated["is_special_char_filter_mode"] as Boolean)
        assertFalse(migrated["bypass_ignore_case_enabled"] as Boolean)
        assertFalse(migrated["bypass_unicode_normalization_enabled"] as Boolean)
        assertFalse(migrated["is_search_mode"] as Boolean)
        assertFalse(migrated["is_ai_filter_mode"] as Boolean)
        assertFalse(migrated["is_pum_source_filter_mode"] as Boolean)
        assertFalse(migrated["pum_recheck_every_cycle"] as Boolean)
        assertFalse(migrated["pum_block_all_posts"] as Boolean)
        assertFalse(migrated["pum_use_custom_action_config"] as Boolean)
        assertFalse(migrated["pum_delete_only_mode"] as Boolean)
        assertTrue(migrated["pum_delete_post_on_block"] as Boolean)
        assertEquals("BLOCK", migrated["pum_block_process_mode"])
        assertEquals(6, migrated["pum_block_duration_hours"])
        assertFalse(migrated.containsKey("pum_block_reason_text"))
        assertTrue(migrated["noti_master"] as Boolean)
    }

    @Test
    fun migrationPreservesPumSettingsAndMakesThemExportable() {
        val migrated = migrateBotSettingsSnapshot(
            mapOf(
                "is_pum_source_filter_mode" to true,
                "pum_recheck_every_cycle" to true,
                "pum_block_all_posts" to true,
                "pum_use_custom_action_config" to true,
                "pum_delete_only_mode" to true,
                "pum_delete_post_on_block" to false,
                "pum_block_process_mode" to "HOLD",
                "pum_block_reason_text" to "펌 출처 위반",
                "pum_block_duration_hours" to 24,
            )
        )

        assertTrue(migrated["is_pum_source_filter_mode"] as Boolean)
        assertTrue(migrated["pum_recheck_every_cycle"] as Boolean)
        assertTrue(migrated["pum_block_all_posts"] as Boolean)
        assertTrue(migrated["pum_use_custom_action_config"] as Boolean)
        assertTrue(migrated["pum_delete_only_mode"] as Boolean)
        assertFalse(migrated["pum_delete_post_on_block"] as Boolean)
        assertEquals("HOLD", migrated["pum_block_process_mode"])
        assertEquals("펌 출처 위반", migrated["pum_block_reason_text"])
        assertEquals(24, migrated["pum_block_duration_hours"])
        assertTrue("is_pum_source_filter_mode" in EXPORTABLE_BOOLEAN_KEYS)
        assertTrue("pum_recheck_every_cycle" in EXPORTABLE_BOOLEAN_KEYS)
    }

    @Test
    fun migrationNormalizesUnsafePumActionSettings() {
        val unsafe = migrateBotSettingsSnapshot(
            mapOf(
                "pum_block_process_mode" to "delete",
                "pum_block_duration_hours" to 50_000,
            )
        )
        val numericLow = migrateBotSettingsSnapshot(
            mapOf("pum_block_process_mode" to "HOLD", "pum_block_duration_hours" to "-3")
        )

        assertEquals("BLOCK", unsafe["pum_block_process_mode"])
        assertEquals(744, unsafe["pum_block_duration_hours"])
        assertEquals("HOLD", numericLow["pum_block_process_mode"])
        assertEquals(1, numericLow["pum_block_duration_hours"])
    }

    @Test
    fun migrationUsesExplicitLegacyDeleteOnlyWhenProcessModeIsMissing() {
        val migrated = migrateBotSettingsSnapshot(mapOf("pum_delete_only_mode" to true))

        assertEquals("DELETE", migrated["pum_block_process_mode"])
        assertEquals(6, migrated["pum_block_duration_hours"])
    }

    @Test
    fun migrationFailsSafeWhenExplicitProcessModeIsInvalidDespiteLegacyDeleteOnly() {
        val migrated = migrateBotSettingsSnapshot(
            mapOf(
                "pum_block_process_mode" to "invalid",
                "pum_delete_only_mode" to true,
            )
        )

        assertEquals("BLOCK", migrated["pum_block_process_mode"])
    }

    @Test
    fun bypassEnhancementTogglesAreExportable() {
        assertTrue("bypass_ignore_case_enabled" in EXPORTABLE_BOOLEAN_KEYS)
        assertTrue("bypass_unicode_normalization_enabled" in EXPORTABLE_BOOLEAN_KEYS)
    }

    @Test
    fun migrationPreservesRunningStateForLiveBots() {
        val migrated = migrateBotSettingsSnapshot(
            mapOf(
                "is_running" to true,
                "should_restore_after_restart" to true
            )
        )

        assertEquals(true, migrated["is_running"])
        assertEquals(true, migrated["should_restore_after_restart"])
    }
}
