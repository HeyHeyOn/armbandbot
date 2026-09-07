package com.heyheyon.armbandbot

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModerationClaimResponseParsingTest {
    @Test
    fun onlyValidTopLevelResultControlsClaimStatus() {
        assertEquals(ClaimStatus.SUCCEEDED, classifyModerationClaimStatus("{\"result\":\"success\"}"))
        assertEquals(ClaimStatus.SUCCEEDED, classifyModerationClaimStatus("{\"result\":\" SUCCESS \"}"))
        assertEquals(ClaimStatus.FAILED, classifyModerationClaimStatus("{\"result\":\"failed\"}"))
        assertEquals(ClaimStatus.UNKNOWN, classifyModerationClaimStatus("{\"result\":\"success\""))
        assertEquals(ClaimStatus.UNKNOWN, classifyModerationClaimStatus("garbage \"result\":\"success\""))
        assertEquals(ClaimStatus.UNKNOWN, classifyModerationClaimStatus("{\"nested\":{\"result\":\"success\"}}"))
        assertEquals(ClaimStatus.UNKNOWN, classifyModerationClaimStatus("<script>\"result\":\"success\"</script>"))
        assertEquals(ClaimStatus.UNKNOWN, classifyModerationClaimStatus("{\"Result\":\"success\"}"))
    }
}
