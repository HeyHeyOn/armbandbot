package com.heyheyon.armbandbot

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.LinkedHashMap

internal enum class AiFilterProvider {
    OPENAI_COMPATIBLE,
    GEMINI_DIRECT,
}

internal data class AiFilterConfig(
    val enabled: Boolean,
    val provider: AiFilterProvider = AiFilterProvider.OPENAI_COMPATIBLE,
    val endpoint: String,
    val apiKey: String,
    val model: String,
    val userPrompt: String,
    val reviewMode: Boolean = true,
    val timeoutMs: Int = 15000,
)

internal data class AiFilterRequest(
    val postTitle: String,
    val postAuthor: String,
    val postNick: String,
    val postBody: String,
    val postImageAlts: List<String>,
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

internal data class AiFilterEvaluation(
    val decision: AiFilterDecision? = null,
    val failureReason: String? = null,
) {
    val shouldReview: Boolean get() = decision?.type == AiFilterDecisionType.REVIEW
    val shouldBlock: Boolean get() = decision?.type == AiFilterDecisionType.BLOCK
}

internal class AiFilterClient(
    private val config: AiFilterConfig,
    private val logger: (String) -> Unit = {},
) {
    companion object {
        private const val CACHE_LIMIT = 200
        private val evaluationCache = object : LinkedHashMap<String, AiFilterEvaluation>(CACHE_LIMIT, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AiFilterEvaluation>?): Boolean = size > CACHE_LIMIT
        }
        private val cacheLock = Any()
    }

    private val fixedPrompt = """
        당신은 커뮤니티 게시글 전용 안전 필터입니다.
        댓글은 검사 대상이 아닙니다.
        기존 1차 룰 기반 필터를 통과한 게시글만 2차로 받습니다.
        review 우선 모드이므로 애매하거나 판단 근거가 부족하면 REVIEW 를 선택하세요.
        반드시 JSON 객체 하나만 출력하세요. 마크다운, 설명, 코드블록 금지.
        허용 가능한 action 값: ALLOW, REVIEW, BLOCK
        JSON 스키마:
        {
          \"action\": \"ALLOW|REVIEW|BLOCK\",
          \"reason\": \"한 줄 요약\",
          \"category\": \"spam|sexual|abuse|scam|other\",
          \"confidence\": 0~100,
          \"evidence\": [\"근거1\", \"근거2\"]
        }
        REVIEW 는 의심되지만 자동 확신 차단이 어려울 때 사용하세요.
        BLOCK 은 명백한 위반일 때만 사용하세요.
    """.trimIndent()

    fun evaluate(request: AiFilterRequest): AiFilterEvaluation {
        if (!config.enabled) return AiFilterEvaluation()
        if (config.apiKey.isBlank() || config.model.isBlank()) {
            return AiFilterEvaluation(failureReason = "AI ?? ?? ???")
        }
        if (config.provider == AiFilterProvider.OPENAI_COMPATIBLE && config.endpoint.isBlank()) {
            return AiFilterEvaluation(failureReason = "AI ?? Endpoint ???")
        }

        val cacheKey = buildCacheKey(request)
        synchronized(cacheLock) {
            evaluationCache[cacheKey]?.let {
                logger("AI ?? ?? ??")
                return it
            }
        }

        val evaluation = try {
            val responseText = callApi(request)
            parseResponse(responseText)
        } catch (e: Exception) {
            logger("AI ?? ?? ??: ${e.message}")
            AiFilterEvaluation(failureReason = e.message ?: "AI ?? ?? ??")
        }

        synchronized(cacheLock) {
            evaluationCache[cacheKey] = evaluation
        }
        return evaluation
    }

    private fun buildCacheKey(request: AiFilterRequest): String {
        return listOf(
            config.provider.name,
            config.endpoint,
            config.model,
            config.userPrompt,
            config.reviewMode.toString(),
            request.postTitle.trim(),
            request.postAuthor.trim(),
            request.postNick.trim(),
            request.postBody.trim(),
            request.postImageAlts.joinToString("|") { it.trim() }
        ).joinToString("\n")
    }

    private fun buildComposedUserPrompt(request: AiFilterRequest): String = buildString {
        appendLine(config.userPrompt.ifBlank { "추가 사용자 지침 없음" })
        appendLine()
        appendLine("[게시글 메타]")
        appendLine("작성자 ID/IP: ${request.postAuthor}")
        appendLine("닉네임: ${request.postNick}")
        appendLine("제목: ${request.postTitle}")
        appendLine("본문: ${request.postBody}")
        appendLine("이미지 alt: ${request.postImageAlts.joinToString(" | ").ifBlank { "없음" }}")
    }

    private fun buildOpenAiPayload(request: AiFilterRequest): JSONObject {
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

    private fun buildGeminiPayload(request: AiFilterRequest): JSONObject {
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
        if (config.provider == AiFilterProvider.GEMINI_DIRECT) {
            if (config.endpoint.isNotBlank()) return config.endpoint
            val encodedModel = URLEncoder.encode(config.model, Charsets.UTF_8.name())
            return "https://generativelanguage.googleapis.com/v1beta/models/${encodedModel}:generateContent?key=${config.apiKey}"
        }
        return config.endpoint
    }

    private fun callApi(request: AiFilterRequest): String {
        val requestUrl = buildRequestUrl()
        val payload = when (config.provider) {
            AiFilterProvider.OPENAI_COMPATIBLE -> buildOpenAiPayload(request)
            AiFilterProvider.GEMINI_DIRECT -> buildGeminiPayload(request)
        }

        val connection = (URL(requestUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = config.timeoutMs
            readTimeout = config.timeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            when (config.provider) {
                AiFilterProvider.OPENAI_COMPATIBLE -> setRequestProperty("Authorization", "Bearer ${config.apiKey}")
                AiFilterProvider.GEMINI_DIRECT -> if (!requestUrl.contains("key=")) {
                    setRequestProperty("x-goog-api-key", config.apiKey)
                }
            }
        }

        connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val text = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
        if (status !in 200..299) {
            Log.w("AiFilterClient", "HTTP $status: $text")
            error("HTTP $status")
        }
        return text
    }

    private fun parseResponse(responseText: String): AiFilterEvaluation {
        val root = JSONObject(responseText)
        val content = when (config.provider) {
            AiFilterProvider.OPENAI_COMPATIBLE -> root.optJSONArray("choices")
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
            return AiFilterEvaluation(failureReason = "AI 응답 비어 있음")
        }

        val parsed = runCatching { JSONObject(content) }.getOrElse {
            logger("AI 필터 JSON 파싱 실패: ${it.message}")
            return AiFilterEvaluation(failureReason = "AI JSON 파싱 실패")
        }

        val actionRaw = parsed.optString("action", "REVIEW").uppercase()
        val category = parsed.optString("category", "other").ifBlank { "other" }
        val confidence = parsed.optInt("confidence", 0).coerceIn(0, 100)
        val reason = parsed.optString("reason", "AI 판단 사유 없음").ifBlank { "AI 판단 사유 없음" }
        val evidence = parsed.optJSONArray("evidence")?.let { array ->
            buildList {
                for (i in 0 until array.length()) {
                    val value = array.optString(i).trim()
                    if (value.isNotEmpty()) add(value)
                }
            }
        }.orEmpty()

        val type = when (actionRaw) {
            "ALLOW" -> AiFilterDecisionType.ALLOW
            "BLOCK" -> if (config.reviewMode) AiFilterDecisionType.REVIEW else AiFilterDecisionType.BLOCK
            "REVIEW" -> AiFilterDecisionType.REVIEW
            else -> return AiFilterEvaluation(failureReason = "알 수 없는 action: $actionRaw")
        }

        if (type == AiFilterDecisionType.ALLOW && confidence >= 80 && evidence.any { it.contains("차단") || it.contains("위반") }) {
            return AiFilterEvaluation(failureReason = "AI 응답 모순 감지")
        }

        val decoratedReason = buildString {
            append(reason)
            if (evidence.isNotEmpty()) {
                append(" / 근거: ")
                append(evidence.joinToString("; "))
            }
        }

        return AiFilterEvaluation(
            decision = AiFilterDecision(
                type = type,
                reason = decoratedReason,
                category = category,
                confidence = confidence,
                rawJson = parsed.toString(),
            )
        )
    }
}
