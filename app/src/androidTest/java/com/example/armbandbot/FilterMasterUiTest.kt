package com.heyheyon.armbandbot

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.heyheyon.armbandbot.ui.botColors
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class FilterMasterUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val bot="filter_master_fixture"
    private val p get()=context.getSharedPreferences("bot_prefs_$bot",Context.MODE_PRIVATE)
    @org.junit.Before fun prepare() { GlobalBotState.initDb(context); p.edit().clear().putString("saved_cookie","local-ui-fixture-no-network").putString("normal_text","사과").putString("bypass_text","바나나").putBoolean("is_yudong_post_block",true).commit() }
    @org.junit.After fun cleanup() { p.edit().clear().commit() }
    private fun switch(tag:String)=compose.onNode(hasAnyAncestor(hasTestTag(tag)) and SemanticsMatcher.expectValue(SemanticsProperties.Role,Role.Switch),useUnmergedTree=true)
    @Test fun mainRowSwitchesAreIndependentOfNavigationAndPreserveChildSettings() {
        val opens=mutableListOf<String>()
        compose.setContent { MaterialTheme { Column {
            FilterMasterEntry(p,botColors(false),WORD_FILTER_ENABLED_KEY) {opens.add("WORD")}
            FilterMasterEntry(p,botColors(false),YUDONG_FILTER_ENABLED_KEY) {opens.add("YUDONG")}
        } } }
        switch("word-filter-entry").assertIsOn().performClick().assertIsOff()
        switch("yudong-filter-entry").assertIsOn().performClick().assertIsOff()
        assertTrue(opens.isEmpty())
        compose.onNodeWithText("금지어 필터").performClick()
        assertEquals(listOf("WORD"),opens)
        assertEquals("사과",p.getString("normal_text",null))
        assertTrue(p.getBoolean("is_yudong_post_block",false))
    }
    @Test fun actualWordPageDisablesLocalCloudAndSubOptionsAndRestoresThem() {
        compose.setContent { MaterialTheme {BotDetailScreen(bot,false,{},{})} }
        compose.onNodeWithTag("bot-tab-filters").performClick()
        compose.onNodeWithText("금지어 필터").performScrollTo().performClick()
        compose.onNodeWithTag("word-filter-master").assertIsOn().performClick().assertIsOff()
        compose.onNodeWithTag("remote-cloud-normal").assertIsNotEnabled()
        compose.onNodeWithTag("remote-cloud-bypass").assertIsNotEnabled()
        compose.onNodeWithText("일반 금지어 (완전히 일치하는 경우 차단)").assertIsNotEnabled()
        compose.onNodeWithTag("word-filter-master").performScrollTo().performClick().assertIsOn()
        compose.onNodeWithTag("remote-cloud-normal").assertIsEnabled()
        compose.onNodeWithText("일반 금지어 (완전히 일치하는 경우 차단)").performClick()
        compose.onNodeWithText("일반 금지어 설정").assertExists()
        assertEquals("사과",p.getString("normal_text",null))
    }
    @Test fun actualYudongPageDisablesEverySubSwitchAndReenablesWithoutLosingValues() {
        compose.setContent { MaterialTheme {BotDetailScreen(bot,false,{},{})} }
        compose.onNodeWithTag("bot-tab-filters").performClick()
        compose.onNodeWithText("유동 필터").performScrollTo().performClick()
        compose.onNodeWithTag("yudong-filter-master").assertIsOn().performClick().assertIsOff()
        compose.onAllNodes(hasAnyAncestor(hasTestTag("yudong-filter-options")) and SemanticsMatcher.expectValue(SemanticsProperties.Role,Role.Switch),useUnmergedTree=true)
            .assertCountEquals(7).fetchSemanticsNodes().forEach { assertTrue(it.config.contains(SemanticsProperties.Disabled)) }
        compose.onNodeWithTag("yudong-filter-master").performClick().assertIsOn()
        assertTrue(p.getBoolean("is_yudong_post_block",false))
    }
    @Test fun realServiceConfigAndToggleStateGateLocalRemoteAndYudongWithoutStartingService() {
        val service=BotService()
        val load=BotService::class.java.getDeclaredMethod("loadBotConfig",String::class.java,android.content.SharedPreferences::class.java).apply {isAccessible=true}
        fun config()=load.invoke(service,bot,p)!!
        fun field(value:Any,name:String)=value.javaClass.getDeclaredField(name).apply {isAccessible=true}.get(value)
        listOf("comment","image","dc_media","voice").forEach {p.edit().putBoolean("is_yudong_${it}_block",true).commit()}
        assertEquals(listOf("사과"),(field(config(),"normalWords") as Array<*>).toList())
        setFilterMasterEnabled(p,WORD_FILTER_ENABLED_KEY,false);setFilterMasterEnabled(p,YUDONG_FILTER_ENABLED_KEY,false)
        val off=config()
        assertTrue((field(off,"normalWords") as Array<*>).isEmpty())
        assertTrue((field(off,"bypassWords") as Array<*>).isEmpty())
        listOf("Post","Comment","Image","DcMedia","Voice").forEach {assertEquals(false,field(off,"isYudong${it}Block"))}
        assertEquals(false,field(off,"yudongDcMediaActivationRecheckPending"))
        setFilterMasterEnabled(p,YUDONG_FILTER_ENABLED_KEY,true)
        assertEquals(true,field(config(),"yudongDcMediaActivationRecheckPending"))
    }
}
