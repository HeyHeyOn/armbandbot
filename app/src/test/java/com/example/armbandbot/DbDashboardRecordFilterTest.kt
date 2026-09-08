package com.heyheyon.armbandbot

import org.junit.Assert.*
import org.junit.Test

class DbDashboardRecordFilterTest {
    private val options = dashboardScopeOptions(listOf(DashboardScopeBot("one", "동일", true), DashboardScopeBot("two", "동일", true)), emptySet())
    @Test fun defaultHasNoSummary() = assertNull(dashboardRecordFilterSummary("ALL", DashboardRecordScope.All, options))
    @Test fun galleryOnly() = assertEquals("gallery", dashboardRecordFilterSummary("gallery", DashboardRecordScope.All, options))
    @Test fun privateOnlyUsesExistingLabel() = assertEquals(options[2].label, dashboardRecordFilterSummary("ALL", options[2].scope, options))
    @Test fun bothCommaJoined() = assertEquals("gallery, ${options[2].label}", dashboardRecordFilterSummary("gallery", options[2].scope, options))
    @Test fun explicitCommonIsNotDefault() = assertEquals("공용", dashboardRecordFilterSummary("ALL", DashboardRecordScope.Exact(GLOBAL_SCAN_SCOPE), options))
    @Test fun duplicateNamesRemainDistinct() = assertNotEquals(dashboardRecordFilterSummary("ALL", options[2].scope, options), dashboardRecordFilterSummary("ALL", options[3].scope, options))
}
