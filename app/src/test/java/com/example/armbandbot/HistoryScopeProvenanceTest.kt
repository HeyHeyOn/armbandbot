package com.heyheyon.armbandbot

import org.junit.Assert.*
import org.junit.Test

class HistoryScopeProvenanceTest {
    private fun columns(scope: String? = null): Map<String, Any?> = mutableMapOf<String, Any?>(
        "gallType" to "M", "gallId" to "fixture", "postNum" to "1", "targetType" to "POST",
        "targetNo" to "1", "targetAuthor" to "author", "targetContent" to "body",
        "blockReason" to "reason", "holdReason" to "reason", "actorBotId" to "same-bot"
    ).apply { if (scope != null) put("scopeId", scope) }

    @Test fun historicalScopeSurvivesImportWithoutConsultingCurrentBotMode() {
        assertEquals("private-before-toggle", blockHistoryFromBackupColumns(columns("private-before-toggle")).scopeId)
        assertEquals(GLOBAL_SCAN_SCOPE, holdHistoryFromBackupColumns(columns(GLOBAL_SCAN_SCOPE)).scopeId)
    }
    @Test fun backupMergeIdentityPreservesSameActorsDistinctRecordedScopes() {
        val original = blockHistoryFromBackupColumns(columns("private-at-operation"))
        assertEquals(blockHistoryMergeKey(original), blockHistoryMergeKey(original.copy(id = 99)))
        assertNotEquals(blockHistoryMergeKey(original), blockHistoryMergeKey(original.copy(scopeId = GLOBAL_SCAN_SCOPE)))
        assertNotEquals(blockHistoryMergeKey(original), blockHistoryMergeKey(original.copy(actorBotId = "another-bot")))
    }
    @Test fun legacyActorDoesNotInventHistoricalDbProvenance() {
        assertEquals(LEGACY_ACTOR_BOT_ID, blockHistoryFromBackupColumns(columns()).scopeId)
        assertEquals(LEGACY_ACTOR_BOT_ID, holdHistoryFromBackupColumns(columns()).scopeId)
    }
}
