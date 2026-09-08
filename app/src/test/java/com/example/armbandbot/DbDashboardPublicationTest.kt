package com.heyheyon.armbandbot

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class DbDashboardPublicationTest {
    @Test fun delayedSuccessAndErrorCannotPublishAfterAbaSwitch() = runBlocking {
        var scope: DashboardRecordScope = DashboardRecordScope.Exact("a")
        var epoch = 0
        var visible = "initial"
        val success = CompletableDeferred<String>()
        val failure = CompletableDeferred<String>()
        val request = DashboardScopeRequest(scope, epoch)
        val jobs = listOf(success, failure).map { deferred -> launch {
            val outcome = runCatching { deferred.await() }
            request.publishIfCurrent(scope, epoch) {
                visible = outcome.getOrElse { "late error" }
            }
        } }
        yield()
        scope = DashboardRecordScope.Exact("b"); epoch++
        success.complete("late success")
        yield()
        assertEquals("initial", visible)
        scope = DashboardRecordScope.Exact("a"); epoch++
        failure.completeExceptionally(IllegalStateException("snapshot failed"))
        jobs.joinAll()
        assertEquals("initial", visible)
        DashboardScopeRequest(scope, epoch).publishIfCurrent(scope, epoch) { visible = "fresh" }
        assertEquals("fresh", visible)
    }

    @Test fun combinedScopeGalleryFieldQueryFilteringPrecedesHundredRowPageInBothDirections() = runBlocking {
        val rows = (1..260).flatMap { n ->
            listOf(GLOBAL_SCAN_SCOPE, "private").flatMap { scope ->
                listOf("wanted", "other").map { gallery ->
                    CheckedPost(gallType = "M", gallId = gallery, postNum = "$n", commentCount = 0,
                        scopeId = scope, checkTime = n.toLong(), title = if (n % 2 == 0) "needle" else "irrelevant")
                }
            }
        }
        for (scope in listOf(DashboardRecordScope.All, DashboardRecordScope.Exact(GLOBAL_SCAN_SCOPE), DashboardRecordScope.Exact("private"))) {
            for (ascending in listOf(false, true)) {
                for (query in listOf("", "needle")) {
                    val actual = searchDashboardRows(candidates = rows, query = query, enabledCodes = setOf(DashboardSearchMatchCode.POST_TITLE),
                        includeRow = { dashboardIncludesPost(scope, "wanted", it) }, comparator = checkedPostDashboardComparator("CHECK", ascending), limit = 100,
                        directDocument = CheckedPost::toDashboardSearchDocument, snapshotDocument = { DashboardSearchDocument() })
                    val expected = rows.filter { (scope == DashboardRecordScope.All || it.scopeId == (scope as DashboardRecordScope.Exact).scopeId) && it.gallId == "wanted" && (query.isBlank() || it.title == "needle") }
                        .sortedWith(checkedPostDashboardComparator("CHECK", ascending)).take(100)
                    assertEquals("$scope $ascending $query", expected, actual.map { it.row })
                }
            }
        }
        val disabledTitle = searchDashboardRows(candidates = rows, query = "needle", enabledCodes = setOf(DashboardSearchMatchCode.POST_CONTENT),
            includeRow = { dashboardIncludesPost(DashboardRecordScope.All, "wanted", it) }, comparator = checkedPostDashboardComparator("CHECK", false), limit = 100,
            directDocument = CheckedPost::toDashboardSearchDocument, snapshotDocument = { DashboardSearchDocument() })
        assertTrue(disabledTitle.isEmpty())
    }
}
