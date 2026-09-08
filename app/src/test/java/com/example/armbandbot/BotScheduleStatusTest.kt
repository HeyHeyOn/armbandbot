package com.heyheyon.armbandbot

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.OffsetDateTime
import java.time.ZoneId

class BotScheduleStatusTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val now = OffsetDateTime.parse("2026-09-07T12:00:00+09:00").toInstant().toEpochMilli()

    @Test
    fun loginAndStoppedStatesTakePriority() {
        val schedule = BotRunSchedule(true, 9 * 60, 18 * 60)
        assertEquals("로그인 필요", botScheduleStatus(false, true, now, zone, schedule))
        assertEquals("중지됨", botScheduleStatus(true, false, now, zone, schedule))
    }

    @Test
    fun activeAndWaitingRunningBotsAreDistinct() {
        assertEquals("실행 중", botScheduleStatus(true, true, now, zone, BotRunSchedule(true, 9 * 60, 18 * 60)))
        assertEquals(
            "예약 대기 · 18:00 시작",
            botScheduleStatus(true, true, now, zone, BotRunSchedule(true, 18 * 60, 23 * 60)),
        )
    }

    @Test fun unsortedDisjointWindowsShowNextUnionOpeningNotFirstRow() {
        val schedule = BotRunSchedule(true, listOf(BotRunWindow(1200, 1320), BotRunWindow(780, 840)))
        assertEquals("예약 대기 · 13:00 시작", botScheduleStatus(true, true, now, zone, schedule))
    }

    @Test fun nextDayUnionOpeningUsesEarliestWindowNotFirstRow() {
        val late = OffsetDateTime.parse("2026-09-07T23:30:00+09:00").toInstant().toEpochMilli()
        val schedule = BotRunSchedule(true, listOf(BotRunWindow(600, 660), BotRunWindow(120, 180), BotRunWindow(150, 240)))
        assertEquals("예약 대기 · 02:00 시작", botScheduleStatus(true, true, late, zone, schedule))
        assertEquals(OffsetDateTime.parse("2026-09-08T02:00:00+09:00").toInstant().toEpochMilli(),
            late + evaluateSchedule(late, zone, schedule).millisUntilBoundary)
    }

    @Test fun invalidSettingsAreVisibleButManualStopStillWins() {
        val invalid = loadBotRunSchedule(mapOf("run_schedule_enabled" to true, RUN_SCHEDULE_WINDOWS_JSON_KEY to "broken"))
        assertEquals("예약 대기 · 시간대 설정 오류", botScheduleStatus(true, true, now, zone, invalid))
        assertEquals("중지됨", botScheduleStatus(true, false, now, zone, invalid))
    }

    @Test fun fullDayUnionNeverShowsWaiting() {
        val schedule = BotRunSchedule(true, listOf(BotRunWindow(0, 720), BotRunWindow(720, 0)))
        assertEquals("실행 중", botScheduleStatus(true, true, now, zone, schedule))
    }

    @Test
    fun overnightRangeMakesNextDayExplicit() {
        assertEquals("22:00 ~ 06:00 · 다음 날 종료", scheduleRangeLabel(22 * 60, 6 * 60))
        assertEquals("09:00 ~ 18:00", scheduleRangeLabel(9 * 60, 18 * 60))
    }

    @Test
    fun disabledScheduleIsRunningAllDay() {
        assertEquals("실행 중", botScheduleStatus(true, true, now, zone, BotRunSchedule.disabled()))
    }
}
