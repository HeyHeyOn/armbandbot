package com.heyheyon.armbandbot

import org.jsoup.Jsoup

object CommentModerationText {
    fun extract(rawHtml: String): String {
        if (rawHtml.isBlank()) return ""
        val doc = Jsoup.parseBodyFragment(rawHtml)
        doc.select("a.mention").remove()
        return doc.body().text()
    }
}
