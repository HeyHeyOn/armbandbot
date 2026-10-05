package com.heyheyon.armbandbot

import org.junit.Assert.*
import org.junit.Test

class AutomationGalleryCompatibilityTest {
    @Test fun mobileListAndPostShareDesktopIdentity() {
        listOf("https://m.dcinside.com/board/laboratory1", "http://m.dcinside.com/board/laboratory1/2371?recommend=1", "https://M.DCINSIDE.COM/board/laboratory1?page=2").forEach {
            assertEquals("M" to "laboratory1", automationGallery(it))
        }
    }
    @Test fun miniMobileVariantsRetainMiniTypeAndActualId() {
        listOf("https://m.dcinside.com/mini/armbandbot", "https://m.dcinside.com/mini/board/armbandbot", "https://m.dcinside.com/mini/board/armbandbot/295", "https://gall.dcinside.com/mini/armbandbot").forEach {
            assertEquals("MI" to "armbandbot", automationGallery(it))
        }
    }
    @Test fun desktopShortAndExistingQueryVariantsAreAccepted() {
        listOf("https://gall.dcinside.com/laboratory1", "https://gall.dcinside.com/mgallery/board/lists/laboratory1", "http://gall.dcinside.com/mgallery/board/view/?id=laboratory1&no=2371", "https://gall.dcinside.com/board/lists/?id=laboratory1").forEach {
            assertEquals("M" to "laboratory1", automationGallery(it))
        }
    }
    @Test fun unsafeAmbiguousAndMalformedTargetsFailClosed() {
        listOf("https://evil.test/board/laboratory1", "https://m.dcinside.com.evil.test/board/laboratory1", "https://user@m.dcinside.com/board/laboratory1", "https://gall.dcinside.com/mgallery/board/lists/?id=foo&id=bar", "https://m.dcinside.com/mini/board/foo?id=bar", "https://m.dcinside.com/board/%2Ffoo", "https://m.dcinside.com/board/", "https://gall.dcinside.com/mgallery/board/lists/").forEach {
            assertTrue("Unexpected valid target: $it", runCatching { automationGallery(it) }.isFailure)
        }
    }
    @Test fun galleryScopedMovesNeverLeakEvenWhenTabIdsAndKeywordsMatch() {
        val a = TabMoveRule("a", "M", "one", "시험", 10, "일반", false)
        val b = TabMoveRule("b", "MI", "two", "시험", 10, "테스트", false)
        assertEquals(a, matchingMove(listOf(a,b), PostKey("M","one","1"), "시험",false,false,false))
        assertEquals(b, matchingMove(listOf(a,b), PostKey("MI","two","1"), "시험",false,false,false))
        assertNull(matchingMove(listOf(a,b), PostKey("M","two","1"), "시험",false,false,false))
    }
}
