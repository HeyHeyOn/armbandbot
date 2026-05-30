package com.heyheyon.armbandbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BotSettingsRuntimeMigrationTest {
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
        assertFalse(migrated["is_search_mode"] as Boolean)
        assertFalse(migrated["is_ai_filter_mode"] as Boolean)
        assertTrue(migrated["noti_master"] as Boolean)
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
