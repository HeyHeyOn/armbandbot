package com.heyheyon.armbandbot

import org.junit.Assert.*
import org.junit.Test

/** Run the real comparator/tracker with Android's Unicode normalization on API24 and API35. No network. */
class RepeatSpamPolicyAndroidTest {
    private val now = 1_800_000_000_000L
    @Test fun confirmedRateBatchSurvivesCycleDelayOnMinorAndMini() {
        for (type in listOf("M", "MI")) for (author in listOf("ip:203.239", "uid:fixture")) {
            val tracker = RepeatSpamTracker().apply {
                updatePolicy("fixture", RepeatSpamSettings(enabled = true, contentEnabled = false,
                    postingRate = PostingRateSettings(enabled = true, ipCount = 3, uidCount = 3)))
            }
            val posts = (1..3).map { RepeatSpamPost(PostKey(type, "fixture", "$it"), author,
                now - (4 - it) * 15_000, "서로 다른 제목 $it", "서로 다른 본문 $it") }
            posts.reversed().forEach { tracker.observe(it, false, now, true) }
            tracker.complete(posts.first().key, SpamMatchKind.POSTING_RATE, true)
            posts.drop(1).forEach {
                assertTrue(tracker.needsRecheck(it.key, now + 120_000))
                assertNotNull(tracker.observe(it, false, now + 120_000, true))
                tracker.complete(it.key, SpamMatchKind.POSTING_RATE, true)
                assertFalse(tracker.canAct(it.key, now + 120_000))
            }
        }
    }

    @Test fun confirmedBatchProtectionAndStopStillCancelActions() {
        val tracker = RepeatSpamTracker().apply { updatePolicy("fixture", RepeatSpamSettings(enabled = true, count = 2)) }
        val first = RepeatSpamPost(PostKey("M", "fixture", "1"), "ip:203.239", now, "충분히 긴 반복 제목입니다", "")
        val second = first.copy(key = first.key.copy(postNo = "2"))
        tracker.observe(first, true, now); tracker.observe(second, true, now)
        tracker.observe(first, false, now + 120_000)
        assertFalse(tracker.canAct(second.key, now + 120_000))
        tracker.clear(); assertEquals(0, tracker.size())
    }
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
