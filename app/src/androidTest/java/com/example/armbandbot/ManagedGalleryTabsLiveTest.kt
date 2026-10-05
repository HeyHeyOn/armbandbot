package com.heyheyon.armbandbot

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.heyheyon.armbandbot.ui.botColors
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Read-only server checks: saving a disabled local rule never moves a post. */
class ManagedGalleryTabsLiveTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun savedManagerSessionLoadsTabsAndSavesDisabledRule() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("automation_live") == "authorized_fixture")
        val cookie = File(context.filesDir, "automation_live_cookie.txt").readText()
        check(cookie.isNotBlank())
        val p = context.getSharedPreferences("managed_tabs_live_fixture", Context.MODE_PRIVATE)
        try {
            p.edit().clear().putString("saved_cookie", cookie)
                .putString("target_urls", "https://m.dcinside.com/board/laboratory1").commit()
            compose.setContent {
                MaterialTheme { PostAutomationSettingsScreen(p, botColors(false), AutomationSettingsPage.TAB) {} }
            }
            compose.waitUntil(45_000) {
                compose.onAllNodesWithTag("move-target-menu").fetchSemanticsNodes().isNotEmpty() ||
                    compose.onAllNodesWithTag("automation-error").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("automation-error").assertDoesNotExist()
            compose.onNodeWithTag("move-target-menu").performScrollTo().performClick()
            compose.onNodeWithText("테스트").performClick()
            compose.onNodeWithTag("move-normal-keywords").performScrollTo().performClick()
        compose.onNodeWithTag("move-keyword-input").performTextReplacement("AB160-local-only")
        compose.onNodeWithTag("move-keyword-save").performClick()
            compose.onNodeWithTag("move-save").performScrollTo().performClick()
            val rule = parseTabMoveRules(p.getString(MOVE_RULES_KEY, "[]")!!).single()
            assertEquals("laboratory1", rule.gallId)
            assertEquals("M", rule.gallType)
            assertEquals(10, rule.headtext)
            assertEquals("테스트", rule.label)
            assertEquals(listOf("AB160-local-only"), rule.normalKeywords)
            assertFalse(p.getBoolean(MOVE_ENABLED_KEY, false))
        } finally {
            p.edit().clear().commit()
        }
    }

    @Test fun anonymousSessionCannotUsePublicTabChoices() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("automation_live") == "authorized_fixture")
        val failure = runCatching { loadManagedGalleryTabs("M" to "laboratory1", "ci_c=anonymous-fixture") }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertEquals("laboratory1의 관리 권한을 확인해 주세요.", failure?.message)
    }
}
