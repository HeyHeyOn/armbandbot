package com.heyheyon.armbandbot

import android.content.Context
import android.widget.TimePicker
import android.view.View
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.ViewAction
import androidx.test.espresso.UiController
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.platform.app.InstrumentationRegistry
import com.heyheyon.armbandbot.ui.botColors
import org.hamcrest.Matcher
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class IndependentRemoteListUiTest {
    @get:Rule val compose=createComposeRule()
    private val p get()=InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("independent_remote_ui",Context.MODE_PRIVATE)
    @org.junit.Before fun prepare(){p.edit().clear().commit()}
    @org.junit.After fun cleanup(){p.edit().clear().commit()}
    @Test fun cloudOpensOnlyItsChannelCancelDoesNotSaveAndEnableIsIndependent() {
        var edits=0
        compose.setContent {MaterialTheme { RemoteListTextCard("ID/IP 화이트리스트","local",botColors(true),p,"user_whitelist") {edits++} }}
        compose.onNodeWithTag("remote-cloud-user_whitelist").performClick()
        assertEquals(0,edits)
        compose.onNodeWithTag("remote-list-dialog").assertExists()
        compose.onNodeWithTag("remote-list-url").performTextReplacement("https://raw.githubusercontent.com/a/b/main/white.txt")
        compose.onNodeWithText("취소").performClick()
        assertEquals("",p.getString(remoteListPrefKey("user_whitelist","url"),""))
        compose.onNodeWithTag("remote-cloud-user_whitelist").performClick()
        compose.onNodeWithTag("remote-list-url").performTextReplacement("https://raw.githubusercontent.com/a/b/main/white.txt")
        compose.onNodeWithTag("remote-list-enabled").performClick()
        compose.onNodeWithTag("remote-list-save").performClick()
        assertTrue(p.getBoolean(remoteListPrefKey("user_whitelist","enabled"),false))
        assertFalse(p.getBoolean(remoteListPrefKey("user_blacklist","enabled"),false))
        compose.onNodeWithTag("remote-cloud-user_whitelist").performClick()
        compose.onNodeWithTag("remote-list-enabled").assertIsOn().performClick()
        compose.onNodeWithTag("remote-list-save").performClick()
        assertFalse(p.getBoolean(remoteListPrefKey("user_whitelist","enabled"),true))
        assertEquals("https://raw.githubusercontent.com/a/b/main/white.txt",p.getString(remoteListPrefKey("user_whitelist","url"),null))
    }
    @Test fun invalidUrlStaysDraftAndHelpUsesOneColumnNoTypeNames() {
        compose.setContent {MaterialTheme {RemoteListSettingsDialog(p,"normal",botColors(false)) {}}}
        compose.onNodeWithTag("remote-list-url").performTextReplacement("https://evil.example/list")
        compose.onNodeWithTag("remote-list-enabled").performClick()
        compose.onNodeWithTag("remote-list-save").performClick()
        compose.onNodeWithTag("remote-list-error").assertExists()
        assertEquals("",p.getString(remoteListPrefKey("normal","url"),""))
        compose.onNodeWithTag("remote-list-help").performClick()
        compose.onNodeWithTag("remote-list-help-dialog").assertExists()
        compose.onNodeWithText("한 열",substring=true).assertExists()
        compose.onNodeWithText("type,value",substring=true).assertDoesNotExist()
    }
}

class AutomationGalleryUiTest {
    @get:Rule val compose=createComposeRule()
    private val p get()=InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("multi_gallery_ui",Context.MODE_PRIVATE)
    @org.junit.After fun cleanup(){p.edit().clear().commit()}
    @Test fun mobileGalleriesHaveIndependentTabsAndRulesWithoutCrossGalleryApply() {
        p.edit().clear().putString("target_urls","https://m.dcinside.com/board/laboratory1\nhttps://m.dcinside.com/mini/armbandbot").commit()
        compose.setContent {MaterialTheme {PostAutomationSettingsScreen(p,botColors(true),AutomationSettingsPage.TAB,loadTabs={ g,_-> if(g.second=="laboratory1")listOf(GalleryCategory(10,"테스트"))else listOf(GalleryCategory(20,"제안")) }){}}}
        compose.waitUntil(10000){compose.onAllNodesWithTag("move-target-menu").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("move-target-menu").performScrollTo().performClick();compose.onNodeWithText("테스트").performClick()
        compose.onNodeWithTag("move-normal-keywords").performScrollTo().performClick()
        compose.onNodeWithTag("move-keyword-input").performTextReplacement("first")
        compose.onNodeWithTag("move-keyword-save").performClick()
        compose.onNodeWithTag("move-save").performScrollTo().performClick()
        compose.onNodeWithTag("move-gallery-menu").performScrollTo().performClick();compose.onNodeWithText("armbandbot · 미니갤").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("move-target-menu").performScrollTo().performClick();compose.onNodeWithText("제안").performClick()
        compose.onNodeWithTag("move-normal-keywords").performScrollTo().performClick()
        compose.onNodeWithTag("move-keyword-input").performTextReplacement("second")
        compose.onNodeWithTag("move-keyword-save").performClick()
        compose.onNodeWithTag("move-save").performScrollTo().performClick()
        val rules=parseTabMoveRules(p.getString(MOVE_RULES_KEY,"[]")!!)
        assertEquals(listOf("laboratory1","armbandbot"),rules.map { it.gallId })
        assertEquals(listOf(10,20),rules.map { it.headtext })
        assertEquals(20,matchingMove(rules,PostKey("MI","armbandbot","1"),"first second",false,false,false)?.headtext)
        assertNull(matchingMove(rules,PostKey("M","other","1"),"first second",false,false,false))
    }
}
