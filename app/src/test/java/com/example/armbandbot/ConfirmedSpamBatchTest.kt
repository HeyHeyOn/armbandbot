package com.heyheyon.armbandbot

import org.junit.Assert.*
import org.junit.Test

class ConfirmedSpamBatchTest {
    private val now = 1_800_000_000_000L
    private fun post(n: Int) = RepeatSpamPost(PostKey("M", "fixture", "$n"), "ip:203.239",
        now - (4 - n) * 15_000L, "다른 제목 $n", "다른 본문 $n")
    private fun tracker() = RepeatSpamTracker().apply {
        updatePolicy("one", RepeatSpamSettings(enabled = true, contentEnabled = false,
            postingRate = PostingRateSettings(enabled = true, ipCount = 3, ipSeconds = 60)))
    }

    @Test fun reverseScanKeepsRemainingConfirmedPostsAfterCycleWait() {
        val t = tracker()
        // The newest-first list reaches the threshold at the oldest post.
        listOf(3, 2, 1).forEach { t.observe(post(it), false, now, true) }
        t.complete(post(1).key, SpamMatchKind.POSTING_RATE, allKinds = true)
        // The next cycle starts after the detection window, but these exact posts already qualified.
        for (n in listOf(3, 2)) {
            assertTrue("Confirmed post $n was lost during the cycle wait", t.needsRecheck(post(n).key, now + 120_000))
            assertNotNull(t.observe(post(n), false, now + 120_000, true))
            assertTrue(t.canAct(post(n).key, now + 120_000, SpamMatchKind.POSTING_RATE))
            t.complete(post(n).key, SpamMatchKind.POSTING_RATE, allKinds = true)
        }
    }

    private fun confirmed() = tracker().apply { listOf(3, 2, 1).forEach { observe(post(it), false, now, true) } }

    @Test fun confirmationDoesNotPullInNewPostsAfterOriginalWindow() {
        val t = confirmed()
        assertNull(t.observe(post(4).copy(createdAt = now + 120_000), false, now + 120_000, true))
        assertFalse(t.canAct(post(4).key, now + 120_000))
        assertTrue(t.canAct(post(2).key, now + 120_000))
    }

    @Test fun rescansCannotExtendConfirmationDeadline() {
        val t = confirmed()
        for (elapsed in listOf(120_000L, 600_000L, CONFIRMED_SPAM_RETENTION_MS)) {
            assertNotNull(t.observe(post(2), false, now + elapsed, true))
        }
        assertFalse(t.canAct(post(2).key, now + CONFIRMED_SPAM_RETENTION_MS + 1))
        assertNull(t.observe(post(2), false, now + CONFIRMED_SPAM_RETENTION_MS + 1, true))
    }

    @Test fun editedOrNewlyProtectedEvidenceCancelsWholeConfirmedGroup() {
        for (protect in listOf(false, true)) {
            val t = confirmed()
            t.observe(if (protect) post(2) else post(2).copy(title = "수정된 글"), false, now + 120_000, !protect)
            for (n in 1..3) assertFalse(t.canAct(post(n).key, now + 120_000))
        }
    }

    @Test fun longDetectionWindowDoesNotRenewExpiredConfirmedActions() {
        for (kind in SpamMatchKind.entries) {
            val t = RepeatSpamTracker().apply {
                updatePolicy("one", RepeatSpamSettings(enabled = true, count = 3, windowMinutes = 60,
                    contentEnabled = kind == SpamMatchKind.CONTENT,
                    postingRate = PostingRateSettings(enabled = kind == SpamMatchKind.POSTING_RATE,
                        ipCount = 3, ipSeconds = 3600)))
            }
            val posts = (1..3).map { post(it).copy(title = "반복되는 충분한 길이의 제목입니다") }
            posts.forEach { t.observe(it, kind == SpamMatchKind.CONTENT, now, kind == SpamMatchKind.POSTING_RATE) }
            assertTrue(t.canAct(posts.first().key, now))
            val expired = now + CONFIRMED_SPAM_RETENTION_MS + 1
            assertFalse(t.canAct(posts.first().key, expired))
            posts.forEach { assertNull(t.observe(it, kind == SpamMatchKind.CONTENT, expired, kind == SpamMatchKind.POSTING_RATE)) }
        }
    }

    @Test fun completedPostsAreNotRepeatedButStillSupportRemainingEvidence() {
        val t = confirmed()
        t.complete(post(1).key, SpamMatchKind.POSTING_RATE, allKinds = true)
        assertNull(t.observe(post(1), false, now + 120_000, true))
        assertTrue(t.canAct(post(2).key, now + 120_000))
        assertFalse(t.canAct(post(1).key, now + 120_000))
    }

    @Test fun stopsChangesClockRollbackAndExplicitForgetCancelConfirmation() {
        for (reset in listOf("stop", "settings", "clock", "forget")) {
            val t = confirmed()
            when (reset) {
                "stop" -> t.clear()
                "settings" -> t.updatePolicy("two", RepeatSpamSettings())
                "clock" -> assertFalse(t.canAct(post(2).key, now - 1))
                "forget" -> t.forget(post(1).key)
            }
            assertFalse(t.canAct(post(2).key, now + 120_000))
        }
    }

    @Test fun unconfirmedCandidatesStillExpireAtUserThreshold() {
        val t = tracker()
        t.observe(post(1), false, now, true)
        t.observe(post(2), false, now, true)
        assertNull(t.observe(post(3), false, now + 60_001, true))
        assertFalse(t.needsRecheck(post(1).key, now + 60_001))
    }

    @Test fun minorMiniAndContentDetectionRetainConfirmedFirstAndRemainingPosts() {
        for (type in listOf("M", "MI")) for (kind in SpamMatchKind.entries) {
            val t = RepeatSpamTracker().apply { updatePolicy("one", RepeatSpamSettings(enabled = true, count = 3,
                windowMinutes = 1, contentEnabled = kind == SpamMatchKind.CONTENT,
                postingRate = PostingRateSettings(enabled = kind == SpamMatchKind.POSTING_RATE, ipCount = 3))) }
            val posts = (1..3).map { post(it).copy(key = post(it).key.copy(gallType = type), title = "반복되는 충분한 길이의 제목입니다") }
            posts.reversed().forEach { t.observe(it, kind == SpamMatchKind.CONTENT, now, kind == SpamMatchKind.POSTING_RATE) }
            t.complete(posts.first().key, kind, allKinds = true)
            posts.drop(1).forEach {
                assertEquals(kind, t.observe(it, kind == SpamMatchKind.CONTENT, now + 120_000, kind == SpamMatchKind.POSTING_RATE)?.kind)
                t.complete(it.key, kind, allKinds = true)
                assertFalse(t.canAct(it.key, now + 120_000))
            }
        }
    }
}
