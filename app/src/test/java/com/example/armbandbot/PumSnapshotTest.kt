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
    fun cleanupPreservesDcButtonsVisuallyWhileMakingThemInert() {
        val live = Jsoup.parse("""
            <html><body>
              <main id='container'><section><article><div class='view_content_wrap'><div class='gallview_contents'><div class='positionr'>
              <div class='btn_recommend_box'>
                <button class='btn_recom_up visual-control' title='추천' aria-label='추천하기'
                    data-visual-state='ready' style='display:inline-block;color:#555'
                    onclick='boom()' action='https://evil.test/submit' method='post'
                    enctype='text/plain' target='_blank' popover disabled form='evil-form'
                    formaction='javascript:boom()' formmethod='post' formenctype='text/plain' formtarget='_blank'
                    name='vote' value='up' autofocus popovertarget='evil-popover' popovertargetaction='show'
                    command='show-modal' commandfor='evil-dialog' formnovalidate
                    interestfor='evil-interest' interesttarget='evil-target'><span class='sp_img icon_recom_up'></span></button>
                <button class='btn_recom_down' onfocus='boom()'><span class='sp_img icon_recom_down'></span></button>
                <span class='button-wrapper'><button class='btn_snsmore' onmouseover='boom()'><span class='sp_img icon_snsmore'></span></button></span>
                <button class='btn_snscrap' onpointerdown='boom()'><span class='sp_img icon_snscrap'></span></button>
                <button class='btn_report' onblur='boom()'><span class='sp_img icon_report'></span></button>
                <button type="button" class="btn_silbechu" data-no="2317"><em class="sp_img icon_silbechu"></em>실베추</button>
                <button type="button" class="btn_cloned btn_svc" onclick="Pum.write_open()"><em class="sp_img icon_cloned_b"></em>펌 0</button>
              </div>
              </div></div></div></article></section></main>
              <button class='rogue'><a href='https://evil.example/'>navigate</a></button>
              <form id='evil-form'><input name='payload' value='run'></form>
            </body></html>
        """.trimIndent())

        val snapshot = PumSnapshot.withStaticCard(live, null)
        val buttons = snapshot.select(".btn_recommend_box button")
        val upButton = snapshot.selectFirst("button.btn_recom_up")!!
        val silbechuButton = snapshot.selectFirst("button.btn_silbechu")!!
        val clonedButton = snapshot.selectFirst("button.btn_cloned")!!

        assertEquals(7, buttons.size)
        assertEquals(1, snapshot.select("button.btn_recom_up > .sp_img.icon_recom_up").size)
        assertEquals(1, snapshot.select("button.btn_recom_down > .sp_img.icon_recom_down").size)
        assertEquals(1, snapshot.select("button.btn_snsmore > .sp_img.icon_snsmore").size)
        assertEquals(1, snapshot.select("button.btn_snscrap > .sp_img.icon_snscrap").size)
        assertEquals(1, snapshot.select("button.btn_report > .sp_img.icon_report").size)
        assertEquals("btn_silbechu", silbechuButton.className())
        assertEquals(1, silbechuButton.select(":root > em.sp_img.icon_silbechu").size)
        assertEquals("실베추", silbechuButton.text())
        assertEquals("btn_cloned btn_svc", clonedButton.className())
        assertEquals(1, clonedButton.select(":root > em.sp_img.icon_cloned_b").size)
        assertEquals("펌 0", clonedButton.text())
        assertFalse(clonedButton.hasAttr("onclick"))
        assertTrue(buttons.all { it.attr("type") == "button" })
        assertEquals("btn_recom_up visual-control", upButton.className())
        assertEquals("추천", upButton.attr("title"))
        assertEquals("추천하기", upButton.attr("aria-label"))
        assertEquals("ready", upButton.attr("data-visual-state"))
        assertEquals("display:inline-block;color:#555", upButton.attr("style"))
        assertTrue(snapshot.select("button[action], button[method], button[enctype], button[target], button[popover], button[disabled]").isEmpty())
        assertTrue(snapshot.select("button[onclick], button[onfocus], button[onmouseover], button[onpointerdown], button[onblur]").isEmpty())
        assertTrue(snapshot.select("button[form], button[formaction], button[formmethod], button[formenctype], button[formtarget], button[name], button[value], button[autofocus], button[popovertarget], button[popovertargetaction], button[command], button[commandfor], button[formnovalidate], button[interestfor], button[interesttarget]").isEmpty())
        assertTrue(snapshot.select("button.rogue, button.rogue a, a[href='https://evil.example/']").isEmpty())
        assertTrue(snapshot.select("form, input").isEmpty())
    }

    @Test
    fun cleanupKeepsExactlySevenControlsOnlyInTheAuthoritativeBoxAndSanitizesTheirDescendants() {
        val live = Jsoup.parse("""
            <html><head><link rel='stylesheet' href='https://nstatic.dcinside.com/dc/w/css/common.css'></head><body>
              <div class='btn_recommend_box decoy'>
                <button class='btn_recom_up'>decoy up</button>
                <button class='btn_report'>decoy report</button>
              </div>
              <main id='container'><section><article><div class='view_content_wrap'><div class='gallview_contents'>
              <div class='write_div'><div class='positionr'><div class='btn_recommend_box nested-decoy'>
                <button class='btn_recom_up'>injected</button><button class='btn_recom_down'>injected</button>
                <button class='btn_silbechu'>injected</button><button class='btn_cloned'>injected</button>
                <button class='btn_snsmore'>injected</button><button class='btn_snscrap'>injected</button>
                <button class='btn_report'>injected</button>
              </div></div></div>
              <div class='positionr'>
                <div class='btn_recommend_box authoritative'>
                  <button class='btn_recom_up'><span class='blind'>추천</span><em class='sp_img icon_recom_up'></em></button>
                  <button class='btn_recom_up duplicate'>duplicate</button>
                  <button class='btn_recom_down'><span class='blind'>비추천</span><em class='sp_img icon_recom_down'></em></button>
                  <button class='btn_silbechu'><a href='https://evil.test/vote'><span class='visual'>실베추</span></a><img src='https://evil.test/pixel.png'><em class='sp_img icon_silbechu'></em></button>
                  <button class='btn_cloned'><video src='https://evil.test/movie.mp4' poster='https://evil.test/poster.jpg'></video><em class='sp_img icon_cloned_b'></em>펌 0</button>
                  <button class='btn_snsmore'><details open><summary><span>공유</span></summary></details><em class='sp_img icon_snsmore'></em></button>
                  <button class='btn_snscrap'><label tabindex='0'><em class='sp_img icon_scrap'></em>스크랩</label></button>
                  <button class='btn_report'><picture><source srcset='https://evil.test/report.webp'><img src='https://evil.test/report.png'></picture><span>신고</span><em class='sp_img icon_report'></em></button>
                  <button class='btn_report duplicate'>duplicate</button>
                  <button class='btn_recom_up btn_report ambiguous'>ambiguous</button>
                  <button class='other'>other</button>
                </div>
                <div class='btn_recommend_box sibling-decoy'><button class='btn_cloned'>decoy cloned</button></div>
              </div></div></div></article></section></main>
              <button class='btn_recom_down outside'>outside</button>
            </body></html>
        """.trimIndent())

        val snapshot = PumSnapshot.withStaticCard(live, null)
        val box = snapshot.selectFirst(".btn_recommend_box.authoritative")!!
        val classes = listOf(
            "btn_recom_up", "btn_recom_down", "btn_silbechu", "btn_cloned",
            "btn_snsmore", "btn_snscrap", "btn_report",
        )

        assertEquals(1, snapshot.select(".btn_recommend_box").size)
        assertEquals(7, snapshot.select("button").size)
        classes.forEach { assertEquals(it, 1, box.select("button.$it").size) }
        assertTrue(snapshot.select("button.duplicate, button.ambiguous, button.other, button.outside").isEmpty())
        assertTrue(box.select("a, img, picture, source, video, audio, track, details, summary, label, button button, link, style").isEmpty())
        assertTrue(box.select("[href], [src], [srcset], [poster], [tabindex], [contenteditable]").isEmpty())
        assertEquals("실베추", box.selectFirst("button.btn_silbechu span.visual")!!.text())
        assertEquals(1, box.select("button.btn_silbechu > em.sp_img.icon_silbechu").size)
        assertEquals("펌 0", box.selectFirst("button.btn_cloned")!!.text())
        assertEquals("공유", box.selectFirst("button.btn_snsmore span")!!.text())
        assertEquals("스크랩", box.selectFirst("button.btn_snscrap")!!.text())
        assertEquals("신고", box.selectFirst("button.btn_report span")!!.text())
        assertFalse(snapshot.html().contains("evil.test"))
      }

    @Test
    fun cleanupRemovesOnlyTheGaejukDecorationAndPreservesPostImagesAndHiddenTimg10() {
        val live = Jsoup.parse("""
            <html><head><style id='styleGaejuki'>#gaejukimg { position: fixed }</style></head><body>
              <div class='write_div'><img class='post-image' src='https://images.example.test/post.png'></div>
              <div id='gaejukimg' class='moveimg_off'>
                <img src='https://nstatic.dcinside.com/dc/w/images/moveimg.png'>
              </div>
              <div id='timg10' class='moveimg_off' style='display:none'>hidden utility</div>
            </body></html>
        """.trimIndent())

        val snapshot = PumSnapshot.withStaticCard(live, null)

        assertTrue(snapshot.select("#gaejukimg, style#styleGaejuki").isEmpty())
        assertEquals("https://images.example.test/post.png", snapshot.selectFirst(".write_div img.post-image")!!.attr("src"))
        assertEquals("hidden utility", snapshot.selectFirst("#timg10.moveimg_off")!!.text())
    }

    @Test
    fun gaejukCleanupDoesNotRemoveANonStyleElementWithTheDecorationStyleId() {
        val document = Jsoup.parse("<div id='styleGaejuki'>keep collision</div>")

        PumSnapshot.removeGaejukDecoration(document)

        assertEquals("keep collision", document.selectFirst("div#styleGaejuki")!!.text())
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
