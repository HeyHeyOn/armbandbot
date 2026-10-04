package com.heyheyon.armbandbot

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class BotIsolationScheduleUiContractTest {
    private fun source(name: String) = listOf(File("app/src/main/java/com/example/armbandbot/$name"), File("src/main/java/com/example/armbandbot/$name")).first { it.isFile }.readText()

    @Test fun remoteIsBasicAndManagementFollowsIndependentAndSchedule() {
        val detail = source("BotDetailScreen.kt").substringAfter("Text(\"기본 탐색 설정\"")
        val remote = detail.indexOf("RemoteListSettingItem(")
        val independent = detail.indexOf("IndependentDbSettingsCard(")
        val schedule = detail.indexOf("BotRunScheduleSettingsCard(")
        val management = detail.indexOf("ManagementAutomationSettings(")
        val followup = detail.indexOf("Text(\"차단 후속 동작\"")
        assertTrue(remote >= 0 && independent > remote && schedule > independent)
        assertTrue(management > schedule && followup > management)
        assertFalse(detail.contains("게시글 자동화·원격 목록"))
        assertFalse(detail.contains("runScheduleStartMinute"))
        val entries = source("AutomationSettingsEntries.kt")
        assertTrue(entries.contains("Text(\"관리 자동화\""))
        assertTrue(entries.contains("ModernSettingItem(\"갤러리 설정 자동 갱신\""))
    }

    @Test fun inlineSettingsShareModernCardAndThemeContract() {
        val common = source("ui/CommonComponents.kt")
        assertTrue("Missing shared inline block", common.contains("fun ModernSettingsBlock("))
        val block = common.substringAfter("fun ModernSettingsBlock(").substringBefore("fun ModernSettingItem(")
        listOf("RoundedCornerShape(12.dp)", "defaultElevation = 0.dp", "padding(vertical = 4.dp)",
            "padding(16.dp)", "size(24.dp)", "width(16.dp)", "FontWeight.Bold", "15.sp", "12.sp",
            "colors.card", "colors.text", "colors.subText", "colors.iconTint", "ColumnScope.() -> Unit").forEach {
            assertTrue("Missing block token: $it", block.contains(it))
        }
        assertFalse(block.contains("clickable"))
        val detail = source("BotDetailScreen.kt").substringAfter("Text(\"기본 탐색 설정\"")
        assertTrue(detail.contains("IndependentDbSettingsCard(botId, botPref, isRunning, colors)"))
        assertTrue(detail.contains("BotRunScheduleSettingsCard(botPref, colors)"))
        val schedule = source("BotRunScheduleSettingsCard.kt")
        assertTrue(schedule.contains("ModernSettingsBlock("))
        assertTrue(schedule.contains("ModernSettingsSwitch("))
        assertTrue(schedule.contains("colors.subText"))
    }

    @Test fun scopeGuardsRemain() {
        val detail = source("BotDetailScreen.kt")
        val toggle = source("IndependentDbSettingsCard.kt")
        assertTrue(toggle.contains("!running && !preferences.getBoolean(\"is_running\", false)"))
        assertTrue(toggle.contains("!BotService.hasUnfinishedDatabaseWork(botId)"))
        assertTrue(toggle.contains("GlobalBotState.withDatabaseMaintenanceLock"))
        assertFalse(detail.contains("dao.replaceScopeBaseline("))
        assertFalse(detail.contains("showScopeBaselineDialog) {"))
        assertFalse(toggle.contains("deletePostsForScope"))
    }
    @Test fun legacyEqualPairIsDisplayedAndCannotEnableUntilIntentionalRepair() {
        for (minute in listOf(0, 300, 1439)) {
            val saved = mutableListOf<BotRunSchedule>()
            val loaded = loadBotRunSchedule(mapOf("run_schedule_enabled" to false,
                "run_schedule_start_minute" to minute, "run_schedule_end_minute" to minute))
            val state = BotRunScheduleEditorState(loaded, saved::add)
            assertEquals(minute, state.legacyEditor?.startMinute)
            assertEquals(minute, state.legacyEditor?.endMinute)
            state.changeEnabled(true)
            state.addWindow()
            state.editWindow(0, minute, minute)
            assertFalse(state.enabled)
            assertTrue(saved.isEmpty())
            assertEquals(minute, state.legacyEditor?.endMinute)
            val reopened = BotRunScheduleEditorState(loaded, saved::add)
            assertEquals(minute, reopened.legacyEditor?.startMinute)
            assertEquals(minute, reopened.legacyEditor?.endMinute)
            assertTrue(saved.isEmpty())
            val end = (minute + 60) % 1440
            state.editWindow(0, minute, end)
            assertNull(state.legacyEditor)
            assertEquals(BotRunWindow(minute, end), saved.single().windows.single())
            assertFalse(saved.single().enabled)
            state.changeEnabled(true)
            assertTrue(saved.last().enabled)
        }
    }

    private fun initial() = BotRunScheduleLoadResult.Valid(BotRunSchedule(true, listOf(BotRunWindow(60, 120))))

    @Test fun addRemoveReindexAndMasterOffPersistOrderedList() {
        val saved = mutableListOf<BotRunSchedule>()
        val state = BotRunScheduleEditorState(initial(), saved::add)
        state.addWindow()
        assertEquals(listOf(BotRunWindow(60,120), BotRunWindow(540,1080)), state.windows)
        state.changeEnabled(false)
        assertEquals(2, saved.last().windows.size)
        assertFalse(saved.last().enabled)
        state.removeWindow(0)
        assertEquals(BotRunWindow(540,1080), state.windows.single())
        state.removeWindow(0)
        assertEquals(1, state.windows.size)
        val reopened = BotRunScheduleEditorState(BotRunScheduleLoadResult.Valid(saved.last()), {})
        assertEquals(state.windows, reopened.windows)
    }

    @Test fun equalEndpointsRejectedEvenWhenDisabledBeforePersistence() {
        val saved = mutableListOf<BotRunSchedule>()
        val state = BotRunScheduleEditorState(initial(), saved::add)
        state.changeEnabled(false)
        state.editWindow(0, 60, 60)
        assertEquals(1, saved.size)
        assertNotNull(state.error)
        assertEquals(BotRunWindow(60,120), state.windows.single())
    }

    @Test fun invalidCanonicalRequiresExplicitRepair() {
        val saved = mutableListOf<BotRunSchedule>()
        val state = BotRunScheduleEditorState(BotRunScheduleLoadResult.Error(true, "broken", "invalid"), saved::add)
        assertTrue(state.enabled)
        assertNotNull(state.error)
        state.changeEnabled(false)
        assertTrue(saved.isEmpty())
        state.repair()
        assertEquals(listOf(BotRunWindow(540,1080)), saved.single().windows)
        assertTrue(saved.single().enabled)
        assertNull(state.error)
    }

    @Test fun capIs64NotThreeAndOvernightAccepted() {
        val state = BotRunScheduleEditorState(initial(), {})
        repeat(70) { state.addWindow() }
        assertEquals(64, state.windows.size)
        state.editWindow(0, 1320, 120)
        assertEquals(BotRunWindow(1320,120), state.windows.first())
    }
}
