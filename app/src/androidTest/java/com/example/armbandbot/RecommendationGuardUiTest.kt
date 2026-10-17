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

class RecommendationGuardUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val p get() = context.getSharedPreferences("recommend_ui", Context.MODE_PRIVATE)
    @org.junit.Before fun setup() { p.edit().clear().commit() }
    @org.junit.After fun cleanup() { p.edit().clear().commit() }

    @Test fun defaultsDisabledAndEditRequiresEnable() {
        compose.setContent { MaterialTheme { RecommendationSettingsScreen(p, botColors(true)) {} } }
        compose.onNodeWithTag("recommend-enabled").assertIsOff()
        compose.onNodeWithTag("recommend-edit").assertIsNotEnabled()
        compose.onNodeWithText("3분 이내에 추천이 10개 이상 늘면 감지").assertExists()
        compose.onNodeWithTag("recommend-enabled").performClick().assertIsOn()
        compose.onNodeWithTag("recommend-edit").assertIsEnabled()
    }
    @Test fun saveValidCriteriaRejectInvalidAndCancelPreservesValue() {
        p.edit().putBoolean(RECOMMEND_GUARD_ENABLED, true).commit()
        compose.setContent { MaterialTheme { RecommendationSettingsScreen(p, botColors(false)) {} } }
        compose.onNodeWithTag("recommend-edit").performClick()
        compose.onNodeWithTag("recommend-seconds").performTextReplacement("0")
        compose.onNodeWithTag("recommend-save").performClick()
        compose.onNodeWithText("기준 시간은 10초~60분으로 입력해 주세요.").assertExists()
        assertEquals(180, p.getInt(RECOMMEND_GUARD_SECONDS, 180))
        compose.onNodeWithTag("recommend-seconds").performTextReplacement("125")
        compose.onNodeWithTag("recommend-count").performTextReplacement("23")
        compose.onNodeWithTag("recommend-save").performClick()
        compose.onNodeWithText("2분 5초 이내에 추천이 23개 이상 늘면 감지").assertExists()
        assertEquals(125, p.getInt(RECOMMEND_GUARD_SECONDS, 0)); assertEquals(23, p.getInt(RECOMMEND_GUARD_COUNT, 0))
        compose.onNodeWithTag("recommend-edit").performClick()
        compose.onNodeWithTag("recommend-count").performTextReplacement("999")
        compose.onNodeWithText("취소").performClick()
        assertEquals(23, p.getInt(RECOMMEND_GUARD_COUNT, 0))
    }
    @Test fun managementEntryOpensMatchingPageAndBackKeepsCriteria() {
        compose.setContent { MaterialTheme {
            var opened by remember { mutableStateOf(false) }
            if (opened) RecommendationSettingsScreen(p, botColors(true)) { opened = false }
            else RecommendationSettingsEntry(p, botColors(true)) { assertEquals(RECOMMEND_GUARD_ROUTE, it); opened = true }
        } }
        compose.onNodeWithText("추천수 조작 방지").performClick()
        compose.onNodeWithTag("recommend-guard-page").assertExists()
        compose.onNodeWithTag("recommend-enabled").performClick()
        compose.onNodeWithTag("recommend-help").performClick()
        compose.onNodeWithText("추천수 조작 방지 도움말").assertExists()
        compose.onNodeWithText("닫기").performClick()
        assertTrue(p.getBoolean(RECOMMEND_GUARD_ENABLED, false))
    }
}
