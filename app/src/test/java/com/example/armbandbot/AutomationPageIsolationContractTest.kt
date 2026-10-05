package com.heyheyon.armbandbot

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationPageIsolationContractTest {
    @Test fun isolatedPagesDoNotReadUnrelatedRuleLists() {
        val file = listOf(File("app/src/main/java/com/example/armbandbot/PostAutomationSettingsScreen.kt"), File("src/main/java/com/example/armbandbot/PostAutomationSettingsScreen.kt")).first { it.isFile }
        val source = file.readText().replace(Regex("\\s+"), "")
        assertTrue("Bump rule errors must not leak into other pages", source.contains("if(page==AutomationSettingsPage.BUMP)runCatching{parseBumpRules"))
        assertTrue("Tab rule errors must not leak into other pages", source.contains("if(page==AutomationSettingsPage.TAB)runCatching{parseTabMoveRules"))
    }
}
