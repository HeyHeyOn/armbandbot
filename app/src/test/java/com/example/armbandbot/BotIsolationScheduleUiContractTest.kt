package com.heyheyon.armbandbot

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BotIsolationScheduleUiContractTest {
    private fun source(name: String): String {
        val candidates = listOf(
            File("app/src/main/java/com/example/armbandbot/$name"),
            File("src/main/java/com/example/armbandbot/$name"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()?.replace("\r\n", "\n")
            ?: error("$name not found")
    }

    @Test
    fun isolationAndScheduleStateUseOneClosureCapture() {
        val detail = source("BotDetailScreen.kt")
        val screen = detail.substringAfter("fun BotDetailScreen(")
        val fields = listOf(
            "independentScanStateEnabled", "showScopeBaselineDialog", "scopeBaselineChoice",
            "hasPrivateScopeBaseline", "isScopeTransitioning", "runScheduleEnabled",
            "runScheduleStartMinute", "runScheduleEndMinute",
        )
        assertTrue(detail.contains("private class BotIsolationScheduleUiState(botPref: SharedPreferences)"))
        assertTrue(screen.contains("val isolationScheduleUi = remember { BotIsolationScheduleUiState(botPref) }"))
        fields.forEach { field ->
            assertTrue("$field must remain observable holder state", detail.contains("var $field by mutableStateOf("))
            assertTrue("$field must be accessed through the holder, not captured separately",
                Regex("(?<![\\w.])$field\\b").find(screen) == null)
            assertTrue(screen.contains("isolationScheduleUi.$field"))
        }
    }

    @Test
    fun botDetailExposesIsolationAndValidatedScheduleControls() {
        val detail = source("BotDetailScreen.kt")
        assertTrue(detail.contains("독립 검사 기록"))
        assertTrue(detail.contains("다른 봇의 검사 완료 기록과 분리"))
        assertTrue(detail.contains("independent_scan_state"))
        assertTrue(detail.contains("작동 시간대"))
        assertTrue(detail.contains("run_schedule_enabled"))
        assertTrue(detail.contains("run_schedule_start_minute"))
        assertTrue(detail.contains("run_schedule_end_minute"))
        assertTrue(detail.contains("botPref.getInt(\"run_schedule_end_minute\", 1439)"))
        assertTrue(detail.contains("시작과 종료 시각은 달라야"))
    }

    @Test
    fun scopeTransitionControlsAndConfirmationRejectRunningBots() {
        val detail = source("BotDetailScreen.kt")
        val toggle = detail.substringAfter("checked = isolationScheduleUi.independentScanStateEnabled,").substringBefore("onCheckedChange")
        assertTrue(toggle.contains("enabled = !isRunning && !isolationScheduleUi.isScopeTransitioning"))
        val runToggle = detail.substringAfter("checked = isRunning,").substringBefore("onCheckedChange")
        assertTrue("A baseline transition must not race a start request", runToggle.contains("enabled = !isolationScheduleUi.isScopeTransitioning"))
        val confirmation = detail.substringAfter("if (isolationScheduleUi.showScopeBaselineDialog)").substringBefore("AnimatedContent(")
        assertTrue(confirmation.contains("TextButton(enabled = !isRunning && !isolationScheduleUi.isScopeTransitioning"))
        val freshCheck = confirmation.indexOf("check(!botPref.getBoolean(\"is_running\", false))")
        val mutation = confirmation.indexOf("dao.replaceScopeBaseline(")
        assertTrue("Confirm must check persisted running state before mutating the baseline", freshCheck >= 0 && freshCheck < mutation)
    }

    @Test
    fun botListShowsWaitingStateWithoutPretendingTheBotIsStopped() {
        val list = source("BotListScreen.kt")
        assertTrue(list.contains("예약 대기"))
        assertTrue(list.contains("run_schedule_enabled"))
        assertTrue(list.contains("evaluateSchedule"))
    }

    @Test
    fun botCopyAndImportDoNotInheritPrivateScopeOrRunningState() {
        val main = source("MainActivity.kt")
        val transfer = source("BotSettingsTransfer.kt")
        assertTrue(main.contains("prepareCopiedBotSettingsSnapshot("))
        assertTrue(transfer.contains("independent_scan_state_enabled"))
        assertTrue(transfer.contains("is_running"))
        assertTrue(transfer.contains("should_restore_after_restart"))
    }

    @Test
    fun dashboardShowsScopeAndActorAttribution() {
        val dashboard = source("DbDashboardScreen.kt")
        assertTrue(dashboard.contains("scopeId"))
        assertTrue(dashboard.contains("actorBotId"))
        assertTrue(dashboard.contains("검사 범위"))
        assertTrue(dashboard.contains("조치 봇"))
    }
}
