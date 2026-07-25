package com.heyheyon.armbandbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiFilterProtocolTest {
    private val keyA = PostKey("M", "gallery-a", "77")
    private val keyB = PostKey("MI", "gallery-b", "77")

    @Test
    fun registryRoundTripsSamePostNumberAndCommentIdsWithoutCollision() {
        val request = request(post(keyA, "A"), post(keyB, "B"))
        val registry = AiWireRegistry.create(request)

        assertEquals(listOf("p0", "p1"), registry.posts.map { it.wirePostId })
        assertEquals(keyA, registry.resolvePost("p0")?.postKey)
        assertEquals(keyB, registry.resolvePost("p1")?.postKey)
        assertEquals(keyA, registry.resolveComment("p0:c0")?.postKey)
        assertEquals(keyB, registry.resolveComment("p1:c0")?.postKey)
        assertEquals("same-comment", registry.resolveComment("p1:c0")?.comment?.commentId)
        assertNull(registry.resolvePost("77"))
        assertNull(registry.resolvePost("p99"))
        assertNull(registry.resolveComment("p0:same-comment"))
        assertNull(registry.resolveComment("malformed"))
    }

    @Test
    fun structuredResponseMapsOnlyUniqueRegisteredOpaqueKeysToFullPostKeys() {
        val client = client(AiFilterProvider.OPENAI_COMPATIBLE)
        val request = request(post(keyA, "A"), post(keyB, "B"))
        val content = """{"results":[
            {"type":"P","key":"p0","decision":0,"reason":"ok","evidence":"-"},
            {"type":"P","key":"p1","decision":2,"reason":"bad","evidence":"B"},
            {"type":"C","key":"p0:c0","decision":2,"reason":"bad","evidence":"comment-A"},
            {"type":"P","key":"77","decision":2,"reason":"unknown","evidence":"A"},
            {"type":"C","key":"p9:c0","decision":2,"reason":"unknown","evidence":"comment-A"}
        ]}""".trimIndent()

        val evaluation = client.parseContentForTest(content, request)

        assertEquals(listOf(keyA, keyB), evaluation.postDecisions.map { it.postKey })
        assertEquals(AiFilterDecisionType.ALLOW, evaluation.postDecisions[0].decision.type)
        assertEquals(AiFilterDecisionType.BLOCK, evaluation.postDecisions[1].decision.type)
        assertEquals("same-comment", evaluation.postDecisions[0].commentDecisions.single().commentId)
    }

    @Test
    fun duplicateResponseKeyIsRejectedRatherThanLastWriteWinning() {
        val request = request(post(keyA, "A"))
        val content = """{"results":[
            {"type":"P","key":"p0","decision":0,"reason":"ok","evidence":"-"},
            {"type":"P","key":"p0","decision":2,"reason":"bad","evidence":"A"}
        ]}""".trimIndent()

        val evaluation = client(AiFilterProvider.OPENAI_COMPATIBLE).parseContentForTest(content, request)

        assertTrue(evaluation.postDecisions.isEmpty())
        assertTrue(evaluation.failureReason != null)
    }

    @Test
    fun malformedPaddedResponseKeysAreRejectedAcrossSupportedFormats() {
        val request = request(post(keyA, "A"))
        val client = client(AiFilterProvider.OPENAI_COMPATIBLE)

        val structured = client.parseContentForTest(
            """{"results":[{"type":"P","key":" p0 ","decision":2,"reason":"bad","evidence":"A"}]}""",
            request,
        )
        val legacy = client.parseContentForTest(
            """{"results":[{"post_no":" p0 ","post_decision":"BLOCK","post_reason":"bad","post_evidence":"A"}]}""",
            request,
        )
        val compact = client.parseContentForTest("P| p0 |2|bad|A", request)

        listOf(structured, legacy, compact).forEach { evaluation ->
            assertTrue(evaluation.postDecisions.isEmpty())
            assertTrue(evaluation.failureReason != null)
        }
    }

    @Test
    fun duplicateCommentResponseKeyIsRejectedRatherThanCreatingTwoPlans() {
        val request = request(post(keyA, "A"))
        val content = """{"results":[
            {"type":"C","key":"p0:c0","decision":2,"reason":"bad","evidence":"comment-A"},
            {"type":"C","key":"p0:c0","decision":0,"reason":"ok","evidence":"-"}
        ]}""".trimIndent()

        val evaluation = client(AiFilterProvider.OPENAI_COMPATIBLE).parseContentForTest(content, request)

        assertTrue(evaluation.postDecisions.isEmpty())
        assertTrue(evaluation.failureReason != null)
    }

    @Test
    fun everyProviderPayloadUsesOpaqueIdsAndNeverSerializesPostIdentity() {
        val request = request(post(keyA, "A"), post(keyB, "B"))
        AiFilterProvider.entries.forEach { provider ->
            val payload = client(provider).buildProviderPayloadForTest(request)
            assertTrue("$provider missing p0", payload.contains("p0"))
            assertTrue("$provider missing p1", payload.contains("p1"))
            assertTrue("$provider missing opaque comment", payload.contains("p0:c0"))
            assertFalse("$provider leaked gallery A", payload.contains("gallery-a"))
            assertFalse("$provider leaked gallery B", payload.contains("gallery-b"))
            assertFalse("$provider leaked real post number", payload.contains("\"post_no\":\"77\""))
            assertFalse("$provider leaked real comment id", payload.contains("same-comment"))
        }
    }

    @Test
    fun cacheKeySeparatesFullIdentityAndEveryContentField() {
        val base = post(keyA, "body")
        val client = client(AiFilterProvider.OPENAI_COMPATIBLE)
        val baseKey = client.buildCacheKeyForTest(request(base))
        val galleryKey = client.buildCacheKeyForTest(request(base.copy(postKey = keyB)))
        val enrichedKey = client.buildCacheKeyForTest(request(base.copy(body = "body\nPUM SOURCE CONTENT")))
        val mediaKey = client.buildCacheKeyForTest(request(base.copy(mediaSources = listOf("source-extra"))))
        val commentKey = client.buildCacheKeyForTest(request(base.copy(comments = listOf(base.comments.single().copy(body = "changed")))))

        assertNotEquals(baseKey, galleryKey)
        assertNotEquals(baseKey, enrichedKey)
        assertNotEquals(baseKey, mediaKey)
        assertNotEquals(baseKey, commentKey)
    }

    private fun post(key: PostKey, body: String) = AiFilterPostInput(
        postKey = key,
        title = "title-$body",
        authorIdOrIp = "author",
        nickname = "nickname",
        body = body,
        mediaSources = listOf("media"),
        comments = listOf(AiFilterCommentInput("same-comment", "commenter", "cnick", "comment-$body")),
    )

    private fun request(vararg posts: AiFilterPostInput) = AiFilterBatchRequest(posts.toList())

    private fun client(provider: AiFilterProvider) = AiFilterClient(
        AiFilterConfig(
            enabled = true,
            provider = provider,
            endpoint = "http://localhost",
            apiKey = "test-key",
            model = "test-model",
            userPrompt = "policy",
            reviewMode = false,
        )
    )
}
