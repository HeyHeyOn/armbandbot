package com.heyheyon.armbandbot

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject
import java.io.File

/** Reproduces list state observations; never taps a switch or starts a service. */
class BotRunningStateUiTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun prefs(id: String) = context.getSharedPreferences("bot_prefs_$id",Context.MODE_PRIVATE)
    @Test fun listSwitchFollowsServiceStateAndBotIdentity() {
        val a="post297_ui_a";val b="post297_ui_b"
        val result=JSONObject()
        prefs(a).edit().clear().putString("bot_name","A").putString("saved_cookie","offline-fixture").putBoolean("is_running",true).commit()
        prefs(b).edit().clear().putString("bot_name","B").putString("saved_cookie","offline-fixture").putBoolean("is_running",false).commit()
        var currentId by mutableStateOf(a)
        try {
            compose.setContent { MaterialTheme { Column {
                androidx.compose.material3.Text("count=" + rememberRunningBotCount(context,listOf(a,b)))
                BotListItem(0,currentId,null,0f,false,{}, {}, {}, {}, {}, {}, {}, {})
            } } }
            val switch=compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role,Role.Switch))
            switch.assertIsOn()
            compose.onNodeWithText("count=1").assertExists()
            compose.runOnIdle { prefs(a).edit().putBoolean("is_running",false).commit() }
            compose.waitForIdle()
            switch.assertIsOff()
            compose.onNodeWithText("count=0").assertExists()
            result.put("service_stopped_prefs_false_ui_off",true)
            compose.runOnIdle { prefs(a).edit().putBoolean("is_running",true).commit() }
            switch.assertIsOn()
            compose.runOnIdle { currentId=b }
            compose.onNodeWithText("B").assertExists()
            switch.assertIsOff()
            assertFalse(prefs(b).getBoolean("is_running",true))
            result.put("item_changed_to_stopped_bot_b_ui_off",true)
            compose.runOnIdle { prefs(a).edit().putBoolean("is_running",false).commit() }
            switch.assertIsOff()
            compose.runOnIdle { prefs(b).edit().putBoolean("is_running",true).commit() }
            switch.assertIsOn()
            compose.runOnIdle { prefs(b).edit().putBoolean("is_running",false).commit() }
            switch.assertIsOff()
        } finally {
            File(context.getExternalFilesDir(null),"running-state-ui.json").writeText(result.toString(2))
            context.deleteSharedPreferences("bot_prefs_$a");context.deleteSharedPreferences("bot_prefs_$b")
        }
    }

    @Test fun reorderedBotUsesItsCurrentPositionOnTheNextDrag() {
        val a="post297_drag_a";val b="post297_drag_b"
        prefs(a).edit().putString("bot_name","Drag A").putBoolean("is_running",true).commit()
        prefs(b).edit().putString("bot_name","Drag B").putBoolean("is_running",false).commit()
        var ids by mutableStateOf(listOf(a,b))
        val starts=mutableListOf<Int>()
        try {
            compose.setContent { MaterialTheme { Column {
                ids.forEachIndexed { index,id -> key(id) {
                    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.testTag(id)) {
                        BotListItem(index,id,null,0f,false,{}, { starts.add(it) }, {}, {}, {}, {}, {}, {})
                    }
                } }
            } } }
            fun dragA() {
                compose.onNodeWithTag(a).performTouchInput {
                    down(center);advanceEventTime(700);moveBy(androidx.compose.ui.geometry.Offset(0f,30f));up()
                }
            }
            dragA()
            compose.runOnIdle { assertEquals(listOf(0),starts);ids=listOf(b,a) }
            dragA()
            compose.runOnIdle { assertEquals(listOf(0,1),starts) }
            compose.onNode(hasAnyAncestor(hasTestTag(a)) and SemanticsMatcher.expectValue(SemanticsProperties.Role,Role.Switch),useUnmergedTree=true).assertIsOn()
            compose.onNode(hasAnyAncestor(hasTestTag(b)) and SemanticsMatcher.expectValue(SemanticsProperties.Role,Role.Switch),useUnmergedTree=true).assertIsOff()
        } finally {
            context.deleteSharedPreferences("bot_prefs_$a");context.deleteSharedPreferences("bot_prefs_$b")
        }
    }
}
