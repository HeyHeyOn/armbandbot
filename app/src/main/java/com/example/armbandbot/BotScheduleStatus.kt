package com.heyheyon.armbandbot

import java.time.ZoneId

internal fun botScheduleStatus(
    isLoggedIn: Boolean,
    isRunning: Boolean,
    nowEpochMillis: Long,
    zoneId: ZoneId,
    schedule: BotRunScheduleLoadResult,
): String {
    if (!isLoggedIn) return "로그인 필요"
    if (!isRunning) return "중지됨"
    return when (schedule) {
        is BotRunScheduleLoadResult.Valid -> botScheduleStatus(isLoggedIn, isRunning, nowEpochMillis, zoneId, schedule.schedule)
        is BotRunScheduleLoadResult.Error -> "예약 대기 · 시간대 설정 오류"
    }
}

internal fun botScheduleStatus(
    isLoggedIn: Boolean,
    isRunning: Boolean,
    nowEpochMillis: Long,
    zoneId: ZoneId,
    schedule: BotRunSchedule,
): String {
    if (!isLoggedIn) return "로그인 필요"
    if (!isRunning) return "중지됨"
    val decision = evaluateSchedule(nowEpochMillis, zoneId, schedule)
    return if (decision.state == ScheduleState.ACTIVE) {
        "실행 중"
    } else {
        val opening = java.time.Instant.ofEpochMilli(nowEpochMillis)
            .plusMillis(decision.millisUntilBoundary).atZone(zoneId)
        "예약 대기 · ${formatMinuteOfDay(opening.hour * 60 + opening.minute)} 시작"
    }
}

internal fun scheduleRangeLabel(startMinute: Int, endMinute: Int): String {
    require(startMinute in 0..1439 && endMinute in 0..1439) { "시간대 값이 올바르지 않습니다." }
    val suffix = if (startMinute > endMinute) " · 다음 날 종료" else ""
    return "${formatMinuteOfDay(startMinute)} ~ ${formatMinuteOfDay(endMinute)}$suffix"
}

internal fun formatMinuteOfDay(minute: Int): String {
    require(minute in 0..1439) { "시각 값이 올바르지 않습니다." }
    return "%02d:%02d".format(minute / 60, minute % 60)
}
