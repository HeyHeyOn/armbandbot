package com.heyheyon.armbandbot

import java.time.ZoneId

internal const val MAX_SCHEDULE_RECHECK_DELAY_MS = 60_000L

internal data class BotWorkGate(
    val mayStartNetworkOrAction: Boolean,
    val recheckDelayMillis: Long,
)

internal fun evaluateBotWorkGate(
    nowEpochMillis: Long,
    zoneId: ZoneId,
    schedule: BotRunSchedule,
): BotWorkGate {
    val decision = evaluateSchedule(nowEpochMillis, zoneId, schedule)
    return BotWorkGate(
        mayStartNetworkOrAction = decision.state == ScheduleState.ACTIVE,
        recheckDelayMillis = decision.millisUntilBoundary.coerceAtMost(MAX_SCHEDULE_RECHECK_DELAY_MS),
    )
}
