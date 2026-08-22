package com.heyheyon.armbandbot

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DcMediaDetectionTest {
    @Test
    fun canonicalMovieUrlAcceptsOnlyExactHttpsDcPlayerWithNumericNumber() {
        assertEquals(
            "https://gall.dcinside.com/board/movie/movie_view?no=7016978",
            DcMediaDetection.canonicalMovieUrl(
                "https://gall.dcinside.com/board/movie/movie_view?no=7016978",
            ),
        )
        assertEquals(
            "https://gall.dcinside.com/board/movie/movie_view?no=7016978",
            DcMediaDetection.canonicalMovieUrl(
                "//gall.dcinside.com/board/movie/movie_view?no=7016978",
            ),
        )
        assertEquals(
            "https://gall.dcinside.com/board/movie/movie_view?no=7016978",
            DcMediaDetection.canonicalMovieUrl(
                "/board/movie/movie_view?no=7016978",
                "https://gall.dcinside.com/mgallery/board/view/?id=laboratory1&no=2336",
            ),
        )

        listOf(
            "http://gall.dcinside.com/board/movie/movie_view?no=7016978",
            "https://m.dcinside.com/board/movie/movie_view?no=7016978",
            "https://gall.dcinside.com:443/board/movie/movie_view?no=7016978",
            "https://gall.dcinside.com/board/movie/movie_view?no=abc",
            "https://gall.dcinside.com/board/movie/movie_view?no=0",
            "https://gall.dcinside.com/board/movie/movie_view?no=1&next=https://evil.example",
            "https://gall.dcinside.com/board/movie/movie_view?no=1#fragment",
            "https://gall.dcinside.com.evil.example/board/movie/movie_view?no=1",
        ).forEach { assertNull(it, DcMediaDetection.canonicalMovieUrl(it)) }
    }

    @Test
    fun detectsDcMovieIframeOnlyInsideOuterPostBody() {
        val document = Jsoup.parse(
            """
            <html><body>
              <div class='write_div'>
                <p>본문</p>
                <iframe id='movieIcon7016978'
                  src='https://gall.dcinside.com/board/movie/movie_view?no=7016978'></iframe>
              </div>
            </body></html>
            """.trimIndent(),
            "https://gall.dcinside.com/mini/board/view/?id=sample&no=1",
        )

        assertTrue(DcMediaDetection.hasAttachedMovie(document))
    }

    @Test
    fun ignoresDcMovieIframeOutsidePostBody() {
        val document = Jsoup.parse(
            """
            <html><body>
              <iframe src='https://gall.dcinside.com/board/movie/movie_view?no=1'></iframe>
              <div class='write_div'><p>일반 본문</p></div>
            </body></html>
            """.trimIndent(),
        )

        assertFalse(DcMediaDetection.hasAttachedMovie(document))
    }

    @Test
    fun ignoresYoutubeVoiceAdsAndGenericIframes() {
        listOf(
            "https://www.youtube.com/embed/abc",
            "https://gall.dcinside.com/voice/player?vr=abc",
            "https://ad.example/movie/video/dcmedia",
            "https://gall.dcinside.com/board/movie/not_movie_view?no=1",
        ).forEach { source ->
            val document = Jsoup.parse("<div class='write_div'><iframe src='$source'></iframe></div>")
            assertFalse("unexpected match: $source", DcMediaDetection.hasAttachedMovie(document))
        }
    }

    @Test
    fun ignoresMovieIframeInsidePumSourceCard() {
        val document = Jsoup.parse(
            """
            <div class='write_div'>
              <p>바깥 본문</p>
              <div id='pum_container' class='cloned_card'>
                <iframe src='https://gall.dcinside.com/board/movie/movie_view?no=2'></iframe>
              </div>
            </div>
            """.trimIndent(),
        )

        assertFalse(DcMediaDetection.hasAttachedMovie(document))
    }

    @Test
    fun listMarkerRequiresDcMovieIconClass() {
        val marked = Jsoup.parse("<table><tr><td><span class='icon_img icon_movie'></span></td></tr></table>")
        val plain = Jsoup.parse("<table><tr><td>movie video dcmedia</td></tr></table>")

        assertTrue(DcMediaDetection.hasListMarker(marked.selectFirst("tr")!!))
        assertFalse(DcMediaDetection.hasListMarker(plain.selectFirst("tr")!!))
    }

    @Test
    fun settingsDefaultOffAndSurviveTransfer() {
        assertFalse(defaultBooleanValue("is_yudong_dc_media_block"))
        assertTrue("is_yudong_dc_media_block" in EXPORTABLE_BOOLEAN_KEYS)
        assertFalse(migrateBotSettingsSnapshot(emptyMap())["is_yudong_dc_media_block"] as Boolean)

        val imported = parseAndMigrateBotSettingsExport(
            BotSettingsExport(
                botName = "동영상 필터 봇",
                strings = emptyMap(),
                booleans = mapOf("is_yudong_dc_media_block" to true),
                ints = emptyMap(),
                floats = emptyMap(),
                stringSets = emptyMap(),
            ).toJson()
        )
        assertTrue(imported.booleans["is_yudong_dc_media_block"] == true)
    }

    @Test
    fun kkangSettingsDefaultOffAndSurviveTransfer() {
        assertFalse(defaultBooleanValue("is_kkang_dc_media_block"))
        assertTrue("is_kkang_dc_media_block" in EXPORTABLE_BOOLEAN_KEYS)
        assertFalse(migrateBotSettingsSnapshot(emptyMap())["is_kkang_dc_media_block"] as Boolean)

        val imported = parseAndMigrateBotSettingsExport(
            BotSettingsExport(
                botName = "깡계 동영상 필터 봇",
                strings = emptyMap(),
                booleans = mapOf("is_kkang_dc_media_block" to true),
                ints = emptyMap(),
                floats = emptyMap(),
                stringSets = emptyMap(),
            ).toJson()
        )
        assertTrue(imported.booleans["is_kkang_dc_media_block"] == true)
    }

    @Test
    fun kkangPolicyRequiresEnabledKkangOuterPostWithAttachedMovie() {
        assertTrue(shouldBlockKkangDcMedia(true, isKkang = true, hasDcMovie = true, contentOnly = false))
        assertFalse(shouldBlockKkangDcMedia(false, isKkang = true, hasDcMovie = true, contentOnly = false))
        assertFalse(shouldBlockKkangDcMedia(true, isKkang = false, hasDcMovie = true, contentOnly = false))
        assertFalse(shouldBlockKkangDcMedia(true, isKkang = true, hasDcMovie = false, contentOnly = false))
        assertFalse(shouldBlockKkangDcMedia(true, isKkang = true, hasDcMovie = true, contentOnly = true))
    }

    @Test
    fun policyBlocksOnlyEnabledAnonymousOuterPostWithAttachedMovie() {
        assertTrue(shouldBlockYudongDcMedia(true, postUid = "", hasDcMovie = true, contentOnly = false))
        assertFalse(shouldBlockYudongDcMedia(false, postUid = "", hasDcMovie = true, contentOnly = false))
        assertFalse(shouldBlockYudongDcMedia(true, postUid = "member-id", hasDcMovie = true, contentOnly = false))
        assertFalse(shouldBlockYudongDcMedia(true, postUid = "", hasDcMovie = false, contentOnly = false))
        assertFalse(shouldBlockYudongDcMedia(true, postUid = "", hasDcMovie = true, contentOnly = true))
    }
}
