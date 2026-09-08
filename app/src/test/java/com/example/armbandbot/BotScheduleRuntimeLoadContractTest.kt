package com.heyheyon.armbandbot

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class BotScheduleRuntimeLoadContractTest {
    @Test fun serviceAndListUseSharedLoaderWithoutLegacyFallback() {
        for (name in listOf("BotService.kt", "BotListScreen.kt")) {
            val source = File("src/main/java/com/example/armbandbot/$name").readText()
            assertTrue(name, source.contains("loadBotRunSchedule("))
            assertFalse(name, source.contains("getInt(\"run_schedule_start_minute\""))
            assertFalse(name, source.contains("getOrElse { BotRunSchedule.disabled() }"))
        }
    }
    @Test fun serviceWaitReportsInvalidSettingsAndRereadsEachIteration() {
        val source = File("src/main/java/com/example/armbandbot/BotService.kt").readText()
        val wait = source.substringAfter("private suspend fun awaitActiveSchedule(").substringBefore("companion object")
        assertTrue(wait, wait.substringAfter("while (true)").contains("readCurrentSchedule(botId)"))
        assertTrue(wait, wait.contains("BotRunScheduleLoadResult.Error"))
        assertTrue(wait, wait.contains("시간대 설정 오류"))
    }
}
