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
import java.util.LinkedHashMap

internal enum class AiFilterProvider {
    OPENAI_COMPATIBLE,
    GEMINI_DIRECT,
    GROQ,
    LM_STUDIO,
}

internal data class AiFilterConfig(
    val enabled: Boolean,
    val provider: AiFilterProvider = AiFilterProvider.OPENAI_COMPATIBLE,
    val endpoint: String,
    val apiKey: String,
    val model: String,
    val userPrompt: String,
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
    val postNo: String,
    val title: String,
    val authorIdOrIp: String,
    val nickname: String,
    val body: String,
    val mediaSources: List<String>,
    val comments: List<AiFilterCommentInput>,
)

internal data class AiFilterBatchRequest(
    val posts: List<AiFilterPostInput>,
)

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
)

internal data class AiFilterCommentDecision(
    val commentId: String,
    val decision: AiFilterDecision,
)

internal data class AiFilterPostDecision(
    val postNo: String,
    val decision: AiFilterDecision,
    val commentDecisions: List<AiFilterCommentDecision> = emptyList(),
)

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
) {
    companion object {
        private const val CACHE_LIMIT = 100
        private val evaluationCache = object : LinkedHashMap<String, AiFilterBatchEvaluation>(CACHE_LIMIT, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AiFilterBatchEvaluation>?): Boolean = size > CACHE_LIMIT
        }
        private val cacheLock = Any()
    }

    private val fixedPrompt = """
        당신은 커뮤니티 게시글/댓글 전용 2차 AI 필터입니다.
        기존 1차 룰 기반 필터를 통과한 게시글 묶음을 입력으로 받습니다.
        입력은 항상 원문 전체이며, 게시글과 댓글을 반드시 분리해서 판단해야 합니다.
        출력은 JSON 객체 하나만 허용됩니다. 마크다운, 설명, 코드블록 금지.
        허용 가능한 decision 값: ALLOW, REVIEW, BLOCK
        게시글 decision 과 댓글 decision 은 별개입니다.
        게시글이 정상이고 댓글만 문제인 경우, 게시글은 ALLOW/PASS 성격으로 두고 댓글만 BLOCK 하세요.
        애매하거나 판단 근거가 부족하면 REVIEW 를 선택하세요.
        reviewMode 가 활성화되어 있으면 BLOCK 은 앱 쪽에서 REVIEW 로 완화될 수 있습니다.
        각 게시글과 댓글은 서로 완전히 독립적으로 판단해야 합니다.
        같은 배치에 포함된 다른 게시글/댓글의 키워드, 맥락, 결론, 추정 의도를 현재 항목 판단에 절대 적용하지 마세요.
        현재 항목 텍스트에 직접 나타난 근거만 사용하세요.
        유사 표현, 연상, 비슷한 어감, 느슨한 의미 확장만으로 차단하지 마세요.
        한 항목이 차단 대상이어도 다른 항목은 처음부터 별도로 다시 판단해야 합니다.
        JSON 스키마:
        {
          "results": [
            {
              "post_no": "문자열",
              "post_decision": "ALLOW|REVIEW|BLOCK",
              "post_reason": "한 줄 요약",
              "post_category": "spam|sexual|abuse|scam|other",
              "post_confidence": 0,
              "comments": [
                {
                  "comment_id": "문자열",
                  "decision": "ALLOW|REVIEW|BLOCK",
                  "reason": "한 줄 요약",
                  "category": "spam|sexual|abuse|scam|other",
                  "confidence": 0
                }
              ]
            }
          ]
        }
    """.trimIndent()

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
            evaluationCache[cacheKey]?.let {
                logger("AI 배치 캐시 사용 (post=${it.postDecisions.size}, failure=${it.failureReason != null})")
                return it
            }
        }

        val requestUri = runCatching { URI(buildRequestUrl()) }.getOrNull()
        val keyPreview = when {
            config.apiKey.isBlank() -> "blank"
            config.apiKey.length <= 8 -> "len=${config.apiKey.length}"
            else -> "${config.apiKey.take(4)}...len=${config.apiKey.length}"
        }
        val debugSummary = buildString {
            append("provider=${config.provider.name}")
            append(" / model=${config.model}")
            append(" / endpointHost=${requestUri?.host ?: "none"}")
            append(" / urlHasKey=${buildRequestUrl().contains("key=")}")
            append(" / customEndpoint=${config.endpoint.isNotBlank()}")
            append(" / key=$keyPreview")
        }

        val evaluation = try {
            if (config.debugLoggingEnabled) {
                logger("MARKER_AI_ENTER_V2 posts=${request.posts.size}")
            }
            val responseText = callApi(request)
            parseBatchResponse(responseText, request).copy(debugSummary = debugSummary)
        } catch (e: Exception) {
            val failureMessage = e.message ?: "AI 배치 호출 실패"
            logger("AI 배치 호출 실패 [MARKER_AI_CATCH_V2]: $failureMessage")
            AiFilterBatchEvaluation(failureReason = failureMessage, debugSummary = debugSummary)
        }

        if (evaluation.failureReason == null && evaluation.postDecisions.isNotEmpty()) {
            synchronized(cacheLock) {
                evaluationCache[cacheKey] = evaluation
            }
        } else {
            logger("AI 배치 캐시 저장 생략 (failure=${evaluation.failureReason != null}, postDecisions=${evaluation.postDecisions.size})")
        }
        return evaluation
    }

    private fun buildCacheKey(request: AiFilterBatchRequest): String {
        return buildString {
            appendLine(config.provider.name)
            appendLine(config.endpoint)
            appendLine(config.model)
            appendLine(config.userPrompt)
            appendLine(config.reviewMode.toString())
            request.posts.forEach { post ->
                appendLine(post.postNo)
                appendLine(post.title)
                appendLine(post.authorIdOrIp)
                appendLine(post.nickname)
                appendLine(post.body)
                appendLine(post.mediaSources.joinToString("|"))
                post.comments.forEach { comment ->
                    appendLine(comment.commentId)
                    appendLine(comment.authorIdOrIp)
                    appendLine(comment.nickname)
                    appendLine(comment.body)
                }
            }
        }
    }

    private fun buildComposedUserPrompt(request: AiFilterBatchRequest): String {
        val payload = JSONObject().apply {
            put("user_prompt", config.userPrompt.ifBlank { "추가 사용자 지침 없음" })
            put("posts", JSONArray().apply {
                request.posts.forEach { post ->
                    put(JSONObject().apply {
                        put("post_no", post.postNo)
                        put("title", post.title)
                        put("author_id_or_ip", post.authorIdOrIp)
                        put("nickname", post.nickname)
                        put("body", post.body)
                        put("media_sources", JSONArray(post.mediaSources))
                        put("comments", JSONArray().apply {
                            post.comments.forEach { comment ->
                                put(JSONObject().apply {
                                    put("comment_id", comment.commentId)
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

    private fun buildOpenAiPayload(request: AiFilterBatchRequest): JSONObject {
        val composedUserPrompt = buildComposedUserPrompt(request)
        return JSONObject().apply {
            put("model", config.model)
            put("response_format", JSONObject().put("type", "json_object"))
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", fixedPrompt))
                put(JSONObject().put("role", "user").put("content", composedUserPrompt))
            })
            put("temperature", 0.1)
        }
    }

    private fun buildGeminiPayload(request: AiFilterBatchRequest): JSONObject {
        val composedUserPrompt = buildComposedUserPrompt(request)
        return JSONObject().apply {
            put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", fixedPrompt))))
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
                    "https://generativelanguage.googleapis.com/v1beta/models/${encodedModel}:generateContent?key=${config.apiKey}"
                }
            }
            AiFilterProvider.GROQ -> {
                if (config.endpoint.isNotBlank()) config.endpoint else "https://api.groq.com/openai/v1/chat/completions"
            }
            AiFilterProvider.OPENAI_COMPATIBLE -> config.endpoint
            AiFilterProvider.LM_STUDIO -> if (config.endpoint.isNotBlank()) config.endpoint else "http://10.0.2.2:1234/v1/chat/completions"
        }
    }

    private fun callApi(request: AiFilterBatchRequest): String {
        val requestUrl = buildRequestUrl()
        val payload = when (config.provider) {
            AiFilterProvider.OPENAI_COMPATIBLE, AiFilterProvider.GROQ, AiFilterProvider.LM_STUDIO -> buildOpenAiPayload(request)
            AiFilterProvider.GEMINI_DIRECT -> buildGeminiPayload(request)
        }
        val requestUri = runCatching { URI(requestUrl) }.getOrNull()
        val keyPreview = when {
            config.apiKey.isBlank() -> "blank"
            config.apiKey.length <= 8 -> "len=${config.apiKey.length}"
            else -> "${config.apiKey.take(4)}...len=${config.apiKey.length}"
        }
        val sanitizedUrl = buildString {
            append(requestUri?.scheme ?: "")
            if (!requestUri?.host.isNullOrBlank()) {
                append("://")
                append(requestUri?.host)
            }
            append(requestUri?.rawPath ?: requestUrl)
            val rawQuery = requestUri?.rawQuery.orEmpty()
            if (rawQuery.isNotBlank()) {
                val hasKey = rawQuery.contains("key=", ignoreCase = true)
                append("?")
                append(if (hasKey) "key=[REDACTED_SECRET]" else rawQuery)
            }
        }
        val payloadPreview = payload.toString().replace("\n", " ").replace("\r", " ").take(400)
        val authModePreview = when (config.provider) {
            AiFilterProvider.OPENAI_COMPATIBLE, AiFilterProvider.GROQ -> "bearer"
            AiFilterProvider.LM_STUDIO -> if (config.apiKey.isBlank()) "none" else "bearer"
            AiFilterProvider.GEMINI_DIRECT -> if (requestUrl.contains("key=")) "url-key" else "x-goog-api-key"
        }
        if (config.debugLoggingEnabled) {
            logger(
                "AI REQUEST PREP / provider=${config.provider.name} / model=${config.model} / url=${sanitizedUrl.ifBlank { requestUrl.take(120) }} / apiKey=$keyPreview / authMode=$authModePreview / urlHasKey=${requestUrl.contains("key=")} / customEndpoint=${config.endpoint.isNotBlank()} / payload=$payloadPreview"
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
                when (config.provider) {
                    AiFilterProvider.OPENAI_COMPATIBLE, AiFilterProvider.GROQ -> {
                        setRequestProperty("Authorization", "Bearer ${config.apiKey}")
                    }
                    AiFilterProvider.LM_STUDIO -> {
                        if (config.apiKey.isNotBlank()) {
                            setRequestProperty("Authorization", "Bearer ${config.apiKey}")
                        }
                    }
                    AiFilterProvider.GEMINI_DIRECT -> {
                        val useHeaderKey = !requestUrl.contains("key=")
                        if (useHeaderKey) {
                            setRequestProperty("x-goog-api-key", config.apiKey)
                        }
                    }
                }
            }

            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
            if (status in 200..299) {
                return text
            }

            Log.w("AiFilterClient", "HTTP $status: $text")
            val trimmedError = text.replace("\n", " ").replace("\r", " ").take(300)
            val authModePreview = when (config.provider) {
                AiFilterProvider.OPENAI_COMPATIBLE, AiFilterProvider.GROQ -> "bearer"
                AiFilterProvider.LM_STUDIO -> if (config.apiKey.isBlank()) "none" else "bearer"
                AiFilterProvider.GEMINI_DIRECT -> if (requestUrl.contains("key=")) "url-key" else "x-goog-api-key"
            }
            logger("AI HTTP 오류 / provider=${config.provider.name} / model=${config.model} / status=$status / attempt=${attempt + 1} / authMode=$authModePreview / urlHasKey=${requestUrl.contains("key=")} / key=$keyPreview / customEndpoint=${config.endpoint.isNotBlank()} / body=$trimmedError")
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

    private fun parseBatchResponse(responseText: String, request: AiFilterBatchRequest): AiFilterBatchEvaluation {
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

        val parsed = runCatching { JSONObject(content) }.getOrElse {
            logger("AI 배치 JSON 파싱 실패: ${it.message}")
            return AiFilterBatchEvaluation(failureReason = "AI JSON 파싱 실패", rawResponseText = responseText, parsedContentText = content)
        }

        val resultsArray = parsed.optJSONArray("results") ?: run {
            logger("AI 파싱 실패: results 배열 누락")
            return AiFilterBatchEvaluation(failureReason = "AI results 누락", rawResponseText = responseText, parsedContentText = content)
        }
        val requestPostMap = request.posts.associateBy { it.postNo }
        val postDecisions = mutableListOf<AiFilterPostDecision>()

        for (i in 0 until resultsArray.length()) {
            val item = resultsArray.optJSONObject(i) ?: continue
            val postNo = item.optString("post_no", "").trim()
            if (postNo.isBlank()) {
                logger("AI 파싱 무시: post_no 비어 있음 / index=$i")
                continue
            }
            val requestPost = requestPostMap[postNo]
            if (requestPost == null) {
                logger("AI 파싱 무시: 요청에 없는 post_no=$postNo")
                continue
            }

            val postDecision = parseDecision(
                actionRaw = item.optString("post_decision", "REVIEW"),
                reason = item.optString("post_reason", "AI 판단 사유 없음"),
                category = item.optString("post_category", "other"),
                confidence = item.optInt("post_confidence", 0),
                reviewMode = config.reviewMode,
                rawJson = item.toString()
            )
            if (postDecision == null) {
                logger("AI 파싱 무시: 게시글 decision 해석 실패 / post_no=$postNo / raw=${item.toString().take(300)}")
                continue
            }

            val requestCommentMap = requestPost.comments.associateBy { it.commentId }
            val commentDecisions = mutableListOf<AiFilterCommentDecision>()
            val commentsArray = item.optJSONArray("comments") ?: JSONArray()
            for (j in 0 until commentsArray.length()) {
                val commentItem = commentsArray.optJSONObject(j) ?: continue
                val commentId = commentItem.optString("comment_id", "").trim()
                if (!requestCommentMap.containsKey(commentId)) {
                    logger("AI 파싱 무시: 요청에 없는 comment_id=$commentId / post_no=$postNo")
                    continue
                }
                val commentDecision = parseDecision(
                    actionRaw = commentItem.optString("decision", "REVIEW"),
                    reason = commentItem.optString("reason", "AI 판단 사유 없음"),
                    category = commentItem.optString("category", "other"),
                    confidence = commentItem.optInt("confidence", 0),
                    reviewMode = config.reviewMode,
                    rawJson = commentItem.toString()
                )
                if (commentDecision == null) {
                    logger("AI 파싱 무시: 댓글 decision 해석 실패 / post_no=$postNo / comment_id=$commentId")
                    continue
                }
                commentDecisions += AiFilterCommentDecision(commentId = commentId, decision = commentDecision)
            }

            postDecisions += AiFilterPostDecision(
                postNo = postNo,
                decision = postDecision,
                commentDecisions = commentDecisions
            )
        }

        logger("AI 파싱 완료: postDecisions=${postDecisions.size}")
        return AiFilterBatchEvaluation(postDecisions = postDecisions, rawResponseText = responseText, parsedContentText = content)
    }

    private fun parseDecision(
        actionRaw: String,
        reason: String,
        category: String,
        confidence: Int,
        reviewMode: Boolean,
        rawJson: String,
    ): AiFilterDecision? {
        val type = when (actionRaw.uppercase()) {
            "ALLOW", "PASS" -> AiFilterDecisionType.ALLOW
            "REVIEW" -> AiFilterDecisionType.REVIEW
            "BLOCK" -> if (reviewMode) AiFilterDecisionType.REVIEW else AiFilterDecisionType.BLOCK
            else -> return null
        }
        return AiFilterDecision(
            type = type,
            reason = reason.ifBlank { "AI 판단 사유 없음" },
            category = category.ifBlank { "other" },
            confidence = confidence.coerceIn(0, 100),
            rawJson = rawJson,
        )
    }
}
