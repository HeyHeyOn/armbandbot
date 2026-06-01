package com.heyheyon.armbandbot

import org.jsoup.HttpStatusException

object DeletedPostHandling {
    fun isDeletedOrUnavailablePost(error: Throwable): Boolean {
        val httpError = error as? HttpStatusException ?: return false
        return httpError.statusCode == 404 && httpError.url.contains("/board/view/")
    }
}
