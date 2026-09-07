package com.heyheyon.armbandbot

import org.junit.Assert.assertEquals
import org.junit.Test

class BotScanScopeTest {
    @Test
    fun sharedBotsUseGlobalScope() {
        assertEquals(GLOBAL_SCAN_SCOPE, resolveScanScopeId("bot-a", independent = false))
    }

    @Test
    fun independentBotsUseTheirExactBotId() {
        assertEquals("bot-a", resolveScanScopeId("bot-a", independent = true))
    }

    @Test(expected = IllegalArgumentException::class)
    fun independentScopeRejectsBlankBotId() {
        resolveScanScopeId("  ", independent = true)
    }
}
