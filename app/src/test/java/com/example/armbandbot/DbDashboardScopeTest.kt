package com.heyheyon.armbandbot

import org.junit.Assert.*
import org.junit.Test

class DbDashboardScopeTest {
    @Test fun exactGlobalIsNotAllAndFilteringPrecedesLimit() = kotlinx.coroutines.runBlocking {
        val base = CheckedPost(gallType = "M", gallId = "g", postNum = "1", commentCount = 0, scopeId = GLOBAL_SCAN_SCOPE, title = "needle")
        val rows = listOf(base.copy(scopeId = "other", checkTime = 99), base.copy(gallId = "other", checkTime = 98), base)
        val results = searchDashboardRows(
            candidates = rows, query = "needle", enabledCodes = setOf(DashboardSearchMatchCode.POST_TITLE),
            includeRow = { dashboardIncludesPost(DashboardRecordScope.Exact(GLOBAL_SCAN_SCOPE), "g", it) },
            comparator = checkedPostDashboardComparator("CHECK", false), limit = 1,
            directDocument = CheckedPost::toDashboardSearchDocument,
            snapshotDocument = { DashboardSearchDocument() },
        )
        assertEquals(listOf(base), results.map { it.row })
        assertTrue(dashboardIncludesPost(DashboardRecordScope.All, "ALL", rows.first()))
        assertFalse(dashboardIncludesPost(DashboardRecordScope.Exact(GLOBAL_SCAN_SCOPE), "ALL", rows.first()))
    }
    @Test fun optionsKeepMasterOrderEmptyEnabledRetainedAndOrphans() {
        val options = dashboardScopeOptions(listOf(
            DashboardScopeBot("b", "same", true), DashboardScopeBot("a", "same", false),
            DashboardScopeBot("hidden", "hidden", false)
        ), setOf("a", "deleted", GLOBAL_SCAN_SCOPE))
        assertEquals(listOf(DashboardRecordScope.All, DashboardRecordScope.Exact(GLOBAL_SCAN_SCOPE), DashboardRecordScope.Exact("b"), DashboardRecordScope.Exact("a"), DashboardRecordScope.Exact("deleted")), options.map { it.scope })
        assertNotEquals(options[2].label, options[3].label)
        assertTrue(options[3].label.contains("보존"))
        assertTrue(options[4].label.contains("삭제된 봇"))
        assertEquals(options[2].label, dashboardScopeOptions(listOf(DashboardScopeBot("b", "same", true)), emptySet())[2].label)
    }
    @Test fun staleSuccessAndErrorsRejectScopeAndEpochIncludingAba() {
        val frozen = DashboardScopeRequest(DashboardRecordScope.Exact("a"), 2)
        assertTrue(frozen.isCurrent(DashboardRecordScope.Exact("a"), 2))
        assertFalse(frozen.isCurrent(DashboardRecordScope.Exact("b"), 2))
        assertFalse(frozen.isCurrent(DashboardRecordScope.Exact("a"), 4))
    }
    @Test fun destructiveTargetFreezesExactGlobalRatherThanFullDatabase() {
        assertEquals(DashboardResetTarget.CheckedScope(GLOBAL_SCAN_SCOPE), dashboardResetTarget(DashboardRecordScope.Exact(GLOBAL_SCAN_SCOPE)))
        assertEquals(DashboardResetTarget.WholeDatabase, dashboardResetTarget(DashboardRecordScope.All))
    }
}
