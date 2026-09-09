package com.heyheyon.armbandbot

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DbMultiSelectionUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun twoDbsAndTwoGalleriesApplyTogetherCancelDiscardsDraftAndAllChecksEveryItem() {
        var galleries by mutableStateOf<Set<String>?>(setOf("g1"))
        var scope by mutableStateOf<DashboardRecordScope>(DashboardRecordScope.Exact("a"))
        var open by mutableStateOf(true)
        var calls = 0
        val options = dashboardScopeOptions(listOf(DashboardScopeBot("a", "A", true), DashboardScopeBot("b", "B", true)), emptySet())
        compose.setContent { MaterialTheme {
            if (open) DashboardRecordFilterDialog(galleries, scope, listOf("g1", "g2", "g3"), options, true,
                { open = false }, { g, s -> galleries = g; scope = s; calls++; open = false })
        } }
        compose.onNodeWithTag("record-filter-gallery-g2").performScrollTo().performClick().assertIsOn()
        compose.onNodeWithTag("record-filter-gallery-g1").performScrollTo().assertIsOn()
        compose.onNodeWithTag("record-filter-scope-b").performScrollTo().performClick().assertIsOn()
        compose.onNodeWithTag("record-filter-scope-a").performScrollTo().assertIsOn()
        compose.onNodeWithTag("record-filter-cancel").performClick()
        compose.runOnIdle { assertEquals(0, calls); assertEquals(setOf("g1"), galleries); open = true }
        compose.onNodeWithTag("record-filter-gallery-g2").assertIsOff().performClick()
        compose.onNodeWithTag("record-filter-scope-b").performScrollTo().assertIsOff().performClick()
        compose.onNodeWithTag("record-filter-apply").performClick()
        compose.runOnIdle {
            assertEquals(1, calls); assertEquals(setOf("g1", "g2"), galleries)
            assertEquals(DashboardRecordScope.Selected(setOf("a", "b")), scope); open = true
        }
        compose.onNodeWithTag("record-filter-gallery-ALL").performScrollTo().performClick()
        listOf("g1", "g2", "g3").forEach { compose.onNodeWithTag("record-filter-gallery-$it").performScrollTo().assertIsOn() }
        compose.onNodeWithTag("record-filter-scope-ALL").performScrollTo().performClick()
        listOf(GLOBAL_SCAN_SCOPE, "a", "b").forEach { compose.onNodeWithTag("record-filter-scope-$it").performScrollTo().assertIsOn() }
        compose.onNodeWithTag("record-filter-apply").performClick()
        compose.runOnIdle { assertNull(galleries); assertEquals(DashboardRecordScope.All, scope) }
    }
}
