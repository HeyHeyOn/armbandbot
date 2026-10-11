package com.heyheyon.armbandbot

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
        compose.setContent { MaterialTheme { var enabled by remember { mutableStateOf(false) }
            Column(Modifier.verticalScroll(rememberScrollState())) { RepeatSpamSettingsCard(p,botColors(dark),enabled) {
                enabled=it; p.edit().putBoolean(REPEAT_ENABLED_KEY,it).repeatSpamChanged().apply()
            } }
        } }
    }
    private fun exercise(dark: Boolean) {
        screen(dark)
        compose.onNodeWithTag("repeat-minutes").assertIsNotEnabled()
        compose.onNodeWithTag("repeat-enabled").performClick()
        compose.onNodeWithTag("repeat-minutes").performTextReplacement("17")
        compose.onNodeWithTag("repeat-count").performScrollTo().performTextReplacement("2")
        compose.onNodeWithTag("repeat-similarity").performScrollTo().performTextReplacement("100")
        assertEquals(10,repeatSpamSettings(p).windowMinutes)
        compose.onNodeWithTag("repeat-save").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(17,repeatSpamSettings(p).windowMinutes); assertEquals(2,repeatSpamSettings(p).count); assertEquals(100,repeatSpamSettings(p).similarityPercent) }
        compose.onNodeWithTag("repeat-observe").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(repeatSpamSettings(p).observeOnly) }
        compose.onNodeWithTag("repeat-enabled").performScrollTo().performClick()
        compose.onNodeWithTag("repeat-save").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(17,repeatSpamSettings(p).windowMinutes) }
    }
    @Test fun lightCriteriaSaveAndMasterProtection() = exercise(false)
    @Test fun darkCriteriaSaveAndMasterProtection() = exercise(true)
    @Test fun invalidCriteriaCannotOverwriteSavedValuesAndDefaultsNeedSaving() {
        screen(false); compose.onNodeWithTag("repeat-enabled").performClick()
        compose.onNodeWithTag("repeat-count").performScrollTo().performTextReplacement("1")
        compose.onNodeWithTag("repeat-save").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("기본값 불러오기").performClick()
        compose.onNodeWithTag("repeat-save").performClick()
        compose.runOnIdle { assertEquals(RepeatSpamSettings(enabled=true),repeatSpamSettings(p)) }
    }
}
