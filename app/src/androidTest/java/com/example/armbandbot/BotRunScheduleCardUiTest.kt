package com.heyheyon.armbandbot

import android.content.Context
import android.widget.TimePicker
import android.view.View
import androidx.compose.runtime.*
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.ViewAction
import androidx.test.espresso.UiController
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.*
import org.hamcrest.Matcher
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import com.heyheyon.armbandbot.ui.botColors
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Isolated fixture: never opens MainActivity or starts a service/network request. */
class BotRunScheduleCardUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun narrowHeaderKeepsTitleAndSwitchSeparateAndBodyEditable() {
        val state = BotRunScheduleEditorState(
            BotRunScheduleLoadResult.Valid(BotRunSchedule(true, listOf(BotRunWindow(540, 1080)))), {})
        compose.setContent { MaterialTheme {
            Column(Modifier.width(280.dp).verticalScroll(rememberScrollState())) {
                BotRunScheduleSettingsCard(state, botColors(true))
            }
        } }
        val title = compose.onNodeWithText("작동 시간대").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val toggle = compose.onNodeWithTag("schedule-enabled").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("Header title must not overlap switch", title.right <= toggle.left)
        compose.onNodeWithTag("schedule-card").assertHasNoClickAction()
        compose.onNodeWithTag("schedule-enabled").performClick().assertIsOff()
        compose.onNodeWithTag("schedule-add").performScrollTo().performClick()
        compose.onNodeWithText("시간대2").assertExists()
        compose.onNodeWithTag("schedule-start-1").performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("schedule-end-1").performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.runOnIdle { assertEquals(2, state.windows.size) }
    }

    private fun pick(hour: Int, minute: Int) {
        onView(isAssignableFrom(TimePicker::class.java)).perform(object : ViewAction {
            override fun getConstraints(): Matcher<View> = isAssignableFrom(TimePicker::class.java)
            override fun getDescription() = "set native time picker"
            override fun perform(ui: UiController, view: View) {
                (view as TimePicker).apply { this.hour = hour; this.minute = minute }
                ui.loopMainThreadUntilIdle()
            }
        })
        onView(withId(android.R.id.button1)).perform(click())
        compose.waitForIdle()
    }

    @Test fun threeRowsNativeEditEqualityDisabledRetentionAndRenderedReopen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("schedule_ui_three_rows_fixture", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        saveBotRunSchedule(prefs, BotRunSchedule(true, listOf(BotRunWindow(600, 660))))
        var generation by mutableIntStateOf(0)
        try {
            compose.setContent { MaterialTheme { key(generation) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    BotRunScheduleSettingsCard(prefs, botColors(false))
                }
            } } }
            repeat(2) { compose.onNodeWithTag("schedule-add").performScrollTo().performClick() }
            compose.onNodeWithText("시간대3").assertExists()
            compose.onNodeWithTag("schedule-start-2").performScrollTo().performClick()
            pick(22, 0)
            compose.onNodeWithTag("schedule-end-2").performScrollTo().performClick()
            pick(5, 0)
            compose.onNodeWithText("22:00 ~ 05:00 · 다음 날 종료").assertExists()
            compose.onNodeWithTag("schedule-remove-1").performScrollTo().performClick()
            compose.onNodeWithText("시간대3").assertDoesNotExist()
            compose.onNodeWithText("시간대2").assertExists()
            compose.onNodeWithTag("schedule-enabled").performScrollTo().performClick().assertIsOff()
            val before = prefs.getString(RUN_SCHEDULE_WINDOWS_JSON_KEY, null)
            compose.onNodeWithTag("schedule-end-1").performScrollTo().performClick()
            pick(22, 0)
            compose.onNodeWithTag("schedule-error").assertExists()
            assertEquals(before, prefs.getString(RUN_SCHEDULE_WINDOWS_JSON_KEY, null))
            compose.runOnIdle { generation++ }
            compose.onNodeWithTag("schedule-enabled").assertIsOff()
            compose.onNodeWithText("시간대2").assertExists()
            compose.onNodeWithText("22:00 ~ 05:00 · 다음 날 종료").assertExists()
            compose.runOnIdle {
                val saved = (loadBotRunSchedule(prefs) as BotRunScheduleLoadResult.Valid).schedule
                assertFalse(saved.enabled)
                assertEquals(listOf(BotRunWindow(600, 660), BotRunWindow(1320, 300)), saved.windows)
            }
        } finally { prefs.edit().clear().commit() }
    }

    @Test fun legacyEqualNativePickerPreservesOriginalUntilRepaired() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("schedule_ui_equal_fixture", Context.MODE_PRIVATE)
        prefs.edit().clear().putInt("run_schedule_start_minute", 300)
            .putInt("run_schedule_end_minute", 300).commit()
        try {
            compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
                BotRunScheduleSettingsCard(prefs, botColors(false))
            } } }
            compose.onNodeWithText("시작 05:00").assertExists()
            compose.onNodeWithText("종료 05:00").assertExists()
            compose.onNodeWithTag("schedule-enabled").assertIsOff().assertIsNotEnabled()
            compose.onNodeWithTag("schedule-add").assertIsNotEnabled()
            assertFalse(prefs.contains(RUN_SCHEDULE_WINDOWS_JSON_KEY))
            compose.onNodeWithTag("schedule-end-0").performScrollTo().performClick()
            pick(5, 0)
            assertFalse(prefs.contains(RUN_SCHEDULE_WINDOWS_JSON_KEY))
            assertEquals(300, prefs.getInt("run_schedule_end_minute", -1))
            compose.onNodeWithTag("schedule-end-0").performScrollTo().performClick()
            pick(6, 0)
            compose.onNodeWithTag("schedule-enabled").performScrollTo().assertIsEnabled().assertIsOff()
            val saved = (loadBotRunSchedule(prefs) as BotRunScheduleLoadResult.Valid).schedule
            assertEquals(listOf(BotRunWindow(300, 360)), saved.windows)
            assertFalse(saved.enabled)
        } finally { prefs.edit().clear().commit() }
    }

    @Test fun addRemoveReindexAndReloadRealPreferences() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("schedule_ui_fixture", Context.MODE_PRIVATE)
        prefs.edit().clear().putInt("run_schedule_start_minute", 60).putInt("run_schedule_end_minute", 120).commit()
        try {
            compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { BotRunScheduleSettingsCard(prefs, botColors(false)) } } }
            assertFalse(prefs.contains(RUN_SCHEDULE_WINDOWS_JSON_KEY))
            compose.onNodeWithText("시간대1").assertExists()
            compose.onNodeWithTag("schedule-remove-0").assertIsNotEnabled()
            compose.onNodeWithTag("schedule-add").performScrollTo().performClick()
            compose.onNodeWithText("시간대2").assertExists()
            compose.onNodeWithTag("schedule-remove-0").performScrollTo().performClick()
            compose.onNodeWithText("시간대2").assertDoesNotExist()
            compose.onNodeWithText("시간대1").assertExists()
            compose.runOnIdle {
                val result = loadBotRunSchedule(prefs) as BotRunScheduleLoadResult.Valid
                assertEquals(listOf(BotRunWindow(540,1080)), result.schedule.windows)
                assertFalse(result.schedule.enabled)
            }
        } finally { prefs.edit().clear().commit() }
    }

    @Test fun corruptCanonicalShowsExplicitRepairAndNeverAutoSaves() {
        var saved: BotRunSchedule? = null
        val state = BotRunScheduleEditorState(BotRunScheduleLoadResult.Error(true,"broken","손상된 설정")) { saved = it }
        compose.setContent { MaterialTheme { BotRunScheduleSettingsCard(state) } }
        compose.onNodeWithTag("schedule-error").assertExists()
        compose.onNodeWithTag("schedule-enabled").assertIsNotEnabled().assertIsOn()
        assertNull(saved)
        compose.onNodeWithTag("schedule-repair").performClick()
        compose.onNodeWithTag("schedule-error").assertDoesNotExist()
        compose.onNodeWithTag("schedule-enabled").assertIsOn()
        compose.runOnIdle { assertEquals(listOf(BotRunWindow(540,1080)), saved!!.windows) }
    }
}
