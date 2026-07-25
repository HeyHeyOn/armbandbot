package com.heyheyon.armbandbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AiFilterProtocolTest {
    private val keyA = PostKey("M", "gallery-a", "77")
    private val keyB = PostKey("MI", "gallery-b", "77")

    @Before
    fun clearCache() = AiFilterClient.clearCacheForTest()

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
    fun structuredResponseMapsCompleteRegisteredOpaqueKeysToFullPostKeys() {
        val request = request(post(keyA, "A"), post(keyB, "B"))
        val content = """{"results":[
            {"type":"P","key":"p0","decision":0,"reason":"ok","evidence":"-"},
            {"type":"P","key":"p1","decision":2,"reason":"bad","evidence":"B"},
            {"type":"C","key":"p0:c0","decision":2,"reason":"bad","evidence":"comment-A"},
            {"type":"C","key":"p1:c0","decision":0,"reason":"ok","evidence":"-"}
        ]}""".trimIndent()
        val evaluation = client(AiFilterProvider.OPENAI_COMPATIBLE).parseContentForTest(content, request)
        assertNull(evaluation.failureReason)
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
            {"type":"P","key":"p0","decision":2,"reason":"bad","evidence":"A"},
            {"type":"C","key":"p0:c0","decision":0,"reason":"ok","evidence":"-"}
        ]}""".trimIndent()
        assertFailure(client(AiFilterProvider.OPENAI_COMPATIBLE).parseContentForTest(content, request))
    }

    @Test
    fun malformedPaddedResponseKeysAreRejectedAcrossSupportedFormats() {
        val request = request(post(keyA, "A"))
        val client = client(AiFilterProvider.OPENAI_COMPATIBLE)
        listOf(
            """{"results":[{"type":"P","key":" p0 ","decision":0,"reason":"ok","evidence":"-"},{"type":"C","key":"p0:c0","decision":0,"reason":"ok","evidence":"-"}]}""",
            """{"results":[{"post_no":" p0 ","post_decision":"ALLOW","post_reason":"ok","post_evidence":"-","comments":[{"comment_id":"p0:c0","decision":"ALLOW","reason":"ok","evidence":"-"}]}]}""",
            "P| p0 |0|ok|-\nC|p0:c0|0|ok|-",
        ).forEach { assertFailure(client.parseContentForTest(it, request)) }
    }

    @Test
    fun duplicateCommentResponseKeyIsRejectedRatherThanCreatingTwoPlans() {
        val request = request(post(keyA, "A"))
        val content = """{"results":[
            {"type":"P","key":"p0","decision":0,"reason":"ok","evidence":"-"},
            {"type":"C","key":"p0:c0","decision":2,"reason":"bad","evidence":"comment-A"},
            {"type":"C","key":"p0:c0","decision":0,"reason":"ok","evidence":"-"}
        ]}""".trimIndent()
        assertFailure(client(AiFilterProvider.OPENAI_COMPATIBLE).parseContentForTest(content, request))
    }

    @Test
    fun compactDuplicateKeyCountsMalformedDecisionLineBeforeDroppingIt() {
        val request = request(post(keyA, "A"))
        val content = "P|p0|BOGUS|malformed|-\nP|p0|2|bad|A\nC|p0:c0|0|ok|-"
        assertFailure(client(AiFilterProvider.OPENAI_COMPATIBLE).parseContentForTest(content, request))
    }

    @Test
    fun partialCommentOnlyTruncatedAndUnknownResponsesFailInEveryFormat() {
        val request = request(post(keyA, "A"))
        val client = client(AiFilterProvider.OPENAI_COMPATIBLE)
        listOf(
            """{"results":[{"type":"C","key":"p0:c0","decision":0,"reason":"ok","evidence":"-"}]}""",
            """{"results":[{"post_no":"p0","post_decision":"ALLOW","post_reason":"ok","post_evidence":"-","comments":[]}]}""",
            "P|p0|0|ok|-",
            """{"results":[{"type":"P","key":"p0","decision":0,"reason":"ok","evidence":"-"},{"type":"C","key":"p0:c0","decision":0,"reason":"ok","evidence":"-"},{"type":"P","key":"p9","decision":0,"reason":"ok","evidence":"-"}]}""",
        ).forEach { assertFailure(client.parseContentForTest(it, request)) }
    }

    @Test
    fun completeLegacyAndCompactResponsesAreAccepted() {
        val request = request(post(keyA, "A"))
        val client = client(AiFilterProvider.OPENAI_COMPATIBLE)
        val legacy = """{"results":[{"post_no":"p0","post_decision":"ALLOW","post_reason":"ok","post_evidence":"-","comments":[{"comment_id":"p0:c0","decision":"ALLOW","reason":"ok","evidence":"-"}]}]}"""
        val compact = "P|p0|0|ok|-\nC|p0:c0|0|ok|-"
        assertNull(client.parseContentForTest(legacy, request).failureReason)
        assertNull(client.parseContentForTest(compact, request).failureReason)
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
    fun cacheKeySeparatesFullIdentityEveryContentFieldAndCredentialNamespace() {
        val base = post(keyA, "body")
        val client = client(AiFilterProvider.OPENAI_COMPATIBLE)
        val baseKey = client.buildCacheKeyForTest(request(base))
        assertNotEquals(baseKey, client.buildCacheKeyForTest(request(base.copy(postKey = keyB))))
        assertNotEquals(baseKey, client.buildCacheKeyForTest(request(base.copy(body = "body\nPUM SOURCE CONTENT"))))
        assertNotEquals(baseKey, client.buildCacheKeyForTest(request(base.copy(mediaSources = listOf("source-extra")))))
        assertNotEquals(baseKey, client.buildCacheKeyForTest(request(base.copy(comments = listOf(base.comments.single().copy(body = "changed"))))))
        assertNotEquals(baseKey, client(AiFilterProvider.OPENAI_COMPATIBLE, apiKey = "another").buildCacheKeyForTest(request(base)))
        assertFalse(baseKey.contains("test-key"))
    }

    @Test
    fun geminiDefaultUsesCanonicalUrlAndHeaderAuthentication() {
        val secret = "gemini-super-secret"
        val client = client(AiFilterProvider.GEMINI_DIRECT, endpoint = "", apiKey = secret)
        val url = client.buildRequestUrlForTest()
        val headers = client.buildAuthHeadersForTest(url)
        assertFalse(url.contains("?key="))
        assertFalse(url.contains(secret))
        assertEquals(secret, headers["x-goog-api-key"])
        assertFalse(headers.toString().contains("***"))
    }

    @Test
    fun logUrlSanitizerStripsEntireQuery() {
        val sanitized = AiFilterClient.sanitizeUrlForLog("https://example.test/path?key=***&tenant=private")
        assertEquals("https://example.test/path", sanitized)
        assertFalse(sanitized.contains("private"))
        assertFalse(sanitized.contains("***"))
    }

    @Test
    fun cacheIsCredentialIsolatedExpiresAndNeverExposesCredential() {
        var now = 1_000L
        var calls = 0
        val response = """{"choices":[{"message":{"content":"{\"results\":[{\"type\":\"P\",\"key\":\"p0\",\"decision\":0,\"reason\":\"ok\",\"evidence\":\"-\"},{\"type\":\"C\",\"key\":\"p0:c0\",\"decision\":0,\"reason\":\"ok\",\"evidence\":\"-\"}]}"}}]}"""
        fun cachedClient(credential: String) = AiFilterClient(
            config(AiFilterProvider.OPENAI_COMPATIBLE, apiKey = credential),
            clockMillis = { now },
            apiCaller = { _, _ -> calls++; response },
        )
        val request = request(post(keyA, "A"))
        val secretA = "credential-A-secret"
        val first = cachedClient(secretA)
        assertFalse(first.buildCacheKeyForTest(request).contains(secretA))
        assertNull(first.evaluateBatch(request).failureReason)
        assertNull(cachedClient(secretA).evaluateBatch(request).failureReason)
        assertEquals(1, calls)
        assertNull(cachedClient("credential-B-secret").evaluateBatch(request).failureReason)
        assertEquals(2, calls)
        now += AiFilterClient.CACHE_TTL_MS + 1
        assertNull(cachedClient(secretA).evaluateBatch(request).failureReason)
        assertEquals(3, calls)
    }

    @Test
    fun incompleteResponsesAreNotCachedAndCredentialNeverAppearsInLogs() {
        var calls = 0
        val logs = mutableListOf<String>()
        val incomplete = """{"choices":[{"message":{"content":"{\"results\":[{\"type\":\"P\",\"key\":\"p0\",\"decision\":0,\"reason\":\"ok\",\"evidence\":\"-\"}]}"}}]}"""
        val complete = """{"choices":[{"message":{"content":"{\"results\":[{\"type\":\"P\",\"key\":\"p0\",\"decision\":0,\"reason\":\"ok\",\"evidence\":\"-\"},{\"type\":\"C\",\"key\":\"p0:c0\",\"decision\":0,\"reason\":\"ok\",\"evidence\":\"-\"}]}"}}]}"""
        val secret = "never-log-this-credential"
        val client = AiFilterClient(
            config(AiFilterProvider.OPENAI_COMPATIBLE, apiKey = secret).copy(debugLoggingEnabled = true),
            logger = logs::add,
            apiCaller = { _, _ -> calls++; if (calls == 1) incomplete else complete },
        )
        val request = request(post(keyA, "A"))

        assertFailure(client.evaluateBatch(request))
        assertNull(client.evaluateBatch(request).failureReason)
        assertEquals(2, calls)
        assertTrue(logs.none { it.contains(secret) || it.contains(secret.take(4)) })
    }

    @Test
    fun exceptionLogsStripConfiguredEndpointQueryValues() {
        val endpoint = "https://example.test/v1/chat?key=url-secret&tenant=private"
        val logs = mutableListOf<String>()
        val client = AiFilterClient(
            config(AiFilterProvider.OPENAI_COMPATIBLE, endpoint = endpoint),
            logger = logs::add,
            apiCaller = { _, _ -> error("request failed at $endpoint") },
        )

        val evaluation = client.evaluateBatch(request(post(keyA, "A")))

        assertTrue(evaluation.failureReason != null)
        assertTrue(logs.none { it.contains("url-secret") || it.contains("tenant=private") })
        assertFalse(evaluation.failureReason.orEmpty().contains("url-secret"))
    }

    private fun assertFailure(evaluation: AiFilterBatchEvaluation) {
        assertTrue(evaluation.postDecisions.isEmpty())
        assertTrue(evaluation.failureReason != null)
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

    private fun config(provider: AiFilterProvider, endpoint: String = "http://localhost", apiKey: String = "test-key") =
        AiFilterConfig(
            enabled = true,
            provider = provider,
            endpoint = endpoint,
            apiKey = apiKey,
            model = "test-model",
            userPrompt = "policy",
            reviewMode = false,
        )

    private fun client(provider: AiFilterProvider, endpoint: String = "http://localhost", apiKey: String = "test-key") =
        AiFilterClient(config(provider, endpoint, apiKey))
}
