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
// Detection uses creation time; finishing an already confirmed batch has its own bounded lifetime.
internal const val CONFIRMED_SPAM_RETENTION_MS = 15 * 60_000L

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
    val contentEnabled: Boolean = true,
    val postingRate: PostingRateSettings = PostingRateSettings(),
) {
    val windowMillis: Long get() = windowMinutes * 60_000L
    fun bounded() = copy(windowMinutes = windowMinutes.coerceIn(1, 60), count = count.coerceIn(2, 20),
        similarityPercent = similarityPercent.coerceIn(85, 100), minimumLength = minimumLength.coerceAtLeast(0),
        postingRate = postingRate.bounded())
}

internal data class RepeatSpamPost(
    val key: PostKey, val author: String, val createdAt: Long, val title: String, val body: String,
)

internal data class RepeatSpamMatch(val keys: Set<PostKey>, val windowMinutes: Int,
    val kind: SpamMatchKind = SpamMatchKind.CONTENT, val windowSeconds: Int = windowMinutes * 60) {
    val label: String get() = if (kind == SpamMatchKind.CONTENT) "반복 글 감지" else "연속 작성 감지"
    val reason: String get() = (if (kind == SpamMatchKind.CONTENT) "같은 작성자 정보의 반복 내용 ${keys.size}개 / 최근 ${windowMinutes}분"
        else "같은 작성자 정보의 연속 작성 ${keys.size}개 / 최근 ${windowSeconds}초") +
        " / 글번호 ${keys.map { it.postNo }.sorted().joinToString(",")}"
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
    private val completed = mutableSetOf<Pair<PostKey, SpamMatchKind>>()
    private val contentEligible = mutableSetOf<PostKey>()
    private val rateEligible = mutableSetOf<PostKey>()
    private val pending = linkedMapOf<PostKey, RepeatSpamMatch>()
    private data class Confirmation(val match: RepeatSpamMatch, val detectedAt: Long)
    private val confirmed = linkedMapOf<Pair<PostKey, SpamMatchKind>, Confirmation>()
    private var dirty = true
    private var nextExpiryAt = Long.MAX_VALUE
    private var lastEvaluatedAt = Long.MIN_VALUE

    @Synchronized fun updatePolicy(nextRevision: String, nextSettings: RepeatSpamSettings) {
        if (revision != nextRevision || settings != nextSettings.bounded() || !nextSettings.enabled) {
            clear(); revision = nextRevision; settings = nextSettings.bounded()
        }
    }
    @Synchronized fun clear() { events.clear(); completed.clear(); pending.clear(); confirmed.clear(); contentEligible.clear(); rateEligible.clear(); dirty = true; nextExpiryAt = Long.MAX_VALUE; lastEvaluatedAt = Long.MIN_VALUE }
    @Synchronized fun size(): Int = events.size
    private fun withdrawConfirmations(key: PostKey) {
        if (confirmed.entries.removeAll { key in it.value.match.keys }) dirty = true
    }
    @Synchronized fun forget(key: PostKey) { withdrawConfirmations(key); if (events.remove(key) != null) dirty = true; completed.removeAll { it.first == key }; pending.remove(key); contentEligible.remove(key); rateEligible.remove(key) }
    @Synchronized fun complete(key: PostKey, kind: SpamMatchKind = SpamMatchKind.CONTENT, allKinds: Boolean = false) {
        if (allKinds) SpamMatchKind.entries.forEach { completed.add(key to it) } else completed.add(key to kind)
        confirmed.keys.removeAll { it.first == key && (allKinds || it.second == kind) }
        pending.remove(key)
        // A completed HOLD must not suppress a different detector's later destructive decision.
        dirty = true
    }
    @Synchronized fun needsRecheck(key: PostKey, now: Long): Boolean {
        rebuild(now); return key in pending
    }
    @Synchronized fun observe(post: RepeatSpamPost, eligible: Boolean, now: Long, postingRateEligible: Boolean = false): RepeatSpamMatch? {
        val content = eligible && settings.contentEnabled
        val rate = postingRateEligible && settings.postingRate.enabled && settings.postingRate.includes(post.author)
        val retention = maxOf(if (content) settings.windowMillis else 0L,
            if (rate) settings.postingRate.seconds(post.author) * 1000L else 0L)
        val unchanged = events[post.key] == post && (post.key in contentEligible) == content && (post.key in rateEligible) == rate
        val awaitingConfirmedAction = unchanged && confirmed.values.any { value ->
            post.key in value.match.keys && now >= value.detectedAt && now - value.detectedAt <= CONFIRMED_SPAM_RETENTION_MS
        }
        if (!settings.enabled || (!content && !rate) || post.author.isBlank() || post.createdAt < 0 || post.createdAt > now ||
            (now - post.createdAt > retention && !awaitingConfirmedAction)) {
            forget(post.key); rebuild(now); return null
        }
        if (!unchanged) {
            withdrawConfirmations(post.key)
            completed.removeAll { it.first == post.key }; dirty = true
        }
        if (content) contentEligible.add(post.key) else contentEligible.remove(post.key)
        if (rate) rateEligible.add(post.key) else rateEligible.remove(post.key)
        events[post.key] = post
        rebuild(now)
        return pending[post.key]
    }
    @Synchronized fun canAct(key: PostKey, now: Long, kind: SpamMatchKind? = null): Boolean {
        rebuild(now); return pending[key]?.let { kind == null || kind == it.kind } == true
    }
    private fun rebuild(now: Long) {
        if (now < lastEvaluatedAt) clear()
        lastEvaluatedAt = now
        if (!dirty && now < nextExpiryAt) return
        dirty = false
        // A long user detection window must not immediately re-confirm a timed-out action.
        // Keep the terminal marker while its unchanged evidence remains in this tracker.
        confirmed.forEach { (key, value) ->
            if (now - value.detectedAt > CONFIRMED_SPAM_RETENTION_MS) completed.add(key)
        }
        confirmed.entries.removeAll { (key, value) ->
            key in completed || now < value.detectedAt || now - value.detectedAt > CONFIRMED_SPAM_RETENTION_MS
        }
        val retainedEvidence = confirmed.values.flatMap { it.match.keys }.toSet()
        fun expires(post: RepeatSpamPost): Long = post.createdAt + maxOf(
            if (post.key in contentEligible) settings.windowMillis else 0L,
            if (post.key in rateEligible) settings.postingRate.seconds(post.author) * 1000L else 0L)
        events.entries.removeAll { now < it.value.createdAt || (now > expires(it.value) && it.key !in retainedEvidence) }
        if (events.size > maxEvents) events.values.sortedByDescending { it.createdAt }.drop(maxEvents).forEach { events.remove(it.key) }
        completed.removeAll { it.first !in events }
        contentEligible.retainAll(events.keys); rateEligible.retainAll(events.keys)
        confirmed.entries.removeAll { (_, value) -> value.match.keys.any { it !in events } }
        // Re-evaluate at either detector's boundary, even when the other retains the event longer.
        nextExpiryAt = events.values.flatMap { post -> listOfNotNull(
            (post.createdAt + settings.windowMillis + 1).takeIf { post.key in contentEligible && it > now },
            (post.createdAt + settings.postingRate.seconds(post.author) * 1000L + 1).takeIf { post.key in rateEligible && it > now })
        }.minOrNull() ?: Long.MAX_VALUE
        pending.clear()
        if (!settings.enabled) return
        events.values.groupBy { Triple(it.key.gallType, it.key.gallId, it.author) }.values.forEach { sameAuthor ->
            val ordered = sameAuthor.filter { it.key in contentEligible && now - it.createdAt <= settings.windowMillis }
                .sortedWith(compareBy<RepeatSpamPost> { it.createdAt }.thenBy { it.key.postNo.toLongOrNull() ?: 0 })
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
                    group.filterNot { (it.key to SpamMatchKind.CONTENT) in completed }.forEach {
                        confirmed.putIfAbsent(it.key to SpamMatchKind.CONTENT, Confirmation(match, now))
                    }
                }
            }
            val author = sameAuthor.first().author
            val ratePosts = sameAuthor.filter { it.key in rateEligible && now - it.createdAt <= settings.postingRate.seconds(author) * 1000L }
            if (settings.postingRate.enabled && ratePosts.size >= settings.postingRate.count(author)) {
                val match = RepeatSpamMatch(ratePosts.map { it.key }.toSet(), 0, SpamMatchKind.POSTING_RATE, settings.postingRate.seconds(author))
                ratePosts.filterNot { (it.key to SpamMatchKind.POSTING_RATE) in completed }.forEach {
                    confirmed.putIfAbsent(it.key to SpamMatchKind.POSTING_RATE, Confirmation(match, now))
                }
            }
        }
        // Rechecking a page cannot renew a confirmation indefinitely or recruit expired/new posts.
        confirmed.forEach { (key, value) ->
            if (key.second == SpamMatchKind.CONTENT) pending[key.first] = value.match
        }
        confirmed.forEach { (key, value) ->
            if (key.second == SpamMatchKind.POSTING_RATE &&
                (key.first !in pending || (settings.observeOnly && !settings.postingRate.observeOnly))) pending[key.first] = value.match
        }
        nextExpiryAt = minOf(nextExpiryAt, confirmed.values.minOfOrNull { it.detectedAt + CONFIRMED_SPAM_RETENTION_MS + 1 } ?: Long.MAX_VALUE)
    }
}
