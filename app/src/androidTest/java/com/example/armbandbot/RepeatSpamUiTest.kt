package com.heyheyon.armbandbot

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.heyheyon.armbandbot.ui.botColors
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RepeatSpamUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val p get() = context.getSharedPreferences("repeat_ui_fixture", Context.MODE_PRIVATE)
    @org.junit.Before fun reset() { p.edit().clear().commit() }
    @org.junit.After fun cleanup() { p.edit().clear().commit() }
    private fun screen(dark: Boolean) {
        compose.setContent { MaterialTheme { var enabled by remember { mutableStateOf(p.getBoolean(REPEAT_ENABLED_KEY, false)) }
            Column(Modifier.verticalScroll(rememberScrollState())) { RepeatSpamSettingsCard(p,botColors(dark),enabled) {
                enabled=it; p.edit().putBoolean(REPEAT_ENABLED_KEY,it).repeatSpamChanged().apply()
            } }
        } }
    }
    private fun tap(tag: String, times: Int = 1) = repeat(times) { compose.onNodeWithTag(tag).performScrollTo().performClick() }
    private fun revision() = p.getLong(REPEAT_REVISION_KEY, 0)
    private fun chooseMode(mode: String) {
        compose.onNodeWithTag("repeat_spam-action-mode").performScrollTo().performClick()
        compose.onNodeWithTag("repeat_spam-action-mode-$mode").performClick()
        compose.waitForIdle()
    }

    private fun exercise(dark: Boolean) {
        screen(dark)
        // Master off: every control is present but disabled, nothing can change.
        compose.onNodeWithTag("repeat-min-length").assertIsNotEnabled()
        compose.onNodeWithTag("repeat-minutes-plus").assertIsNotEnabled()
        compose.onNodeWithTag("repeat_spam-action-mode").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("repeat-enabled").performScrollTo().performClick()
        // Each tap is saved immediately; there is no separate save button any more.
        tap("repeat-minutes-plus", 2)                     // 10 -> 15 -> 20
        tap("repeat-count-minus", 2)                      // 4 -> 3 -> 2
        tap("repeat-similarity-plus", 3)                  // 85 -> 90 -> 95 -> 100
        compose.runOnIdle {
            val s = repeatSpamSettings(p)
            assertEquals(20, s.windowMinutes); assertEquals(2, s.count); assertEquals(100, s.similarityPercent)
        }
        compose.onNodeWithTag("repeat-minutes").assertTextEquals("20분")
        compose.onNodeWithTag("repeat-count-minus").assertIsNotEnabled()     // lower bound 2
        compose.onNodeWithTag("repeat-similarity-plus").assertIsNotEnabled() // upper bound 100
        compose.onNodeWithTag("repeat-summary").assert(hasText("20분 안에 제목이나 본문이 비슷한 글을 2개 이상", substring = true))
        // Whole target rows are toggleable.
        compose.onNodeWithTag("repeat-yudong").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
        // The same 개별 차단 설정 block as other filters: 삭제 by default, then 보류 / 차단.
        compose.onNodeWithTag("repeat_spam-action-mode").performScrollTo().assertTextContains("삭제")
        chooseMode("HOLD")
        compose.runOnIdle { assertTrue(repeatSpamSettings(p).observeOnly); assertEquals("HOLD", p.getString(REPEAT_PROCESS_MODE_KEY, null)) }
        compose.onNodeWithTag("repeat-summary").assert(hasText("보류 기록으로 남깁니다", substring = true))
        chooseMode("BLOCK")
        compose.onNodeWithTag("repeat_spam-block-duration").performScrollTo().assertExists()
        compose.onNodeWithTag("repeat_spam-block-reason").performScrollTo().assertExists()
        compose.onNodeWithTag("repeat-summary").assert(hasText("차단하고 첫 글까지 모두 삭제", substring = true))
        compose.runOnIdle { assertEquals("BLOCK", repeatActionView(p).mode); assertFalse(repeatSpamSettings(p).observeOnly) }
        // Direct input accepts zero, saves only on confirmation, and rejects malformed values.
        tap("repeat-min-length")
        compose.onNodeWithTag("repeat-min-length-input").performTextReplacement("0")
        compose.onNodeWithTag("repeat-min-length-save").performClick()
        compose.runOnIdle { assertEquals(0, repeatSpamSettings(p).minimumLength) }
        tap("repeat-min-length")
        compose.onNodeWithTag("repeat-min-length-input").assertTextContains("0")
        for (invalid in listOf("", "-1", "1.5", "2147483648", "abc")) {
            compose.onNodeWithTag("repeat-min-length-input").performTextReplacement(invalid)
            compose.onNodeWithTag("repeat-min-length-save").assertIsNotEnabled()
        }
        compose.onNodeWithTag("repeat-min-length-input").performTextReplacement("17")
        compose.onNodeWithText("취소").performClick()
        compose.runOnIdle { assertEquals(0, repeatSpamSettings(p).minimumLength) }
        tap("repeat-min-length")
        compose.onNodeWithTag("repeat-min-length-input").performTextReplacement("17")
        compose.onNodeWithTag("repeat-min-length-save").performClick()
        compose.runOnIdle { assertEquals(17, repeatSpamSettings(p).minimumLength) }
        // Defaults button restores and saves at once.
        tap("repeat-defaults")
        compose.runOnIdle { val s = repeatSpamSettings(p); assertEquals(Triple(10, 4, 85), Triple(s.windowMinutes, s.count, s.similarityPercent)); assertEquals(8, s.minimumLength) }
        compose.onNodeWithTag("repeat-defaults").assertIsNotEnabled()
        // Master off again keeps values and disables the controls.
        compose.onNodeWithTag("repeat-enabled").performScrollTo().performClick()
        compose.onNodeWithTag("repeat-min-length").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("repeat-count-plus").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertFalse(repeatSpamSettings(p).enabled); assertEquals("BLOCK", repeatActionView(p).mode) }
        compose.onNodeWithTag("repeat_spam-action-mode").performScrollTo().assertIsNotEnabled()
    }
    @Test fun lightStepperSavesImmediatelyAndMasterProtects() = exercise(false)
    @Test fun darkStepperSavesImmediatelyAndMasterProtects() = exercise(true)

    @Test fun everyCriteriaChangeRestartsEvidenceAndOldValuesArePreserved() {
        p.edit().putBoolean(REPEAT_ENABLED_KEY, true).putInt(REPEAT_WINDOW_KEY, 17).putInt(REPEAT_SIMILARITY_KEY, 96).commit()
        screen(false)
        compose.onNodeWithTag("repeat-minutes").assertTextEquals("17분")       // imported value shown unchanged
        compose.onNodeWithTag("repeat-similarity").assertTextEquals("96%")
        val before = revision()
        tap("repeat-minutes-plus")
        compose.runOnIdle { assertEquals(20, repeatSpamSettings(p).windowMinutes); assertNotEquals(before, revision()) }
        tap("repeat-similarity-minus")
        compose.runOnIdle { assertEquals(95, repeatSpamSettings(p).similarityPercent) }
    }

    @Test fun noTargetWarnsAndHelpOpens() {
        p.edit().putBoolean(REPEAT_ENABLED_KEY, true).commit()
        screen(false)
        tap("repeat-yudong"); tap("repeat-kkang")
        compose.runOnIdle { assertFalse(repeatSpamSettings(p).yudong); assertFalse(repeatSpamSettings(p).kkang) }
        compose.onNodeWithTag("repeat-summary").assert(hasText("검사 대상이 모두 꺼져", substring = true))
        compose.onNodeWithTag("repeat-help").performScrollTo().performClick()
        compose.onNodeWithTag("repeat-help-dialog").assertExists()
        compose.onNodeWithText("닫기").performClick()
        compose.onNodeWithTag("repeat-help-dialog").assertDoesNotExist()
    }

    @Test fun observeOnlyUsersStartOnHoldAndCustomOffFollowsDefault() {
        p.edit().putBoolean(REPEAT_ENABLED_KEY, true).putBoolean(REPEAT_OBSERVE_KEY, true).putString("block_process_mode", "BLOCK").commit()
        screen(false)
        compose.onNodeWithTag("repeat_spam-action-mode").performScrollTo().assertTextContains("보류")
        compose.onNodeWithTag("repeat-summary").assert(hasText("보류 기록으로 남깁니다", substring = true))
        compose.onNodeWithTag("repeat_spam-custom-action").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(RepeatActionView("BLOCK", 6, true, true), repeatActionView(p)) }
        compose.onNodeWithTag("repeat-summary").assert(hasText("처리: 차단 기본 설정", substring = true))
        compose.onNodeWithTag("repeat_spam-action-mode").performScrollTo().assertIsNotEnabled()
    }
}
