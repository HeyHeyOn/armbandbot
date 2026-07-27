package com.heyheyon.armbandbot

import org.junit.Assert.assertEquals
import org.junit.Test

class PumSettingsNormalizationTest {
    @Test
    fun `valid process modes are preserved exactly`() {
        listOf("BLOCK", "DELETE", "HOLD").forEach { mode ->
            assertEquals(mode, normalizePumSettings(mode, 6).processMode)
        }
    }

    @Test
    fun `lowercase arbitrary blank and missing process modes fail safe to block`() {
        listOf("block", "delete", "hold", "ARBITRARY", "", "   ", null).forEach { mode ->
            assertEquals("BLOCK", normalizePumSettings(mode, 6).processMode)
        }
    }

    @Test
    fun `legacy delete-only remains compatible only when process mode is absent`() {
        assertEquals(
            "DELETE",
            normalizePumSettings(null, 6, legacyDeleteOnly = true, processModePresent = false).processMode,
        )
        assertEquals(
            "BLOCK",
            normalizePumSettings("invalid", 6, legacyDeleteOnly = true, processModePresent = true).processMode,
        )
        assertEquals(
            "HOLD",
            normalizePumSettings("HOLD", 6, legacyDeleteOnly = true).processMode,
        )
    }

    @Test
    fun `duration uses safe default and clamps explicitly numeric values`() {
        assertEquals(6, normalizePumSettings("BLOCK", null).blockDurationHours)
        assertEquals(6, normalizePumSettings("BLOCK", "").blockDurationHours)
        assertEquals(6, normalizePumSettings("BLOCK", "not-a-number").blockDurationHours)
        assertEquals(1, normalizePumSettings("BLOCK", -9).blockDurationHours)
        assertEquals(1, normalizePumSettings("BLOCK", 0).blockDurationHours)
        assertEquals(1, normalizePumSettings("BLOCK", "-2").blockDurationHours)
        assertEquals(24, normalizePumSettings("BLOCK", "24.9").blockDurationHours)
        assertEquals(744, normalizePumSettings("BLOCK", 100_000).blockDurationHours)
    }

    @Test
    fun `runtime settings resolver cannot forward invalid values`() {
        val normalized = normalizePumSettings("delete", Int.MAX_VALUE)

        assertEquals("BLOCK", normalized.processMode)
        assertEquals(744, normalized.blockDurationHours)
    }
}
