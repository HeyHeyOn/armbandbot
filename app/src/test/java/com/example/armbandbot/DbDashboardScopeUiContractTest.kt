package com.heyheyon.armbandbot

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class DbDashboardScopeUiContractTest {
    private val source = listOf(File("src/main/java/com/example/armbandbot/DbDashboardScreen.kt"), File("app/src/main/java/com/example/armbandbot/DbDashboardScreen.kt")).first { it.isFile }.readText()
    @Test fun monitoringUsesCompactRecordFilter() {
        assertTrue(source.contains("DashboardRecordFilterDialog("))
        assertTrue(source.contains("record-filter-button"))
        assertTrue(source.contains("record-filter-summary"))
        assertTrue(source.contains("if (post.scopeId != GLOBAL_SCAN_SCOPE)"))
        assertTrue(source.contains("모니터링 기록"))
        assertFalse(source.contains("검사 범위 선택과 무관"))
        assertTrue(source.contains("검사·조치·보류 기록"))
        assertTrue(source.contains("전체 DB 백업"))
    }
    @Test fun switchInvalidatesSynchronouslyAndDismissesDestructiveUi() {
        val body = source.substringAfter("fun switchRecordScope(").substringBefore("suspend fun loadGeneralData")
        listOf("dashboardDataEpoch++", "generalLoadVersion++", "blockLoadVersion++", "holdLoadVersion++", "generalPosts = emptyList()", "generalMatches = emptyMap()", "generalLoadError = null", "pendingDeletePost = null", "pendingDeleteBlock = null", "pendingDeleteHold = null", "recordFilterUi.frozenReset = null", "recordFilterUi.showResetChooser = false", "generalLimit = 100", "generalListState.requestScrollToItem(0)").forEach { assertTrue(it, body.contains(it)) }
    }
    @Test fun capturedScopeGatesSuccessAndErrorAndFrozenResetTarget() {
        val loader = source.substringAfter("suspend fun loadGeneralData()").substringBefore("suspend fun loadBlockData()")
        assertEquals(2, Regex("requestScope.isCurrent").findAll(loader).count())
        assertTrue(loader.contains("dashboardIncludesPost(requestScope.scope, gallFilter, it)"))
        assertTrue(source.contains("recordFilterUi.frozenReset?.let { resetTarget ->"))
        assertTrue(source.contains("recordFilterUi.frozenReset === resetTarget"))
        assertTrue(source.contains("resetDashboardRecords(checkNotNull(database), context.cacheDir, resetTarget)"))
    }
}
