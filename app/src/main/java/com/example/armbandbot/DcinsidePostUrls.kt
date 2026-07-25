package com.heyheyon.armbandbot

import java.net.URLDecoder
import java.net.URI
import java.util.Locale

data class DcPostLocator(
    val gallId: String,
    val postNo: String,
    val gallType: String,
    val refererUrl: String
)

data class SafeDcPostUrl(
    val key: PostKey,
    val url: String,
    val refererUrl: String,
)

object DcinsidePostUrls {
    /** Strict parser for URLs obtained from untrusted PUM markup and redirects. */
    fun parseSafeCanonicalPostUrl(rawUrl: String, self: PostKey? = null): SafeDcPostUrl? {
        val uri = try { URI(rawUrl.trim()) } catch (_: Exception) { return null }
        if (!uri.scheme.equals("https", ignoreCase = true) ||
            !uri.host.equals("gall.dcinside.com", ignoreCase = true) ||
            uri.port != -1 || uri.userInfo != null || uri.fragment != null
        ) return null

        val type = when (uri.path) {
            "/board/view/", "/board/view" -> "G"
            "/mgallery/board/view/", "/mgallery/board/view" -> "M"
            "/mini/board/view/", "/mini/board/view" -> "MI"
            else -> return null
        }
        val values = linkedMapOf<String, String>()
        for (part in (uri.rawQuery ?: return null).split('&')) {
            val pair = part.split('=', limit = 2)
            if (pair.size != 2) continue
            val name = decode(pair[0])
            if (name in values) return null
            values[name] = decode(pair[1])
        }
        val id = values["id"] ?: return null
        val no = values["no"] ?: return null
        if (!id.matches(Regex("[A-Za-z0-9_-]+")) || !no.matches(Regex("[0-9]+"))) return null
        val key = PostKey(type, id, no)
        if (key == self) return null
        val canonical = canonicalDetailUrl(key)
        return SafeDcPostUrl(key, canonical, canonical)
    }

    fun canonicalDetailUrl(key: PostKey): String {
        val prefix = when (key.gallType) { "M" -> "/mgallery"; "MI" -> "/mini"; else -> "" }
        return "https://gall.dcinside.com$prefix/board/view/?id=${key.gallId}&no=${key.postNo}"
    }

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
