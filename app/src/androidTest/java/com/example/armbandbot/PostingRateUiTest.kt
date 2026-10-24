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

class PostingRateUiTest {
    @get:Rule val compose=createComposeRule()
    private val p get()=InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("rate_ui_fixture",Context.MODE_PRIVATE)
    @org.junit.Before fun reset(){p.edit().clear().commit()}
    @org.junit.After fun cleanup(){p.edit().clear().commit()}
    private fun tap(tag:String)=compose.onNodeWithTag(tag).performScrollTo().performClick()
    private fun exercise(dark:Boolean) {
        compose.setContent { MaterialTheme { var master by remember { mutableStateOf(false) }
            Column(Modifier.verticalScroll(rememberScrollState())) { RepeatSpamSettingsCard(p,botColors(dark),master) {
                master=it;p.edit().putBoolean(REPEAT_ENABLED_KEY,it).repeatSpamChanged().apply()
            } }
        } }
        compose.onNodeWithTag("rate-enabled").performScrollTo().assertIsNotEnabled()
        tap("repeat-enabled");tap("rate-enabled")
        compose.onNodeWithTag("posting_rate-action-mode").performScrollTo().assertTextContains("보류")
        tap("repeat-content-enabled")
        compose.onNodeWithTag("repeat-min-length").assertIsNotEnabled()
        compose.onNodeWithTag("rate-ip-seconds").assertIsEnabled()
        for ((tag,value) in listOf("rate-ip-seconds" to "180","rate-ip-count" to "8","rate-uid-seconds" to "90","rate-uid-count" to "4")) {
            tap(tag);compose.onNodeWithTag("rate-number-input").performTextReplacement(value)
            compose.onNodeWithTag("rate-number-save").performClick()
        }
        compose.runOnIdle {
            val s=postingRateSettings(p)
            assertEquals(180,s.ipSeconds);assertEquals(8,s.ipCount);assertEquals(90,s.uidSeconds);assertEquals(4,s.uidCount)
            assertFalse(repeatSpamSettings(p).contentEnabled);assertEquals("DELETE",repeatActionView(p).mode)
        }
        tap("rate-ip-seconds")
        for(invalid in listOf("","0","9","3601","-1","1.5","2147483648")) {
            compose.onNodeWithTag("rate-number-input").performTextReplacement(invalid)
            compose.onNodeWithTag("rate-number-save").assertIsNotEnabled()
        }
        compose.onNodeWithTag("rate-number-input").performTextReplacement("60")
        compose.onNodeWithText("취소").performClick()
        compose.runOnIdle { assertEquals(180,postingRateSettings(p).ipSeconds) }
        tap("posting_rate-action-mode");compose.onNodeWithTag("posting_rate-action-mode-DELETE").performClick()
        compose.runOnIdle { assertEquals("DELETE",postingRateActionView(p).mode) }
        tap("rate-ip-target");compose.onNodeWithTag("rate-ip-count").assertIsNotEnabled()
        tap("rate-uid-target");compose.onNodeWithTag("rate-summary").assertTextContains("검사 대상이 모두 꺼져",substring=true)
        tap("rate-enabled");compose.onNodeWithTag("rate-uid-target").assertIsNotEnabled()
        tap("repeat-enabled");compose.onNodeWithTag("rate-enabled").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(180,postingRateSettings(p).ipSeconds);assertFalse(postingRateSettings(p).enabled) }
    }
    @Test fun lightControlsDefaultsAndIndependentSettings()=exercise(false)
    @Test fun darkControlsDefaultsAndIndependentSettings()=exercise(true)
}
