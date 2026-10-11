package com.heyheyon.armbandbot

import org.junit.Assert.*
import org.junit.Test

class RepeatSpamSettingsTest {
    private fun restore(export: BotSettingsExport): MemoryPreferences = MemoryPreferences().apply {
        val values = prepareImportedSettingsForNewBot(parseAndMigrateBotSettingsExport(export.toJson()))
        edit().apply {
            values.forEach { (key, value) -> when (value) {
                is String -> putString(key, value)
                is Boolean -> putBoolean(key, value)
                is Int -> putInt(key, value)
                is Long -> putLong(key, value)
                is Float -> putFloat(key, value)
                is Set<*> -> @Suppress("UNCHECKED_CAST") putStringSet(key, value as Set<String>)
            } }
        }.commit()
    }
    @Test fun inheritedBlockSettingsSurviveRealExportAndImport() {
        val p = MemoryPreferences()
        p.edit().putString(REPEAT_PROCESS_MODE_KEY, "BLOCK")
            .putInt("block_duration_hours", 168).putBoolean("delete_post_on_block", false).commit()
        val expected = repeatActionView(p)
        val restored = restore(exportBotSettings(p))
        assertEquals(expected, repeatActionView(restored))
        assertFalse(restored.contains(REPEAT_DURATION_KEY))
        assertFalse(restored.contains(REPEAT_DELETE_POST_KEY))
        restored.edit().putInt("block_duration_hours", 24).commit()
        assertEquals(24, repeatActionView(restored).durationHours)
    }
    @Test fun defaultActionChoiceSurvivesRealExportAndImport() {
        listOf("HOLD", "DELETE", "BLOCK", "LEGACY_DELETE").forEach { mode ->
            val p = MemoryPreferences()
            p.edit().putBoolean(REPEAT_CUSTOM_ACTION_KEY, false).apply {
                if (mode == "LEGACY_DELETE") putBoolean("delete_only_mode", true)
                else putString("block_process_mode", mode)
            }.commit()
            val expected = repeatActionView(p)
            assertEquals(mode, expected, repeatActionView(restore(exportBotSettings(p))))
        }
    }
    @Test fun oldFilesDoNotInventRepeatBlockOverrides() {
        val export = BotSettingsExport(botName = "old", strings = mapOf(REPEAT_PROCESS_MODE_KEY to "BLOCK"),
            booleans = mapOf("delete_post_on_block" to false), ints = mapOf("block_duration_hours" to 24),
            floats = emptyMap(), stringSets = emptyMap())
        assertEquals(RepeatActionView("BLOCK", 24, false, false), repeatActionView(restore(export)))
    }
    @Test fun legacySampleCountIsNotMigratedAsMinutes() {
        val migrated=migrateBotSettingsSnapshot(mapOf("spam_burst_window_minutes" to 19, REPEAT_ENABLED_KEY to true))
        assertEquals(19,migrated["spam_burst_window_minutes"])
        assertEquals(10,migrated[REPEAT_WINDOW_KEY]); assertEquals(4,migrated[REPEAT_COUNT_KEY])
        assertEquals(true,migrated[REPEAT_ENABLED_KEY])
    }
    @Test fun allUserCriteriaRoundTripAndRuntimeStateDoesNotExport() {
        val p=MemoryPreferences()
        p.edit().putBoolean(REPEAT_ENABLED_KEY,true).putBoolean(REPEAT_OBSERVE_KEY,true)
            .putInt(REPEAT_WINDOW_KEY,17).putInt(REPEAT_COUNT_KEY,7).putInt(REPEAT_SIMILARITY_KEY,96).commit()
        val export=BotSettingsExport(botName="test", strings=emptyMap(),
            booleans=EXPORTABLE_BOOLEAN_KEYS.associateWith { p.getBoolean(it,defaultBooleanValue(it)) },
            ints=EXPORTABLE_INT_KEYS.associateWith { p.getInt(it,defaultIntValue(it)) }, floats=emptyMap(),stringSets=emptyMap())
        val decoded=parseAndMigrateBotSettingsExport(export.toJson())
        assertEquals(17,decoded.ints[REPEAT_WINDOW_KEY]); assertEquals(7,decoded.ints[REPEAT_COUNT_KEY])
        assertEquals(96,decoded.ints[REPEAT_SIMILARITY_KEY]); assertEquals(true,decoded.booleans[REPEAT_OBSERVE_KEY])
        assertTrue(decoded.booleans[REPEAT_YUDONG_KEY]!!); assertTrue(decoded.booleans[REPEAT_KKANG_KEY]!!)
        assertFalse(export.toJson().toString().contains(REPEAT_REVISION_KEY))
    }
    @Test fun repeatActionSettingsRoundTrip() {
        val p=MemoryPreferences()
        p.edit().putBoolean(REPEAT_CUSTOM_ACTION_KEY,true).putString(REPEAT_PROCESS_MODE_KEY,"BLOCK").putInt(REPEAT_DURATION_KEY,168)
            .putBoolean(REPEAT_DELETE_POST_KEY,false).putString(REPEAT_REASON_KEY,"도배 금지").commit()
        val export=BotSettingsExport(botName="test",
            strings=EXPORTABLE_STRING_KEYS.mapNotNull { k -> p.getString(k,null)?.let { k to it } }.toMap(),
            booleans=EXPORTABLE_BOOLEAN_KEYS.associateWith { p.getBoolean(it,defaultBooleanValue(it)) },
            ints=EXPORTABLE_INT_KEYS.associateWith { p.getInt(it,defaultIntValue(it)) }, floats=emptyMap(),stringSets=emptyMap())
        val decoded=parseAndMigrateBotSettingsExport(export.toJson())
        assertEquals("BLOCK",decoded.strings[REPEAT_PROCESS_MODE_KEY]); assertEquals("도배 금지",decoded.strings[REPEAT_REASON_KEY])
        assertEquals(168,decoded.ints[REPEAT_DURATION_KEY]); assertEquals(false,decoded.booleans[REPEAT_DELETE_POST_KEY])
        assertEquals(true,decoded.booleans[REPEAT_CUSTOM_ACTION_KEY])
    }
    @Test fun untouchedActionDefaultsExportAsCustomOn() {
        assertTrue(defaultBooleanValue(REPEAT_CUSTOM_ACTION_KEY)); assertTrue(defaultBooleanValue(REPEAT_DELETE_POST_KEY))
        assertEquals(6, defaultIntValue(REPEAT_DURATION_KEY))
    }
    @Test fun protectionAndSettingsChangesInvalidateScanAndPendingPolicy() {
        val p=MemoryPreferences()
        val changes=listOf<()->Unit>(
            {p.edit().putInt(REPEAT_COUNT_KEY,2).commit();Unit},
            {p.edit().putInt(REPEAT_WINDOW_KEY,20).commit();Unit},
            {p.edit().putBoolean(REPEAT_ENABLED_KEY,true).commit();Unit},
            {p.edit().putBoolean(REPEAT_OBSERVE_KEY,true).commit();Unit},
            {persistOrderedMultilineText(p,"nickname_whitelist","보호닉");Unit},
            {persistOrderedMultilineText(p,"user_whitelist","1.2");Unit},
            {persistOrderedMultilineText(p,"block_exempt_post_numbers","10");Unit},
            {p.edit().putString("target_urls","https://gall.dcinside.com/mini/board/lists/?id=other").commit();Unit},
        )
        changes.forEach { change -> val before=automationPolicyRevision(snapshotScanPreferences(p)); change(); assertNotEquals(before,automationPolicyRevision(snapshotScanPreferences(p))) }
    }
    @Test fun offOnBetweenCyclesStillInvalidatesAccumulatedEvidence() {
        val p=MemoryPreferences(); p.edit().putBoolean(REPEAT_ENABLED_KEY,true).commit()
        val before=repeatSpamPolicyRevision(snapshotScanPreferences(p))
        p.edit().putBoolean(REPEAT_ENABLED_KEY,false).repeatSpamChanged().commit()
        p.edit().putBoolean(REPEAT_ENABLED_KEY,true).repeatSpamChanged().commit()
        assertNotEquals(before,repeatSpamPolicyRevision(snapshotScanPreferences(p)))
    }
}
