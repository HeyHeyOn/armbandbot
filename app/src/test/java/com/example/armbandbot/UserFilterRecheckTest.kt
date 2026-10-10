package com.heyheyon.armbandbot

import org.junit.Assert.*
import org.junit.Test

class UserFilterRecheckTest {
    private val key = PostKey("MI", "armbandbot", "owned")
    private fun revision(p: android.content.SharedPreferences) = automationPolicyRevision(snapshotScanPreferences(p))

    @Test fun addingIdAfterCompletedScanRechecksUnchangedCommentsOnce() {
        val p = MemoryPreferences(); val state = AutomationRecheckState()
        state.update(revision(p)); state.mark(key)
        persistOrderedMultilineText(p, "user_blacklist", "target")
        p.edit().putBoolean("is_user_filter_mode", true).commit()
        state.update(revision(p)); assertTrue(state.needs(key))
        // Failure/cancellation leaves it pending; a completed scan consumes it.
        state.update(revision(p)); assertTrue(state.needs(key))
        state.mark(key); state.update(revision(p)); assertFalse(state.needs(key))
    }

    @Test fun switchesWhitelistExemptionsAndActionChangesInvalidateExistingScans() {
        val p = MemoryPreferences()
        val changes: List<() -> Unit> = listOf(
            { p.edit().putBoolean("is_user_filter_mode", true).commit(); Unit },
            { persistOrderedMultilineText(p, "user_whitelist", "protected"); Unit },
            { persistOrderedMultilineText(p, "user_whitelist", ""); Unit },
            { persistOrderedMultilineText(p, "block_exempt_post_numbers", "100"); Unit },
            { p.edit().putString("block_process_mode", "HOLD").commit(); Unit },
            { p.edit().putString("block_process_mode", "DELETE").commit(); Unit },
            { p.edit().putBoolean("user_use_custom_action_config", true).commit(); Unit },
            { p.edit().putString("user_block_process_mode", "DELETE").commit(); Unit },
            { p.edit().putBoolean("is_user_filter_mode", false).commit(); Unit },
        )
        changes.forEach { change -> val before = revision(p); change(); assertNotEquals(before, revision(p)) }
    }

    @Test fun equivalentListsAndRuntimeMetadataDoNotCauseRepeatedRechecks() {
        val p = MemoryPreferences()
        p.edit().putStringSet("user_blacklist", linkedSetOf("b", "a")).commit()
        val before = revision(p)
        persistOrderedMultilineText(p, "user_blacklist", "a # note\nb\na")
        p.edit().putString("saved_cookie", "changed").putBoolean("is_running", true)
            .putLong("last_cycle", 3).putString("bot_name", "renamed").commit()
        assertEquals(before, revision(p))
    }

    @Test fun savingDuringScanIsVisibleNextCycleWithoutMixingListAndRevision() {
        val p = MemoryPreferences()
        persistOrderedMultilineText(p, "user_blacklist", "old")
        val snapshot = snapshotScanPreferences(p); val before = automationPolicyRevision(snapshot)
        persistOrderedMultilineText(p, "user_blacklist", "new")
        p.edit().putBoolean("is_user_filter_mode", true).commit()
        assertEquals(listOf("old"), loadOrderedMultilineValues(snapshot, "user_blacklist"))
        assertFalse(snapshot.getBoolean("is_user_filter_mode", false))
        assertEquals(before, automationPolicyRevision(snapshot))
        assertNotEquals(before, revision(p))
    }

    @Test fun rechecksAreBotAndGalleryScopedAndBounded() {
        val a = AutomationRecheckState(); val b = AutomationRecheckState()
        a.update("one"); b.update("one"); a.mark(key)
        assertFalse(a.needs(key)); assertTrue(b.needs(key))
        assertTrue(a.needs(key.copy(gallType="M")))
        repeat(10000) { a.mark(key.copy(postNo=it.toString())) }
        assertFalse(a.needs(key.copy(postNo="overflow")))
        a.update("two"); assertTrue(a.needs(key))
    }

    @Test fun copiedIdRulesRecheckOnceWithoutLosingValues() {
        val p = MemoryPreferences()
        persistOrderedMultilineText(p, "user_blacklist", "local")
        p.edit().putBoolean("is_user_filter_mode", true).putString("block_process_mode", "DELETE").commit()
        val snapshot = snapshotScanPreferences(p)
        val copied = prepareCopiedBotSettingsSnapshot(snapshot.all.filterValues { it != null }.mapValues { it.value!! }, "copy")
        val q = MemoryPreferences()
        copied.forEach { (k,v) -> when(v) {
            is String -> q.edit().putString(k,v).commit()
            is Boolean -> q.edit().putBoolean(k,v).commit()
            is Int -> q.edit().putInt(k,v).commit()
        } }
        assertEquals(listOf("local"), loadOrderedMultilineValues(q, "user_blacklist"))
        assertTrue(q.getBoolean("is_user_filter_mode", false))
        val state = AutomationRecheckState()
        state.update(revision(q)); assertTrue(state.needs(key))
        state.mark(key); state.update(revision(q)); assertFalse(state.needs(key))
    }
}
