package com.heyheyon.armbandbot

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PumSnapshotTest {
    private fun fixture(name: String) = javaClass.getResource("/pum/$name")!!.readText()

    @Test
    fun immediateBlockOnlyReceivesPumResolutionForMatchingPost() {
        val resolution = resolved(fixture("2317-card-response.html"))
        val current = PostKey("M", "laboratory1", "2317")

        assertEquals(resolution, matchingPumResolution(current, current, resolution))
        assertNull(matchingPumResolution(PostKey("M", "laboratory1", "2318"), current, resolution))
    }

    @Test
    fun nativeDcCardResponseIsFrozenAtLoaderPositionWithoutCustomMarkup() {
        val live = dcPage()
        val before = live.html()

        val snapshot = PumSnapshot.withStaticCard(live, resolved(fixture("2317-card-response.html")))
        val native = snapshot.selectFirst("#pum_container.cloned_card")!!

        assertNotSame(live, snapshot)
        assertEquals(before, live.html())
        assertEquals(1, snapshot.select("#pum_container.cloned_card").size)
        assertEquals(1, native.select(".cloned_card_body > a[href]").size)
        assertEquals("/mgallery/board/view/?id=laboratory1&no=2315", native.selectFirst(".cloned_card_body > a")!!.attr("href"))
        assertTrue(native.selectFirst(".cloned_card_body > a > p")!!.attr("style").contains("-webkit-line-clamp:3"))
        assertTrue(native.selectFirst("header.gallview_head")!!.text().contains("실험실"))
        assertTrue(snapshot.select(".armbandbot-pum-card, .armbandbot-snapshot").isEmpty())
        assertTrue(snapshot.select("script, form, iframe, object, embed").isEmpty())
        assertEquals(1, snapshot.select("link[rel=stylesheet]").size)
        assertEquals("overflow:hidden;width:900px", snapshot.selectFirst(".write_div")!!.attr("style"))
        assertEquals(
            "https://gall.dcinside.com/mgallery/board/view/?id=laboratory1&no=2317",
            snapshot.selectFirst("meta[name=armbandbot-base-url]")!!.attr("content"),
        )
        assertTrue(snapshot.selectFirst(".view_comment")!!.text().contains("댓글창"))
    }

    @Test
    fun nativeFreezeIsIdempotentAndReplacesTheExistingDynamicContainer() {
        val first = PumSnapshot.withStaticCard(dcPage(), resolved(fixture("2317-card-response.html")))
        val changed = fixture("2317-card-response.html").replace("테스트 А.Ᏼ-С_D", "변경된 카드")

        val second = PumSnapshot.withStaticCard(first, resolved(changed))

        assertEquals(1, second.select("#pum_container.cloned_card").size)
        assertTrue(second.selectFirst("#pum_container")!!.text().contains("변경된 카드"))
        assertFalse(second.selectFirst("#pum_container")!!.text().contains("테스트 А.Ᏼ-С_D"))
        assertTrue(second.select(".armbandbot-pum-card").isEmpty())
    }

    @Test
    fun missingCardResponseFreezesTheRecognizedDcResponseWithoutCustomWarning() {
        val missingHtml = fixture("pum_card_missing.html")
        val snapshot = PumSnapshot.withStaticCard(
            dcPage(),
            PumResolution(PumSourceStatus.MISSING, dynamicCardHtml = missingHtml),
        )

        assertTrue(snapshot.select("script").isEmpty())
        assertEquals(1, snapshot.select("#pum_container.cloned_card").size)
        assertTrue(snapshot.selectFirst("#pum_container")!!.text().contains("삭제되었거나 존재하지 않는 원문입니다"))
        assertTrue(snapshot.select(".armbandbot-pum-card").isEmpty())
        assertTrue(snapshot.selectFirst(".write_div")!!.text().contains("바깥 본문"))
    }

    @Test
    fun cleanupPreservesDcVisualClassesStylesAndStylesheetButRemovesExecutableBehavior() {
        val live = Jsoup.parse("""
            <html><head>
              <meta charset='utf-8'><meta http-equiv='refresh' content='0;url=https://evil.test'>
              <base href='https://evil.test/'><style>.write_div{display:block}.cloned_card{border:1px solid #ddd}</style>
              <link rel='stylesheet' href='https://nstatic.dcinside.com/dc/w/css/common.css'>
              <link rel='stylesheet' href='https://evil.test/x.css'>
            </head><body class='dc_body' onload='boom()' style='background:url(javascript:boom)'>
              <div class='write_div' style='overflow:hidden;width:900px' onclick='boom()'>
                <a class='safe' href='/mgallery/board/view/?id=laboratory1&amp;no=2315'>원문</a>
                <a class='bad' href='javascript:boom'>위험</a>
                <script>boom()</script><form><input autofocus></form><iframe src='https://evil.test/frame'></iframe>
              </div>
            </body></html>
        """.trimIndent())

        val snapshot = PumSnapshot.withStaticCard(live, null)

        assertEquals("dc_body", snapshot.body().className())
        assertEquals("overflow:hidden;width:900px", snapshot.selectFirst(".write_div")!!.attr("style"))
        assertEquals("/mgallery/board/view/?id=laboratory1&no=2315", snapshot.selectFirst("a.safe")!!.attr("href"))
        assertFalse(snapshot.selectFirst("a.bad")!!.hasAttr("href"))
        assertEquals(1, snapshot.select("head style").size)
        assertEquals(1, snapshot.select("link[rel=stylesheet]").size)
        assertEquals(1, snapshot.select("meta[charset]").size)
        assertTrue(snapshot.select("script, base, meta[http-equiv=refresh], form, input, iframe, object, embed").isEmpty())
        assertTrue(snapshot.select("[onload], [onclick]").isEmpty())
        assertFalse(snapshot.body().hasAttr("style"))
        assertFalse(snapshot.html().contains("evil.test/x.css"))
        assertFalse(snapshot.html().contains("javascript:", ignoreCase = true))
    }

    @Test
    fun finalCleanupPreservesGeneratedCommentDisplayStylesAndRemovesHandlers() {
        val snapshot = PumSnapshot.withStaticCard(dcPage(), null)
        snapshot.selectFirst(".view_comment")!!.append("""
            <ul class='cmt_list' style='display:block' onclick='boom()'>
              <li class='ub-content' style='display:block'><p class='usertxt'>댓글 본문</p><script>boom()</script></li>
            </ul>
        """.trimIndent())

        PumSnapshot.removeExecutableBehavior(snapshot)

        assertEquals("display:block", snapshot.selectFirst(".cmt_list")!!.attr("style"))
        assertEquals("display:block", snapshot.selectFirst(".cmt_list > li")!!.attr("style"))
        assertTrue(snapshot.select("script, [onclick]").isEmpty())
        assertTrue(snapshot.text().contains("댓글 본문"))
    }

    @Test
    fun initialAndLatestKeepIndependentNativeDcCardVersions() {
        val root = Files.createTempDirectory("pum_native_snapshot_versions").toFile()
        val dir = File(root, "snapshots_bot").apply { mkdirs() }
        val initial = File(dir, "laboratory1_2317_initial.html")
        val latest = File(dir, "laboratory1_2317_latest.html")
        val cardA = fixture("2317-card-response.html").replace("테스트 А.Ᏼ-С_D", "카드 A")
        val cardB = fixture("2317-card-response.html").replace("테스트 А.Ᏼ-С_D", "카드 B")

        val initialPath = saveGeneralSnapshotPreservingExistingInitial(
            initial, latest, null, PumSnapshot.withStaticCard(dcPage(), resolved(cardA)).html(), listOf(root)
        )
        val baselineBytes = initial.readBytes()
        val preservedPath = saveGeneralSnapshotPreservingExistingInitial(
            initial, latest, initialPath, PumSnapshot.withStaticCard(dcPage(), resolved(cardB)).html(), listOf(root)
        )

        assertEquals(initial.canonicalPath, preservedPath)
        assertTrue(baselineBytes.contentEquals(initial.readBytes()))
        assertTrue(Jsoup.parse(initial.readText()).selectFirst("#pum_container")!!.text().contains("카드 A"))
        assertTrue(Jsoup.parse(latest.readText()).selectFirst("#pum_container")!!.text().contains("카드 B"))
        assertTrue(Jsoup.parse(latest.readText()).select(".armbandbot-pum-card, .armbandbot-snapshot").isEmpty())
        root.deleteRecursively()
    }

    private fun dcPage() = Jsoup.parse("""
        <html><head>
          <meta charset='utf-8'>
          <link rel='stylesheet' href='https://nstatic.dcinside.com/dc/w/css/common.css'>
        </head><body><article class='gallview_contents'><div class='view_content_wrap'>
          <div class='gallview_head'><h3><span class='title_subject'>펌 테스트2</span><b class='font_blue009'>(펌)</b></h3></div>
          <div class='write_div' style='overflow:hidden;width:900px'>
            <p>바깥 본문</p><div id='pum_card'></div><script>${fixture("2317-outer-loader.js")}</script>
          </div>
          <div class='view_comment' id='focus_cmt'>댓글창</div>
        </div></article></body></html>
    """.trimIndent(), "https://gall.dcinside.com/mgallery/board/view/?id=laboratory1&no=2317")

    private fun resolved(cardHtml: String) = PumResolution(
        status = PumSourceStatus.RESOLVED,
        sourceKey = PostKey("M", "laboratory1", "2315"),
        sourceUrl = "https://gall.dcinside.com/mgallery/board/view/?id=laboratory1&no=2315",
        title = "테스트",
        bodyText = "테스트 А.Ᏼ-С_D",
        sanitizedHtml = "<p>테스트 А.Ᏼ-С_D</p>",
        contentHash = "abc123",
        dynamicCardHtml = cardHtml,
    )
}
