package com.heyheyon.armbandbot

import org.junit.Assert.*
import org.junit.Test

class RepeatSpamUiTextTest {
    @Test fun minuteStepsAreFineBelowTenAndCoarseAbove() {
        assertEquals(2, repeatStep(1, REPEAT_MINUTE_STEPS, true))
        assertEquals(15, repeatStep(10, REPEAT_MINUTE_STEPS, true))
        assertEquals(10, repeatStep(15, REPEAT_MINUTE_STEPS, false))
        assertEquals(60, repeatStep(60, REPEAT_MINUTE_STEPS, true))
        assertEquals(1, repeatStep(1, REPEAT_MINUTE_STEPS, false))
        assertTrue(REPEAT_MINUTE_STEPS.all { it in 1..60 } && REPEAT_MINUTE_STEPS.first() == 1 && REPEAT_MINUTE_STEPS.last() == 60)
    }
    @Test fun valuesBetweenStepsMoveToTheNeighbouringStep() {
        assertEquals(20, repeatStep(17, REPEAT_MINUTE_STEPS, true))
        assertEquals(15, repeatStep(17, REPEAT_MINUTE_STEPS, false))
        assertEquals(100, repeatStep(96, REPEAT_SIMILARITY_STEPS, true))
        assertEquals(95, repeatStep(96, REPEAT_SIMILARITY_STEPS, false))
    }
    @Test fun countAndSimilarityStayInsidePolicyBounds() {
        assertEquals(2, repeatStep(2, REPEAT_COUNT_STEPS, false)); assertEquals(20, repeatStep(20, REPEAT_COUNT_STEPS, true))
        assertEquals(85, repeatStep(85, REPEAT_SIMILARITY_STEPS, false)); assertEquals(100, repeatStep(100, REPEAT_SIMILARITY_STEPS, true))
        val bounded = RepeatSpamSettings(windowMinutes = 1, count = 2, similarityPercent = 85)
        assertEquals(bounded, bounded.bounded())
    }
    @Test fun summaryDescribesTheCurrentCombination() {
        val delete = RepeatActionView("DELETE", 6, true, false)
        assertEquals("같은 작성자가 10분 안에 비슷한 글을 4개 이상 올리면 첫 글까지 모두 삭제합니다.", repeatSpamSummary(RepeatSpamSettings(), delete))
        assertTrue(repeatSpamSummary(RepeatSpamSettings(), delete.copy(mode = "HOLD")).endsWith("지우지 않고 보류 기록으로 남깁니다."))
        assertTrue(repeatSpamSummary(RepeatSpamSettings(), delete.copy(mode = "BLOCK", durationHours = 24)).endsWith("작성자를 1일 차단하고 첫 글까지 모두 삭제합니다."))
        assertTrue(repeatSpamSummary(RepeatSpamSettings(), delete.copy(mode = "BLOCK", deletePost = false)).endsWith("작성자를 6시간 차단합니다. 글은 지우지 않습니다."))
        assertEquals("유동 · 깡계", repeatSpamTargets(RepeatSpamSettings()))
        assertEquals("깡계", repeatSpamTargets(RepeatSpamSettings(yudong = false)))
        assertNull(repeatSpamTargets(RepeatSpamSettings(yudong = false, kkang = false)))
    }
    @Test fun similarityWordsGetStricterTowardHundred() {
        assertEquals("정리 후 똑같은 글만", repeatSimilarityMeaning(100))
        assertNotEquals(repeatSimilarityMeaning(85), repeatSimilarityMeaning(95))
    }

    @Test fun missingActionKeysKeepTheOldDeleteOrHoldBehaviour() {
        val p = MemoryPreferences()
        p.edit().putString("block_process_mode", "BLOCK").commit()           // a strict default must not leak in
        assertEquals(RepeatActionView("DELETE", 6, true, false), repeatActionView(p))
        assertFalse(repeatSpamSettings(p).observeOnly)
        p.edit().putBoolean(REPEAT_OBSERVE_KEY, true).commit()               // 1.7.0-beta1 "감지 기록만"
        assertEquals("HOLD", repeatActionView(p).mode)
        assertTrue(repeatSpamSettings(p).observeOnly)
    }
    @Test fun explicitModeWinsAndCustomOffFollowsTheDefaultAction() {
        val p = MemoryPreferences()
        p.edit().putBoolean(REPEAT_OBSERVE_KEY, true).putString(REPEAT_PROCESS_MODE_KEY, "BLOCK")
            .putInt(REPEAT_DURATION_KEY, 24).putBoolean(REPEAT_DELETE_POST_KEY, false).commit()
        assertEquals(RepeatActionView("BLOCK", 24, false, false), repeatActionView(p))
        p.edit().putBoolean(REPEAT_CUSTOM_ACTION_KEY, false).putString("block_process_mode", "HOLD").putInt("block_duration_hours", 168).commit()
        assertEquals(RepeatActionView("HOLD", 168, true, true), repeatActionView(p))
        assertTrue(repeatSpamSettings(p).observeOnly)
        p.edit().remove("block_process_mode").putBoolean("delete_only_mode", true).commit()
        assertEquals("DELETE", repeatActionView(p).mode)
    }
    @Test fun actionChangesRestartPendingRepeatEvidence() {
        val p = MemoryPreferences(); p.edit().putBoolean(REPEAT_ENABLED_KEY, true).commit()
        val before = repeatSpamPolicyRevision(snapshotScanPreferences(p))
        p.edit().putString(REPEAT_PROCESS_MODE_KEY, "BLOCK").commit()
        assertNotEquals(before, repeatSpamPolicyRevision(snapshotScanPreferences(p)))
    }
}
