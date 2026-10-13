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

class AllPostsRecheckUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val bot = "all_recheck_ui"
    private val p get() = context.getSharedPreferences("bot_prefs_$bot", Context.MODE_PRIVATE)
    @org.junit.Before fun prepare() {
        GlobalBotState.initDb(context)
        p.edit().clear().putString("saved_cookie", "local-ui-fixture-no-network")
            .putBoolean("pum_recheck_every_cycle", true).commit()
    }
    @org.junit.After fun cleanup() { p.edit().clear().commit() }

    @Test fun actualSpeedPageSavesAndRetainsChoiceWithoutChangingPumOption() {
        compose.setContent { MaterialTheme { BotDetailScreen(bot, false, {}, {}) } }
        compose.onNodeWithTag("bot-tab-settings").performClick()
        compose.onNodeWithText("탐색 속도 및 범위").performScrollTo().performClick()
        compose.onNodeWithTag("all-posts-recheck").performScrollTo().assertIsOff().performClick().assertIsOn()
        assertTrue(p.getBoolean(ALL_POSTS_RECHECK_KEY, false))
        assertTrue(p.getBoolean("pum_recheck_every_cycle", false))
        compose.onNodeWithContentDescription("뒤로").performClick()
        compose.onNodeWithText("탐색 속도 및 범위").performScrollTo().performClick()
        compose.onNodeWithTag("all-posts-recheck").performScrollTo().assertIsOn().performClick().assertIsOff()
        assertFalse(p.getBoolean(ALL_POSTS_RECHECK_KEY, true))
        assertTrue(p.getBoolean("pum_recheck_every_cycle", false))
    }

    @Test fun switchingBotsAndThemesKeepsSeparateSavedValues() {
        val other = context.getSharedPreferences("bot_prefs_${bot}_other", Context.MODE_PRIVATE)
        val current = mutableStateOf(false)
        other.edit().clear().putBoolean(ALL_POSTS_RECHECK_KEY, true).commit()
        try {
            compose.setContent { MaterialTheme {
                AllPostsRecheckSetting(if (current.value) "${bot}_other" else bot, if (current.value) other else p, botColors(current.value))
            } }
            compose.onNodeWithTag("all-posts-recheck").assertIsOff()
            compose.runOnIdle { current.value = true }
            compose.onNodeWithTag("all-posts-recheck").assertIsOn().performClick().assertIsOff()
            compose.runOnIdle { current.value = false }
            compose.onNodeWithTag("all-posts-recheck").assertIsOff().performClick().assertIsOn()
            assertTrue(p.getBoolean(ALL_POSTS_RECHECK_KEY, false))
            assertFalse(other.getBoolean(ALL_POSTS_RECHECK_KEY, true))
        } finally { other.edit().clear().commit() }
    }
}
