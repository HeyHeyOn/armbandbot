package com.heyheyon.armbandbot

import org.junit.Assert.assertEquals
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

        val postPlans = setOf(
            AiPostExecutionPlan(keyA, "reason", "category", 100),
            AiPostExecutionPlan(keyB, "reason", "category", 100),
        )
        val commentPlans = setOf(
            AiCommentExecutionPlan(keyA, "same-comment", "reason", "category", 100),
            AiCommentExecutionPlan(keyB, "same-comment", "reason", "category", 100),
        )

        assertEquals(2, postPlans.size)
        assertEquals(2, commentPlans.size)
    }

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
