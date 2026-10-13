package com.heyheyon.armbandbot

import org.junit.Assert.*
import org.junit.Test

class AllPostsRecheckTest {
    @Test fun enabledRechecksUnchangedOrdinaryAndPumPostsOnEveryCycle() {
        for (pum in listOf(false, true)) {
            repeat(3) {
                assertTrue(shouldRecheckPost(2, 2, "동일한 제목", "동일한 제목",
                    hasPumListMarker = pum, allPostsRecheckEveryCycle = true))
            }
        }
    }

    @Test fun disabledKeepsExistingChangeDetectionAndIndependentPumOption() {
        assertFalse(shouldRecheckPost(2, 2, "동일한 제목", "동일한 제목"))
        assertTrue(shouldRecheckPost(2, 3, "동일한 제목", "동일한 제목"))
        assertTrue(shouldRecheckPost(2, 2, "이전 제목", "새 제목"))
        assertTrue(shouldRecheckPost(2, 2, "제목", "제목", pumRecheckEveryCycle = true, hasPumListMarker = true))
        assertFalse(shouldRecheckPost(2, 2, "제목", "제목", pumRecheckEveryCycle = true, hasPumListMarker = false))
    }

    @Test fun repeatedInspectionIsNotConsumedByTheOneTimePolicySweep() {
        val state = AutomationRecheckState().apply { update("unchanged-policy") }
        for (type in listOf("M", "MI")) {
            val key = PostKey(type, "fixture", "1")
            repeat(3) {
                state.mark(key)
                assertFalse(state.needs(key))
                assertTrue(shouldRecheckPost(0, 0, "같은 제목", "같은 제목", allPostsRecheckEveryCycle = true))
            }
        }
    }

    @Test fun forceRecheckPreventsHeldRowsFromSkippingDetailAdmission() {
        assertTrue(shouldSkipPumHoldPreflight(true, true, true, true, true, true))
        assertFalse(shouldSkipPumHoldPreflight(true, true, true, true, true, true, otherForcedRecheck = true))
        assertTrue(shouldSkipYudongDcMediaHoldPreflight(true, true, true, true, true, false))
        assertFalse(shouldSkipYudongDcMediaHoldPreflight(true, true, true, true, true, true))
    }

    @Test fun migrationDefaultsOffWithoutChangingTheExistingPumChoice() {
        for (pum in listOf(false, true)) {
            val migrated = migrateBotSettingsSnapshot(mapOf("pum_recheck_every_cycle" to pum))
            assertEquals(false, migrated[ALL_POSTS_RECHECK_KEY])
            assertEquals(pum, migrated["pum_recheck_every_cycle"])
        }
    }

    @Test fun realExportImportAndBotCopyPreserveBothValuesAndExistingScanLimits() {
        for (enabled in listOf(false, true)) {
            val prefs = MemoryPreferences()
            prefs.edit().putBoolean(ALL_POSTS_RECHECK_KEY, enabled)
                .putBoolean("pum_recheck_every_cycle", !enabled).putInt("scan_page_count", 2)
                .putFloat("delay_post_min_sec", 3f).putBoolean("is_running", true).commit()
            val imported = prepareImportedSettingsForNewBot(parseAndMigrateBotSettingsExport(exportBotSettings(prefs).toJson()))
            val copied = prepareCopiedBotSettingsSnapshot(prefs.all, "복사된 봇")
            for (values in listOf(imported, copied)) {
                assertEquals(enabled, values[ALL_POSTS_RECHECK_KEY])
                assertEquals(!enabled, values["pum_recheck_every_cycle"])
                assertEquals(2, values["scan_page_count"])
                assertEquals(3f, values["delay_post_min_sec"])
                assertEquals(false, values["is_running"])
            }
        }
    }
}
