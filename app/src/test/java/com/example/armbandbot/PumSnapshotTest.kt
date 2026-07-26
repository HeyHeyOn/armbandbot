package com.heyheyon.armbandbot

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PumSnapshotTest {
    @Test
    fun immediateBlockOnlyReceivesPumResolutionForMatchingPost() {
        val resolution = resolved(sanitizedHtml = "<p>source</p>")
        val current = PostKey("MI", "test", "10")

        assertEquals(resolution, matchingPumResolution(current, current, resolution))
        assertEquals(null, matchingPumResolution(PostKey("MI", "test", "11"), current, resolution))
    }

    @Test
    fun resolvedCardIsStaticStoredAndDoesNotMutateLiveDocument() {
        val live = Jsoup.parse("<html><body><article><div class='write_div'><script>loadPum()</script><p>outer</p></div></article></body></html>")
        val before = live.html()
        val resolution = resolved(
            sanitizedHtml = """
                <p onclick='steal()'>source body</p>
                <!-- private source comment -->
                <img class='written_dccon' src='//dcimg.example/con.gif' alt='콘'>
                <img src='https://images.example/a.jpg' onerror='steal()'>
                <iframe src='https://gall.dcinside.com/voice/player?vr=7'></iframe>
                <script>alert(1)</script>
            """.trimIndent(),
            mediaSources = listOf("https://images.example/a.jpg")
        )

        val snapshot = PumSnapshot.withStaticCard(live, resolution, checkedAt = "2026-07-25T12:00:00Z")
        val card = snapshot.selectFirst(".armbandbot-pum-card")!!

        assertNotSame(live, snapshot)
        assertEquals(before, live.html())
        assertEquals("RESOLVED", card.attr("data-status"))
        assertEquals("MI/test/10", card.attr("data-source-key"))
        assertEquals("https://gall.dcinside.com/mini/board/view/?id=test&no=10", card.attr("data-source-url"))
        assertEquals("2026-07-25T12:00:00Z", card.attr("data-checked-at"))
        assertEquals("abc123", card.attr("data-content-hash"))
        assertTrue(card.text().contains("펌 원문"))
        assertTrue(card.text().contains("test 갤러리"))
        assertTrue(card.text().contains("source title"))
        assertTrue(card.text().contains("source author"))
        assertTrue(card.text().contains("source preview"))
        assertTrue(card.text().contains("source body"))
        assertEquals(2, card.select("img").size)
        assertEquals("https://dcimg.example/con.gif", card.selectFirst("img.written_dccon")!!.attr("src"))
        assertTrue(card.selectFirst(".armbandbot-pum-voice")!!.text().contains("보이스"))
        assertTrue(card.select("script, form, iframe, object, embed, input").isEmpty())
        assertFalse(card.html().contains("onclick", true))
        assertFalse(card.html().contains("onerror", true))
        assertFalse(card.html().contains("private source comment"))
        assertFalse(snapshot.html().contains("loadPum()"))
    }

    @Test
    fun dangerousUrlsAndExecutableElementsAreRemoved() {
        val resolution = resolved(
            sourceUrl = "javascript:alert(1)",
            sanitizedHtml = """
                <a href='javascript:alert(1)'>bad</a>
                <img src='data:text/html,boom'>
                <video poster='vbscript:boom'><source src='file:///secret'></video>
                <form><input autofocus onfocus='steal()'></form><object data='https://evil.test/x'></object>
            """.trimIndent()
        )

        val card = PumSnapshot.withStaticCard(Jsoup.parse("<div class=write_div>outer</div>"), resolution).selectFirst(".armbandbot-pum-card")!!

        assertFalse(card.hasAttr("data-source-url"))
        assertTrue(card.select("form, input, object, script, iframe, embed").isEmpty())
        assertTrue(card.select("[src], [href], [poster]").isEmpty())
        assertFalse(card.html().contains("onfocus", true))
    }

    @Test
    fun adversarialBehaviorIsRemovedFromWholeSnapshotAndSourceCard() {
        val live = Jsoup.parse("""
            <html><head>
              <meta http-equiv='refresh' content='0;url=javascript:boom'>
              <base href='https://evil.test/'><style>.write_div{display:block}</style>
              <link rel='stylesheet' href='https://nstatic.dcinside.com/dc/w/css/common.css'>
              <link rel='stylesheet' href='https://evil.test/x.css'>
            </head><body style='background:url(javascript:boom)' onload='boom()'>
              <svg><animate onbegin='boom()'/><a xlink:href='javascript:boom'>svg</a></svg>
              <math href='javascript:boom'><mtext>math</mtext></math>
              <div class='write_div'><img srcset='javascript:boom 1x' background='javascript:boom'></div>
            </body></html>
        """.trimIndent())
        val source = resolved(sanitizedHtml = """
            <p style='color:red' onclick='boom()'>safe text</p>
            <a href='#safe'>anchor</a><a href='blob:https://evil.test/id' cite='javascript:boom'>bad</a>
            <img src='http://images.example/safe.jpg' srcset='data:text/html,boom 2x' data-src='file:///secret'>
            <video poster='https://media.example/poster.jpg'><source src='https://media.example/a.mp4'></video>
            <form action='https://evil.test/'><input formaction='javascript:boom'></form>
            <object data='https://evil.test/gadget'></object><svg><script>boom()</script></svg><math>gadget</math>
        """.trimIndent())

        val snapshot = PumSnapshot.withStaticCard(live, source)
        val card = snapshot.selectFirst(".armbandbot-pum-card")!!

        assertTrue(snapshot.select("meta, base, svg, math, script, form, iframe, object, embed, input").isEmpty())
        assertFalse(snapshot.select("style").isEmpty())
        assertEquals(1, snapshot.select("link[rel=stylesheet]").size)
        assertTrue(snapshot.selectFirst("link[rel=stylesheet]")!!.attr("href").startsWith("https://nstatic.dcinside.com/"))
        assertFalse(snapshot.html().contains("evil.test/x.css"))
        assertTrue(snapshot.select("[style], [srcset], [cite], [background], [xlink\\:href], [action], [formaction], [data]").isEmpty())
        assertFalse(snapshot.html().contains("javascript:", true))
        assertFalse(snapshot.html().contains("data:text", true))
        assertFalse(snapshot.html().contains("file:", true))
        assertFalse(snapshot.html().contains("blob:", true))
        assertEquals("#safe", card.selectFirst("a[href]")!!.attr("href"))
        assertEquals("http://images.example/safe.jpg", card.selectFirst("img")!!.attr("src"))
        assertEquals("https://media.example/a.mp4", card.selectFirst("source")!!.attr("src"))
        assertTrue(card.text().contains("safe text"))
    }

    @Test
    fun nullResolutionSanitizesOrdinaryPageWhilePreservingApprovedStylesheets() {
        val live = Jsoup.parse("""
            <html><head>
              <meta http-equiv='refresh' content='0;url=https://evil.test'>
              <base href='https://evil.test/'><style>.write_div{display:block}</style>
              <link rel='stylesheet' href='https://nstatic.dcinside.com/dc/w/css/common.css'>
              <link rel='stylesheet' href='https://evil.test/x.css'>
            </head><body onload='boom()'><div class='write_div' style='color:red'>
              <script>ordinaryLoader()</script><form><input autofocus></form>
              <iframe src='https://evil.test/frame'></iframe>
              <a href='javascript:boom' onclick='boom()' srcset='data:text/html,boom'>ordinary</a>
            </div></body></html>
        """.trimIndent())

        val snapshot = PumSnapshot.withStaticCard(live, null)

        assertEquals(1, snapshot.select("head style").size)
        assertEquals(1, snapshot.select("link[rel=stylesheet]").size)
        assertTrue(snapshot.selectFirst("link[rel=stylesheet]")!!.attr("href").startsWith("https://nstatic.dcinside.com/"))
        assertTrue(snapshot.select("script, meta, base, form, iframe, input").isEmpty())
        assertTrue(snapshot.select("[style], [onload], [onclick], [srcset], a[href]").isEmpty())
        assertFalse(snapshot.html().contains("evil.test"))
        assertTrue(snapshot.text().contains("ordinary"))
        assertTrue(snapshot.select(".armbandbot-pum-card").isEmpty())
        assertTrue(live.select("style, link[rel=stylesheet], [style], script").isNotEmpty())
    }

    @Test
    fun finalCleanupSanitizesGeneratedCommentsAddedAfterInitialSnapshotPass() {
        val snapshot = PumSnapshot.withStaticCard(
            Jsoup.parse("""
                <html><head><style>.comment_box{display:block}</style>
                <link rel='stylesheet' href='https://nstatic.dcinside.com/dc/w/css/common.css'></head>
                <body><div class='write_div'>ordinary</div></body></html>
            """.trimIndent()),
            null,
        )
        snapshot.body().append("""
            <div class='view_comment' onclick='boom()' style='display:block'>
              <script>boom()</script><form><input autofocus></form>
              <iframe src='javascript:boom'></iframe>
              <a href='javascript:boom' onmouseover='boom()'>comment</a>
              <img src='data:text/html,boom' onerror='boom()'>
            </div>
        """.trimIndent())

        PumSnapshot.removeExecutableBehavior(snapshot)

        assertTrue(snapshot.select("script, form, input, iframe").isEmpty())
        assertTrue(snapshot.select("[style], [onclick], [onmouseover], [onerror], a[href], img[src]").isEmpty())
        assertEquals(1, snapshot.select("head style").size)
        assertEquals(1, snapshot.select("link[rel=stylesheet]").size)
        assertTrue(snapshot.text().contains("comment"))
    }

    @Test
    fun unparsedPumLoaderStoresFailureCardInsteadOfEmptyBody() {
        val live = Jsoup.parse("""
            <html><head><style>.write_div{display:block}</style></head><body>
              <div class='write_div'><script>var u='/ajax/pum_ajax/get_contents'; brokenLoader(u);</script></div>
            </body></html>
        """.trimIndent())

        val snapshot = PumSnapshot.withStaticCard(live, null, checkedAt = "2026-07-25T14:00:00Z")
        val card = snapshot.selectFirst(".armbandbot-pum-card")!!

        assertEquals("INVALID_SOURCE", card.attr("data-status"))
        assertTrue(card.text().contains("원문을 불러오지 못했습니다"))
        assertTrue(snapshot.select("script").isEmpty())
        assertFalse(snapshot.select("style").isEmpty())
    }

    @Test
    fun unresolvedCardStoresAvailableMetadataAndWarning() {
        val resolution = PumResolution(
            status = PumSourceStatus.TEMPORARY_FAILURE,
            sourceKey = PostKey("M", "warn", "77"),
            sourceUrl = "https://gall.dcinside.com/mgallery/board/view/?id=warn&no=77"
        )

        val card = PumSnapshot.withStaticCard(
            Jsoup.parse("<div class=write_div>outer</div>"),
            resolution,
            checkedAt = "2026-07-25T13:00:00Z"
        ).selectFirst(".armbandbot-pum-card")!!

        assertEquals("TEMPORARY_FAILURE", card.attr("data-status"))
        assertEquals("M/warn/77", card.attr("data-source-key"))
        assertEquals("2026-07-25T13:00:00Z", card.attr("data-checked-at"))
        assertTrue(card.text().contains("원문을 불러오지 못했습니다"))
        assertTrue(card.select("script, iframe").isEmpty())
    }

    @Test
    fun initialAndLatestKeepIndependentPumSourceVersionsAndBlockCardIsStatic() {
        val root = Files.createTempDirectory("pum_snapshot_versions").toFile()
        val dir = File(root, "snapshots_bot").apply { mkdirs() }
        val initial = File(dir, "test_10_initial.html")
        val latest = File(dir, "test_10_latest.html")
        val live = Jsoup.parse("<div class='write_div'><script>loader()</script>outer</div>")
        val sourceA = resolved(sanitizedHtml = "<p>source A</p>")
        val sourceB = resolved(sanitizedHtml = "<p>source B</p>")

        val initialPath = saveGeneralSnapshotPreservingExistingInitial(
            initial, latest, null, PumSnapshot.withStaticCard(live, sourceA).html(), listOf(root)
        )
        val baselineBytes = initial.readBytes()
        val preservedPath = saveGeneralSnapshotPreservingExistingInitial(
            initial, latest, initialPath, PumSnapshot.withStaticCard(live, sourceB).html(), listOf(root)
        )
        val blockedHtml = PumSnapshot.withStaticCard(live, sourceB).html()

        assertEquals(initial.canonicalPath, preservedPath)
        assertTrue(baselineBytes.contentEquals(initial.readBytes()))
        assertTrue(Jsoup.parse(initial.readText()).selectFirst(".armbandbot-pum-body")!!.text().contains("source A"))
        assertTrue(Jsoup.parse(latest.readText()).selectFirst(".armbandbot-pum-body")!!.text().contains("source B"))
        assertTrue(Jsoup.parse(blockedHtml).selectFirst(".armbandbot-pum-card")!!.text().contains("source B"))
        assertFalse(blockedHtml.contains("loader()"))
        root.deleteRecursively()
    }

    @Test
    fun nullResolutionKeepsOrdinarySnapshotContentApartFromSafetyCleanup() {
        val live = Jsoup.parse("<html><body><div class='write_div'><p>ordinary</p></div></body></html>")
        val snapshot = PumSnapshot.withStaticCard(live, null)

        assertEquals(live.text(), snapshot.text())
        assertTrue(snapshot.select(".armbandbot-pum-card").isEmpty())
    }

    private fun resolved(
        sourceUrl: String = "https://gall.dcinside.com/mini/board/view/?id=test&no=10",
        sanitizedHtml: String,
        mediaSources: List<String> = emptyList()
    ) = PumResolution(
        status = PumSourceStatus.RESOLVED,
        sourceKey = PostKey("MI", "test", "10"),
        sourceUrl = sourceUrl,
        title = "source title",
        bodyText = "source preview",
        sanitizedHtml = sanitizedHtml,
        mediaSources = mediaSources,
        contentHash = "abc123",
        author = "source author"
    )
}
