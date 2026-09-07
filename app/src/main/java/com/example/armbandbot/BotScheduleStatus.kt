package com.heyheyon.armbandbot

import java.time.ZoneId

internal fun botScheduleStatus(
    isLoggedIn: Boolean,
    isRunning: Boolean,
    nowEpochMillis: Long,
    zoneId: ZoneId,
    schedule: BotRunSchedule,
): String {
    if (!isLoggedIn) return "로그인 필요"
    if (!isRunning) return "중지됨"
    return if (evaluateSchedule(nowEpochMillis, zoneId, schedule).state == ScheduleState.ACTIVE) {
        "실행 중"
    } else {
        "예약 대기 · ${formatMinuteOfDay(schedule.startMinuteOfDay)} 시작"
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
