package com.heyheyon.armbandbot

import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test

class DatabaseWorkRegistryTest {
    @Test fun completingParentStillProtectsInFlightChildUntilActuallyCompleted() {
        val registry = DatabaseWorkRegistry()
        val parent = Job()
        val child = Job(parent)
        registry.register(parent)
        parent.complete()
        assertFalse(parent.isCompleted)
        assertTrue(registry.hasUnfinishedWork())
        child.complete()
        assertTrue(parent.isCompleted)
        assertFalse(registry.hasUnfinishedWork())
    }
    @Test fun eachBotJobMustCompleteBeforeResetIsAllowed() {
        val registry = DatabaseWorkRegistry()
        val first = Job(); val second = Job()
        registry.register(first); registry.register(second)
        first.complete()
        assertTrue(registry.hasUnfinishedWork())
        second.complete()
        assertFalse(registry.hasUnfinishedWork())
    }
}
