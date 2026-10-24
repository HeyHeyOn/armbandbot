package com.heyheyon.armbandbot

internal const val REPEAT_CONTENT_ENABLED_KEY = "repeat_spam_content_enabled"
internal const val RATE_ENABLED_KEY = "posting_rate_enabled"
internal const val RATE_YUDONG_KEY = "posting_rate_yudong"
internal const val RATE_KKANG_KEY = "posting_rate_kkang"
internal const val RATE_IP_SECONDS_KEY = "posting_rate_ip_seconds"
internal const val RATE_IP_COUNT_KEY = "posting_rate_ip_count"
internal const val RATE_UID_SECONDS_KEY = "posting_rate_uid_seconds"
internal const val RATE_UID_COUNT_KEY = "posting_rate_uid_count"

internal enum class SpamMatchKind { CONTENT, POSTING_RATE }

/** Visible IP prefixes are shared identities; their limits deliberately remain separate from UID. */
internal data class PostingRateSettings(
    val enabled: Boolean = false,
    val yudong: Boolean = true,
    val kkang: Boolean = true,
    val ipSeconds: Int = 60,
    val ipCount: Int = 10,
    val uidSeconds: Int = 60,
    val uidCount: Int = 5,
    val observeOnly: Boolean = true,
) {
    fun bounded() = copy(ipSeconds = ipSeconds.coerceIn(10, 3600), uidSeconds = uidSeconds.coerceIn(10, 3600),
        ipCount = ipCount.coerceIn(2, 100), uidCount = uidCount.coerceIn(2, 100))
    fun seconds(author: String): Int = if (author.startsWith("uid:")) uidSeconds else ipSeconds
    fun count(author: String): Int = if (author.startsWith("uid:")) uidCount else ipCount
    fun includes(author: String): Boolean = when {
        author.startsWith("uid:") && author.length > 4 -> kkang
        author.startsWith("ip:") && author.length > 3 -> yudong
        else -> false
    }
}
