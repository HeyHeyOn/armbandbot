package com.heyheyon.armbandbot

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.util.Locale

/** Narrow structural detection for DC's own attached-movie player. */
object DcMediaDetection {
    private const val MOVIE_VIEW_PATH = "/board/movie/movie_view"
    private const val MOVIE_HOST = "gall.dcinside.com"

    fun hasListMarker(row: Element): Boolean = row.select(".icon_movie").isNotEmpty()

    fun hasAttachedMovie(document: Document): Boolean = document
        .select(".write_div iframe[src]")
        .any { iframe -> !isInsidePumCard(iframe) && isDcMovieIframe(iframe) }

    internal fun isDcMovieIframe(iframe: Element): Boolean {
        if (!iframe.tagName().equals("iframe", ignoreCase = true)) return false
        return canonicalMovieUrl(iframe.attr("src"), iframe.baseUri()) != null
    }

    internal fun canonicalMovieUrl(rawSource: String, baseUrl: String? = null): String? {
        val raw = rawSource.trim()
        if (raw.isEmpty()) return null
        val normalized = if (raw.startsWith("//")) "https:$raw" else raw
        val uri = runCatching {
            val parsed = URI(normalized)
            if (parsed.isAbsolute) parsed else URI(baseUrl?.trim().orEmpty()).resolve(parsed)
        }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", true) || uri.host?.lowercase(Locale.ROOT) != MOVIE_HOST ||
            uri.port != -1 || uri.userInfo != null || uri.fragment != null || uri.path != MOVIE_VIEW_PATH) {
            return null
        }
        val movieNo = Regex("^no=([1-9][0-9]*)$").matchEntire(uri.rawQuery.orEmpty())
            ?.groupValues?.get(1) ?: return null
        return "https://$MOVIE_HOST$MOVIE_VIEW_PATH?no=$movieNo"
    }

    private fun isInsidePumCard(iframe: Element): Boolean = iframe.parents().any { parent ->
        parent.id() == "pum_card" || parent.id() == "pum_container" ||
            parent.hasClass("cloned_card") || parent.hasClass("armbandbot-pum-card")
    }
}

fun shouldBlockYudongDcMedia(
    enabled: Boolean,
    postUid: String,
    hasDcMovie: Boolean,
    contentOnly: Boolean,
): Boolean = enabled && !contentOnly && postUid.isBlank() && hasDcMovie

fun shouldBlockKkangDcMedia(
    enabled: Boolean,
    isKkang: Boolean,
    hasDcMovie: Boolean,
    contentOnly: Boolean,
): Boolean = enabled && !contentOnly && isKkang && hasDcMovie
