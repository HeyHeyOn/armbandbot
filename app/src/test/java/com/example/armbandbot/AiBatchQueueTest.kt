package com.heyheyon.armbandbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiBatchQueueTest {
    @Test
    fun samePostNumberInDifferentGalleriesDoesNotReplace() {
        val queue = AiBatchQueue(maxPosts = 10, maxWaitMs = 60_000, maxWeight = 100_000)
        val first = item(PostKey("M", "gallery-a", "42"), "first")
        val second = item(PostKey("MI", "gallery-b", "42"), "second")

        queue.addOrReplace(first)
        queue.addOrReplace(second)

        assertEquals(listOf(first, second), queue.drainFlushable())
    }

    @Test
    fun exactPostKeyStillReplaces() {
        val queue = AiBatchQueue(maxPosts = 10, maxWaitMs = 60_000, maxWeight = 100_000)
        val key = PostKey("G", "gallery", "42")
        val first = item(key, "first")
        val replacement = item(key, "replacement")

        queue.addOrReplace(first)
        queue.addOrReplace(replacement)

        assertEquals(listOf(replacement), queue.drainFlushable())
    }

    @Test
    fun executionPlansRetainFullPostIdentity() {
        val keyA = PostKey("M", "gallery-a", "42")
        val keyB = PostKey("MI", "gallery-b", "42")
        val fingerprintA = AiInputFingerprint.from(item(keyA, "first").postInput)
        val fingerprintB = AiInputFingerprint.from(item(keyB, "second").postInput)

        val postPlans = setOf(
            AiPostExecutionPlan(keyA, "reason", "category", 100, fingerprintA),
            AiPostExecutionPlan(keyB, "reason", "category", 100, fingerprintB),
        )
        val commentPlans = setOf(
            AiCommentExecutionPlan(keyA, "same-comment", "reason", "category", 100, fingerprintA),
            AiCommentExecutionPlan(keyB, "same-comment", "reason", "category", 100, fingerprintB),
        )

        assertEquals(2, postPlans.size)
        assertEquals(2, commentPlans.size)
    }

    @Test
    fun oversizedItemCanBeAtomicallyRemovedWithoutDrainingOtherItems() {
        val queue = AiBatchQueue(maxPosts = 10, maxWaitMs = 60_000, maxWeight = 10)
        val oversized = item(PostKey("M", "gallery-a", "42"), "oversized")
        val other = item(PostKey("MI", "gallery-b", "43"), "other")
        queue.addOrReplace(oversized)
        queue.addOrReplace(other)

        assertEquals(oversized, queue.remove(oversized.postKey))
        assertNull(queue.remove(oversized.postKey))
        assertEquals(listOf(other), queue.drainFlushable())
    }

    @Test
    fun fingerprintCoversAllAiInputContentWithoutContainingIt() {
        val key = PostKey("M", "gallery", "42")
        val original = item(key, "private title").postInput
        val same = original.copy()
        val changedBody = original.copy(body = "changed body")
        val changedMedia = original.copy(mediaSources = listOf("changed media"))
        val changedComment = original.copy(comments = listOf(AiFilterCommentInput("7", "author", "nick", "changed")))

        val fingerprint = AiInputFingerprint.from(original)
        assertEquals(fingerprint, AiInputFingerprint.from(same))
        assertFalse(fingerprint == AiInputFingerprint.from(changedBody))
        assertFalse(fingerprint == AiInputFingerprint.from(changedMedia))
        assertFalse(fingerprint == AiInputFingerprint.from(changedComment))
        assertFalse(fingerprint.value.contains("private title"))
    }

    @Test
    fun delayedResultOnlyMatchesExactPostKeyAndCurrentInput() {
        val input = item(PostKey("M", "gallery", "42"), "title").postInput
        val decision = postDecision(input.postKey, AiFilterDecisionType.BLOCK)
        val result = AiBatchResult.forInput(decision, input)

        assertTrue(result.matches(input.postKey, input))
        val otherKey = PostKey("MI", "gallery", "42")
        assertFalse(result.matches(otherKey, input.copy(postKey = otherKey)))
        assertFalse(result.matches(input.postKey, input.copy(body = "edited")))
    }

    @Test
    fun reviewApplicationIsIdenticalForFreshAndDelayedResults() {
        val key = PostKey("M", "gallery", "42")
        val review = postDecision(key, AiFilterDecisionType.REVIEW, "needs review")

        val delayed = applyAiPostDecision(review)
        val fresh = applyAiPostDecision(review)

        assertEquals(delayed, fresh)
        assertEquals(AiFilterDecisionType.REVIEW, fresh.decision.type)
        assertEquals("needs review", fresh.reviewReason)
        assertEquals("AI 필터 검토 필요", fresh.blockReasonPrefix)
        assertEquals("ai", fresh.notificationType)
    }

    @Test
    fun executionPlansRejectChangedInputFingerprints() {
        val input = item(PostKey("M", "gallery", "42"), "title").postInput
        val fingerprint = AiInputFingerprint.from(input)
        val postPlan = AiPostExecutionPlan(input.postKey, "reason", "category", 80, fingerprint)
        val commentPlan = AiCommentExecutionPlan(input.postKey, "7", "reason", "category", 80, fingerprint)

        assertTrue(postPlan.matches(input))
        assertTrue(commentPlan.matches(input))
        assertFalse(postPlan.matches(input.copy(body = "edited")))
        assertFalse(commentPlan.matches(input.copy(comments = listOf(AiFilterCommentInput("7", "a", "n", "edited")))))
    }

    @Test
    fun terminalCommentExecutionCanBeRemovedWithoutDiscardingReviewOrRetries() {
        val input = item(PostKey("M", "gallery", "42"), "title").postInput
        val review = postDecision(input.postKey, AiFilterDecisionType.REVIEW, "review").copy(
            commentDecisions = listOf(
                AiFilterCommentDecision("1", AiFilterDecision(AiFilterDecisionType.BLOCK, "one", "category", 80, "")),
                AiFilterCommentDecision("2", AiFilterDecision(AiFilterDecisionType.BLOCK, "two", "category", 80, "")),
            )
        )
        val result = AiBatchResult.forInput(review, input)

        val pruned = result.withoutComment("1")

        assertEquals(AiFilterDecisionType.REVIEW, pruned.decision.decision.type)
        assertEquals(listOf("2"), pruned.decision.commentDecisions.map { it.commentId })
        assertTrue(pruned.matches(input.postKey, input))
    }

    @Test
    fun executionPolicyRetriesOperationalFailuresAndThrottleButFinalizesTerminalOutcomes() {
        assertTrue(AiExecutionPolicy.shouldRetry(success = false, response = "HTTP 503"))
        assertTrue(AiExecutionPolicy.shouldRetry(success = true, response = "{\"result\":\"skipped\",\"reason\":\"recent_failure_throttle\"}"))
        assertFalse(AiExecutionPolicy.shouldRetry(success = true, response = "{\"result\":\"success\"}"))
        assertFalse(AiExecutionPolicy.shouldRetry(success = true, response = "{\"result\":\"skipped\",\"reason\":\"invalid_comment_no\"}"))
    }

    private fun postDecision(key: PostKey, type: AiFilterDecisionType, reason: String = "reason") =
        AiFilterPostDecision(
            postKey = key,
            decision = AiFilterDecision(type, reason, "category", 80, ""),
        )

    private fun item(key: PostKey, title: String) = AiBatchQueueItem(
        postKey = key,
        postInput = AiFilterPostInput(
            postKey = key,
            title = title,
            authorIdOrIp = "author",
            nickname = "nick",
            body = "body",
            mediaSources = emptyList(),
            comments = emptyList(),
        ),
        createdAtMs = 1,
    )
}
