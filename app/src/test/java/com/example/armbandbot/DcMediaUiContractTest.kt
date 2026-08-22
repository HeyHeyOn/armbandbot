package com.heyheyon.armbandbot

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DcMediaUiContractTest {
    @Test
    fun yudongSettingsExposeDcMovieToggleAndArmOneTimeRecheck() {
        val source = File("src/main/java/com/example/armbandbot/BotDetailScreen.kt").readText()

        assertTrue(source.contains("유동 디시 동영상 첨부 금지 (게시글)"))
        assertTrue(source.contains("is_yudong_dc_media_block"))
        assertTrue(source.contains("yudong_dc_media_recheck_pending"))
        assertTrue(source.contains("putBoolean(\"yudong_dc_media_recheck_pending\", enabled)"))
    }

    @Test
    fun incompleteActivationScanKeepsPendingForNextCycle() {
        val source = File("src/main/java/com/example/armbandbot/BotService.kt").readText()

        assertTrue(source.contains("UrlProcessOutcome.INCOMPLETE -> completedAllTargets = false"))
        assertTrue(source.contains("if (!pageResult.activationRecheckComplete)"))
        assertTrue(source.contains("return if (config.yudongDcMediaActivationRecheckPending && !activationRecheckComplete)"))
    }

    @Test
    fun runtimeClearsActivationRecheckOnlyAfterCompletedCycle() {
        val source = File("src/main/java/com/example/armbandbot/BotService.kt").readText()

        assertTrue(source.contains("completedAllTargets && isActive && config.yudongDcMediaActivationRecheckPending"))
        assertTrue(source.contains("putBoolean(\"yudong_dc_media_recheck_pending\", false)"))
    }
}
