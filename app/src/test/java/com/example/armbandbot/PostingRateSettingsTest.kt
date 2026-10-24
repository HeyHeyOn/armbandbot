package com.heyheyon.armbandbot
import org.junit.Assert.*
import org.junit.Test

class PostingRateSettingsTest {
    private fun restore(p:MemoryPreferences):MemoryPreferences {
        val values=prepareImportedSettingsForNewBot(parseAndMigrateBotSettingsExport(exportBotSettings(p).toJson()))
        return MemoryPreferences().apply { edit().apply { values.forEach { (k,v) -> when(v) {
            is Boolean->putBoolean(k,v);is Int->putInt(k,v);is String->putString(k,v);is Long->putLong(k,v)
            is Float->putFloat(k,v);is Set<*>->@Suppress("UNCHECKED_CAST") putStringSet(k,v as Set<String>)
        } } }.commit() }
    }
    @Test fun olderSettingsKeepContentAndDoNotEnableNewActions() {
        val p=MemoryPreferences();p.edit().putBoolean(REPEAT_ENABLED_KEY,true).putInt(REPEAT_COUNT_KEY,7).commit()
        val restored=restore(p)
        assertEquals(repeatSpamSettings(p),repeatSpamSettings(restored));assertTrue(repeatSpamSettings(restored).contentEnabled)
        assertFalse(postingRateSettings(restored).enabled);assertEquals("HOLD",postingRateActionView(restored).mode)
        assertEquals("DELETE",repeatActionView(restored).mode)
    }
    @Test fun allNewSettingsAndInheritedActionsRoundTrip() {
        val p=MemoryPreferences();p.edit().putBoolean(REPEAT_CONTENT_ENABLED_KEY,false).putBoolean(RATE_ENABLED_KEY,true)
            .putBoolean(RATE_YUDONG_KEY,false).putInt(RATE_IP_SECONDS_KEY,121).putInt(RATE_UID_SECONDS_KEY,91)
            .putInt(RATE_IP_COUNT_KEY,17).putInt(RATE_UID_COUNT_KEY,8).putString(RATE_MODE_KEY,"BLOCK")
            .putInt("block_duration_hours",24).putBoolean("delete_post_on_block",false).commit()
        val restored=restore(p)
        assertEquals(repeatSpamSettings(p),repeatSpamSettings(restored));assertEquals(postingRateActionView(p),postingRateActionView(restored))
        assertFalse(restored.contains(RATE_DURATION_KEY));assertFalse(restored.contains(RATE_DELETE_KEY))
        restored.edit().putInt("block_duration_hours",168).commit();assertEquals(168,postingRateActionView(restored).durationHours)
        p.edit().putBoolean(RATE_CUSTOM_KEY,false).putString("block_process_mode","DELETE").commit()
        assertEquals(postingRateActionView(p),postingRateActionView(restore(p)))
    }
    @Test fun policyAndScanRevisionChangeForEveryNewCriterion() {
        val p=MemoryPreferences()
        val edits=listOf<Pair<String,Any>>(REPEAT_CONTENT_ENABLED_KEY to false,RATE_ENABLED_KEY to true,RATE_YUDONG_KEY to false,
            RATE_KKANG_KEY to false,RATE_IP_SECONDS_KEY to 91,RATE_UID_SECONDS_KEY to 92,RATE_IP_COUNT_KEY to 20,RATE_UID_COUNT_KEY to 21,
            RATE_MODE_KEY to "DELETE",RATE_CUSTOM_KEY to false,RATE_REASON_KEY to "custom")
        for((k,v) in edits) {
            val before=repeatSpamPolicyRevision(snapshotScanPreferences(p));val scan=automationPolicyRevision(snapshotScanPreferences(p))
            p.edit().apply { when(v) { is Boolean->putBoolean(k,v);is Int->putInt(k,v);is String->putString(k,v) } }.commit()
            assertNotEquals(k,before,repeatSpamPolicyRevision(snapshotScanPreferences(p)))
            assertNotEquals(k,scan,automationPolicyRevision(snapshotScanPreferences(p)))
        }
    }
}
