package com.heyheyon.armbandbot

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class DbDashboardScopeUiContractTest {
    private val source = listOf(File("src/main/java/com/example/armbandbot/DbDashboardScreen.kt"), File("app/src/main/java/com/example/armbandbot/DbDashboardScreen.kt")).first { it.isFile }.readText()
    @Test fun monitoringOnlyStripFollowsGallerySelector() {
        assertTrue(source.indexOf("if (isGlobalDashboard && tabIndex == 0)") > source.indexOf("전체 갤러리"))
        assertTrue(source.contains("모니터링 기록"))
        assertTrue(source.contains("공용 조치 이력"))
        assertTrue(source.contains("전체 DB 백업"))
    }
    @Test fun switchInvalidatesSynchronouslyAndDismissesDestructiveUi() {
        val body = source.substringAfter("fun switchRecordScope(").substringBefore("suspend fun loadGeneralData")
        listOf("dashboardDataEpoch++", "generalLoadVersion++", "blockLoadVersion++", "holdLoadVersion++", "generalPosts = emptyList()", "generalMatches = emptyMap()", "generalLoadError = null", "pendingDeletePost = null", "pendingDeleteBlock = null", "pendingDeleteHold = null", "showClearDbConfirm = false", "generalLimit = 100", "generalListState.requestScrollToItem(0)").forEach { assertTrue(it, body.contains(it)) }
    }
    @Test fun capturedScopeGatesSuccessAndErrorAndFrozenResetTarget() {
        val loader = source.substringAfter("suspend fun loadGeneralData()").substringBefore("suspend fun loadBlockData()")
        assertEquals(2, Regex("requestScope.isCurrent").findAll(loader).count())
        assertTrue(loader.contains("dashboardIncludesPost(requestScope.scope, gallFilter, it)"))
        assertTrue(source.contains("val resetTarget = pendingResetTarget"))
        assertTrue(source.contains("dao.deletePostsForScope(resetTarget.scopeId)"))
    }
}
