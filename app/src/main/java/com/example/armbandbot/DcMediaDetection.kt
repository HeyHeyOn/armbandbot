package com.heyheyon.armbandbot

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.util.Locale

/** Narrow structural detection for DC's own attached-movie player. */
object DcMediaDetection {
    private const val MOVIE_VIEW_PATH = "/board/movie/movie_view"

    fun hasListMarker(row: Element): Boolean = row.select(".icon_movie").isNotEmpty()

    fun hasAttachedMovie(document: Document): Boolean = document
        .select(".write_div iframe[src]")
        .any { iframe -> !isInsidePumCard(iframe) && isDcMovieIframe(iframe) }

    internal fun isDcMovieIframe(iframe: Element): Boolean {
        if (!iframe.tagName().equals("iframe", ignoreCase = true)) return false
        val rawSource = iframe.attr("src").trim()
        if (rawSource.isEmpty()) return false
        val resolvedSource = iframe.absUrl("src").ifBlank { rawSource }
        val normalizedSource = if (resolvedSource.startsWith("//")) "https:$resolvedSource" else resolvedSource
        val uri = runCatching { URI(normalizedSource) }.getOrNull() ?: return false
        if (uri.path.orEmpty().trimEnd('/') != MOVIE_VIEW_PATH) return false

        val host = uri.host?.lowercase(Locale.ROOT)
        if (host == null) {
            return rawSource.startsWith("/") && !rawSource.startsWith("//")
        }
        return host == "dcinside.com" || host.endsWith(".dcinside.com")
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
