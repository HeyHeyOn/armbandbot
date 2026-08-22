package com.heyheyon.armbandbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KkangDetectionTest {
    @Test
    fun gallogCountsRequireTwoValidNonNegativeIntegers() {
        assertEquals(12 to 34, parseGallogCounts("12,34"))
        assertEquals(0 to 0, parseGallogCounts(" 0 , 0 "))
        assertNull(parseGallogCounts(""))
        assertNull(parseGallogCounts("100"))
        assertNull(parseGallogCounts("abc,3"))
        assertNull(parseGallogCounts("3,abc"))
        assertNull(parseGallogCounts("-1,3"))
        assertNull(parseGallogCounts("12,34,unexpected"))
        assertNull(parseGallogCounts("12,34,"))
    }
}
