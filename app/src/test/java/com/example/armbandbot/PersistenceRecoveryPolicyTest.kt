package com.heyheyon.armbandbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class PersistenceRecoveryPolicyTest {
    @Test
    fun noRestorableBotsCancelsWatchdog() {
        assertEquals(
            RecoveryDecision.CANCEL,
            decideRecoveryAction(hasRestorableBots = false, serviceAlive = false)
        )
    }

    @Test
    fun liveServiceOnlyRearmsWatchdog() {
        assertEquals(
            RecoveryDecision.REARM_ONLY,
            decideRecoveryAction(hasRestorableBots = true, serviceAlive = true)
        )
    }

    @Test
    fun missingServiceDefersRestoreThenRearms() {
        assertEquals(
            RecoveryDecision.DEFER_AND_REARM,
            decideRecoveryAction(hasRestorableBots = true, serviceAlive = false)
        )
    }

    @Test
    fun acknowledgesRestoreOnlyWhenEveryExpectedBotIsActive() {
        assertEquals(false, shouldAcknowledgeRestore(setOf("a", "b"), setOf("a")))
        assertEquals(true, shouldAcknowledgeRestore(setOf("a", "b"), setOf("a", "b")))
        assertEquals(false, shouldAcknowledgeRestore(emptySet(), emptySet()))
    }

    @Test
    fun staleGenerationCannotRemoveReplacementJob() {
        val registry = ConcurrentHashMap<String, Any>()
        val oldJob = Any()
        val replacementJob = Any()
        registry["bot"] = replacementJob

        assertFalse(removeIfCurrentGeneration(registry, "bot", oldJob))
        assertSame(replacementJob, registry["bot"])
    }

    @Test
    fun watchdogUsesLowFrequencyFifteenMinuteLease() {
        assertEquals(15L * 60L * 1_000L, PERSISTENCE_WATCHDOG_INTERVAL_MS)
    }
}
