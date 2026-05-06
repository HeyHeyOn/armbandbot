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
    val evidence: String = "",
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
        모든 출력 줄은 반드시 | 로 구분된 정확히 5칸이어야 합니다.
        게시글 형식: P|POST_ID|DECISION|REASON|EVIDENCE
        댓글 형식: C|COMMENT_KEY|DECISION|REASON|EVIDENCE
        TYPE: 게시글은 P, 댓글은 C
        POST_ID: 게시글 번호
        COMMENT_KEY: 입력 comments의 comment_key 값을 그대로 복사한 값. 형식은 POST_ID:COMMENT_ID.
        DECISION: 0=허용, 1=보류, 2=차단
        REASON: 판단 이유. 반드시 10글자 이내의 짧은 한국어. 허용도 반드시 REASON 칸을 채우세요. 예: 문제없음, 단순호응.
        EVIDENCE: 2=차단일 때만 현재 항목 원문에 실제로 포함된 짧은 근거 조각. 허용/보류는 -.
        근거 조각에는 | 문자를 쓰지 마세요.
        게시글 번호는 반드시 POST_ID 칸에만 출력하세요.
        댓글은 POST_ID와 COMMENT_ID를 따로 조합하지 말고 입력의 comment_key를 그대로 복사하세요.
        댓글은 COMMENT_KEY가 입력과 정확히 일치해야 합니다.
        게시글과 댓글은 각각 한 줄씩 출력하세요.
        아래 같은 4칸 출력은 절대 금지입니다: P|123123|0|- 또는 C|123123:126|0|-
        허용/보류여도 REASON 칸과 EVIDENCE 칸을 모두 출력해야 합니다.
        정답 예시는 반드시 5칸입니다.
        예시:
        P|123123|0|문제없음|-
        C|123123:126|2|욕설포함|씨발
        C|123123:127|0|단순호응|-
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
        최종 제출 전에 각 줄의 | 개수를 확인하세요. 각 줄마다 | 문자는 정확히 4개여야 합니다.
        각 게시글과 댓글은 서로 완전히 독립적으로 판단해야 합니다.
        배치는 처리 효율을 위한 포장일 뿐이며 여러 글을 한 사건, 한 대화, 한 흐름으로 묶어 해석하지 마세요.
        같은 배치에 포함된 다른 게시글/댓글의 키워드, 맥락, 결론, 추정 의도를 현재 항목 판단에 절대 적용하지 마세요.
        이전 줄에서 차단 대상이 나왔더라도 다음 줄의 차단 근거로 전염시키지 마세요.
        특정 글/댓글에서 발견한 금지 주제나 분위기를 다른 글/댓글에 일반화하지 마세요.
        현재 항목 텍스트 안에 user_prompt 위반 근거가 직접 없으면 0을 출력하세요.
        2를 출력하려면 EVIDENCE가 현재 항목 텍스트 안에 실제로 포함되어 있어야 합니다.
        댓글 판단의 EVIDENCE는 해당 댓글 본문에서만 가져오고, 게시글 제목/본문이나 다른 댓글에서 가져오면 안 됩니다.
        게시글 판단의 EVIDENCE는 해당 게시글 제목/본문에서만 가져오고, 댓글에서 가져오면 안 됩니다.
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
                                    put("comment_key", "${post.postNo}:${comment.commentId}")
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
            .flatMap { post -> post.comments.map { comment -> "${post.postNo}:${comment.commentId}" to comment } }
            .toMap()
        val postDecisionMap = linkedMapOf<String, AiFilterDecision>()
        val commentDecisionMap = linkedMapOf<String, MutableList<AiFilterCommentDecision>>()
        var parsedLineCount = 0

        content.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach { line ->
                val cleanLine = line.removePrefix("`").removeSuffix("`").trim()
                val parsedLine = parseCompactLine(cleanLine)
                if (parsedLine == null) {
                    logger("AI compact 파싱 무시: beta17 5필드/comment_key 형식 아님 / line=${cleanLine.take(120)}")
                    return@forEach
                }
                val type = parsedLine.type
                val postId = parsedLine.postId
                val commentId = parsedLine.commentId
                val action = parsedLine.action
                val reason = parsedLine.reason
                val evidence = parsedLine.evidence

                when (type) {
                    "P" -> {
                        val post = requestPostMap[postId]
                        if (post == null) {
                            logger("AI compact 파싱 무시: 요청에 없는 post_no=$postId")
                            return@forEach
                        }
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
                        postDecisionMap[postId] = decision
                        parsedLineCount++
                    }
                    "C" -> {
                        val key = "$postId:$commentId"
                        val comment = requestCommentMap[key]
                        if (comment == null) {
                            logger("AI compact 파싱 무시: 요청에 없는 comment_key=$key / raw=${cleanLine.take(120)}")
                            return@forEach
                        }
                        val decision = parseDecision(
                            actionRaw = action,
                            reason = reason,
                            evidence = evidence,
                            sourceText = comment.body,
                            reviewMode = config.reviewMode,
                            rawText = cleanLine
                        ) ?: return@forEach
                        commentDecisionMap.getOrPut(postId) { mutableListOf() } += AiFilterCommentDecision(commentId, decision)
                        parsedLineCount++
                    }
                }
            }

        if (parsedLineCount == 0) return null

        val postDecisions = request.posts.mapNotNull { post ->
            val commentDecisions = commentDecisionMap[post.postNo].orEmpty()
            val postDecision = postDecisionMap[post.postNo] ?: if (commentDecisions.isNotEmpty()) {
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
                postNo = post.postNo,
                decision = postDecision,
                commentDecisions = commentDecisions
            )
        }
        logger("AI compact 파싱 완료: lines=$parsedLineCount / postDecisions=${postDecisions.size}")
        return AiFilterBatchEvaluation(postDecisions = postDecisions)
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
                            postId = newParts[1].trim(),
                            commentId = "-",
                            action = action,
                            reason = newParts[3].trim(),
                            evidence = newParts[4].trim().removeSurrounding("\"")
                        )
                    }
                }
                "C" -> {
                    val commentKey = newParts[1].trim()
                    val action = newParts[2].trim()
                    val keyParts = commentKey.split(":", limit = 2)
                    if (keyParts.size == 2 && isCompactActionToken(action)) {
                        return ParsedCompactLine(
                            type = type,
                            postId = keyParts[0].trim(),
                            commentId = keyParts[1].trim(),
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
                    postId = oldParts[1].trim(),
                    commentId = oldParts[2].trim(),
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
                        postId = shortParts[1].trim(),
                        commentId = "-",
                        action = action,
                        reason = "형식보정",
                        evidence = fallbackEvidence
                    )
                }
                "C" -> {
                    val keyParts = shortParts[1].trim().split(":", limit = 2)
                    if (keyParts.size == 2 && isCompactActionToken(action)) {
                        return ParsedCompactLine(
                            type = type,
                            postId = keyParts[0].trim(),
                            commentId = keyParts[1].trim(),
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
