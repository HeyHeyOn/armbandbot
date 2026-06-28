package com.heyheyon.armbandbot

import java.net.URLDecoder
import java.util.Locale

data class DcPostLocator(
    val gallId: String,
    val postNo: String,
    val gallType: String,
    val refererUrl: String
)

object DcinsidePostUrls {
    fun parsePostLocator(rawUrl: String): DcPostLocator? {
        val url = rawUrl.trim()
        if (url.isBlank()) return null
        val lower = url.lowercase(Locale.ROOT)

        parseMobilePath(url)?.let { (id, no) ->
            return DcPostLocator(id, no, "M", "https://gall.dcinside.com/mgallery/board/view/?id=$id&no=$no")
        }

        val id = queryValue(url, "id").orEmpty()
        val no = queryValue(url, "no").orEmpty()
        if (id.isBlank() || no.isBlank()) return null
        val gallType = when {
            lower.contains("/mini/") -> "MI"
            lower.contains("/mgallery/") -> "M"
            lower.contains("_galltype_=mi") -> "MI"
            lower.contains("_galltype_=m") -> "M"
            else -> "G"
        }
        val referer = when (gallType) {
            "MI" -> "https://gall.dcinside.com/mini/board/view/?id=$id&no=$no"
            "M" -> "https://gall.dcinside.com/mgallery/board/view/?id=$id&no=$no"
            else -> "https://gall.dcinside.com/board/view/?id=$id&no=$no"
        }
        return DcPostLocator(id, no, gallType, referer)
    }

    fun desktopUrl(rawUrl: String): String = parsePostLocator(rawUrl)?.refererUrl ?: rawUrl.trim()

    private fun parseMobilePath(url: String): Pair<String, String>? {
        val match = Regex("""https?://m\.dcinside\.com/(?:mini/)?board/([^/?#]+)/([0-9]+)""", RegexOption.IGNORE_CASE).find(url)
            ?: return null
        return decode(match.groupValues[1]) to decode(match.groupValues[2])
    }

    private fun queryValue(url: String, key: String): String? {
        val match = Regex("""[?&]${Regex.escape(key)}=([^&#]+)""", RegexOption.IGNORE_CASE).find(url)
            ?: return null
        return decode(match.groupValues[1])
    }

    private fun decode(value: String): String = try {
        URLDecoder.decode(value, Charsets.UTF_8.name())
    } catch (_: Exception) {
        value
    }
}
