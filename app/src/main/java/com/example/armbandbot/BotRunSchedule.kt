package com.heyheyon.armbandbot

import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

internal data class BotRunSchedule(
    val enabled: Boolean,
    val startMinuteOfDay: Int,
    val endMinuteOfDay: Int,
) {
    init {
        require(startMinuteOfDay in MINUTE_RANGE) { "시작 시각이 올바르지 않습니다." }
        require(endMinuteOfDay in MINUTE_RANGE) { "종료 시각이 올바르지 않습니다." }
        require(!enabled || startMinuteOfDay != endMinuteOfDay) {
            "작동 시간대의 시작과 종료 시각은 달라야 합니다."
        }
    }

    companion object {
        private val MINUTE_RANGE = 0 until 24 * 60

        fun disabled(): BotRunSchedule = BotRunSchedule(
            enabled = false,
            startMinuteOfDay = 0,
            endMinuteOfDay = 0,
        )
    }
}

internal enum class ScheduleState { ACTIVE, WAITING }

internal data class ScheduleDecision(
    val state: ScheduleState,
    val millisUntilBoundary: Long,
)

internal fun evaluateSchedule(
    nowEpochMillis: Long,
    zoneId: ZoneId,
    schedule: BotRunSchedule,
): ScheduleDecision {
    if (!schedule.enabled) {
        return ScheduleDecision(ScheduleState.ACTIVE, Long.MAX_VALUE)
    }

    val now = Instant.ofEpochMilli(nowEpochMillis)
    val active = isScheduleActive(now, zoneId, schedule)
    val nextBoundary = scheduleBoundaryCandidates(now, zoneId, schedule)
        .asSequence()
        .filter { it.isAfter(now) }
        .sorted()
        .firstOrNull { candidate ->
            isScheduleActive(candidate.minusMillis(1), zoneId, schedule) !=
                isScheduleActive(candidate, zoneId, schedule)
        }
        ?: error("다음 작동 시간대 경계를 계산할 수 없습니다.")

    return ScheduleDecision(
        state = if (active) ScheduleState.ACTIVE else ScheduleState.WAITING,
        millisUntilBoundary = (nextBoundary.toEpochMilli() - nowEpochMillis).coerceAtLeast(0L),
    )
}

private fun isScheduleActive(
    instant: Instant,
    zoneId: ZoneId,
    schedule: BotRunSchedule,
): Boolean {
    val local = instant.atZone(zoneId)
    val minute = local.hour * 60 + local.minute
    return if (schedule.startMinuteOfDay < schedule.endMinuteOfDay) {
        minute >= schedule.startMinuteOfDay && minute < schedule.endMinuteOfDay
    } else {
        minute >= schedule.startMinuteOfDay || minute < schedule.endMinuteOfDay
    }
}

private fun scheduleBoundaryCandidates(
    now: Instant,
    zoneId: ZoneId,
    schedule: BotRunSchedule,
): Set<Instant> = buildSet {
    val today = now.atZone(zoneId).toLocalDate()
    for (dayOffset in -1L..3L) {
        val date = today.plusDays(dayOffset)
        for (minute in listOf(schedule.startMinuteOfDay, schedule.endMinuteOfDay)) {
            addLocalBoundary(LocalDateTime.of(date, LocalTime.of(minute / 60, minute % 60)), zoneId)
        }
    }

    val horizon = now.plus(4, ChronoUnit.DAYS)
    var transition = zoneId.rules.nextTransition(now.minusMillis(1))
    while (transition != null && !transition.instant.isAfter(horizon)) {
        add(transition.instant)
        transition = zoneId.rules.nextTransition(transition.instant.plusMillis(1))
    }
}

private fun MutableSet<Instant>.addLocalBoundary(local: LocalDateTime, zoneId: ZoneId) {
    val rules = zoneId.rules
    val offsets = rules.getValidOffsets(local)
    if (offsets.isEmpty()) {
        rules.getTransition(local)?.instant?.let(::add)
    } else {
        offsets.forEach { offset -> add(local.toInstant(offset)) }
    }
}
