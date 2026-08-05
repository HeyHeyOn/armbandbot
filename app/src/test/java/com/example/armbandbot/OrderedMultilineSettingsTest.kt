package com.heyheyon.armbandbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderedMultilineSettingsTest {
    @Test
    fun `normalization trims blanks removes exact duplicates and preserves first order`() {
        val normalized = normalizeOrderedMultilineText(
            "  alpha # first  \n\nbeta\nalpha # first\nAlpha # first\nbeta  "
        )

        assertEquals(
            listOf("alpha # first", "beta", "Alpha # first"),
            normalized.lines,
        )
        assertEquals("alpha # first\nbeta\nAlpha # first", normalized.text)
        assertEquals(linkedSetOf("alpha # first", "beta", "Alpha # first"), normalized.values)
        assertEquals(2, normalized.removedDuplicateCount)
    }

    @Test
    fun `saved ordered text is preferred and normalized over unordered legacy values`() {
        val resolved = resolveOrderedMultilineText(
            savedText = "second\nfirst\nsecond",
            legacyValues = setOf("legacy"),
        )

        assertEquals("second\nfirst", resolved.text)
        assertEquals(linkedSetOf("second", "first"), resolved.values)
    }

    @Test
    fun `legacy values remain available when ordered text does not exist`() {
        val legacy = linkedSetOf("old # memo", "another")

        val resolved = resolveOrderedMultilineText(savedText = null, legacyValues = legacy)

        assertEquals(listOf("old # memo", "another"), resolved.lines)
        assertEquals(legacy, resolved.values)
    }

    @Test
    fun `ordered keys include user and keyword lists but exclude character sets`() {
        assertTrue("bypass" in ORDERED_MULTILINE_SETTING_KEYS)
        assertTrue("user_blacklist" in ORDERED_MULTILINE_SETTING_KEYS)
        assertTrue("nickname_bypass_blacklist" in ORDERED_MULTILINE_SETTING_KEYS)
        assertTrue("search_keywords" in ORDERED_MULTILINE_SETTING_KEYS)
        assertEquals("user_blacklist_text", orderedMultilineTextKey("user_blacklist"))
        assertTrue("special_char_whitelist" !in ORDERED_MULTILINE_SETTING_KEYS)
    }
}
