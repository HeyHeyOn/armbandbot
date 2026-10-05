package com.heyheyon.armbandbot

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.heyheyon.armbandbot.ui.botColors
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AutomationNavigationUiTest {
    @get:Rule val compose = createComposeRule()
    private val p get() = InstrumentationRegistry.getInstrumentation().targetContext
        .getSharedPreferences("automation_navigation_fixture", Context.MODE_PRIVATE)
    @org.junit.Before fun prepare() {
        p.edit().clear().putString("target_urls", "https://gall.dcinside.com/mgallery/board/lists/?id=laboratory1")
            .putString(BUMP_RULES_KEY, encodeBumpRules(listOf(BumpRule("saved", "https://gall.dcinside.com/mgallery/board/view/?id=laboratory1&no=2371", listOf(540)))))
            .putString(MOVE_RULES_KEY, encodeTabMoveRules(listOf(TabMoveRule("saved", "M", "laboratory1", "시험", 10, "테스트", false))))
            .putString("remote_lists_url", "https://raw.githubusercontent.com/HeyHeyOn/armbandbot/master/docs/examples/remote-lists.json")
            .putString("banned_normal", "local-retained").commit()
    }
    @org.junit.After fun cleanup() { p.edit().clear().commit() }
    private fun switch(tag:String) = compose.onNode(hasAnyAncestor(hasTestTag(tag)) and
        SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch), useUnmergedTree=true)
    private fun management(open:(String)->Unit) {
        compose.setContent { MaterialTheme {
            var refresh by remember { mutableStateOf(false) }
            Column { ManagementAutomationSettings(p, botColors(true), refresh, { refresh=it }, open) }
        } }
    }
    @Test fun managementSwitchesDoNotNavigateAndKeepSavedRules() {
        val opened=mutableListOf<String>(); management { opened.add(it) }
        compose.onNodeWithText("관리 자동화").assertIsDisplayed()
        compose.onNodeWithText("원격 목록").assertDoesNotExist()
        switch("bump-entry").assertIsOff().performClick().assertIsOn()
        assertTrue(p.getBoolean(BUMP_ENABLED_KEY,false)); assertTrue(opened.isEmpty())
        switch("bump-entry").performClick().assertIsOff()
        assertEquals(1,parseBumpRules(p.getString(BUMP_RULES_KEY,"[]")!!).size)
        switch("move-entry").performClick().assertIsOn(); assertTrue(opened.isEmpty())
        compose.onNodeWithText("예약 끌올").performClick()
        assertEquals(listOf("BUMP_SETTINGS"),opened)
    }
    @Test fun remoteCloudBelongsToItsFilterAndDoesNotOpenTheLocalEditor() {
        var edits=0
        compose.setContent { MaterialTheme { RemoteListTextCard("일반 금지어","local-retained",botColors(false),p,"normal") { edits++ } } }
        compose.onNodeWithTag("remote-cloud-normal").performClick()
        compose.onNodeWithTag("remote-list-dialog").assertExists()
        assertEquals(0,edits)
        compose.onNodeWithTag("remote-list-enabled").assertIsOff()
        assertEquals("local-retained",p.getString("banned_normal",null))
    }
    @Test fun missingScheduleCannotEnableAndOffersSettings() {
        p.edit().remove(BUMP_RULES_KEY).commit()
        val opened=mutableListOf<String>();management { opened.add(it) }
        switch("bump-entry").performClick()
        assertFalse(p.getBoolean(BUMP_ENABLED_KEY,false)); assertTrue(opened.isEmpty())
        compose.onNodeWithTag("automation-enable-error").assertExists()
        compose.onNodeWithText("설정하기").performClick()
        assertEquals(listOf("BUMP_SETTINGS"),opened)
    }
    @Test fun refreshRowUsesExistingCallbackWithoutOpeningSettings() {
        val opened=mutableListOf<String>();management { opened.add(it) }
        switch("gallery-refresh-entry").performClick().assertIsOn()
        assertTrue(opened.isEmpty())
        compose.onNodeWithText("갤러리 설정 자동 갱신").performClick()
        assertEquals(listOf("GALLERY_REFRESH"),opened)
    }
    @Test fun bumpPageIsFocusedAndLongGuidanceIsOptIn() {
        p.edit().putString(MOVE_RULES_KEY,"broken").commit()
        compose.setContent { MaterialTheme { PostAutomationSettingsScreen(p,botColors(true),AutomationSettingsPage.BUMP) {} } }
        compose.onNodeWithTag("bump-url").assertExists()
        compose.onNodeWithTag("move-keyword").assertDoesNotExist()
        compose.onNodeWithTag("remote-url").assertDoesNotExist()
        compose.onNodeWithTag("automation-error").assertDoesNotExist()
        compose.onNodeWithTag("automation-help-dialog").assertDoesNotExist()
        compose.onNodeWithTag("automation-help").performClick()
        compose.onNodeWithTag("automation-help-dialog").assertExists()
        compose.onNodeWithText("닫기").performClick()
        compose.onNodeWithTag("automation-help-dialog").assertDoesNotExist()
    }
    @Test fun tabPageIsFocusedAndUsesNaturalTitle() {
        compose.setContent { MaterialTheme { PostAutomationSettingsScreen(p,botColors(false),AutomationSettingsPage.TAB) {} } }
        compose.onNodeWithText("자동 탭 분류").assertIsDisplayed()
        compose.onNodeWithTag("move-keyword").assertExists()
        compose.onNodeWithTag("bump-url").assertDoesNotExist()
        compose.onNodeWithTag("remote-url").assertDoesNotExist()
    }
    @Test fun remotePopupHidesLongInstructionsUntilHelp() {
        p.edit().putString(BUMP_RULES_KEY,"broken").putString(MOVE_RULES_KEY,"broken").commit()
        compose.setContent { MaterialTheme { RemoteListSettingsDialog(p,"normal",botColors(true)) {} } }
        compose.onNodeWithTag("remote-list-url").assertExists()
        compose.onNodeWithTag("bump-url").assertDoesNotExist()
        compose.onNodeWithTag("move-keyword").assertDoesNotExist()
        compose.onNodeWithTag("remote-list-error").assertDoesNotExist()
        compose.onNodeWithText("한 열",substring=true).assertDoesNotExist()
        compose.onNodeWithTag("remote-list-help").performClick()
        compose.onNodeWithText("한 열",substring=true).assertExists()
    }
}
