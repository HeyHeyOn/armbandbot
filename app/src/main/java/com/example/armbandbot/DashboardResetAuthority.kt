package com.heyheyon.armbandbot

/** A successful fresh read is required even when known bot options are already available. */
internal suspend fun loadDashboardResetInventory(
    bots: List<DashboardScopeBot>,
    readScopeIds: suspend () -> Set<String>,
): List<DashboardScopeOption> = dashboardScopeOptions(bots, readScopeIds())
    .filter { it.scope is DashboardRecordScope.Exact }

/** Authority belongs to this immutable confirmation, not the current browsing filter. */
internal class FrozenDashboardReset(scopeIds: Set<String>, val allDatabases: Boolean, labels: Map<String, String> = emptyMap()) {
    val labels: Map<String, String> = labels.toMap()
    val scopeIds: Set<String> = scopeIds.toSet()
    init { require(this.scopeIds.isNotEmpty()) { "초기화할 DB를 선택하세요." } }
}

internal fun freezeDashboardResetTarget(allDatabases: Boolean, chosen: Set<String>, inventory: Set<String>): FrozenDashboardReset =
    FrozenDashboardReset(if (allDatabases) inventory else chosen, allDatabases)
