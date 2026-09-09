package com.heyheyon.armbandbot

/** Whole monitoring inventory is distinct from the exact shared scan scope. */
internal sealed interface DashboardRecordScope {
    data object All : DashboardRecordScope
    data class Exact(val scopeId: String) : DashboardRecordScope
    data class Selected(val scopeIds: Set<String>) : DashboardRecordScope
}

internal data class DashboardScopeBot(val id: String, val name: String, val independentEnabled: Boolean, val initialized: Boolean = false)
internal data class DashboardScopeOption(val scope: DashboardRecordScope, val label: String)

internal fun dashboardScopeOptions(bots: List<DashboardScopeBot>, retainedScopeIds: Set<String>): List<DashboardScopeOption> {
    val known = bots.distinctBy { it.id }.filter { it.id != GLOBAL_SCAN_SCOPE }
    // Always include the suffix, so names stay stable when another same-name bot is added/removed.
    fun shortId(id: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(id.toByteArray(Charsets.UTF_8)).take(4).joinToString("") { "%02x".format(it.toInt() and 255) }
    return buildList {
        add(DashboardScopeOption(DashboardRecordScope.All, "전체"))
        add(DashboardScopeOption(DashboardRecordScope.Exact(GLOBAL_SCAN_SCOPE), "공용"))
        known.filter { it.independentEnabled || it.initialized || it.id in retainedScopeIds }.forEach {
            add(DashboardScopeOption(DashboardRecordScope.Exact(it.id), "${it.name.ifBlank { "이름 없는 봇" }} · ${shortId(it.id)}${if (it.independentEnabled) "" else " (보존)"}"))
        }
        (retainedScopeIds - known.map { it.id }.toSet() - GLOBAL_SCAN_SCOPE).sorted().forEach {
            add(DashboardScopeOption(DashboardRecordScope.Exact(it), if (it == LEGACY_ACTOR_BOT_ID) "이전 기록 (DB 범위 미상)" else "삭제된 봇 · ${shortId(it)}"))
        }
    }
}

internal fun DashboardRecordScope.includes(scopeId: String): Boolean = when (this) {
    DashboardRecordScope.All -> true
    is DashboardRecordScope.Exact -> this.scopeId == scopeId
    is DashboardRecordScope.Selected -> scopeId in scopeIds
}

internal fun dashboardIncludesPost(scope: DashboardRecordScope, gallery: String, post: CheckedPost): Boolean =
    scope.includes(post.scopeId) && (gallery == "ALL" || gallery == post.gallId)

internal fun dashboardIncludesPost(scope: DashboardRecordScope, galleries: Set<String>?, post: CheckedPost): Boolean =
    scope.includes(post.scopeId) && (galleries == null || post.gallId in galleries)

internal fun dashboardIncludesHistory(scope: DashboardRecordScope, galleries: Set<String>?, recordedScopeId: String, galleryId: String): Boolean =
    scope.includes(recordedScopeId) && (galleries == null || galleryId in galleries)

/** The same immutable boundary is used for successful results and errors (including ABA switches). */
internal data class DashboardScopeRequest(val scope: DashboardRecordScope, val epoch: Int) {
    fun isCurrent(currentScope: DashboardRecordScope, currentEpoch: Int): Boolean = scope == currentScope && epoch == currentEpoch
    inline fun publishIfCurrent(currentScope: DashboardRecordScope, currentEpoch: Int, publish: () -> Unit) {
        if (isCurrent(currentScope, currentEpoch)) publish()
    }
}
