package com.heyheyon.armbandbot

import org.junit.Assert.*
import org.junit.Test

/** Run the real comparator/tracker with Android's Unicode normalization on API24 and API35. No network. */
class RepeatSpamPolicyAndroidTest {
    private val now = 1_800_000_000_000L
    @Test fun minorAndMiniTitleOrBodyRepeatsIncludeFirstAndRespectProtection() {
        for (type in listOf("M", "MI")) for (title in listOf(true, false)) {
            val tracker = RepeatSpamTracker().apply {
                updatePolicy("fixture", RepeatSpamSettings(enabled = true, minimumLength = 0, count = 2))
            }
            val first = RepeatSpamPost(PostKey(type, "fixture", "1"), "ip:203.239", now - 60_000, if (title) "가" else "다른제목1", if (title) "다른본문1" else "나")
            val second = first.copy(key = first.key.copy(postNo = "2"), title = if (title) "가" else "다른제목2", body = if (title) "다른본문2" else "나")
            assertNull(tracker.observe(first, true, now))
            assertEquals(setOf(first.key, second.key), tracker.observe(second, true, now)!!.keys)
            assertTrue(tracker.canAct(first.key, now))
            tracker.observe(first, false, now)
            assertFalse(tracker.canAct(second.key, now))
        }
    }
    @Test fun shortFuzzyAndEmptyBodyRulesUseAndroidNormalization() {
        val a = RepeatSpamPost(PostKey("M", "fixture", "1"), "ip:203.239", now, "가나다라마바사아자차카타파하거너더러머", "")
        assertTrue(RepeatSpamText.matches(a, a.copy(title = a.title + "버"), 85, 0))
        assertFalse(RepeatSpamText.matches(a, a.copy(title = a.title + "버"), 95, 0))
        assertFalse(RepeatSpamText.matches(a.copy(title = "사과"), a.copy(title = "바나나"), 85, 0))
        assertTrue(RepeatSpamText.matches(a.copy(title = "Ａ"), a.copy(title = "a"), 100, 0))
        assertTrue(RepeatSpamText.matches(a.copy(title = "😀"), a.copy(title = "😀"), 100, 0))
    }
}
