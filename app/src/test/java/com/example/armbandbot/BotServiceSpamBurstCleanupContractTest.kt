package com.heyheyon.armbandbot

import org.junit.Assert.assertEquals
import org.junit.Test

class BotServiceSpamBurstCleanupContractTest {
    @Test
    fun cleanupRemovesBareAndDecoratedBotEventBucketsOnly() {
        val eventsByKey = linkedMapOf(
            "bot-1" to 1,
            "bot-1:secondary" to 2,
            "bot-1_archive" to 3,
            "bot-10" to 4,
            "unrelated" to 5,
        )

        removeSpamBurstEventsForBot(eventsByKey, "bot-1")

        assertEquals(linkedMapOf("bot-10" to 4, "unrelated" to 5), eventsByKey)
    }
}