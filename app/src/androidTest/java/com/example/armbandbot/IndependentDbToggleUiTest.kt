package com.heyheyon.armbandbot

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.heyheyon.armbandbot.ui.botColors
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class IndependentDbToggleUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun toggleImmediatelyReusesOneScopeWithoutChangingAnyHistory() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "independent_toggle_fixture_${UUID.randomUUID()}"
        val prefs = context.getSharedPreferences(name, 0)
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val original = CheckedPost("M", "fixture", "1", 4, 99L, scopeId = "fixture-bot")
        try {
            db.postDao().insertOrUpdate(original)
            db.postDao().insertOrUpdate(original.copy(scopeId = GLOBAL_SCAN_SCOPE, commentCount = 8))
            compose.setContent { MaterialTheme { IndependentDbSettingsCard("fixture-bot", prefs, false, botColors(true)) } }
            compose.onNodeWithText("개별 DB 사용").assertExists()
            compose.onNodeWithText("검사 기록을 다른 봇과 분리하여 처리합니다.").assertExists()
            repeat(3) {
                compose.onNodeWithTag("independent-db-enabled").performClick().assertIsOn()
                assertTrue(prefs.getBoolean("independent_scan_state_enabled", false))
                compose.onNodeWithText("독립 검사 기록 시작").assertDoesNotExist()
                compose.onNodeWithTag("independent-db-enabled").performClick().assertIsOff()
                assertFalse(prefs.getBoolean("independent_scan_state_enabled", true))
                assertEquals(original, db.postDao().getPostsForScope("fixture-bot").single())
                assertEquals(8, db.postDao().getPostsForScope(GLOBAL_SCAN_SCOPE).single().commentCount)
            }
            assertTrue(prefs.getBoolean("independent_scan_state_initialized", false))
            val divider = compose.onNodeWithTag("independent-db-divider", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val toggle = compose.onNodeWithTag("independent-db-enabled").fetchSemanticsNode().boundsInRoot
            assertTrue(divider.width > 0 && divider.right < toggle.left)
            saveBeta4UiEvidence("independent-toggle-no-footer")
            assertEquals(2, db.postDao().getPostCount())
            compose.onNodeWithText("공용 검사 기록").assertDoesNotExist()
        } finally { db.close(); context.deleteSharedPreferences(name) }
    }

    @Test fun firstEnableCreatesOnlyEmptyStableScopeAndRetainsInventoryAfterOff() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "independent_empty_fixture_${UUID.randomUUID()}"
        val prefs = context.getSharedPreferences(name, 0)
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            val common = CheckedPost("M", "fixture", "1", 8, scopeId = GLOBAL_SCAN_SCOPE)
            db.postDao().insertOrUpdate(common)
            compose.setContent { MaterialTheme { IndependentDbSettingsCard("new-bot", prefs, false, botColors(false)) } }
            compose.onNodeWithTag("independent-db-enabled").performClick().assertIsOn()
            assertEquals("new-bot", resolveScanScopeId("new-bot", prefs.getBoolean("independent_scan_state_enabled", false)))
            assertTrue(db.postDao().getPostsForScope("new-bot").isEmpty())
            assertEquals(common, db.postDao().getPostsForScope(GLOBAL_SCAN_SCOPE).single())
            compose.onAllNodes(isDialog()).assertCountEquals(0)
            compose.onNodeWithTag("independent-db-enabled").performClick().assertIsOff()
            val options = dashboardScopeOptions(listOf(DashboardScopeBot("new-bot", "new", false,
                prefs.getBoolean("independent_scan_state_initialized", false))), emptySet())
            assertEquals(1, options.count { it.scope == DashboardRecordScope.Exact("new-bot") })
            compose.onNodeWithTag("independent-db-enabled").performClick().assertIsOn()
            assertEquals(1, db.postDao().getPostCount())
            saveBeta4UiEvidence("independent-first-enable-empty-light")
        } finally { db.close(); context.deleteSharedPreferences(name) }
    }

    @Test fun runningBotCannotChangeDbScope() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "independent_running_fixture_${UUID.randomUUID()}"
        val prefs = context.getSharedPreferences(name, 0)
        try {
            compose.setContent { MaterialTheme { IndependentDbSettingsCard("fixture-bot", prefs, true, botColors(false)) } }
            compose.onNodeWithTag("independent-db-enabled").assertIsNotEnabled().assertIsOff()
            assertFalse(prefs.contains("independent_scan_state_enabled"))
        } finally { context.deleteSharedPreferences(name) }
    }
}
