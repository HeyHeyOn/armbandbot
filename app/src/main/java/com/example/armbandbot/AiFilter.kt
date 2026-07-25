package com.heyheyon.armbandbot

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.LinkedHashMap

internal enum class AiFilterProvider {
    OPENAI_COMPATIBLE,
    GEMINI_DIRECT,
    GROQ,
    LM_STUDIO,
}

internal val DefaultAiFilterSystemPrompt = """
        당신은 커뮤니티 게시글/댓글 전용 2차 AI 필터입니다.
        기존 1차 룰 기반 필터를 통과한 게시글 묶음을 입력으로 받습니다.
        입력은 항상 원문 전체이며, 게시글과 댓글을 반드시 분리해서 판단해야 합니다.
        출력은 반드시 JSON 객체 1개만 허용됩니다. 마크다운, 설명, 코드블록, 일반 문장, compact line 출력 금지.
        최상위 JSON 형식은 정확히 다음과 같습니다.
        {"results":[{"type":"P","key":"POST_ID","decision":0,"reason":"문제없음","evidence":"-"},{"type":"C","key":"POST_ID:COMMENT_ID","decision":0,"reason":"단순호응","evidence":"-"}]}
        results: 판단 결과 배열
        type: 게시글은 P, 댓글은 C
        key: 게시글은 입력의 post_no, 댓글은 입력 comments의 comment_key 값을 그대로 복사한 값
        decision: 0=허용, 1=보류, 2=차단. 반드시 숫자로 출력하세요.
        reason: 판단 이유. 반드시 10글자 이내의 짧은 한국어. 허용도 반드시 채우세요. 예: 문제없음, 단순호응.
        evidence: 2=차단일 때만 현재 항목 원문에 실제로 포함된 짧은 근거 조각. 허용/보류는 반드시 "-".
        모든 results 항목은 type, key, decision, reason, evidence 5개 필드를 반드시 포함해야 합니다.
        추가 필드, 누락 필드, null 값, 문자열 decision, 배열 밖 텍스트는 금지입니다.
        게시글 번호는 P 항목의 key에만 출력하세요.
        댓글은 POST_ID와 COMMENT_ID를 따로 나누지 말고 입력의 comment_key를 그대로 key에 복사하세요.
        입력에 없는 ID를 새로 만들거나 출력하지 마세요.
        게시글과 댓글은 각각 결과 객체 1개씩 출력하세요.
        게시글 판단과 댓글 판단은 별개입니다.
        게시글이 정상이고 댓글만 문제인 경우 게시글은 0, 댓글만 2로 출력하세요.
        애매하거나 판단 근거가 부족하면 1을 선택하세요.
        reviewMode 가 활성화되어 있으면 2는 앱 쪽에서 1로 완화될 수 있습니다.
        사용자 지침(user_prompt)이 유일한 차단 정책입니다.
        user_prompt에 명시되지 않은 사유로 2를 출력하지 마세요.
        일반적인 성적 내용, 욕설, 취향, 정체성, 논쟁성은 user_prompt가 직접 금지하지 않으면 0으로 두세요.
        선의의 확장 해석, 자체 커뮤니티 규칙 추가, 넓은 도덕 판단을 금지합니다.
        삭제된 댓글 안내문(예: 해당 댓글은 삭제되었습니다)은 판단 대상이 아니며 항상 0입니다.
        각 게시글과 댓글은 서로 완전히 독립적으로 판단해야 합니다.
        배치는 처리 효율을 위한 포장일 뿐이며 여러 글을 한 사건, 한 대화, 한 흐름으로 묶어 해석하지 마세요.
        같은 배치에 포함된 다른 게시글/댓글의 키워드, 맥락, 결론, 추정 의도를 현재 항목 판단에 절대 적용하지 마세요.
        이전 항목에서 차단 대상이 나왔더라도 다음 항목의 차단 근거로 전염시키지 마세요.
        특정 글/댓글에서 발견한 금지 주제나 분위기를 다른 글/댓글에 일반화하지 마세요.
        현재 항목 텍스트 안에 user_prompt 위반 근거가 직접 없으면 0을 출력하세요.
        2를 출력하려면 evidence가 현재 항목 텍스트 안에 실제로 포함되어 있어야 합니다.
        댓글 판단의 evidence는 해당 댓글 본문에서만 가져오고, 게시글 제목/본문이나 다른 댓글에서 가져오면 안 됩니다.
        게시글 판단의 evidence는 해당 게시글 제목/본문에서만 가져오고, 댓글에서 가져오면 안 됩니다.
        현재 항목 텍스트에 직접 나타난 근거만 사용하세요.
        유사 표현, 연상, 비슷한 어감, 느슨한 의미 확장만으로 차단하지 마세요.
        한 항목이 차단 대상이어도 다른 항목은 처음부터 별도로 다시 판단해야 합니다.
    """.trimIndent()

internal data class AiFilterConfig(
    val enabled: Boolean,
    val provider: AiFilterProvider = AiFilterProvider.OPENAI_COMPATIBLE,
    val endpoint: String,
    val apiKey: String,
    val model: String,
    val userPrompt: String,
    val systemPrompt: String = DefaultAiFilterSystemPrompt,
    val reviewMode: Boolean = true,
    val timeoutMs: Int = 20000,
    val debugLoggingEnabled: Boolean = false,
)

internal data class AiFilterCommentInput(
    val commentId: String,
    val authorIdOrIp: String,
    val nickname: String,
    val body: String,
)

internal data class AiFilterPostInput(
    val postKey: PostKey,
    val title: String,
    val authorIdOrIp: String,
    val nickname: String,
    val body: String,
    val mediaSources: List<String>,
    val comments: List<AiFilterCommentInput>,
) {
    val postNo: String get() = postKey.postNo
}

internal data class AiFilterBatchRequest(
    val posts: List<AiFilterPostInput>,
)

internal data class AiWirePost(
    val wirePostId: String,
    val post: AiFilterPostInput,
) {
    val postKey: PostKey get() = post.postKey
}

internal data class AiWireComment(
    val wireCommentKey: String,
    val postKey: PostKey,
    val comment: AiFilterCommentInput,
)

/** Per-request, non-reversible IDs are the only identities accepted from an AI provider. */
internal class AiWireRegistry private constructor(
    val posts: List<AiWirePost>,
    private val postsByWireId: Map<String, AiWirePost>,
    private val commentsByWireKey: Map<String, AiWireComment>,
) {
    fun resolvePost(wireId: String): AiWirePost? = postsByWireId[wireId]
    fun resolveComment(wireKey: String): AiWireComment? = commentsByWireKey[wireKey]

    fun commentsFor(post: AiWirePost): List<Pair<String, AiFilterCommentInput>> =
        post.post.comments.mapIndexed { index, comment -> "${post.wirePostId}:c$index" to comment }

    companion object {
        fun create(request: AiFilterBatchRequest): AiWireRegistry {
            require(request.posts.map { it.postKey }.distinct().size == request.posts.size) {
                "Duplicate PostKey in AI request"
            }
            val wirePosts = request.posts.mapIndexed { index, post -> AiWirePost("p$index", post) }
            val comments = wirePosts.flatMap { wirePost ->
                wirePost.post.comments.mapIndexed { index, comment ->
                    AiWireComment("${wirePost.wirePostId}:c$index", wirePost.postKey, comment)
                }
            }
            return AiWireRegistry(
                posts = wirePosts,
                postsByWireId = wirePosts.associateBy { it.wirePostId },
                commentsByWireKey = comments.associateBy { it.wireCommentKey },
            )
        }
    }
}

internal enum class AiFilterDecisionType {
    ALLOW,
    REVIEW,
    BLOCK,
}

internal data class AiFilterDecision(
    val type: AiFilterDecisionType,
    val reason: String,
    val category: String,
    val confidence: Int,
    val rawJson: String,
    val evidence: String = "",
)

internal data class AiFilterCommentDecision(
    val commentId: String,
    val decision: AiFilterDecision,
)

internal data class AiFilterPostDecision(
    val postKey: PostKey,
    val decision: AiFilterDecision,
    val commentDecisions: List<AiFilterCommentDecision> = emptyList(),
) {
    val postNo: String get() = postKey.postNo
}

internal data class AiFilterBatchEvaluation(
    val postDecisions: List<AiFilterPostDecision> = emptyList(),
    val failureReason: String? = null,
    val rawResponseText: String? = null,
    val parsedContentText: String? = null,
    val debugSummary: String? = null,
)

internal class AiFilterClient(
    private val config: AiFilterConfig,
    private val logger: (String) -> Unit = {},
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val apiCaller: ((AiFilterBatchRequest, AiWireRegistry) -> String)? = null,
) {
    companion object {
        private const val CACHE_LIMIT = 100
        internal const val CACHE_TTL_MS = 5 * 60 * 1000L
        private data class CacheEntry(val evaluation: AiFilterBatchEvaluation, val createdAtMillis: Long)
        private val evaluationCache = object : LinkedHashMap<String, CacheEntry>(CACHE_LIMIT, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean = size > CACHE_LIMIT
        }
        private val cacheLock = Any()

        internal fun clearCacheForTest() = synchronized(cacheLock) { evaluationCache.clear() }

        internal fun sanitizeUrlForLog(url: String): String = runCatching {
            val uri = URI(url)
            URI(uri.scheme, uri.authority, uri.path, null, null).toString()
        }.getOrElse { url.substringBefore('?') }

        private fun credentialNamespace(credential: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(credential.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }

    private val systemPrompt = config.systemPrompt.ifBlank { DefaultAiFilterSystemPrompt }

    private fun redactForLog(value: String): String {
        var redacted = value
        if (config.endpoint.contains('?')) {
            redacted = redacted.replace(config.endpoint, sanitizeUrlForLog(config.endpoint))
        }
        if (config.apiKey.isNotBlank()) {
            redacted = redacted.replace(config.apiKey, "[REDACTED_SECRET]")
        }
        return redacted
    }

    fun evaluateBatch(request: AiFilterBatchRequest): AiFilterBatchEvaluation {
        if (!config.enabled) return AiFilterBatchEvaluation()
        if (request.posts.isEmpty()) return AiFilterBatchEvaluation()
        if (config.model.isBlank()) {
            return AiFilterBatchEvaluation(failureReason = "AI 모델 설정이 비어 있습니다")
        }
        if (config.provider != AiFilterProvider.LM_STUDIO && config.apiKey.isBlank()) {
            return AiFilterBatchEvaluation(failureReason = "AI API Key 설정이 비어 있습니다")
        }
        if (config.provider == AiFilterProvider.OPENAI_COMPATIBLE && config.endpoint.isBlank()) {
            return AiFilterBatchEvaluation(failureReason = "AI endpoint 가 비어 있습니다")
        }

        val cacheKey = buildCacheKey(request)
        synchronized(cacheLock) {
            evaluationCache[cacheKey]?.let { entry ->
                if (clockMillis() - entry.createdAtMillis < CACHE_TTL_MS) {
                    logger("AI 배치 캐시 사용 (post=${entry.evaluation.postDecisions.size})")
                    return entry.evaluation
                }
                evaluationCache.remove(cacheKey)
            }
        }

        val requestUri = runCatching { URI(buildRequestUrl()) }.getOrNull()
        val debugSummary = buildString {
            append("provider=${config.provider.name}")
            append(" / model=${config.model}")
            append(" / endpointHost=${requestUri?.host ?: "none"}")
            append(" / urlHasKey=${buildRequestUrl().contains("key=")}")
            append(" / customEndpoint=${config.endpoint.isNotBlank()}")
        }

        val evaluation = try {
            if (config.debugLoggingEnabled) {
                logger("MARKER_AI_ENTER_V2 posts=${request.posts.size}")
            }
            val registry = AiWireRegistry.create(request)
            val responseText = apiCaller?.invoke(request, registry) ?: callApi(request, registry)
            parseBatchResponse(responseText, request, registry).copy(debugSummary = debugSummary)
        } catch (e: Exception) {
            val failureMessage = redactForLog(e.message ?: "AI 배치 호출 실패")
            logger("AI 배치 호출 실패 [MARKER_AI_CATCH_V2]: $failureMessage")
            AiFilterBatchEvaluation(failureReason = failureMessage, debugSummary = debugSummary)
        }

        if (evaluation.failureReason == null && evaluation.postDecisions.isNotEmpty()) {
            synchronized(cacheLock) {
                evaluationCache[cacheKey] = CacheEntry(evaluation, clockMillis())
            }
        } else {
            logger("AI 배치 캐시 저장 생략 (failure=${evaluation.failureReason != null}, postDecisions=${evaluation.postDecisions.size})")
        }
        return evaluation
    }

    internal fun buildCacheKeyForTest(request: AiFilterBatchRequest): String = buildCacheKey(request)

    private fun buildCacheKey(request: AiFilterBatchRequest): String {
        return buildString {
            fun field(value: String) { append(value.length).append(':').append(value).append('|') }
            field(config.provider.name)
            field(config.endpoint)
            field(credentialNamespace(config.apiKey))
            field(config.model)
            field(config.userPrompt)
            field(systemPrompt)
            field(config.reviewMode.toString())
            request.posts.forEach { post ->
                field(post.postKey.gallType)
                field(post.postKey.gallId)
                field(post.postKey.postNo)
                field(post.title)
                field(post.authorIdOrIp)
                field(post.nickname)
                field(post.body)
                field(post.mediaSources.size.toString())
                post.mediaSources.forEach(::field)
                field(post.comments.size.toString())
                post.comments.forEach { comment ->
                    field(comment.commentId)
                    field(comment.authorIdOrIp)
                    field(comment.nickname)
                    field(comment.body)
                }
            }
        }
    }

    private fun buildComposedUserPrompt(registry: AiWireRegistry): String {
        val payload = JSONObject().apply {
            put("user_prompt", config.userPrompt.ifBlank { "추가 사용자 지침 없음" })
            put("posts", JSONArray().apply {
                registry.posts.forEach { wirePost ->
                    val post = wirePost.post
                    put(JSONObject().apply {
                        put("post_no", wirePost.wirePostId)
                        put("title", post.title)
                        put("author_id_or_ip", post.authorIdOrIp)
                        put("nickname", post.nickname)
                        put("body", post.body)
                        put("media_sources", JSONArray(post.mediaSources))
                        put("comments", JSONArray().apply {
                            registry.commentsFor(wirePost).forEach { (wireCommentKey, comment) ->
                                put(JSONObject().apply {
                                    put("comment_key", wireCommentKey)
                                    put("comment_id", wireCommentKey)
                                    put("author_id_or_ip", comment.authorIdOrIp)
                                    put("nickname", comment.nickname)
                                    put("body", comment.body)
                                })
                            }
                        })
                    })
                }
            })
        }
        return payload.toString()
    }

    private fun buildOpenAiPayload(registry: AiWireRegistry): JSONObject {
        val composedUserPrompt = buildComposedUserPrompt(registry)
        return JSONObject().apply {
            put("model", config.model)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", systemPrompt))
                put(JSONObject().put("role", "user").put("content", composedUserPrompt))
            })
            put("temperature", 0.1)
            if (config.provider == AiFilterProvider.LM_STUDIO) {
                put("response_format", buildStructuredResponseFormat())
            }
        }
    }

    private fun buildStructuredResponseFormat(): JSONObject {
        return JSONObject().apply {
            put("type", "json_schema")
            put("json_schema", JSONObject().apply {
                put("name", "ai_filter_batch")
                put("strict", true)
                put("schema", buildStructuredResponseSchema())
            })
        }
    }

    private fun buildStructuredResponseSchema(): JSONObject {
        return JSONObject().apply {
            put("type", "object")
            put("additionalProperties", false)
            put("required", JSONArray(listOf("results")))
            put("properties", JSONObject().apply {
                put("results", JSONObject().apply {
                    put("type", "array")
                    put("items", JSONObject().apply {
                        put("type", "object")
                        put("additionalProperties", false)
                        put("required", JSONArray(listOf("type", "key", "decision", "reason", "evidence")))
                        put("properties", JSONObject().apply {
                            put("type", JSONObject().apply {
                                put("type", "string")
                                put("enum", JSONArray(listOf("P", "C")))
                            })
                            put("key", JSONObject().apply { put("type", "string") })
                            put("decision", JSONObject().apply {
                                put("type", "integer")
                                put("enum", JSONArray(listOf(0, 1, 2)))
                            })
                            put("reason", JSONObject().apply { put("type", "string") })
                            put("evidence", JSONObject().apply { put("type", "string") })
                        })
                    })
                })
            })
        }
    }

    private fun buildGeminiPayload(registry: AiWireRegistry): JSONObject {
        val composedUserPrompt = buildComposedUserPrompt(registry)
        return JSONObject().apply {
            put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
            put(
                "contents",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", composedUserPrompt)))
                )
            )
            put(
                "generationConfig",
                JSONObject()
                    .put("temperature", 0.1)
                    .put("responseMimeType", "application/json")
            )
        }
    }

    private fun buildRequestUrl(): String {
        return when (config.provider) {
            AiFilterProvider.GEMINI_DIRECT -> {
                if (config.endpoint.isNotBlank()) config.endpoint else {
                    val encodedModel = URLEncoder.encode(config.model, Charsets.UTF_8.name())
                    "https://generativelanguage.googleapis.com/v1beta/models/${encodedModel}:generateContent"
                }
            }
            AiFilterProvider.GROQ -> {
                if (config.endpoint.isNotBlank()) config.endpoint else "https://api.groq.com/openai/v1/chat/completions"
            }
            AiFilterProvider.OPENAI_COMPATIBLE -> config.endpoint
            AiFilterProvider.LM_STUDIO -> buildLmStudioRequestUrl()
        }
    }

    private fun buildLmStudioRequestUrl(): String {
        val raw = config.endpoint.trim()
        if (raw.isBlank()) return "http://10.0.2.2:1234/v1/chat/completions"
        if (raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true)) {
            return raw
        }
        val hostAndPort = if (raw.contains(":")) raw else "$raw:1234"
        return "http://$hostAndPort/v1/chat/completions"
    }

    internal fun buildRequestUrlForTest(): String = buildRequestUrl()

    internal fun buildAuthHeadersForTest(requestUrl: String = buildRequestUrl()): Map<String, String> = buildMap {
        when (config.provider) {
            AiFilterProvider.OPENAI_COMPATIBLE, AiFilterProvider.GROQ -> put("Authorization", "Bearer ${config.apiKey}")
            AiFilterProvider.LM_STUDIO -> if (config.apiKey.isNotBlank()) put("Authorization", "Bearer ${config.apiKey}")
            AiFilterProvider.GEMINI_DIRECT -> if (!requestUrl.contains("key=", ignoreCase = true)) {
                put("x-goog-api-key", config.apiKey)
            }
        }
    }

    internal fun buildProviderPayloadForTest(request: AiFilterBatchRequest): String {
        val registry = AiWireRegistry.create(request)
        return when (config.provider) {
            AiFilterProvider.OPENAI_COMPATIBLE, AiFilterProvider.GROQ, AiFilterProvider.LM_STUDIO -> buildOpenAiPayload(registry)
            AiFilterProvider.GEMINI_DIRECT -> buildGeminiPayload(registry)
        }.toString()
    }

    private fun callApi(request: AiFilterBatchRequest, registry: AiWireRegistry): String {
        val requestUrl = buildRequestUrl()
        val payload = when (config.provider) {
            AiFilterProvider.OPENAI_COMPATIBLE, AiFilterProvider.GROQ, AiFilterProvider.LM_STUDIO -> buildOpenAiPayload(registry)
            AiFilterProvider.GEMINI_DIRECT -> buildGeminiPayload(registry)
        }
        val sanitizedUrl = sanitizeUrlForLog(requestUrl)
        val payloadPreview = payload.toString().replace("\n", " ").replace("\r", " ").take(400)

        val authModePreview = when (config.provider) {
            AiFilterProvider.OPENAI_COMPATIBLE, AiFilterProvider.GROQ -> "bearer"
            AiFilterProvider.LM_STUDIO -> if (config.apiKey.isBlank()) "none" else "bearer"
            AiFilterProvider.GEMINI_DIRECT -> if (requestUrl.contains("key=")) "url-key" else "x-goog-api-key"
        }
        if (config.debugLoggingEnabled) {
            logger(
                "AI REQUEST PREP / provider=${config.provider.name} / model=${config.model} / url=$sanitizedUrl / authMode=$authModePreview / urlHasKey=${requestUrl.contains("key=")} / customEndpoint=${config.endpoint.isNotBlank()} / payload=$payloadPreview"
            )
        }

        val retryableStatuses = setOf(429, 500, 502, 503, 504)
        val retryDelaysMs = listOf(1500L, 4000L)
        var lastError: String? = null

        for (attempt in 0..retryDelaysMs.size) {
            val connection = (URL(requestUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = config.timeoutMs
                readTimeout = config.timeoutMs
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                buildAuthHeadersForTest(requestUrl).forEach(::setRequestProperty)
            }

            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
            if (status in 200..299) {
                return text
            }

            val safeErrorText = redactForLog(text)
            Log.w("AiFilterClient", "HTTP $status: $safeErrorText")
            val trimmedError = safeErrorText.replace("\n", " ").replace("\r", " ").take(300)
            val authModePreview = when (config.provider) {
                AiFilterProvider.OPENAI_COMPATIBLE, AiFilterProvider.GROQ -> "bearer"
                AiFilterProvider.LM_STUDIO -> if (config.apiKey.isBlank()) "none" else "bearer"
                AiFilterProvider.GEMINI_DIRECT -> if (requestUrl.contains("key=")) "url-key" else "x-goog-api-key"
            }
            logger("AI HTTP 오류 / provider=${config.provider.name} / model=${config.model} / status=$status / attempt=${attempt + 1} / authMode=$authModePreview / urlHasKey=${requestUrl.contains("key=")} / customEndpoint=${config.endpoint.isNotBlank()} / body=$trimmedError")
            lastError = "HTTP $status / $trimmedError"

            val shouldRetry = status in retryableStatuses && attempt < retryDelaysMs.size
            if (!shouldRetry) {
                error(lastError)
            }

            val delayMs = retryDelaysMs[attempt]
            logger("AI RETRY / delayMs=${delayMs}")
            Thread.sleep(delayMs)
        }

        error(lastError ?: "AI call failed")
    }

    private fun parseBatchResponse(responseText: String, request: AiFilterBatchRequest, registry: AiWireRegistry): AiFilterBatchEvaluation {
        val root = JSONObject(responseText)
        val content = when (config.provider) {
            AiFilterProvider.OPENAI_COMPATIBLE, AiFilterProvider.GROQ, AiFilterProvider.LM_STUDIO -> root.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content", "")
                ?.trim()
                .orEmpty()
            AiFilterProvider.GEMINI_DIRECT -> root.optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?.optJSONObject(0)
                ?.optString("text", "")
                ?.trim()
                .orEmpty()
        }

        if (content.isBlank()) {
            logger("AI 파싱 실패: content 비어 있음")
            return AiFilterBatchEvaluation(failureReason = "AI 응답이 비어 있습니다", rawResponseText = responseText, parsedContentText = content)
        }

        if (config.debugLoggingEnabled) {
            logger("AI raw content 길이=${content.length}")
            logger("AI raw content 미리보기=${content.take(500)}")
        }

        parseStructuredJsonBatchResponse(content, request, registry)?.let { parsed ->
            return parsed.copy(rawResponseText = responseText, parsedContentText = content)
        }

        parseLegacyJsonBatchResponse(content, request, registry)?.let { parsed ->
            return parsed.copy(rawResponseText = responseText, parsedContentText = content)
        }

        parseCompactBatchResponse(content, request, registry)?.let { parsed ->
            return parsed.copy(rawResponseText = responseText, parsedContentText = content)
        }

        logger("AI JSON/compact 파싱 실패")
        return AiFilterBatchEvaluation(failureReason = "AI 응답 파싱 실패", rawResponseText = responseText, parsedContentText = content)
    }

    internal fun parseContentForTest(content: String, request: AiFilterBatchRequest): AiFilterBatchEvaluation {
        val registry = AiWireRegistry.create(request)
        return parseStructuredJsonBatchResponse(content, request, registry)
            ?: parseLegacyJsonBatchResponse(content, request, registry)
            ?: parseCompactBatchResponse(content, request, registry)
            ?: AiFilterBatchEvaluation(failureReason = "AI 응답 파싱 실패", parsedContentText = content)
    }

    private fun expectedTypedKeys(registry: AiWireRegistry): Set<String> = buildSet {
        registry.posts.forEach { post ->
            add("P|${post.wirePostId}")
            registry.commentsFor(post).forEach { (key, _) -> add("C|$key") }
        }
    }

    private fun hasExactCoverage(keys: List<String>, registry: AiWireRegistry): Boolean {
        val expected = expectedTypedKeys(registry)
        return keys.size == expected.size && keys.distinct().size == keys.size && keys.toSet() == expected
    }

    private fun parseStructuredJsonBatchResponse(content: String, request: AiFilterBatchRequest, registry: AiWireRegistry): AiFilterBatchEvaluation? {
        val parsed = parseJsonObjectFromContent(content) ?: return null
        val resultsArray = parsed.optJSONArray("results") ?: return null
        val typedKeys = (0 until resultsArray.length()).map { index ->
            val item = resultsArray.optJSONObject(index) ?: return null
            "${item.optString("type", "").trim().uppercase()}|${item.optString("key", "")}"
        }
        if (!hasExactCoverage(typedKeys, registry)) return null
        val keyCounts = (0 until resultsArray.length())
            .mapNotNull { resultsArray.optJSONObject(it)?.optString("key", "")?.takeIf(String::isNotBlank) }
            .groupingBy { it }
            .eachCount()
        val postDecisionMap = linkedMapOf<PostKey, AiFilterDecision>()
        val commentDecisionMap = linkedMapOf<PostKey, MutableList<AiFilterCommentDecision>>()
        var parsedCount = 0

        for (i in 0 until resultsArray.length()) {
            val item = resultsArray.optJSONObject(i) ?: continue
            val type = item.optString("type", "").trim().uppercase()
            val key = item.optString("key", "")
            if (keyCounts[key] != 1) {
                logger("AI JSON 파싱 무시: 중복 response key=$key")
                continue
            }
            val action = when (val rawDecision = item.opt("decision")) {
                is Number -> rawDecision.toInt().toString()
                else -> item.optString("decision", "").trim()
            }
            val reason = item.optString("reason", "AI판단")
            val evidence = item.optString("evidence", "-")
            when (type) {
                "P" -> {
                    val wirePost = registry.resolvePost(key)
                    if (wirePost == null) {
                        logger("AI JSON 파싱 무시: 요청에 없는 post key=$key")
                        continue
                    }
                    val post = wirePost.post
                    val sourceText = listOf(post.title, post.body).joinToString("\n")
                    val decision = parseDecision(
                        actionRaw = action,
                        reason = reason,
                        evidence = evidence,
                        sourceText = sourceText,
                        reviewMode = config.reviewMode,
                        rawText = item.toString()
                    ) ?: continue
                    postDecisionMap[post.postKey] = decision
                    parsedCount++
                }
                "C" -> {
                    val wireComment = registry.resolveComment(key)
                    if (wireComment == null) {
                        logger("AI JSON 파싱 무시: 요청에 없는 comment key=$key")
                        continue
                    }
                    val comment = wireComment.comment
                    val decision = parseDecision(
                        actionRaw = action,
                        reason = reason,
                        evidence = evidence,
                        sourceText = comment.body,
                        reviewMode = config.reviewMode,
                        rawText = item.toString()
                    ) ?: continue
                    commentDecisionMap.getOrPut(wireComment.postKey) { mutableListOf() } +=
                        AiFilterCommentDecision(comment.commentId, decision)
                    parsedCount++
                }
            }
        }

        if (parsedCount != expectedTypedKeys(registry).size) return null
        val postDecisions = request.posts.mapNotNull { post ->
            val commentDecisions = commentDecisionMap[post.postKey].orEmpty()
            val postDecision = postDecisionMap[post.postKey] ?: if (commentDecisions.isNotEmpty()) {
                AiFilterDecision(
                    type = AiFilterDecisionType.ALLOW,
                    reason = "댓글만판단",
                    category = "other",
                    confidence = 0,
                    rawJson = "implicit-post-allow"
                )
            } else {
                return@mapNotNull null
            }
            AiFilterPostDecision(postKey = post.postKey, decision = postDecision, commentDecisions = commentDecisions)
        }
        logger("AI JSON 파싱 완료: items=$parsedCount / postDecisions=${postDecisions.size}")
        return AiFilterBatchEvaluation(postDecisions = postDecisions)
    }

    private fun parseLegacyJsonBatchResponse(content: String, request: AiFilterBatchRequest, registry: AiWireRegistry): AiFilterBatchEvaluation? {
        val parsed = parseJsonObjectFromContent(content) ?: return null
        val resultsArray = parsed.optJSONArray("results") ?: return null
        val typedKeys = buildList {
            for (i in 0 until resultsArray.length()) {
                val item = resultsArray.optJSONObject(i) ?: return null
                add("P|${item.optString("post_no", "")}")
                item.optJSONArray("comments")?.let { comments ->
                    for (j in 0 until comments.length()) {
                        val comment = comments.optJSONObject(j) ?: return null
                        add("C|${comment.optString("comment_id", "")}")
                    }
                }
            }
        }
        if (!hasExactCoverage(typedKeys, registry)) return null
        val postKeyCounts = (0 until resultsArray.length())
            .mapNotNull { resultsArray.optJSONObject(it)?.optString("post_no", "")?.takeIf(String::isNotBlank) }
            .groupingBy { it }.eachCount()
        val postDecisions = mutableListOf<AiFilterPostDecision>()

        for (i in 0 until resultsArray.length()) {
            val item = resultsArray.optJSONObject(i) ?: continue
            val wirePostId = item.optString("post_no", "")
            if (wirePostId.isBlank() || postKeyCounts[wirePostId] != 1) continue
            val wirePost = registry.resolvePost(wirePostId) ?: continue
            val requestPost = wirePost.post
            val sourceText = listOf(requestPost.title, requestPost.body).joinToString("\n")
            val postDecision = parseDecision(
                actionRaw = item.optString("post_decision", "REVIEW"),
                reason = item.optString("post_reason", "AI판단"),
                evidence = item.optString("post_evidence", "-"),
                sourceText = sourceText,
                reviewMode = config.reviewMode,
                rawText = item.toString()
            ) ?: continue

            val commentDecisions = mutableListOf<AiFilterCommentDecision>()
            val commentsArray = item.optJSONArray("comments") ?: JSONArray()
            val commentKeyCounts = (0 until commentsArray.length())
                .mapNotNull { commentsArray.optJSONObject(it)?.optString("comment_id", "")?.takeIf(String::isNotBlank) }
                .groupingBy { it }.eachCount()
            for (j in 0 until commentsArray.length()) {
                val commentItem = commentsArray.optJSONObject(j) ?: continue
                val wireCommentKey = commentItem.optString("comment_id", "")
                if (commentKeyCounts[wireCommentKey] != 1) continue
                val wireComment = registry.resolveComment(wireCommentKey)
                    ?.takeIf { it.postKey == requestPost.postKey } ?: continue
                val comment = wireComment.comment
                val commentDecision = parseDecision(
                    actionRaw = commentItem.optString("decision", "REVIEW"),
                    reason = commentItem.optString("reason", "AI판단"),
                    evidence = commentItem.optString("evidence", "-"),
                    sourceText = comment.body,
                    reviewMode = config.reviewMode,
                    rawText = commentItem.toString()
                ) ?: continue
                commentDecisions += AiFilterCommentDecision(commentId = comment.commentId, decision = commentDecision)
            }
            postDecisions += AiFilterPostDecision(postKey = requestPost.postKey, decision = postDecision, commentDecisions = commentDecisions)
        }
        if (postDecisions.size != request.posts.size ||
            postDecisions.sumOf { it.commentDecisions.size } != request.posts.sumOf { it.comments.size }
        ) return null
        logger("AI legacy JSON 파싱 완료: postDecisions=${postDecisions.size}")
        return AiFilterBatchEvaluation(postDecisions = postDecisions)
    }

    private fun parseJsonObjectFromContent(content: String): JSONObject? {
        val cleaned = content.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        return runCatching { JSONObject(cleaned) }.getOrNull()
    }

    private fun parseCompactBatchResponse(content: String, request: AiFilterBatchRequest, registry: AiWireRegistry): AiFilterBatchEvaluation? {
        val rawLines = content.lineSequence()
            .map { it.trim().removePrefix("`").removeSuffix("`").trim() }
            .filter { it.isNotBlank() }
            .toList()
        val rawKeys = rawLines.mapNotNull(::identifyCompactTypedKey)
        if (rawKeys.size != rawLines.size || !hasExactCoverage(rawKeys, registry)) return null
        val parsedLines = rawLines.asSequence()
            .mapNotNull(::parseCompactLine)
            .toList()
        val keyCounts = parsedLines.groupingBy {
            if (it.type == "P") it.postId else "${it.postId}:${it.commentId}"
        }.eachCount()
        val postDecisionMap = linkedMapOf<PostKey, AiFilterDecision>()
        val commentDecisionMap = linkedMapOf<PostKey, MutableList<AiFilterCommentDecision>>()
        var parsedLineCount = 0

        parsedLines.forEach { parsedLine ->
                val cleanLine = parsedLine.toString()
                val type = parsedLine.type
                val postId = parsedLine.postId
                val commentId = parsedLine.commentId
                val responseKey = if (type == "P") postId else "$postId:$commentId"
                if (keyCounts[responseKey] != 1) return@forEach
                val action = parsedLine.action
                val reason = parsedLine.reason
                val evidence = parsedLine.evidence

                when (type) {
                    "P" -> {
                        val wirePost = registry.resolvePost(postId)
                        if (wirePost == null) {
                            logger("AI compact 파싱 무시: 요청에 없는 post_no=$postId")
                            return@forEach
                        }
                        val post = wirePost.post
                        if (commentId != "-" && commentId.isNotBlank()) {
                            logger("AI compact 파싱 무시: 게시글 줄 COMMENT_ID 오류 / post_no=$postId / comment_id=$commentId")
                            return@forEach
                        }
                        val sourceText = listOf(post.title, post.body).joinToString("\n")
                        val decision = parseDecision(
                            actionRaw = action,
                            reason = reason,
                            evidence = evidence,
                            sourceText = sourceText,
                            reviewMode = config.reviewMode,
                            rawText = cleanLine
                        ) ?: return@forEach
                        postDecisionMap[post.postKey] = decision
                        parsedLineCount++
                    }
                    "C" -> {
                        val key = "$postId:$commentId"
                        val wireComment = registry.resolveComment(key)
                        if (wireComment == null) {
                            logger("AI compact 파싱 무시: 요청에 없는 comment_key=$key / raw=${cleanLine.take(120)}")
                            return@forEach
                        }
                        val comment = wireComment.comment
                        val decision = parseDecision(
                            actionRaw = action,
                            reason = reason,
                            evidence = evidence,
                            sourceText = comment.body,
                            reviewMode = config.reviewMode,
                            rawText = cleanLine
                        ) ?: return@forEach
                        commentDecisionMap.getOrPut(wireComment.postKey) { mutableListOf() } +=
                            AiFilterCommentDecision(comment.commentId, decision)
                        parsedLineCount++
                    }
                }
            }

        if (parsedLineCount != expectedTypedKeys(registry).size) return null

        val postDecisions = request.posts.mapNotNull { post ->
            val commentDecisions = commentDecisionMap[post.postKey].orEmpty()
            val postDecision = postDecisionMap[post.postKey] ?: if (commentDecisions.isNotEmpty()) {
                AiFilterDecision(
                    type = AiFilterDecisionType.ALLOW,
                    reason = "댓글만판단",
                    category = "other",
                    confidence = 0,
                    rawJson = "implicit-post-allow"
                )
            } else {
                return@mapNotNull null
            }
            AiFilterPostDecision(
                postKey = post.postKey,
                decision = postDecision,
                commentDecisions = commentDecisions
            )
        }
        logger("AI compact 파싱 완료: lines=$parsedLineCount / postDecisions=${postDecisions.size}")
        return AiFilterBatchEvaluation(postDecisions = postDecisions)
    }

    /** Extract the wire identity before validating the decision token. */
    private fun identifyCompactTypedKey(line: String): String? {
        val parts = line.split("|")
        if (parts.size < 2) return null
        return when (val type = parts[0].trim().uppercase()) {
            "P" -> "P|${parts[1]}"
            "C" -> {
                val key = if (parts[1].contains(':')) parts[1] else {
                    if (parts.size < 3) return null
                    "${parts[1]}:${parts[2]}"
                }
                "$type|$key"
            }
            else -> null
        }
    }

    private data class ParsedCompactLine(
        val type: String,
        val postId: String,
        val commentId: String,
        val action: String,
        val reason: String,
        val evidence: String,
    )

    private fun parseCompactLine(line: String): ParsedCompactLine? {
        val newParts = line.split("|", limit = 5)
        if (newParts.size == 5) {
            val type = newParts[0].trim().uppercase()
            when (type) {
                "P" -> {
                    val action = newParts[2].trim()
                    if (isCompactActionToken(action)) {
                        return ParsedCompactLine(
                            type = type,
                            postId = newParts[1],
                            commentId = "-",
                            action = action,
                            reason = newParts[3].trim(),
                            evidence = newParts[4].trim().removeSurrounding("\"")
                        )
                    }
                }
                "C" -> {
                    val commentKey = newParts[1]
                    val action = newParts[2].trim()
                    val keyParts = commentKey.split(":", limit = 2)
                    if (keyParts.size == 2 && isCompactActionToken(action)) {
                        return ParsedCompactLine(
                            type = type,
                            postId = keyParts[0],
                            commentId = keyParts[1],
                            action = action,
                            reason = newParts[3].trim(),
                            evidence = newParts[4].trim().removeSurrounding("\"")
                        )
                    }
                }
            }
        }

        val oldParts = line.split("|", limit = 6)
        if (oldParts.size >= 6) {
            val type = oldParts[0].trim().uppercase()
            val action = oldParts[3].trim()
            if ((type == "P" || type == "C") && isCompactActionToken(action)) {
                return ParsedCompactLine(
                    type = type,
                    postId = oldParts[1],
                    commentId = oldParts[2],
                    action = action,
                    reason = oldParts[4].trim(),
                    evidence = oldParts[5].trim().removeSurrounding("\"")
                )
            }
        }

        val shortParts = line.split("|", limit = 4)
        if (shortParts.size == 4) {
            val type = shortParts[0].trim().uppercase()
            val action = shortParts[2].trim()
            val fallbackEvidence = shortParts[3].trim().removeSurrounding("\"")
            when (type) {
                "P" -> if (isCompactActionToken(action)) {
                    return ParsedCompactLine(
                        type = type,
                        postId = shortParts[1],
                        commentId = "-",
                        action = action,
                        reason = "형식보정",
                        evidence = fallbackEvidence
                    )
                }
                "C" -> {
                    val keyParts = shortParts[1].split(":", limit = 2)
                    if (keyParts.size == 2 && isCompactActionToken(action)) {
                        return ParsedCompactLine(
                            type = type,
                            postId = keyParts[0],
                            commentId = keyParts[1],
                            action = action,
                            reason = "형식보정",
                            evidence = fallbackEvidence
                        )
                    }
                }
            }
        }
        return null
    }

    private fun isCompactActionToken(value: String): Boolean {
        return when (value.trim().uppercase()) {
            "0", "1", "2", "ALLOW", "PASS", "REVIEW", "BLOCK" -> true
            else -> false
        }
    }

    private fun parseDecision(
        actionRaw: String,
        reason: String,
        evidence: String = "",
        sourceText: String = "",
        reviewMode: Boolean,
        rawText: String,
    ): AiFilterDecision? {
        val rawAction = actionRaw.trim().uppercase()
        val normalizedEvidence = selectMatchingEvidence(sourceText, evidence)
        val evidenceMatches = normalizedEvidence != null
        val type = when (rawAction) {
            "0", "ALLOW", "PASS" -> AiFilterDecisionType.ALLOW
            "1", "REVIEW" -> AiFilterDecisionType.REVIEW
            "2", "BLOCK" -> {
                if (!evidenceMatches) {
                    logger("AI 차단 무효화: 근거 불일치 / evidence=${evidence.trim().take(20)} / raw=${rawText.take(120)}")
                    AiFilterDecisionType.ALLOW
                } else if (reviewMode) {
                    AiFilterDecisionType.REVIEW
                } else {
                    AiFilterDecisionType.BLOCK
                }
            }
            else -> return null
        }
        return AiFilterDecision(
            type = type,
            reason = reason.ifBlank { "AI판단" }.take(10),
            category = "other",
            confidence = 0,
            rawJson = rawText,
            evidence = normalizedEvidence ?: "",
        )
    }

    private fun selectMatchingEvidence(sourceText: String, evidence: String): String? {
        val raw = evidence.trim().removeSurrounding("\"")
        if (raw.isBlank() || raw == "-") return null
        val candidates = buildList {
            add(raw.take(40))
            raw.split("|")
                .map { it.trim().take(40) }
                .filter { it.isNotBlank() && it != "-" }
                .forEach { add(it) }
        }.distinct()
        return candidates.firstOrNull { containsEvidence(sourceText, it) }
    }

    private fun containsEvidence(sourceText: String, evidence: String): Boolean {
        val normalizedSource = normalizeEvidenceText(sourceText)
        val normalizedEvidence = normalizeEvidenceText(evidence)
        return normalizedEvidence.isNotBlank() && normalizedSource.contains(normalizedEvidence)
    }

    private fun normalizeEvidenceText(value: String): String {
        return value
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace(Regex("\\s+"), "")
            .lowercase()
    }

}
