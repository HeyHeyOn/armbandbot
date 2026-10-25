package com.heyheyon.armbandbot

import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

class RepeatSpamPolicyTest {
    private val now = 1_800_000_000_000L
    private val settings = RepeatSpamSettings(enabled = true)
    private fun tracker(s: RepeatSpamSettings = settings, cap: Int = 1000) = RepeatSpamTracker(cap).apply { updatePolicy("one", s) }
    private fun post(n: Int, age: Long = 0, author: String = "ip:1.2", gallery: String = "a", title: String = "반복 검증용 제목입니다", body: String = "같은 내용의 반복 게시 여부를 확인하기 위한 테스트 문장입니다") =
        RepeatSpamPost(PostKey("MI", gallery, n.toString()), author, now - age, title, body)
    private fun fill(t: RepeatSpamTracker) = (1..4).map { post(it, (4-it)*120_000L).also { p -> t.observe(p, true, now) } }

    @Test fun thresholdIncludesFirstPostAndSlowRepeats() {
        val t = tracker(); val posts = fill(t)
        posts.forEach { assertTrue(t.needsRecheck(it.key, now)) }
        assertEquals(posts.map { it.key }.toSet(), t.observe(posts.last(), true, now)!!.keys)
    }
    @Test fun reverseCrawlAlsoIncludesEveryPost() {
        val t = tracker(); val posts = (1..4).map { post(it, (4-it)*120_000L) }
        posts.reversed().forEach { t.observe(it, true, now) }
        posts.forEach { assertTrue(t.needsRecheck(it.key, now)) }
    }
    @Test fun unrelatedFastAuthorsCannotTrigger() {
        val t = tracker(); repeat(6) { assertNull(t.observe(post(it, author="ip:$it.2"), true, now)) }
    }
    @Test fun differentBodyWithSameTitleAndIpStillMatches() {
        val t = tracker(); fill(t)
        assertNotNull(t.observe(post(5, body="이번 이벤트 보상에 대해 궁금해서 질문 드립니다"), true, now))
    }
    @Test fun galleriesAndGalleryTypesNeverShareEvidenceOrIds() {
        val t = tracker(); (1..3).forEach { t.observe(post(it), true, now) }
        assertNull(t.observe(post(4, gallery="b"), true, now))
        assertNull(t.observe(post(4).copy(key=PostKey("M","a","4")), true, now))
        assertFalse(t.needsRecheck(post(1).key, now))
    }
    @Test fun rescanningSamePostDoesNotIncreaseCount() {
        val t = tracker(); repeat(10) { assertNull(t.observe(post(1), true, now)) }; assertEquals(1,t.size())
    }
    @Test fun protectedPostsNeitherCountNorRemainPending() {
        val t = tracker(); val posts = fill(t)
        assertNull(t.observe(posts.first(), false, now))
        posts.forEach { assertFalse(t.needsRecheck(it.key, now)) }
    }
    @Test fun oldFutureAndUnknownAuthorsCannotTrigger() {
        val t = tracker()
        repeat(5) { assertNull(t.observe(post(it, age=601_000), true, now)) }
        repeat(5) { assertNull(t.observe(post(it, age=-1), true, now)) }
        repeat(5) { assertNull(t.observe(post(it, author=""), true, now)) }
        assertEquals(0,t.size())
    }
    @Test fun confirmedBatchExpiresAfterBoundedCompletionTime() {
        val t = tracker(); val p = fill(t)
        assertFalse(t.canAct(p.first().key, now+CONFIRMED_SPAM_RETENTION_MS+1))
    }
    @Test fun changingPolicyOrStoppingClearsAllEvidenceAndPending() {
        val t = tracker(); val posts = fill(t)
        t.updatePolicy("changed-protection",settings)
        assertEquals(0,t.size()); assertFalse(t.needsRecheck(posts.first().key,now))
        fill(t); t.clear(); assertEquals(0,t.size())
        fill(t); t.updatePolicy("off",settings.copy(enabled=false)); assertEquals(0,t.size())
    }
    @Test fun successfulDeletionKeepsOnlyTemporaryEvidenceAndDoesNotRepeatAction() {
        val t = tracker(); val p = fill(t); p.forEach { t.complete(it.key) }
        p.forEach { assertFalse(t.needsRecheck(it.key,now)); assertNull(t.observe(it,true,now)) }
        assertNotNull(t.observe(post(5),true,now))
        assertFalse(t.needsRecheck(post(5).key,now+CONFIRMED_SPAM_RETENTION_MS+1))
    }
    @Test fun editedNormalPostWithdrawsPendingAndOldEvidence() {
        val t = tracker(); val p = fill(t)
        assertNull(t.observe(p.first().copy(title="정상적인 장비 질문", body="정상적인 장비 질문을 새로 작성한 내용입니다"),true,now))
        assertFalse(t.canAct(p.last().key,now))
    }
    @Test fun retentionKeepsNewestAndDropsOldPending() {
        val t = tracker(settings.copy(count=2),3)
        (1..4).forEach { t.observe(post(it,age=(4-it)*10L),true,now) }
        assertEquals(3,t.size()); assertFalse(t.canAct(post(1).key,now)); assertTrue(t.canAct(post(4).key,now))
    }
    @Test fun configurableThresholdAndTimeWindowAreApplied() {
        val t = tracker(settings.copy(count=2,windowMinutes=1))
        assertNull(t.observe(post(1,61_000),true,now))
        assertNull(t.observe(post(2,50_000),true,now))
        assertEquals(2,t.observe(post(3),true,now)!!.keys.size)
    }
    @Test fun titleOnlyReporterExampleAndSpacingVariantsMatch() {
        val a=post(1,title="바람 영자 유기 영자 유기 했네",body="")
        assertTrue(RepeatSpamText.matches(a,a.copy(title="바람***영자 유기\u200b 영자 유기 했네!!!"),85))
    }
    @Test fun shortCommonReactionsAreNotAutomaticTargets() {
        val a=post(1,title="ㅋㅋㅋ",body="")
        assertFalse(RepeatSpamText.matches(a,a,85))
    }
    @Test fun shortNegatedSentencesRemainDifferent() {
        val a=post(1,title="바람 영자 유기 영자 유기 했네",body="")
        assertFalse(RepeatSpamText.matches(a,a.copy(title="바람 영자 유기 영자 유기 안해"),85))
    }
    @Test fun numericValuesAndNegationAreNotStrippedAway() {
        val a=post(1,title="",body="이번 검증 기준은 버전 1.5 항목이며 확인을 허용합니다")
        assertFalse(RepeatSpamText.matches(a,a.copy(body=a.body.replace("1.5","15")),85))
        assertFalse(RepeatSpamText.matches(a,a.copy(body=a.body.replace("허용","금지")),85))
    }
    @Test fun exactSettingRejectsChangedWordsButAcceptsSpacing() {
        val a=post(1,title="")
        assertFalse(RepeatSpamText.matches(a,a.copy(body=a.body+"추가"),100))
        assertTrue(RepeatSpamText.matches(a,a.copy(body=a.body.replace(" ","*")),100))
    }
    @Test fun longTextIsNotComparedUsingTruncatedPrefix() {
        val a=post(1,title="",body="내용".repeat(5000))
        assertFalse(RepeatSpamText.matches(a,a.copy(body=a.body+"다름"),100))
    }
    @Test fun creationDateIsStrictKoreanTimeRegardlessOfDeviceZone() {
        val before=TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            assertEquals(0L,parseRepeatCreationDateMillis("1970-01-01 09:00:00"))
            assertNull(parseRepeatCreationDateMillis("2026-02-30 12:00:00"))
            assertNull(parseRepeatCreationDateMillis("2026-10-11 10:00:00garbage"))
        } finally { TimeZone.setDefault(before) }
    }
    @Test fun settingsStayInsideDocumentedLimits() {
        assertEquals(settings.copy(windowMinutes=60,count=2,similarityPercent=85),settings.copy(windowMinutes=10000,count=0,similarityPercent=0).bounded())
    }
    @Test fun clockRollbackCannotExecuteCachedFutureEvidence() {
        val t=tracker(); val posts=fill(t)
        assertFalse(t.canAct(posts.last().key,now-1))
    }
    @Test fun observeOnlyDoesNotOverrideAnExistingFilterAction() {
        assertFalse(shouldApplyRepeatSpamAction(true,true,true))
        assertTrue(shouldApplyRepeatSpamAction(true,true,false))
        assertTrue(shouldApplyRepeatSpamAction(true,false,true))
        assertFalse(shouldApplyRepeatSpamAction(false,false,false))
    }
    @Test fun equalPostNumbersInTwoGalleriesRemainIndependent() {
        val t=tracker()
        (1..4).forEach { t.observe(post(it),true,now);t.observe(post(it,gallery="b"),true,now) }
        assertEquals(8,t.size())
        assertTrue(t.observe(post(1),true,now)!!.keys.all { it.gallId=="a" })
        assertTrue(t.observe(post(1,gallery="b"),true,now)!!.keys.all { it.gallId=="b" })
    }
}
