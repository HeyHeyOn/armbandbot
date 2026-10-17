package com.heyheyon.armbandbot

import android.content.SharedPreferences
import android.os.SystemClock
import kotlinx.coroutines.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.json.JSONObject

internal class RecommendationSettingsChanged : Exception()

internal class RecommendationMonitor(
    private val p: SharedPreferences,
    private val allowed: () -> Boolean,
    private val scan: suspend (RecommendationMonitor) -> Unit,
    private val release: (RecommendationMatch, () -> Unit) -> String,
    private val log: (String) -> Unit,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    private val tracker = RecommendationTracker()
    private var revision = ""
    private var policy = RecommendationPolicy()
    private var lastFailureAt = Long.MIN_VALUE

    fun check() {
        RuntimeRequestGate.requireCurrent().check()
        if (!allowed() || !p.getBoolean(RECOMMEND_GUARD_ENABLED, false) || revision != recommendationRevision(p)) throw RecommendationSettingsChanged()
    }

    fun consume(rows: List<RecommendationRow>, at: Long, protected: (RecommendationRow) -> Boolean) {
        check()
        for (row in rows) {
            check()
            if (protected(row)) { tracker.forget(row.key); continue }
            val match = tracker.observe(row, at, policy) ?: continue
            if (clock() > match.expiresAt) { tracker.forget(row.key); continue }
            val result = release(match) { check(); if (clock() > match.expiresAt) throw RecommendationSettingsChanged() }
            if (result == "ordinary") continue
            tracker.forget(row.key)
            if (result == "skipped") continue
            val seconds = (match.detectedAt - match.startedAt) / 1000
            log("[추천수 조작 방지] ${row.key.gallId}/${row.key.postNo} · ${seconds}초 동안 ${match.from}→${match.to} (+${match.to - match.from}) · $result")
        }
    }

    suspend fun run() {
        while (currentCoroutineContext().isActive) {
            val started = clock()
            var waitMs = 1000L
            try {
                if (!p.getBoolean(RECOMMEND_GUARD_ENABLED, false) || !allowed()) {
                    tracker.clear(); revision = ""
                } else {
                    val snapshot = snapshotScanPreferences(p)
                    val next = recommendationRevision(snapshot)
                    if (next != revision) { tracker.clear(); revision = next }
                    policy = recommendationPolicy(snapshot)
                    waitMs = policy.pollMs
                    scan(this)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: RecommendationSettingsChanged) { tracker.clear(); revision = "" }
            catch (_: SchedulePausedException) { tracker.clear(); revision = "" }
            catch (error: Exception) {
                tracker.clear()
                if (lastFailureAt == Long.MIN_VALUE || clock() - lastFailureAt >= 60_000) {
                    lastFailureAt = clock()
                    log("[추천수 조작 방지] 관측 대기 · ${error.message?.take(140) ?: "권한·연결·설정을 확인해 주세요."}")
                }
                waitMs = 30_000
            }
            // Interruptible preference polling: stop/disable never waits for the next 30-second tick.
            do {
                delay(1000)
                if (!allowed() || !p.getBoolean(RECOMMEND_GUARD_ENABLED, false) || revision != recommendationRevision(p)) break
            } while (clock() - started < waitMs)
        }
    }
}

/** Injectable transport allows the entire verify -> REL -> verify path to run without live votes. */
internal class RecommendationReleaseClient(
    private val cookie: String,
    private val beforeRequest: () -> Unit,
    private val claim: (PostKey, String, () -> String) -> String,
    private val get: (String) -> Document = { url ->
        executeGatedJsoup(Jsoup.connect(url).userAgent("Mozilla/5.0").header("Cookie", cookie)
            .followRedirects(false).timeout(15_000).maxBodySize(1_048_576), beforeRequest).parse()
    },
    private val post: (PostKey, Map<String, String>) -> String = { key, payload ->
        executeGatedJsoup(Jsoup.connect(nativeActionUrl(key, "set_recommend")).userAgent("Mozilla/5.0")
            .header("Cookie", cookie).header("Referer", DcinsidePostUrls.canonicalDetailUrl(key))
            .header("X-Requested-With", "XMLHttpRequest").followRedirects(false).timeout(MODERATION_REQUEST_TIMEOUT_MS)
            .ignoreContentType(true).data(payload).method(org.jsoup.Connection.Method.POST), beforeRequest).body()
    },
) {
    fun release(key: PostKey, verifyPolicy: () -> Unit, protected: (Document) -> Boolean): String {
        verifyPolicy()
        val detail = get(DcinsidePostUrls.canonicalDetailUrl(key))
        verifyPolicy()
        if (protected(detail)) return "skipped"
        when (recommendationState(detail, key)) {
            RecommendationState.ORDINARY -> return "ordinary"
            RecommendationState.UNKNOWN -> error("개념글 상태 또는 관리 권한을 확인할 수 없습니다.")
            RecommendationState.RECOMMENDED -> Unit
        }
        val response = claim(key, "RECOMMEND_RELEASE") {
            verifyPolicy()
            val payload = recommendationReleasePayload(key, dcCookieToken(cookie))
            val raw = post(key, payload)
            val explicit = runCatching { JSONObject(raw).optString("result") }.getOrNull()
            // Uncertain response must not trigger another mutation; the persistent claim retains it.
            if (explicit != "success") return@claim raw
            verifyPolicy()
            val verified = get(DcinsidePostUrls.canonicalDetailUrl(key))
            if (recommendationState(verified, key) == RecommendationState.ORDINARY) raw else "{\"result\":\"unknown\"}"
        }
        return when (runCatching { JSONObject(response).optString("result") }.getOrNull()) {
            "success" -> "개념글 해제 성공"
            "skipped" -> "skipped"
            "fail", "failed", "failure", "error" -> "개념글 해제 실패"
            else -> "개념글 해제 결과 미확인 · 중복 요청 보류"
        }
    }
}
