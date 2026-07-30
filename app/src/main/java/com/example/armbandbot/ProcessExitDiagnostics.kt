package com.heyheyon.armbandbot

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log

data class HistoricalExitRecord(
    val reason: Int,
    val timestampMs: Long,
    val status: Int,
    val description: String?,
)

fun selectNewestUnprocessedExit(
    records: List<HistoricalExitRecord>,
    lastProcessedTimestampMs: Long,
): HistoricalExitRecord? = records
    .asSequence()
    .filter { it.timestampMs > lastProcessedTimestampMs }
    .maxByOrNull { it.timestampMs }

fun exitReasonLabel(reason: Int): String = when (reason) {
    ApplicationExitInfo.REASON_EXIT_SELF -> "앱 자체 종료"
    ApplicationExitInfo.REASON_SIGNALED -> "시그널 종료"
    ApplicationExitInfo.REASON_LOW_MEMORY -> "메모리 부족"
    ApplicationExitInfo.REASON_CRASH -> "Java 충돌"
    ApplicationExitInfo.REASON_CRASH_NATIVE -> "네이티브 충돌"
    ApplicationExitInfo.REASON_ANR -> "ANR"
    ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "초기화 실패"
    ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "권한 변경"
    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "과도한 자원 사용"
    ApplicationExitInfo.REASON_USER_REQUESTED -> "사용자 요청"
    ApplicationExitInfo.REASON_USER_STOPPED -> "사용자 강제 중지"
    ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "의존 프로세스 종료"
    ApplicationExitInfo.REASON_OTHER -> "기타 시스템 종료"
    ApplicationExitInfo.REASON_FREEZER -> "프로세스 동결"
    ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "앱 상태 변경"
    ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "앱 업데이트"
    ApplicationExitInfo.REASON_UNKNOWN -> "알 수 없음"
    else -> "알 수 없음($reason)"
}

fun formatHistoricalExitSummary(
    reason: Int,
    timestampMs: Long,
    status: Int,
    description: String?,
): String {
    val normalizedDescription = description.orEmpty()
        .replace('\n', ' ')
        .replace('\r', ' ')
        .trim()
        .take(300)
    val detail = normalizedDescription.takeIf { it.isNotBlank() }?.let { " / $it" }.orEmpty()
    return "${exitReasonLabel(reason)} / timestamp=$timestampMs / status=$status$detail"
}

object ProcessExitDiagnostics {
    private const val PREF_NAME = "bot_master"
    private const val KEY_LAST_PROCESSED_EXIT_TIMESTAMP = "last_processed_exit_timestamp"
    private const val KEY_LAST_EXIT_SUMMARY = "last_exit_summary"
    private const val KEY_LAST_EXIT_TIMESTAMP = "last_exit_timestamp"
    private const val KEY_LAST_LIFECYCLE_EVENT = "last_persistence_lifecycle_event"
    private const val KEY_LAST_LIFECYCLE_EVENT_AT = "last_persistence_lifecycle_event_at"

    fun captureLatestHistoricalExit(context: Context): HistoricalExitRecord? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null

        return runCatching {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val lastProcessed = prefs.getLong(KEY_LAST_PROCESSED_EXIT_TIMESTAMP, 0L)
            val activityManager = context.getSystemService(ActivityManager::class.java)
                ?: return null
            val records = activityManager
                .getHistoricalProcessExitReasons(context.packageName, 0, 10)
                .map { info ->
                    HistoricalExitRecord(
                        reason = info.reason,
                        timestampMs = info.timestamp,
                        status = info.status,
                        description = info.description,
                    )
                }
            val newest = selectNewestUnprocessedExit(records, lastProcessed) ?: return null
            val summary = formatHistoricalExitSummary(
                reason = newest.reason,
                timestampMs = newest.timestampMs,
                status = newest.status,
                description = newest.description,
            )
            prefs.edit()
                .putLong(KEY_LAST_PROCESSED_EXIT_TIMESTAMP, newest.timestampMs)
                .putLong(KEY_LAST_EXIT_TIMESTAMP, newest.timestampMs)
                .putString(KEY_LAST_EXIT_SUMMARY, summary)
                .commit()
            Log.w("ProcessExitDiagnostics", "이전 프로세스 종료 감지: $summary")
            newest
        }.onFailure { error ->
            Log.e("ProcessExitDiagnostics", "이전 프로세스 종료 정보 조회 실패", error)
        }.getOrNull()
    }

    fun latestSummary(context: Context): Pair<Long, String>? {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val timestamp = prefs.getLong(KEY_LAST_EXIT_TIMESTAMP, 0L)
        val summary = prefs.getString(KEY_LAST_EXIT_SUMMARY, null).orEmpty()
        return if (timestamp > 0L && summary.isNotBlank()) timestamp to summary else null
    }

    fun recordLifecycleEvent(context: Context, event: String, detail: String? = null) {
        val normalizedDetail = detail.orEmpty()
            .replace('\n', ' ')
            .replace('\r', ' ')
            .trim()
            .take(300)
        val value = if (normalizedDetail.isBlank()) event else "$event / $normalizedDetail"
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_LIFECYCLE_EVENT, value)
            .putLong(KEY_LAST_LIFECYCLE_EVENT_AT, System.currentTimeMillis())
            .commit()
    }
}
