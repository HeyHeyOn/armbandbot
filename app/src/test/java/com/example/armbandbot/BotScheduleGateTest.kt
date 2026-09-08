package com.heyheyon.armbandbot

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime
import java.time.ZoneId

class BotScheduleGateTest {
    private val zone = ZoneId.of("Asia/Seoul")

    @Test
    fun waitingGateBlocksWorkAndCapsRecheckAtOneMinute() {
        val gate = evaluateBotWorkGate(
            nowEpochMillis = epoch("2026-09-07T12:00:00+09:00"),
            zoneId = zone,
            schedule = BotRunSchedule(true, 18 * 60, 22 * 60),
        )

        assertFalse(gate.mayStartNetworkOrAction)
        assertEquals(60_000L, gate.recheckDelayMillis)
    }

    @Test
    fun waitingGateUsesShortRemainingBoundaryDelay() {
        val gate = evaluateBotWorkGate(
            nowEpochMillis = epoch("2026-09-07T17:59:45+09:00"),
            zoneId = zone,
            schedule = BotRunSchedule(true, 18 * 60, 22 * 60),
        )

        assertFalse(gate.mayStartNetworkOrAction)
        assertEquals(15_000L, gate.recheckDelayMillis)
    }

    @Test
    fun activeAndDisabledSchedulesAllowWork() {
        assertTrue(
            evaluateBotWorkGate(
                epoch("2026-09-07T19:00:00+09:00"),
                zone,
                BotRunSchedule(true, 18 * 60, 22 * 60),
            ).mayStartNetworkOrAction
        )
        assertTrue(
            evaluateBotWorkGate(
                epoch("2026-09-07T12:00:00+09:00"),
                zone,
                BotRunSchedule.disabled(),
            ).mayStartNetworkOrAction
        )
    }

    @Test
    fun requestGateReadsTheLatestScheduleForEveryRequest() = runBlocking {
        var active = true
        var reads = 0
        val readFreshGate = {
            reads++
            BotWorkGate(mayStartNetworkOrAction = active, recheckDelayMillis = 1L)
        }

        assertTrue(mayStartScheduledRequest(readFreshGate))
        active = false
        assertFalse(mayStartScheduledRequest(readFreshGate))
        assertEquals(2, reads)
    }

    @Test fun invalidCanonicalDeniesRequestsAndRepairIsReadOnNextRequest() = runBlocking {
        val values = mutableMapOf<String, Any>("run_schedule_enabled" to true, RUN_SCHEDULE_WINDOWS_JSON_KEY to "broken")
        val read = { evaluateBotWorkGate(epoch("2026-09-07T12:00:00+09:00"), zone, loadBotRunSchedule(values)) }
        assertFalse(mayStartScheduledRequest(read))
        assertEquals(60_000L, read().recheckDelayMillis)
        values[RUN_SCHEDULE_WINDOWS_JSON_KEY] = encodeBotRunWindows(listOf(BotRunWindow(0, 720), BotRunWindow(720, 0)))
        assertTrue(mayStartScheduledRequest(read))
        values[RUN_SCHEDULE_WINDOWS_JSON_KEY] = "broken again"
        assertFalse(mayStartScheduledRequest(read))
    }

    @Test fun touchingAndOverlappingWindowsDoNotPauseRequests() {
        for (start in listOf(660, 720)) {
            val schedule = BotRunSchedule(true, listOf(BotRunWindow(540, 720), BotRunWindow(start, 900)))
            assertTrue(evaluateBotWorkGate(epoch("2026-09-07T12:00:00+09:00"), zone, schedule).mayStartNetworkOrAction)
        }
    }

    @Test
    fun pausedCycleHasNoNormalCycleDelay() {
        assertEquals(
            12_345L,
            cycleDelayAfterScheduleOutcome(ScheduleCycleOutcome.COMPLETED, 12_345L),
        )
        assertEquals(
            null,
            cycleDelayAfterScheduleOutcome(ScheduleCycleOutcome.PAUSED_BY_SCHEDULE, 12_345L),
        )
    }

    private fun epoch(value: String): Long = OffsetDateTime.parse(value).toInstant().toEpochMilli()
}
