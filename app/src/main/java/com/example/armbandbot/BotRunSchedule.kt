package com.heyheyon.armbandbot

import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

internal data class BotRunWindow(val startMinuteOfDay: Int, val endMinuteOfDay: Int) {
    init {
        require(startMinuteOfDay in 0..1439 && endMinuteOfDay in 0..1439)
        require(startMinuteOfDay != endMinuteOfDay) { "작동 시간대의 시작과 종료 시각은 달라야 합니다." }
    }
    fun contains(minute: Int): Boolean = if (startMinuteOfDay < endMinuteOfDay) {
        minute >= startMinuteOfDay && minute < endMinuteOfDay
    } else minute >= startMinuteOfDay || minute < endMinuteOfDay
}

internal data class BotRunSchedule(val enabled: Boolean, val windows: List<BotRunWindow>) {
    init { require(windows.size in 1..64) }
    constructor(enabled: Boolean, startMinuteOfDay: Int, endMinuteOfDay: Int) : this(
        enabled, listOf(BotRunWindow(startMinuteOfDay,endMinuteOfDay)))
    val startMinuteOfDay: Int get() = windows.first().startMinuteOfDay
    val endMinuteOfDay: Int get() = windows.first().endMinuteOfDay
    companion object {
        fun disabled(): BotRunSchedule = BotRunSchedule(false, 0, 1439)
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
    if (!schedule.enabled || (0..1439).all { minute -> schedule.windows.any { it.contains(minute) } }) {
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
    return schedule.windows.any { it.contains(minute) }
}

private fun scheduleBoundaryCandidates(
    now: Instant,
    zoneId: ZoneId,
    schedule: BotRunSchedule,
): Set<Instant> = buildSet {
    val today = now.atZone(zoneId).toLocalDate()
    for (dayOffset in -1L..3L) {
        val date = today.plusDays(dayOffset)
        for (minute in schedule.windows.flatMap { listOf(it.startMinuteOfDay, it.endMinuteOfDay) }) {
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
