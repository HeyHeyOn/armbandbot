package com.heyheyon.armbandbot

import org.junit.Assert.*
import org.junit.Test

class DbMultiSelectionBehaviorTest {
    @Test fun selectedDbsAndGalleriesUseCrossProductBeforeLimit() {
        val selected = DashboardRecordScope.Selected(setOf("a", "b"))
        val rows = listOf("c" to "x", "a" to "z", "a" to "x", "b" to "y").mapIndexed { index, (scope, gallery) ->
            CheckedPost(gallType = "M", gallId = gallery, postNum = "$index", commentCount = 0, scopeId = scope)
        }
        assertEquals(listOf("2", "3"), rows.filter { dashboardIncludesPost(selected, setOf("x", "y"), it) }.take(2).map { it.postNum })
        assertFalse(dashboardIncludesPost(selected, emptySet(), rows[2]))
    }
    @Test fun actionAndPendingMembershipUsesRecordedScopeNotActorIdentity() {
        val action = BlockHistory(gallType = "M", gallId = "g1", postNum = "1", targetType = "POST", targetAuthor = "a", targetContent = "body", blockReason = "reason", actorBotId = "same-bot", scopeId = "old-scope")
        val hold = HoldHistory(gallType = "M", gallId = "g2", postNum = "1", targetType = "POST", targetNo = "1", targetAuthor = "a", targetContent = "body", holdReason = "reason", actorBotId = "same-bot", scopeId = GLOBAL_SCAN_SCOPE)
        val filter = DashboardRecordScope.Selected(setOf("old-scope", GLOBAL_SCAN_SCOPE))
        assertTrue(dashboardIncludesHistory(filter, setOf("g1", "g2"), action.scopeId, action.gallId))
        assertTrue(dashboardIncludesHistory(filter, setOf("g1", "g2"), hold.scopeId, hold.gallId))
        assertFalse(dashboardIncludesHistory(DashboardRecordScope.Exact("same-bot"), null, action.scopeId, action.gallId))
        assertNotEquals(blockHistoryMergeKey(action), blockHistoryMergeKey(action.copy(scopeId = GLOBAL_SCAN_SCOPE)))
    }

    @Test fun multiselectionStillRejectsAbaCompletion() {
        val selected = DashboardRecordScope.Selected(setOf("a", "b"))
        val request = DashboardScopeRequest(selected, 1)
        assertTrue(request.isCurrent(DashboardRecordScope.Selected(setOf("b", "a")), 1))
        assertFalse(request.isCurrent(selected, 3))
    }
}
