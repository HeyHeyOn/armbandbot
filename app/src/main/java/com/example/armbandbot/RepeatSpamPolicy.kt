package com.heyheyon.armbandbot

import java.text.Normalizer
import java.util.Locale
import java.util.TimeZone
import java.text.SimpleDateFormat
import java.text.ParsePosition

internal fun parseRepeatCreationDateMillis(value: String): Long? {
    val parser = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).apply {
        isLenient = false; timeZone = TimeZone.getTimeZone("Asia/Seoul")
    }
    val position = ParsePosition(0)
    return parser.parse(value, position)?.takeIf { position.index == value.length }?.time
}

internal const val REPEAT_WINDOW_KEY = "repeat_spam_window_minutes"
internal const val REPEAT_COUNT_KEY = "repeat_spam_count"
internal const val REPEAT_SIMILARITY_KEY = "repeat_spam_similarity_percent"
internal const val REPEAT_MIN_LENGTH_KEY = "repeat_spam_min_length"
internal const val REPEAT_DEFAULT_MIN_LENGTH = 8
internal const val REPEAT_OBSERVE_KEY = "repeat_spam_observe_only"
internal const val REPEAT_REVISION_KEY = "repeat_spam_settings_revision"
internal const val REPEAT_ENABLED_KEY = "is_spam_burst_protection_enabled"
internal const val REPEAT_YUDONG_KEY = "spam_burst_target_yudong"
internal const val REPEAT_KKANG_KEY = "spam_burst_target_kkang"

internal fun shouldApplyRepeatSpamAction(hasMatch: Boolean, observeOnly: Boolean, otherFilterActs: Boolean) =
    hasMatch && (!observeOnly || !otherFilterActs)

internal data class RepeatSpamSettings(
    val enabled: Boolean = false,
    val windowMinutes: Int = 10,
    val count: Int = 4,
    val similarityPercent: Int = 85,
    val observeOnly: Boolean = false,
    val yudong: Boolean = true,
    val kkang: Boolean = true,
    val minimumLength: Int = REPEAT_DEFAULT_MIN_LENGTH,
) {
    val windowMillis: Long get() = windowMinutes * 60_000L
    fun bounded() = copy(windowMinutes = windowMinutes.coerceIn(1, 60), count = count.coerceIn(2, 20),
        similarityPercent = similarityPercent.coerceIn(85, 100), minimumLength = minimumLength.coerceAtLeast(0))
}

internal data class RepeatSpamPost(
    val key: PostKey, val author: String, val createdAt: Long, val title: String, val body: String,
)

internal data class RepeatSpamMatch(val keys: Set<PostKey>, val windowMinutes: Int) {
    val reason: String get() = "같은 작성자 정보의 반복 내용 ${keys.size}개 / 최근 ${windowMinutes}분 / 글번호 ${keys.map { it.postNo }.sorted().joinToString(",")}"
}

/** Local lexical comparison. Never truncate a long document into a false exact match. */
internal object RepeatSpamText {
    private val numbers = Regex("[0-9]+(?:[.,:][0-9]+)*")
    private val negations = Regex("않|없|아니|금지|허용|(?:^|\\s)(?:안|못)(?=\\s)")
    private fun normalized(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).filterNot { Character.getType(it) == Character.FORMAT.toInt() || it in '\uFE00'..'\uFE0F' }
    private fun comparable(a: String, b: String, percent: Int, minimumLength: Int): Boolean {
        val na = normalized(a); val nb = normalized(b)
        val ca = na.filter { it.isLetterOrDigit() }; val cb = nb.filter { it.isLetterOrDigit() }
        if (minOf(ca.length, cb.length) < minimumLength.coerceAtLeast(0)) return false
        // A missing/blank body is not repetition evidence. With no length limit, actual symbol-only
        // text can match exactly; do not collapse unrelated symbols (or empty bodies) into one value.
        if (ca.isEmpty() || cb.isEmpty()) {
            val symbolsA = na.filterNot { it.isWhitespace() }
            val symbolsB = nb.filterNot { it.isWhitespace() }
            return minimumLength <= 0 && ca.isEmpty() && cb.isEmpty() &&
                symbolsA.isNotEmpty() && symbolsA == symbolsB
        }
        if (numbers.findAll(na).map { it.value }.toList() != numbers.findAll(nb).map { it.value }.toList()) return false
        if (negations.findAll(na).map { it.value.trim() }.toList() != negations.findAll(nb).map { it.value.trim() }.toList()) return false
        if (ca == cb) return true
        if (percent == 100) return false
        if (minOf(ca.length, cb.length).toDouble() / maxOf(ca.length, cb.length) < .8) return false
        val gramSize = minOf(3, ca.length, cb.length)
        val sa = ca.windowedSequence(gramSize).toSet(); val sb = cb.windowedSequence(gramSize).toSet()
        return sa.intersect(sb).size * 100.0 / sa.union(sb).size >= percent
    }
    fun matches(a: RepeatSpamPost, b: RepeatSpamPost, percent: Int, minimumLength: Int = REPEAT_DEFAULT_MIN_LENGTH): Boolean =
        matchesField(a, b, percent, minimumLength, title = true) || matchesField(a, b, percent, minimumLength, title = false)

    fun matchesField(a: RepeatSpamPost, b: RepeatSpamPost, percent: Int, minimumLength: Int, title: Boolean): Boolean =
        if (title) comparable(a.title, b.title, percent, minimumLength) else comparable(a.body, b.body, percent, minimumLength)
}

/** One instance per bot; gallery and author are part of every comparison and PostKey of every action. */
internal class RepeatSpamTracker(private val maxEvents: Int = 1000) {
    private var revision: String? = null
    private var settings = RepeatSpamSettings()
    private val events = linkedMapOf<PostKey, RepeatSpamPost>()
    private val completed = mutableSetOf<PostKey>()
    private val pending = linkedMapOf<PostKey, RepeatSpamMatch>()
    private var dirty = true
    private var nextExpiryAt = Long.MAX_VALUE
    private var lastEvaluatedAt = Long.MIN_VALUE

    @Synchronized fun updatePolicy(nextRevision: String, nextSettings: RepeatSpamSettings) {
        if (revision != nextRevision || settings != nextSettings.bounded() || !nextSettings.enabled) {
            clear(); revision = nextRevision; settings = nextSettings.bounded()
        }
    }
    @Synchronized fun clear() { events.clear(); completed.clear(); pending.clear(); dirty = true; nextExpiryAt = Long.MAX_VALUE }
    @Synchronized fun size(): Int = events.size
    @Synchronized fun forget(key: PostKey) { if (events.remove(key) != null) dirty = true; completed.remove(key); pending.remove(key) }
    @Synchronized fun complete(key: PostKey) { completed.add(key); pending.remove(key) }
    @Synchronized fun needsRecheck(key: PostKey, now: Long): Boolean {
        rebuild(now); return key in pending
    }
    @Synchronized fun observe(post: RepeatSpamPost, eligible: Boolean, now: Long): RepeatSpamMatch? {
        if (!settings.enabled || !eligible || post.author.isBlank() || post.createdAt < 0 || post.createdAt > now || now - post.createdAt > settings.windowMillis) {
            forget(post.key); rebuild(now); return null
        }
        if (events[post.key] != post) { completed.remove(post.key); dirty = true }
        events[post.key] = post
        rebuild(now)
        return pending[post.key]
    }
    @Synchronized fun canAct(key: PostKey, now: Long): Boolean { rebuild(now); return key in pending }
    private fun rebuild(now: Long) {
        if (now < lastEvaluatedAt) dirty = true
        lastEvaluatedAt = now
        if (!dirty && now < nextExpiryAt) return
        dirty = false
        events.entries.removeAll { now - it.value.createdAt !in 0..settings.windowMillis }
        if (events.size > maxEvents) events.values.sortedByDescending { it.createdAt }.drop(maxEvents).forEach { events.remove(it.key) }
        completed.retainAll(events.keys)
        nextExpiryAt = events.values.minOfOrNull { it.createdAt + settings.windowMillis + 1 } ?: Long.MAX_VALUE
        pending.clear()
        if (!settings.enabled) return
        events.values.groupBy { Triple(it.key.gallType, it.key.gallId, it.author) }.values.forEach { sameAuthor ->
            val ordered = sameAuthor.sortedWith(compareBy<RepeatSpamPost> { it.createdAt }.thenBy { it.key.postNo.toLongOrNull() ?: 0 })
            // Each field must independently reach the repeat count. Mixing two matching titles
            // with two matching bodies must not manufacture a four-post repetition.
            for (title in listOf(true, false)) {
                val clusters = mutableListOf<MutableList<RepeatSpamPost>>()
                ordered.forEach { post ->
                    // Compare against a fixed representative, never a transitive chain.
                    val group = clusters.firstOrNull {
                        RepeatSpamText.matchesField(it.first(), post, settings.similarityPercent, settings.minimumLength, title)
                    }
                    if (group == null) clusters.add(mutableListOf(post)) else group.add(post)
                }
                clusters.filter { it.size >= settings.count }.forEach { group ->
                    val match = RepeatSpamMatch(group.map { it.key }.toSet(), settings.windowMinutes)
                    group.filterNot { it.key in completed }.forEach { pending[it.key] = match }
                }
            }
        }
    }
}
