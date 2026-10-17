package com.heyheyon.armbandbot

import android.content.SharedPreferences
import org.jsoup.nodes.Document
import org.jsoup.Jsoup
import java.util.ArrayDeque

internal const val RECOMMEND_GUARD_ENABLED = "recommend_guard_enabled"
internal const val RECOMMEND_GUARD_SECONDS = "recommend_guard_window_seconds"
internal const val RECOMMEND_GUARD_COUNT = "recommend_guard_increase_count"
internal const val RECOMMEND_GUARD_ROUTE = "RECOMMEND_GUARD"

internal data class RecommendationPolicy(val seconds: Int = 180, val increase: Int = 10) {
    init {
        require(seconds in 10..3600) { "기준 시간은 10초~60분으로 입력해 주세요." }
        require(increase in 1..1_000_000) { "추천 증가량은 1~1,000,000개로 입력해 주세요." }
    }
    val windowMs get() = seconds * 1000L
    val pollMs get() = (windowMs / 6).coerceIn(5_000L, 30_000L)
}

internal fun recommendationPolicy(p: SharedPreferences) = RecommendationPolicy(
    p.getInt(RECOMMEND_GUARD_SECONDS, 180), p.getInt(RECOMMEND_GUARD_COUNT, 10),
)

internal fun recommendationRevision(p: SharedPreferences): String {
    val keys = listOf(RECOMMEND_GUARD_ENABLED, RECOMMEND_GUARD_SECONDS, RECOMMEND_GUARD_COUNT,
        "target_urls", "scan_page_count", "is_search_mode", "search_type", "search_keywords", "search_keywords_text",
        "is_user_filter_mode", "user_whitelist", "user_whitelist_text", "is_nickname_filter_mode",
        "nickname_whitelist", "nickname_whitelist_text", "block_exempt_post_numbers", "block_exempt_post_numbers_text",
        "saved_cookie", "independent_scan_state_enabled")
    val all = p.all
    return policyHash(keys.joinToString("\n") { key ->
        val value = all[key]
        "$key=${if (value is Set<*>) value.map { it.toString() }.sorted() else value}"
    } + automationPolicyRevision(p))
}

internal data class RecommendationRow(val key: PostKey, val count: Long, val uid: String, val ip: String, val nickname: String)
internal data class RecommendationMatch(val key: PostKey, val from: Long, val to: Long, val startedAt: Long, val detectedAt: Long, val expiresAt: Long)

internal fun parseRecommendationRows(document: Document, gallType: String, gallId: String): List<RecommendationRow> {
    if (gallType !in setOf("M", "MI")) return emptyList()
    return document.select("tr.ub-content").mapNotNull { row ->
        val number = row.attr("data-no")
        if (!number.matches(Regex("[1-9][0-9]*")) || row.attr("data-type") in setOf("icon_notice", "icon_slow")) return@mapNotNull null
        if (row.select(".gall_num").text().contains(Regex("공지|고정"))) return@mapNotNull null
        val title = row.selectFirst(".gall_tit a[href*='no=']") ?: return@mapNotNull null
        val target = DcinsidePostUrls.parseSafeCanonicalPostUrl(title.absUrl("href").ifBlank { title.attr("href") }, null)
            ?: return@mapNotNull null
        val key = PostKey(gallType, gallId, number)
        if (target.key != key) return@mapNotNull null
        val raw = row.select(".gall_recommend").text().trim()
        if (!raw.matches(Regex("(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)"))) return@mapNotNull null
        val count = raw.replace(",", "").toLongOrNull() ?: return@mapNotNull null
        val writer = row.selectFirst(".gall_writer") ?: return@mapNotNull null
        RecommendationRow(key, count, writer.attr("data-uid"), writer.attr("data-ip"), writer.attr("data-nick"))
    }.distinctBy { it.key }
}

/** Monotonic observation times only; never interpolate across an unobserved boundary. */
internal class RecommendationTracker(private val maxPosts: Int = 2048) {
    private data class Point(val at: Long, val count: Long)
    private data class Track(val points: ArrayDeque<Point> = ArrayDeque(), var pending: RecommendationMatch? = null)
    private val tracks = LinkedHashMap<PostKey, Track>()
    fun clear() = tracks.clear()
    fun forget(key: PostKey) { tracks.remove(key) }
    fun observe(row: RecommendationRow, at: Long, policy: RecommendationPolicy): RecommendationMatch? {
        tracks.entries.removeAll { (_, t) -> t.points.peekLast()?.let { at - it.at > policy.windowMs } != false }
        val track = tracks.getOrPut(row.key) { Track() }
        val last = track.points.peekLast()
        if (last != null && at <= last.at) return track.pending?.takeIf { at >= it.detectedAt && at <= it.expiresAt }
        if (last != null && (row.count < last.count || at - last.at > policy.windowMs)) {
            track.points.clear(); track.pending = null
        }
        while (track.points.peekFirst()?.let { at - it.at > policy.windowMs } == true) track.points.removeFirst()
        if (track.pending?.let { at > it.expiresAt } == true) track.pending = null
        val first = track.points.peekFirst()
        if (first != null && row.count - first.count >= policy.increase && track.pending == null) {
            track.pending = RecommendationMatch(row.key, first.count, row.count, first.at, at, at + policy.windowMs)
        }
        // The worker samples at policy.pollMs; the extra slots allow reused list observations.
        track.points.addLast(Point(at, row.count))
        while (track.points.size > 256) track.points.removeFirst()
        while (tracks.size > maxPosts) tracks.remove(tracks.keys.first())
        return track.pending
    }
}

internal enum class RecommendationState { RECOMMENDED, ORDINARY, UNKNOWN }

/** Read only first-party manager controls, never a button pasted into post content. */
internal fun recommendationState(document: Document, key: PostKey): RecommendationState {
    if (document.select("input#no").attr("value") != key.postNo ||
        document.select("input#gallery_id").attr("value") != key.gallId || document.select(".write_div").isEmpty()) return RecommendationState.UNKNOWN
    val templates = document.select("script#minor_manager_view_buttons-tmpl,script#mini_manager_view_buttons-tmpl")
        .filter { it.parents().none { parent -> parent.hasClass("write_div") } }
        .map { Jsoup.parseBodyFragment(it.data(), document.baseUri()) }
    val modes = (listOf(document) + templates).flatMap { fragment ->
        fragment.select(".mng_subject_sel [onclick]").filter { element ->
            element.parents().none { it.hasClass("write_div") }
        }.mapNotNull { element ->
            Regex("""^\s*update_recom\(\s*['"](REL|SET)['"]\s*,\s*${Regex.escape(key.postNo)}\s*\)\s*;?\s*$""")
                .matchEntire(element.attr("onclick"))?.groupValues?.get(1)
        }
    }.toSet()
    return when (modes) {
        setOf("REL") -> RecommendationState.RECOMMENDED
        setOf("SET") -> RecommendationState.ORDINARY
        else -> RecommendationState.UNKNOWN
    }
}

internal fun recommendationReleasePayload(key: PostKey, token: String): Map<String, String> {
    require(key.gallType in setOf("M", "MI") && key.postNo.matches(Regex("[1-9][0-9]*")) && token.isNotBlank())
    return mapOf("ci_t" to token, "id" to key.gallId, "nos[]" to key.postNo, "_GALLTYPE_" to key.gallType, "mode" to "REL")
}
