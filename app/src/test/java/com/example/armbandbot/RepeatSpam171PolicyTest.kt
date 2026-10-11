package com.heyheyon.armbandbot

import org.junit.Assert.*
import org.junit.Test

class RepeatSpam171PolicyTest {
    private val now = 1_800_000_000_000L
    private fun post(title: String, body: String = "", n: Int = 1, type: String = "M") =
        RepeatSpamPost(PostKey(type, "fixture", n.toString()), "ip:203.239", now, title, body)

    @Test fun repeatedTitleMatchesDespiteDifferentBodies() {
        val a = post("악성 반복 제목을 검사하는 예시입니다", "사과 바나나 포도")
        assertTrue(RepeatSpamText.matches(a, a.copy(body = "오늘은 다른 내용을 작성합니다"), 85))
    }
    @Test fun repeatedBodyMatchesDespiteDifferentTitles() {
        val a = post("사과 바나나", "악성 반복 본문을 검사하는 예시입니다")
        assertTrue(RepeatSpamText.matches(a, a.copy(title = "포도 키위"), 85))
    }
    @Test fun underTwentyCharactersCanUseSelectedSimilarity() {
        // 19 and 20 normalized characters; 17/18 matching trigrams = 94.44%.
        val a = post("가나다라마바사아자차카타파하거너더러머")
        assertTrue(RepeatSpamText.matches(a, a.copy(title = a.title + "버"), 85))
        assertFalse(RepeatSpamText.matches(a, a.copy(title = a.title + "버"), 95))
    }

    @Test fun zeroIncludesSingleCharactersShortTitlesAndSymbolOnlyText() {
        for (text in listOf("가", "가나", "ㅋㅋㅋ", "사과 바나나 포도", "!!!", "😀")) {
            val a = post(text)
            assertTrue(text, RepeatSpamText.matches(a, a, 85, 0))
            assertFalse(text, RepeatSpamText.matches(a, a, 85, 8))
        }
        assertFalse(RepeatSpamText.matches(post("!!!"), post("???"), 85, 0))
    }
    @Test fun emptyBodiesAndEmptyNormalizationNeverMatchUnrelatedPosts() {
        for (blank in listOf("", " ", "\u200b", "\uFE0F")) {
            assertFalse(RepeatSpamText.matches(post("사과", blank), post("바나나", blank), 85, 0))
            assertFalse(RepeatSpamText.matches(post(blank), post(blank), 85, 0))
        }
    }
    @Test fun minimumAppliesToBothComparedValuesOfTheSameField() {
        val a = post("사과 바나나 포도", "키위") // title 7, body 2
        assertTrue(RepeatSpamText.matches(a, a, 85, 7))
        assertFalse(RepeatSpamText.matches(a, a, 85, 8)) // do not add title/body lengths
        val long = post("가나다라마바사아자차카타파하거너더러머") // 19
        assertFalse(RepeatSpamText.matches(long, long.copy(title = long.title + "버"), 85, 20))
        assertTrue(RepeatSpamText.matches(post("짧음", "반복하는본문문장"), post("다름", "반복하는본문문장"), 85, 8))
    }
    @Test fun fieldsNeverCompareTitleWithBody() {
        assertFalse(RepeatSpamText.matches(post("반복하는제목문장", "다른본문문장입니다"),
            post("또다른제목문장", "반복하는제목문장"), 85, 0))
    }
    @Test fun largeBodyDoesNotVetoMatchingTitleAndHasNoHiddenLengthCeiling() {
        val a = post("똑같이반복되는제목", "가나다".repeat(4000))
        assertTrue(RepeatSpamText.matches(a, a.copy(body = "마바사".repeat(4000)), 100, 0))
        assertTrue(RepeatSpamText.matches(a.copy(title = "다른제목"), a.copy(title = "별개의제목"), 100, 10000))
        assertFalse(RepeatSpamText.matches(a.copy(title = ""), a.copy(title = "", body = a.body + "추가"), 100, 0))
    }
    @Test fun titleAndBodyOrReachThresholdIncludingFirstInMinorAndMiniGalleries() {
        for (type in listOf("M", "MI")) for (titleMatches in listOf(true, false)) {
            val tracker = RepeatSpamTracker().apply { updatePolicy("fixture", RepeatSpamSettings(enabled = true, minimumLength = 0)) }
            val posts = (1..4).map { n -> post(if (titleMatches) "가" else "다른제목$n", if (titleMatches) "다른본문$n" else "나", n, type) }
            posts.reversed().forEach { tracker.observe(it, true, now) }
            assertEquals(posts.map { it.key }.toSet(), tracker.observe(posts.first(), true, now)!!.keys)
            posts.forEach { assertTrue(tracker.canAct(it.key, now)) }
        }
    }
    @Test fun differentFieldPartialMatchesDoNotManufactureARepeatCount() {
        val tracker = RepeatSpamTracker().apply { updatePolicy("fixture", RepeatSpamSettings(enabled = true, minimumLength = 0)) }
        val posts = listOf(post("사과", "바나나", 1), post("사과", "포도", 2), post("키위", "바나나", 3), post("딸기", "바나나", 4))
        posts.forEach { assertNull(tracker.observe(it, true, now)) }
        posts.forEach { assertFalse(tracker.canAct(it.key, now)) }
        val fifth = post("수박", "바나나", 5)
        assertEquals(setOf("1", "3", "4", "5"), tracker.observe(fifth, true, now)!!.keys.map { it.postNo }.toSet())
        assertFalse(tracker.canAct(posts[1].key, now))
    }
    @Test fun minimumChangeClearsExistingPendingThenUsesNewValue() {
        val settings = RepeatSpamSettings(enabled = true, minimumLength = 0, count = 2)
        val tracker = RepeatSpamTracker().apply { updatePolicy("same", settings) }
        tracker.observe(post("가", n = 1), true, now)
        assertNotNull(tracker.observe(post("가", n = 2), true, now))
        tracker.updatePolicy("same", settings.copy(minimumLength = 8))
        assertFalse(tracker.canAct(post("가").key, now))
        assertEquals(0, tracker.size())
        (1..4).forEach { assertNull(tracker.observe(post("가", n = it), true, now)) }
    }
}
