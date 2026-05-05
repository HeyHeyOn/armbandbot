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
        출력은 아래 압축 줄 형식만 허용됩니다. JSON, 마크다운, 설명, 코드블록 금지.
        형식: TYPE|ID|DECISION|REASON
        TYPE: 게시글은 P, 댓글은 C
        ID: 게시글 번호 또는 댓글 번호
        DECISION: 0=허용, 1=보류, 2=차단
        REASON: 판단 이유. 반드시 10글자 이내의 짧은 한국어.
        게시글과 댓글은 각각 한 줄씩 출력하세요.
        예시:
        P|123123|0|문제없음
        C|126|2|광고성
        C|127|0|단순호응
        게시글 판단과 댓글 판단은 별개입니다.
        게시글이 정상이고 댓글만 문제인 경우 게시글은 0, 댓글만 2로 출력하세요.
        애매하거나 판단 근거가 부족하면 1을 선택하세요.
        reviewMode 가 활성화되어 있으면 2는 앱 쪽에서 1로 완화될 수 있습니다.
        사용자 지침(user_prompt)이 유일한 차단 정책입니다.
        user_prompt에 명시되지 않은 사유로 2를 출력하지 마세요.
        일반적인 성적 내용, 욕설, 취향, 정체성, 논쟁성은 user_prompt가 직접 금지하지 않으면 0으로 두세요.
        선의의 확장 해석, 자체 커뮤니티 규칙 추가, 넓은 도덕 판단을 금지합니다.
        삭제된 댓글 안내문(예: 해당 댓글은 삭제되었습니다)은 판단 대상이 아니며 항상 0입니다.
        입력에 없는 ID를 새로 만들거나 출력하지 마세요.
        각 게시글과 댓글은 서로 완전히 독립적으로 판단해야 합니다.
        같은 배치에 포함된 다른 게시글/댓글의 키워드, 맥락, 결론, 추정 의도를 현재 항목 판단에 절대 적용하지 마세요.
        현재 항목 텍스트에 직접 나타난 근거만 사용하세요.
        유사 표현, 연상, 비슷한 어감, 느슨한 의미 확장만으로 차단하지 마세요.
        한 항목이 차단 대상이어도 다른 항목은 처음부터 별도로 다시 판단해야 합니다.
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

        parseCompactBatchResponse(content, request)?.let { parsed ->
            return parsed.copy(rawResponseText = responseText, parsedContentText = content)
        }

        // Backward-compatible fallback for older JSON-style responses.
        val parsed = runCatching { JSONObject(content) }.getOrElse {
            logger("AI compact/JSON 파싱 실패: ${it.message}")
            return AiFilterBatchEvaluation(failureReason = "AI 응답 파싱 실패", rawResponseText = responseText, parsedContentText = content)
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
                reason = item.optString("post_reason", "AI판단"),
                reviewMode = config.reviewMode,
                rawText = item.toString()
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
                    reason = commentItem.optString("reason", "AI판단"),
                    reviewMode = config.reviewMode,
                    rawText = commentItem.toString()
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

    private fun parseCompactBatchResponse(content: String, request: AiFilterBatchRequest): AiFilterBatchEvaluation? {
        val requestPostMap = request.posts.associateBy { it.postNo }
        val requestCommentMap = request.posts
            .flatMap { post -> post.comments.map { comment -> comment.commentId to post.postNo } }
            .toMap()
        val postDecisionMap = linkedMapOf<String, AiFilterDecision>()
        val commentDecisionMap = linkedMapOf<String, MutableList<AiFilterCommentDecision>>()
        var parsedLineCount = 0

        content.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach { line ->
                val cleanLine = line.removePrefix("`").removeSuffix("`").trim()
                val parts = cleanLine.split("|", limit = 4)
                if (parts.size < 4) return@forEach
                val type = parts[0].trim().uppercase()
                val id = parts[1].trim()
                val action = parts[2].trim()
                val reason = parts[3].trim()
                val decision = parseDecision(
                    actionRaw = action,
                    reason = reason,
                    reviewMode = config.reviewMode,
                    rawText = cleanLine
                ) ?: return@forEach

                when (type) {
                    "P" -> {
                        if (requestPostMap.containsKey(id)) {
                            postDecisionMap[id] = decision
                            parsedLineCount++
                        } else {
                            logger("AI compact 파싱 무시: 요청에 없는 post_no=$id")
                        }
                    }
                    "C" -> {
                        val postNo = requestCommentMap[id]
                        if (postNo != null) {
                            commentDecisionMap.getOrPut(postNo) { mutableListOf() } += AiFilterCommentDecision(id, decision)
                            parsedLineCount++
                        } else {
                            logger("AI compact 파싱 무시: 요청에 없는 comment_id=$id")
                        }
                    }
                }
            }

        if (parsedLineCount == 0) return null

        val postDecisions = request.posts.mapNotNull { post ->
            val postDecision = postDecisionMap[post.postNo] ?: return@mapNotNull null
            AiFilterPostDecision(
                postNo = post.postNo,
                decision = postDecision,
                commentDecisions = commentDecisionMap[post.postNo].orEmpty()
            )
        }
        logger("AI compact 파싱 완료: lines=$parsedLineCount / postDecisions=${postDecisions.size}")
        return AiFilterBatchEvaluation(postDecisions = postDecisions)
    }

    private fun parseDecision(
        actionRaw: String,
        reason: String,
        reviewMode: Boolean,
        rawText: String,
    ): AiFilterDecision? {
        val type = when (actionRaw.trim().uppercase()) {
            "0", "ALLOW", "PASS" -> AiFilterDecisionType.ALLOW
            "1", "REVIEW" -> AiFilterDecisionType.REVIEW
            "2", "BLOCK" -> if (reviewMode) AiFilterDecisionType.REVIEW else AiFilterDecisionType.BLOCK
            else -> return null
        }
        return AiFilterDecision(
            type = type,
            reason = reason.ifBlank { "AI판단" }.take(10),
            category = "other",
            confidence = 0,
            rawJson = rawText,
        )
    }
}
