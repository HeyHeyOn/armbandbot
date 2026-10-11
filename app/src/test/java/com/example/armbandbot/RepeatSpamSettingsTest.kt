package com.heyheyon.armbandbot

import org.junit.Assert.*
import org.junit.Test

class RepeatSpamSettingsTest {
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
