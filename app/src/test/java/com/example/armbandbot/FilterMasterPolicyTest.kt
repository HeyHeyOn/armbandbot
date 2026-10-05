package com.heyheyon.armbandbot

import org.junit.Assert.*
import org.junit.Test

class FilterMasterPolicyTest {
    @Test fun upgradeKeepsBothFiltersEnabledAndRetainsListsAndChildSwitches() {
        val old = mapOf("normal_text" to "사과\n바나나", "is_yudong_post_block" to true)
        val upgraded = migrateBotSettingsSnapshot(old)
        assertEquals(true, upgraded[WORD_FILTER_ENABLED_KEY])
        assertEquals(true, upgraded[YUDONG_FILTER_ENABLED_KEY])
        assertEquals("사과\n바나나", upgraded["normal_text"])
        assertEquals(true, upgraded["is_yudong_post_block"])
    }

    @Test fun masterOffGatesMergedLocalRemoteWordsAndAllYudongOptionsWithoutDeletingThem() {
        val p = MemoryPreferences()
        val keys = listOf("post", "comment", "image", "dc_media", "voice").map { "is_yudong_${it}_block" }
        keys.forEach { p.edit().putBoolean(it, true).commit() }
        p.edit().putString("normal_text", "local").commit()
        val merged = listOf("local", "remote")
        assertEquals(merged, activeWordFilterValues(p, merged))
        keys.forEach { assertTrue(activeYudongFilterOption(p, it)) }
        setFilterMasterEnabled(p, WORD_FILTER_ENABLED_KEY, false)
        setFilterMasterEnabled(p, YUDONG_FILTER_ENABLED_KEY, false)
        assertTrue(activeWordFilterValues(p, merged).isEmpty())
        keys.forEach { assertFalse(activeYudongFilterOption(p, it)); assertTrue(p.getBoolean(it, false)) }
        assertEquals("local", p.getString("normal_text", null))
        val rechecks = AutomationRecheckState()
        val key = PostKey("M", "laboratory1", "1")
        rechecks.update(automationPolicyRevision(p)); rechecks.mark(key)
        assertFalse(rechecks.needs(key))
        setFilterMasterEnabled(p, WORD_FILTER_ENABLED_KEY, true)
        setFilterMasterEnabled(p, YUDONG_FILTER_ENABLED_KEY, true)
        assertEquals(merged, activeWordFilterValues(p, merged))
        keys.forEach { assertTrue(activeYudongFilterOption(p, it)) }
        assertTrue(p.getBoolean("yudong_dc_media_recheck_pending", false))
        rechecks.update(automationPolicyRevision(p)); assertTrue(rechecks.needs(key))
        assertFalse(prepareCopiedBotSettingsSnapshot(p.all, "copy").containsKey(FILTER_MASTER_REVISION_KEY))
    }

    @Test fun disabledMasterSurvivesMigrationCopyAndImportWithoutErasingSettings() {
        val old = mapOf(WORD_FILTER_ENABLED_KEY to false, YUDONG_FILTER_ENABLED_KEY to false,
            "normal_text" to "사과", "is_yudong_post_block" to true)
        val copied = prepareCopiedBotSettingsSnapshot(migrateBotSettingsSnapshot(old), "copy")
        assertEquals(false, copied[WORD_FILTER_ENABLED_KEY])
        assertEquals(false, copied[YUDONG_FILTER_ENABLED_KEY])
        assertEquals("사과", copied["normal_text"])
        assertEquals(true, copied["is_yudong_post_block"])
        val backup = BotSettingsExport(botName="off",strings=mapOf("normal_text" to "사과"),
            booleans=mapOf(WORD_FILTER_ENABLED_KEY to false,YUDONG_FILTER_ENABLED_KEY to false,"is_yudong_post_block" to true),
            ints=emptyMap(),floats=emptyMap(),stringSets=emptyMap())
        val restored = prepareImportedSettingsForNewBot(parseAndMigrateBotSettingsExport(backup.toJson()))
        assertEquals(false, restored[WORD_FILTER_ENABLED_KEY])
        assertEquals(false, restored[YUDONG_FILTER_ENABLED_KEY])
        assertEquals(true, restored["is_yudong_post_block"])
        assertTrue(defaultBooleanValue(WORD_FILTER_ENABLED_KEY))
        assertTrue(defaultBooleanValue(YUDONG_FILTER_ENABLED_KEY))
    }
}
