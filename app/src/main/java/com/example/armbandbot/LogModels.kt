package com.heyheyon.armbandbot

import org.json.JSONObject

enum class BotLogCategory {
    GENERAL,
    CYCLE,
    BLOCK,
    DEBUG,
    SESSION,
    ERROR,
    AI,
    SYSTEM
}

data class BotLogEntry(
    val raw: String,
    val message: String,
    val category: BotLogCategory,
    val timestamp: String? = null
)

fun classifyBotLog(message: String): BotLogCategory {
    return when {
        message.startsWith("[AI 배치 차단!") || message.startsWith("[AI 댓글 차단!") || message.startsWith("[AI 필터 차단!") -> BotLogCategory.BLOCK
        message.contains("[AI ") || message.contains("[AI배치") || message.contains("[AI 배치") || message.contains("[AI 결과") || message.contains("AI HTTP") || message.contains("AI 파싱") || message.contains("AI raw") -> BotLogCategory.AI
        message.contains("[디버그]") -> BotLogCategory.DEBUG
        message.contains("[자동 로그인") || message.contains("[세션 ") || message.contains("[시작 ") || message.contains("[복구 ") || message.contains("로그인") -> BotLogCategory.SESSION
        message.contains("[차단", ignoreCase = false) || message.contains("차단 요청") || message.contains("삭제 요청") || message.contains("차단!") -> BotLogCategory.BLOCK
        message.contains("[치명적 오류]") || message.contains("오류") || message.contains("실패") || message.contains("Exception") -> BotLogCategory.ERROR
        message.contains("사이클") || message.contains("탐색") || message.contains("페이지") || message.contains("대기") || message.contains("검색") -> BotLogCategory.CYCLE
        message.contains("SYSTEM") || message.contains("시스템") -> BotLogCategory.SYSTEM
        else -> BotLogCategory.GENERAL
    }
}

fun parseBotLogEntry(raw: String): BotLogEntry {
    val timestamp = Regex("^\\[(\\d{2}:\\d{2}:\\d{2})]\\s*").find(raw)?.groupValues?.getOrNull(1)
    val message = raw.replaceFirst(Regex("^\\[\\d{2}:\\d{2}:\\d{2}]\\s*"), "")
    return BotLogEntry(
        raw = raw,
        message = message,
        category = classifyBotLog(message),
        timestamp = timestamp
    )
}

fun BotLogEntry.toJsonLine(): String = JSONObject().apply {
    put("raw", raw)
    put("message", message)
    put("category", category.name)
    put("timestamp", timestamp ?: "")
}.toString()

fun botLogEntryFromLine(line: String): BotLogEntry {
    return runCatching {
        val json = JSONObject(line)
        BotLogEntry(
            raw = json.optString("raw"),
            message = json.optString("message"),
            category = runCatching { BotLogCategory.valueOf(json.optString("category")) }.getOrDefault(classifyBotLog(json.optString("message"))),
            timestamp = json.optString("timestamp").ifBlank { null }
        )
    }.getOrElse {
        parseBotLogEntry(line)
    }
}
