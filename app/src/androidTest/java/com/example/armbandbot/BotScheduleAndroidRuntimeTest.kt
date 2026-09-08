package com.heyheyon.armbandbot

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneId

/**
 * Run on both API 24 (core-library desugaring) and API 35.
 * Calls the real evaluator and work gate without clocks, sleeps, services, or network access.
 */
@RunWith(AndroidJUnit4::class)
class BotScheduleAndroidRuntimeTest {
    private val seoul = ZoneId.of("Asia/Seoul")
    private val berlin = ZoneId.of("Europe/Berlin")

    @Test fun canonicalInvalidGateRecoversAfterAtomicSave() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val name = "schedule_runtime_${java.util.UUID.randomUUID()}"
        val prefs = context.getSharedPreferences(name, android.content.Context.MODE_PRIVATE)
        try {
            prefs.edit().putBoolean("run_schedule_enabled", true).putString(RUN_SCHEDULE_WINDOWS_JSON_KEY, "broken").commit()
            val now = Instant.parse("2026-09-07T03:00:00Z").toEpochMilli()
            org.junit.Assert.assertFalse(evaluateBotWorkGate(now, seoul, loadBotRunSchedule(prefs)).mayStartNetworkOrAction)
            val fullDay = BotRunSchedule(true, listOf(BotRunWindow(720, 0), BotRunWindow(0, 720)))
            saveBotRunSchedule(prefs, fullDay)
            assertTrue(evaluateBotWorkGate(now, seoul, loadBotRunSchedule(prefs)).mayStartNetworkOrAction)
            assertEquals("실행 중", botScheduleStatus(true, true, now, seoul, loadBotRunSchedule(prefs)))
        } finally { context.deleteSharedPreferences(name) }
    }

    @Test
    fun disabledScheduleIsActiveWithBoundedWorkGate() {
        assertEvaluation(
            epoch("2026-09-07T00:00:00Z", 1_788_739_200_000L),
            seoul, BotRunSchedule.disabled(), ScheduleState.ACTIVE, Long.MAX_VALUE,
        )
    }

    @Test
    fun daytimeStartIsInclusiveAndEndIsExclusiveToTheMillisecond() {
        val schedule = BotRunSchedule(true, 540, 1080) // 09:00–18:00 Seoul
        val start = epoch("2026-09-07T00:00:00Z", 1_788_739_200_000L)
        val end = epoch("2026-09-07T09:00:00Z", 1_788_771_600_000L)

        assertEvaluation(start - 1L, seoul, schedule, ScheduleState.WAITING, 1L)
        assertEvaluation(start, seoul, schedule, ScheduleState.ACTIVE, 32_400_000L)
        assertEvaluation(end - 1L, seoul, schedule, ScheduleState.ACTIVE, 1L)
        assertEvaluation(end, seoul, schedule, ScheduleState.WAITING, 54_000_000L)
    }

    @Test
    fun overnightWindowStaysActiveAcrossMidnightAndStopsAtEnd() {
        val schedule = BotRunSchedule(true, 1320, 360) // 22:00–06:00 Seoul
        val start = epoch("2026-09-07T13:00:00Z", 1_788_786_000_000L)
        val midnight = epoch("2026-09-07T15:00:00Z", 1_788_793_200_000L)
        val end = epoch("2026-09-07T21:00:00Z", 1_788_814_800_000L)

        assertEvaluation(start - 1L, seoul, schedule, ScheduleState.WAITING, 1L)
        assertEvaluation(start, seoul, schedule, ScheduleState.ACTIVE, 28_800_000L)
        assertEvaluation(midnight - 1L, seoul, schedule, ScheduleState.ACTIVE, 21_600_001L)
        assertEvaluation(midnight, seoul, schedule, ScheduleState.ACTIVE, 21_600_000L)
        assertEvaluation(end - 1L, seoul, schedule, ScheduleState.ACTIVE, 1L)
        assertEvaluation(end, seoul, schedule, ScheduleState.WAITING, 57_600_000L)
    }

    @Test
    fun berlinSpringGapUsesTransitionInstantForNonexistentStart() {
        val schedule = BotRunSchedule(true, 150, 300) // 02:30–05:00 Berlin
        val before = epoch("2026-03-29T00:55:00Z", 1_774_745_700_000L)
        val jump = epoch("2026-03-29T01:00:00Z", 1_774_746_000_000L)
        val end = epoch("2026-03-29T03:00:00Z", 1_774_753_200_000L)

        // 01:59:59.999 CET jumps to 03:00 CEST; 02:30 never occurs.
        assertEvaluation(before, berlin, schedule, ScheduleState.WAITING, 300_000L)
        assertEvaluation(jump - 1L, berlin, schedule, ScheduleState.WAITING, 1L)
        assertEvaluation(jump, berlin, schedule, ScheduleState.ACTIVE, 7_200_000L)
        assertEvaluation(end - 1L, berlin, schedule, ScheduleState.ACTIVE, 1L)
        assertEvaluation(end, berlin, schedule, ScheduleState.WAITING, 77_400_000L)
    }

    @Test
    fun berlinRepeatedHourStopsAtRollbackAndResumesAtSecondStart() {
        val schedule = BotRunSchedule(true, 150, 180) // 02:30–03:00 Berlin
        val firstStart = epoch("2026-10-25T00:30:00Z", 1_792_888_200_000L)
        val rollback = epoch("2026-10-25T01:00:00Z", 1_792_890_000_000L)
        val secondQuarter = epoch("2026-10-25T01:15:00Z", 1_792_890_900_000L)
        val secondStart = epoch("2026-10-25T01:30:00Z", 1_792_891_800_000L)
        val end = epoch("2026-10-25T02:00:00Z", 1_792_893_600_000L)

        assertEvaluation(firstStart - 1L, berlin, schedule, ScheduleState.WAITING, 1L)
        assertEvaluation(firstStart, berlin, schedule, ScheduleState.ACTIVE, 1_800_000L)
        assertEvaluation(rollback - 1L, berlin, schedule, ScheduleState.ACTIVE, 1L)
        assertEvaluation(rollback, berlin, schedule, ScheduleState.WAITING, 1_800_000L)
        assertEvaluation(secondQuarter, berlin, schedule, ScheduleState.WAITING, 900_000L)
        assertEvaluation(secondStart - 1L, berlin, schedule, ScheduleState.WAITING, 1L)
        assertEvaluation(secondStart, berlin, schedule, ScheduleState.ACTIVE, 1_800_000L)
        assertEvaluation(end - 1L, berlin, schedule, ScheduleState.ACTIVE, 1L)
        assertEvaluation(end, berlin, schedule, ScheduleState.WAITING, 84_600_000L)
    }

    @Test
    fun workGatePreservesShortDelaysOnBothSidesOfAnActiveWindow() {
        val schedule = BotRunSchedule(true, 540, 1080)
        val start = epoch("2026-09-07T00:00:00Z", 1_788_739_200_000L)
        val end = epoch("2026-09-07T09:00:00Z", 1_788_771_600_000L)

        assertEvaluation(start - 60_001L, seoul, schedule, ScheduleState.WAITING, 60_001L)
        assertEvaluation(start - 60_000L, seoul, schedule, ScheduleState.WAITING, 60_000L)
        assertEvaluation(start - 15_000L, seoul, schedule, ScheduleState.WAITING, 15_000L)
        assertEvaluation(end - 15_000L, seoul, schedule, ScheduleState.ACTIVE, 15_000L)
    }

    private fun epoch(iso: String, expectedEpochMillis: Long): Long {
        // Also exercise Instant parsing/conversion on the Android runtime, not the host JVM.
        assertEquals(iso, expectedEpochMillis, Instant.parse(iso).toEpochMilli())
        return expectedEpochMillis
    }

    private fun assertEvaluation(
        nowEpochMillis: Long,
        zone: ZoneId,
        schedule: BotRunSchedule,
        expectedState: ScheduleState,
        expectedBoundaryDelay: Long,
    ) {
        val context = "epoch=$nowEpochMillis zone=$zone schedule=$schedule"
        val decision = evaluateSchedule(nowEpochMillis, zone, schedule)
        assertEquals(context, expectedState, decision.state)
        assertEquals(context, expectedBoundaryDelay, decision.millisUntilBoundary)

        val gate = evaluateBotWorkGate(nowEpochMillis, zone, schedule)
        assertEquals(context, expectedState == ScheduleState.ACTIVE, gate.mayStartNetworkOrAction)
        assertEquals(context, minOf(expectedBoundaryDelay, 60_000L), gate.recheckDelayMillis)
        assertTrue("$context: gate must not busy-loop", gate.recheckDelayMillis > 0L)
        assertTrue("$context: gate must recheck within a minute", gate.recheckDelayMillis <= 60_000L)
    }
}
