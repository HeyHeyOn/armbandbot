package com.heyheyon.armbandbot

import org.junit.Assert.*
import org.junit.Test

class DashboardResetAuthorityTest {
    @Test fun authoritativeInventoryUnionsStoredScopesWithKnownBotOptions() = kotlinx.coroutines.runBlocking {
        val options = loadDashboardResetInventory(listOf(DashboardScopeBot("known", "Known", true))) {
            setOf(LEGACY_ACTOR_BOT_ID, "deleted", "action-only")
        }
        assertEquals(setOf(GLOBAL_SCAN_SCOPE, "known", LEGACY_ACTOR_BOT_ID, "deleted", "action-only"),
            options.map { (it.scope as DashboardRecordScope.Exact).scopeId }.toSet())
    }

    @Test fun inventoryReadFailureNeverFallsBackToKnownOptions() = kotlinx.coroutines.runBlocking {
        val failure = IllegalStateException("inventory unavailable")
        try {
            loadDashboardResetInventory(listOf(DashboardScopeBot("known", "Known", true))) { throw failure }
            fail("Read failure must prevent reset authority")
        } catch (actual: IllegalStateException) { assertSame(failure, actual) }
    }

    @Test fun confirmationCopiesLabelsAsWellAsIds() {
        val labels = mutableMapOf("a" to "Original")
        val ids = mutableSetOf("a")
        val target = FrozenDashboardReset(ids, true, labels)
        labels["a"] = "Renamed"; labels["later"] = "Later"; ids.add("later")
        assertEquals(mapOf("a" to "Original"), target.labels)
        assertEquals(setOf("a"), target.scopeIds)
    }

    @Test fun selectedAuthorityIsCopiedAndNeverExpandsToInventory() {
        val draft = mutableSetOf("a")
        val target = freezeDashboardResetTarget(false, draft, setOf("a", "b", GLOBAL_SCAN_SCOPE))
        draft.add("b")
        assertEquals(setOf("a"), target.scopeIds)
        assertFalse(target.allDatabases)
    }
    @Test fun allAuthorityFreezesInventoryAtConfirmationTime() {
        val inventory = mutableSetOf("a", GLOBAL_SCAN_SCOPE)
        val target = freezeDashboardResetTarget(true, emptySet(), inventory)
        inventory.add("created-after-confirmation")
        assertEquals(setOf("a", GLOBAL_SCAN_SCOPE), target.scopeIds)
        assertTrue(target.allDatabases)
    }
    @Test(expected = IllegalArgumentException::class)
    fun noChosenDbIsNeverInterpretedAsAll() {
        freezeDashboardResetTarget(false, emptySet(), setOf("a", GLOBAL_SCAN_SCOPE))
    }
}
