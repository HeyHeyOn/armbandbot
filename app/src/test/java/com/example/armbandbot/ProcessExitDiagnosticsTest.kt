package com.heyheyon.armbandbot

import android.app.ApplicationExitInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessExitDiagnosticsTest {
    @Test
    fun classifiesForegroundServiceResourceLimitExit() {
        assertEquals(
            "과도한 자원 사용",
            exitReasonLabel(ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE)
        )
    }

    @Test
    fun classifiesLowMemoryCrashAndAnrSeparately() {
        assertEquals("메모리 부족", exitReasonLabel(ApplicationExitInfo.REASON_LOW_MEMORY))
        assertEquals("Java 충돌", exitReasonLabel(ApplicationExitInfo.REASON_CRASH))
        assertEquals("네이티브 충돌", exitReasonLabel(ApplicationExitInfo.REASON_CRASH_NATIVE))
        assertEquals("ANR", exitReasonLabel(ApplicationExitInfo.REASON_ANR))
    }

    @Test
    fun classifiesUserAndPackageLifecycleExits() {
        assertEquals("사용자 요청", exitReasonLabel(ApplicationExitInfo.REASON_USER_REQUESTED))
        assertEquals("사용자 강제 중지", exitReasonLabel(ApplicationExitInfo.REASON_USER_STOPPED))
        assertEquals("앱 업데이트", exitReasonLabel(ApplicationExitInfo.REASON_PACKAGE_UPDATED))
    }

    @Test
    fun preservesUnknownReasonCode() {
        assertEquals("알 수 없음(999)", exitReasonLabel(999))
    }

    @Test
    fun selectsOnlyTheNewestUnprocessedExit() {
        val records = listOf(
            HistoricalExitRecord(reason = 3, timestampMs = 100L, status = 0, description = "old"),
            HistoricalExitRecord(reason = 9, timestampMs = 300L, status = 0, description = "new"),
            HistoricalExitRecord(reason = 4, timestampMs = 200L, status = 1, description = "middle"),
        )

        assertEquals(records[1], selectNewestUnprocessedExit(records, lastProcessedTimestampMs = 200L))
        assertEquals(null, selectNewestUnprocessedExit(records, lastProcessedTimestampMs = 300L))
    }

    @Test
    fun formattedSummaryContainsReasonTimestampAndDescription() {
        val summary = formatHistoricalExitSummary(
            reason = ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
            timestampMs = 1_725_000_000_000L,
            status = 0,
            description = "FGS time limit"
        )

        assertTrue(summary.contains("과도한 자원 사용"))
        assertTrue(summary.contains("1725000000000"))
        assertTrue(summary.contains("FGS time limit"))
    }
}
