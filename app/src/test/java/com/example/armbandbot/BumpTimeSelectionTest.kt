package com.heyheyon.armbandbot

import org.junit.Assert.*
import org.junit.Test

class BumpTimeSelectionTest {
    @Test fun addSortsAndDeduplicatesSelectedTimes() {
        assertEquals(listOf(540,1110),updateBumpTimeSelection(listOf(1110),null,540))
        assertEquals(listOf(540),updateBumpTimeSelection(listOf(540),null,540))
    }
    @Test fun editAndRemovePreserveOtherTimes() {
        assertEquals(listOf(555,1110),updateBumpTimeSelection(listOf(540,1110),540,555))
        assertEquals(listOf(1110),updateBumpTimeSelection(listOf(555,1110),555,null))
        assertEquals(emptyList<Int>(),updateBumpTimeSelection(listOf(540),540,null))
    }
    @Test fun outOfRangeAndTooManyTimesNeverApply() {
        assertTrue(runCatching { updateBumpTimeSelection(listOf(540),null,1440) }.isFailure)
        assertTrue(runCatching { updateBumpTimeSelection((0..23).toList(),null,24) }.isFailure)
        assertTrue(runCatching { updateBumpTimeSelection(listOf(540),600,700) }.isFailure)
    }
}
