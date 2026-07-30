package com.heyheyon.armbandbot

import java.util.concurrent.ConcurrentMap

const val PERSISTENCE_WATCHDOG_INTERVAL_MS = 15L * 60L * 1_000L

enum class RecoveryDecision {
    CANCEL,
    REARM_ONLY,
    DEFER_AND_REARM,
}

fun <K, V> removeIfCurrentGeneration(
    registry: ConcurrentMap<K, V>,
    key: K,
    generation: V,
): Boolean = registry.remove(key, generation)

fun shouldAcknowledgeRestore(
    expectedBotIds: Set<String>,
    activeBotIds: Set<String>,
): Boolean = expectedBotIds.isNotEmpty() && activeBotIds.containsAll(expectedBotIds)

fun decideRecoveryAction(
    hasRestorableBots: Boolean,
    serviceAlive: Boolean,
): RecoveryDecision = when {
    !hasRestorableBots -> RecoveryDecision.CANCEL
    serviceAlive -> RecoveryDecision.REARM_ONLY
    else -> RecoveryDecision.DEFER_AND_REARM
}
