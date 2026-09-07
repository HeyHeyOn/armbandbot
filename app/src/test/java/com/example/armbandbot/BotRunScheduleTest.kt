package com.heyheyon.armbandbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId

class BotRunScheduleTest {
    private val seoul = ZoneId.of("Asia/Seoul")

    @Test
    fun disabledScheduleIsAlwaysActive() {
        val decision = evaluateSchedule(
            nowEpochMillis = Instant.parse("2026-09-07T03:00:00Z").toEpochMilli(),
            zoneId = seoul,
            schedule = BotRunSchedule.disabled(),
        )

        assertEquals(ScheduleState.ACTIVE, decision.state)
        assertEquals(Long.MAX_VALUE, decision.millisUntilBoundary)
    }

    @Test
    fun daytimeWindowIncludesStartAndExcludesEnd() {
        val schedule = BotRunSchedule(enabled = true, startMinuteOfDay = 9 * 60, endMinuteOfDay = 18 * 60)

        assertEquals(ScheduleState.ACTIVE, at("2026-09-07T09:00:00+09:00", schedule).state)
        assertEquals(ScheduleState.WAITING, at("2026-09-07T18:00:00+09:00", schedule).state)
    }

    @Test
    fun overnightWindowHandlesBothSidesOfMidnight() {
        val schedule = BotRunSchedule(enabled = true, startMinuteOfDay = 22 * 60, endMinuteOfDay = 6 * 60)

        assertEquals(ScheduleState.ACTIVE, at("2026-09-07T23:00:00+09:00", schedule).state)
        assertEquals(ScheduleState.ACTIVE, at("2026-09-08T05:59:00+09:00", schedule).state)
        assertEquals(ScheduleState.WAITING, at("2026-09-08T12:00:00+09:00", schedule).state)
    }

    @Test
    fun boundaryDelayUsesEpochAndZoneAcrossDstJump() {
        val zone = ZoneId.of("America/New_York")
        val schedule = BotRunSchedule(enabled = true, startMinuteOfDay = 3 * 60 + 30, endMinuteOfDay = 5 * 60)
        val now = Instant.parse("2026-03-08T06:55:00Z").toEpochMilli() // 01:55 before 02:00 -> 03:00 jump

        val decision = evaluateSchedule(now, zone, schedule)

        assertEquals(ScheduleState.WAITING, decision.state)
        assertEquals(35 * 60 * 1_000L, decision.millisUntilBoundary)
        assertTrue(decision.millisUntilBoundary >= 0L)
    }

    @Test
    fun nonexistentStartMinuteTransitionsAtDstGapBoundary() {
        val zone = ZoneId.of("America/New_York")
        val schedule = BotRunSchedule(enabled = true, startMinuteOfDay = 2 * 60 + 30, endMinuteOfDay = 5 * 60)
        val now = Instant.parse("2026-03-08T06:55:00Z").toEpochMilli() // 01:55 before 02:00 -> 03:00 jump

        val decision = evaluateSchedule(now, zone, schedule)

        assertEquals(ScheduleState.WAITING, decision.state)
        assertEquals(5 * 60 * 1_000L, decision.millisUntilBoundary)
    }

    @Test
    fun overlapChoosesTheFutureOccurrenceOfRepeatedStartMinute() {
        val zone = ZoneId.of("America/New_York")
        val schedule = BotRunSchedule(enabled = true, startMinuteOfDay = 90, endMinuteOfDay = 120)
        val now = Instant.parse("2026-11-01T06:15:00Z").toEpochMilli() // second 01:15 after rollback

        val decision = evaluateSchedule(now, zone, schedule)

        assertEquals(ScheduleState.WAITING, decision.state)
        assertEquals(15 * 60 * 1_000L, decision.millisUntilBoundary)
    }

    @Test(expected = IllegalArgumentException::class)
    fun enabledScheduleRejectsEqualBoundaries() {
        BotRunSchedule(enabled = true, startMinuteOfDay = 300, endMinuteOfDay = 300)
    }

    @Test(expected = IllegalArgumentException::class)
    fun scheduleRejectsMinutesOutsideDay() {
        BotRunSchedule(enabled = true, startMinuteOfDay = -1, endMinuteOfDay = 60)
    }

    private fun at(value: String, schedule: BotRunSchedule): ScheduleDecision = evaluateSchedule(
        nowEpochMillis = OffsetDateTime.parse(value).toInstant().toEpochMilli(),
        zoneId = seoul,
        schedule = schedule,
    )
}
