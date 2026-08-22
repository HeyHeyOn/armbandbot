package com.heyheyon.armbandbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KkangDetectionTest {
    @Test
    fun gallogCountsAcceptLegacyAndCurrentNumericResponseShapes() {
        assertEquals(12 to 34, parseGallogCounts("12,34"))
        assertEquals(0 to 0, parseGallogCounts(" 0 , 0 "))
        assertEquals(27 to 163, parseGallogCounts("27,163,0,0,"))
        assertEquals(12 to 34, parseGallogCounts(" 12 , 34 , 0 , 0 , "))
        assertEquals(12 to 34, parseGallogCounts("12,34,"))
    }

    @Test
    fun gallogCountsRejectMalformedOrNonNumericResponseFields() {
        assertNull(parseGallogCounts(""))
        assertNull(parseGallogCounts("100"))
        assertNull(parseGallogCounts("abc,3"))
        assertNull(parseGallogCounts("3,abc"))
        assertNull(parseGallogCounts("-1,3"))
        assertNull(parseGallogCounts("12,34,unexpected"))
        assertNull(parseGallogCounts("12,34,<html>"))
        assertNull(parseGallogCounts("12,34,,0"))
        assertNull(parseGallogCounts("٢٧,١٦٣,٠,٠,"))
        assertNull(parseGallogCounts("2147483648,1"))
        assertNull(parseGallogCounts("1,2147483648"))
        assertNull(parseGallogCounts("1,2,2147483648,0,"))
        assertNull(parseGallogCounts("1,2,-1,0,"))
    }
}
