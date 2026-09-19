package com.heyheyon.armbandbot

import org.junit.Assert.assertEquals
import org.junit.Test

class SpamBurstRetentionPolicyTest {
    @Test
    fun capPreservesNewestEntriesFromNewestFirstList() {
        val events = mutableListOf("newest", "recent", "old", "oldest")

        retainNewestEntries(events, 2)

        assertEquals(listOf("newest", "recent"), events)
    }

    @Test
    fun underCapListIsUnchanged() {
        val events = mutableListOf("newest", "recent")

        retainNewestEntries(events, 3)

        assertEquals(listOf("newest", "recent"), events)
    }

    @Test
    fun zeroCapDropsAllEntries() {
        val events = mutableListOf("newest", "oldest")

        retainNewestEntries(events, 0)

        assertEquals(emptyList<String>(), events)
    }
}