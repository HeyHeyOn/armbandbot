package com.heyheyon.armbandbot

import org.jsoup.HttpStatusException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeletedPostHandlingTest {
    @Test
    fun dcViewHttp404IsTreatedAsDeletedOrUnavailablePost() {
        val error = HttpStatusException(
            "HTTP error fetching URL",
            404,
            "https://gall.dcinside.com/mgallery/board/view/?id=carrotmarket&no=28987"
        )

        assertTrue(DeletedPostHandling.isDeletedOrUnavailablePost(error))
    }

    @Test
    fun nonViewHttp404IsNotTreatedAsDeletedPost() {
        val error = HttpStatusException(
            "HTTP error fetching URL",
            404,
            "https://gall.dcinside.com/mgallery/board/lists/?id=carrotmarket&page=1"
        )

        assertFalse(DeletedPostHandling.isDeletedOrUnavailablePost(error))
    }

    @Test
    fun networkFailureIsNotTreatedAsDeletedPost() {
        assertFalse(DeletedPostHandling.isDeletedOrUnavailablePost(RuntimeException("temporary network failure")))
    }
}
