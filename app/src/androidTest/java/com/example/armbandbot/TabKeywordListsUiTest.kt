package com.heyheyon.armbandbot

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.heyheyon.armbandbot.ui.botColors
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TabKeywordListsUiTest {
    @get:Rule val compose = createComposeRule()
    private val p get() = InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("tab_lists_fixture", Context.MODE_PRIVATE)
    @org.junit.Before fun prepare() { p.edit().clear().putString("target_urls", "https://m.dcinside.com/board/laboratory1\nhttps://m.dcinside.com/mini/armbandbot").commit() }
    @org.junit.After fun cleanup() { p.edit().clear().commit() }
    private fun open() {
        compose.setContent { MaterialTheme { PostAutomationSettingsScreen(p, botColors(true), AutomationSettingsPage.TAB,
            loadTabs={_,_->listOf(GalleryCategory(10,"테스트"))}) {} } }
        compose.waitUntil(10000) { compose.onAllNodesWithTag("move-target-menu").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun keywords(kind:String, text:String, save:Boolean=true) {
        compose.onNodeWithTag("move-$kind-keywords").performScrollTo().performClick()
        compose.onNodeWithTag("move-keyword-input").performTextReplacement(text)
        compose.onNodeWithTag(if(save) "move-keyword-save" else "move-keyword-cancel").performClick()
    }
    private fun target() {
        compose.onNodeWithTag("move-target-menu").performScrollTo().performClick()
        compose.onNodeWithText("테스트").performClick()
    }
    @Test fun multilinePopupsSaveBothListsAndCancelledEditsLeaveRuleUntouched() {
        open(); target()
        keywords("normal","사과\n바나나\n사과")
        keywords("bypass","포도\n딸기")
        assertFalse(p.contains(MOVE_RULES_KEY)) // Popup confirmation edits only the rule draft.
        compose.onNodeWithTag("move-save").performScrollTo().performClick()
        val saved=p.getString(MOVE_RULES_KEY,"[]")!!
        val rule=parseTabMoveRules(saved).single()
        assertEquals(listOf("사과","바나나"),rule.normalKeywords)
        assertEquals(listOf("포도","딸기"),rule.bypassKeywords)
        assertNotNull(matchingMove(listOf(rule),PostKey("M","laboratory1","1"),"포. 도",false,false,false))
        compose.onNodeWithTag("move-edit-${rule.id}").performScrollTo().performClick()
        keywords("normal","discard",save=false)
        keywords("bypass","discard")
        compose.onNodeWithTag("move-cancel").performScrollTo().performClick()
        assertEquals(saved,p.getString(MOVE_RULES_KEY,null))
        assertFalse(p.getBoolean(MOVE_ENABLED_KEY,false))
    }
    @Test fun switchingGalleryCancelsOldDraftAndCannotMoveEditedRuleAcrossGalleries() {
        val old=TabMoveRule("old","M","laboratory1","보존",10,"테스트",false)
        p.edit().putString(MOVE_RULES_KEY,encodeTabMoveRules(listOf(old))).commit()
        open()
        compose.onNodeWithTag("move-edit-old").performScrollTo().performClick()
        keywords("normal","unsaved")
        compose.onNodeWithTag("move-gallery-menu").performScrollTo().performClick()
        compose.onNodeWithText("armbandbot · 미니갤").performClick()
        target(); keywords("bypass","새규칙")
        compose.onNodeWithTag("move-save").performScrollTo().performClick()
        val rules=parseTabMoveRules(p.getString(MOVE_RULES_KEY,"[]")!!)
        assertEquals(old,rules.first())
        assertEquals("MI",rules.last().gallType)
        assertTrue(rules.last().normalKeywords.isEmpty())
        assertEquals(listOf("새규칙"),rules.last().bypassKeywords)
    }
}
