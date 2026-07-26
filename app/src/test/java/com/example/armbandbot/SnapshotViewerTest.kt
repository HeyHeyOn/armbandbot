package com.heyheyon.armbandbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SnapshotViewerTest {
    private fun fixture(name: String) = javaClass.getResource("/pum/$name")!!.readText()

    @Test
    fun nativeDcPumCardParsesItsClickableSourceAndDoesNotContaminateOuterBody() {
        val html = snapshotHtml(
            outerBody = "<p>바깥 본문</p>",
            card = "<div id='pum_container' class='cloned_card'>${fixture("2317-card-response.html")}</div>",
        )

        val parsed = parseSnapshot(writeSnapshot(html).path)
        val preview = parsed.pumPreview!!

        assertEquals(PumSourceStatus.RESOLVED, preview.status)
        assertEquals(PostKey("M", "laboratory1", "2315"), preview.sourceKey)
        assertEquals("https://gall.dcinside.com/mgallery/board/view/?id=laboratory1&no=2315", preview.sourceUrl)
        assertEquals("실험실", preview.galleryLabel)
        assertEquals("테스트", preview.title)
        assertEquals("실갤러(222.104)", preview.author)
        assertTrue(preview.previewText.contains("А.Ᏼ-С_D"))
        assertNull(preview.thumbnailUrl)
        assertTrue(preview.bodyElements.isEmpty())
        assertEquals(listOf(BodyElement.TextElement("바깥 본문")), parsed.bodyElements)
    }

    @Test
    fun webViewBaseUrlUsesValidatedStoredGalleryTypeAndRejectsMaliciousMetadata() {
        val stored = """<html><head><meta name='armbandbot-base-url' content='https://gall.dcinside.com/mgallery/board/view/?id=laboratory1&amp;no=2317'></head></html>"""
        val malicious = """<html><head><meta name='armbandbot-base-url' content='https://evil.example/view?id=x&amp;no=1'></head></html>"""

        assertEquals(
            "https://gall.dcinside.com/mgallery/board/view/?id=laboratory1&no=2317",
            snapshotWebViewBaseUrl(stored, "laboratory1_2317_latest.html"),
        )
        assertEquals(
            "https://gall.dcinside.com/board/view/?id=laboratory1&no=2317",
            snapshotWebViewBaseUrl(malicious, "laboratory1_2317_latest.html"),
        )
    }

    @Test
    fun resolvedPumCardParsesMetadataBodyMediaVoiceAndRepresentativeImage() {
        val html = snapshotHtml(
            outerBody = "<p>바깥 본문</p>",
            card = """
                <section class="armbandbot-pum-card" data-status="RESOLVED"
                    data-source-key="MI/source_gallery/42"
                    data-source-url="https://gall.dcinside.com/mini/board/view/?id=source_gallery&amp;no=42"
                    data-checked-at="2026-07-25T12:00:00Z" data-content-hash="abc123">
                  <h2 class="armbandbot-pum-heading">펌 원문</h2>
                  <div class="armbandbot-pum-gallery">source_gallery 갤러리</div>
                  <h3 class="armbandbot-pum-title">원문 제목</h3>
                  <div class="armbandbot-pum-author">원문 작성자</div>
                  <p class="armbandbot-pum-preview">미리보기 문장</p>
                  <div class="armbandbot-pum-body">
                    직접 원문 텍스트
                    <!-- source comments must not become viewer content -->
                    <p>원문 첫 문단<br>둘째 줄</p>
                    <p><img src="https://images.dcinside.com/source.jpg" alt="대표 이미지"></p>
                    <p><img class="written_dccon" src="https://dcimg5.dcinside.com/dccon.php?no=PUMTOKEN" alt="원문콘"></p>
                    <span class="armbandbot-pum-voice">보이스 원문 (정적 표시)</span>
                  </div>
                </section>
            """.trimIndent()
        )

        val parsed = parseSnapshot(writeSnapshot(html).path)
        val preview = parsed.pumPreview!!

        assertEquals(PumSourceStatus.RESOLVED, preview.status)
        assertEquals("source_gallery 갤러리", preview.galleryLabel)
        assertEquals(PostKey("MI", "source_gallery", "42"), preview.sourceKey)
        assertEquals("https://gall.dcinside.com/mini/board/view/?id=source_gallery&no=42", preview.sourceUrl)
        assertEquals("원문 제목", preview.title)
        assertEquals("원문 작성자", preview.author)
        assertEquals("2026-07-25T12:00:00Z", preview.checkedAt)
        assertEquals("abc123", preview.contentHash)
        assertEquals("미리보기 문장", preview.previewText)
        assertEquals("https://images.dcinside.com/source.jpg", preview.thumbnailUrl)
        assertTrue(preview.bodyElements.any { it == BodyElement.TextElement("직접 원문 텍스트") })
        assertTrue(preview.bodyElements.toString(), preview.bodyElements.any { it == BodyElement.TextElement("원문 첫 문단\n둘째 줄") })
        assertTrue(preview.bodyElements.any { it == BodyElement.ImageElement("https://images.dcinside.com/source.jpg") })
        assertTrue(preview.bodyElements.any { it == BodyElement.ImageElement("https://dcimg5.dcinside.com/dccon.php?no=PUMTOKEN", true) })
        assertTrue(preview.bodyElements.any { it == BodyElement.TextElement("[보이스리플]") })
        assertFalse(preview.bodyElements.filterIsInstance<BodyElement.TextElement>().any { it.text.contains("source comments") })
        assertEquals(listOf(BodyElement.TextElement("바깥 본문")), parsed.bodyElements)
    }

    @Test
    fun everyPumFailureStatusParsesAndHasReadableDisplayLabel() {
        PumSourceStatus.entries.filterNot { it == PumSourceStatus.RESOLVED }.forEach { status ->
            val parsed = parseSnapshot(writeSnapshot(snapshotHtml(card = """
                <section class="armbandbot-pum-card" data-status="${status.name}" data-checked-at="checked">
                  <h2>펌 원문</h2><div class="armbandbot-pum-gallery">테스트 갤러리</div>
                  <div class="armbandbot-pum-warning">원문을 불러오지 못했습니다</div>
                </section>
            """.trimIndent())).path).pumPreview!!

            assertEquals(status, parsed.status)
            assertTrue(pumStatusLabel(status).isNotBlank())
            assertTrue(pumStatusLabel(status) != status.name)
            assertTrue(parsed.bodyElements.isEmpty())
        }
    }

    @Test
    fun unsafeOrMismatchedPumSourceLinksAndUnsafeMediaAreRejected() {
        val malicious = listOf(
            "javascript:alert(1)",
            "https://evil.example/mini/board/view/?id=safe&amp;no=1",
            "https://gall.dcinside.com/mini/board/view/?id=other&amp;no=1"
        )
        malicious.forEach { url ->
            val card = """
                <section class="armbandbot-pum-card" data-status="RESOLVED" data-source-key="MI/safe/1" data-source-url="$url">
                  <div class="armbandbot-pum-body"><img src="data:text/html,boom"><img src="javascript:boom"></div>
                </section>
            """.trimIndent()
            val preview = parseSnapshot(writeSnapshot(snapshotHtml(card = card)).path).pumPreview!!
            assertNull(preview.sourceUrl)
            assertNull(preview.thumbnailUrl)
            assertTrue(preview.bodyElements.none { it is BodyElement.ImageElement })
        }
    }

    @Test
    fun snapshotMediaPolicyOnlyAllowsHttpsDcImageAndDcconHosts() {
        val valid = listOf(
            "https://images.dcinside.com/source.jpg",
            "https://dcimg5.dcinside.com/dccon.php?no=TOKEN",
            "https://dcimg3.dcinside.co.kr/dccon.php?no=TOKEN2",
            "//dcimg1.dcinside.com/image.webp",
        )
        valid.forEach { raw ->
            assertTrue(raw, safeSnapshotMediaUrl(raw)?.startsWith("https://") == true)
        }

        val invalid = listOf(
            "http://images.dcinside.com/source.jpg",
            "https://user:password@images.dcinside.com/source.jpg",
            "https://images.dcinside.com:443/source.jpg",
            "https://evil.example/source.jpg",
            "https://localhost/source.jpg",
            "https://127.0.0.1/source.jpg",
            "https://10.0.0.1/source.jpg",
            "https://169.254.1.1/source.jpg",
            "https://[::1]/source.jpg",
            "https://images.dcinside.com.evil.example/source.jpg",
            "https://notmedia.dcinside.com/source.jpg",
            "javascript:alert(1)",
        )
        invalid.forEach { raw -> assertNull(raw, safeSnapshotMediaUrl(raw)) }
    }

    @Test
    fun maliciousRestoredPumMediaNeverReachesPreviewElements() {
        val card = """
            <section class="armbandbot-pum-card" data-status="RESOLVED" data-source-key="MI/safe/1">
              <div class="armbandbot-pum-body">
                <p><img src="http://dcimg5.dcinside.com/plain.jpg"></p>
                <p><img src="https://127.0.0.1/private"></p>
                <p><img src="https://images.dcinside.com.evil.test/lookalike.jpg"></p>
                <p><img src="https://images.dcinside.com/good.jpg"></p>
              </div>
            </section>
        """.trimIndent()

        val preview = parseSnapshot(writeSnapshot(snapshotHtml(card = card)).path).pumPreview!!

        assertEquals("https://images.dcinside.com/good.jpg", preview.thumbnailUrl)
        assertEquals(
            listOf(BodyElement.ImageElement("https://images.dcinside.com/good.jpg")),
            preview.bodyElements,
        )
    }

    @Test
    fun hugePumBodyIsDeterministicallyBoundedAndMarkedTruncated() {
        val paragraphs = (1..(SNAPSHOT_PUM_MAX_BODY_ELEMENTS + 20)).joinToString("") {
            "<p>${"x".repeat(500)}</p>"
        }
        val images = (1..(SNAPSHOT_PUM_MAX_MEDIA_URLS + 20)).joinToString("") {
            "<img src=\"https://dcimg5.dcinside.com/image/$it.jpg\">"
        }
        val card = """
            <section class="armbandbot-pum-card" data-status="RESOLVED" data-source-key="MI/safe/1">
              <div class="armbandbot-pum-body">$images$paragraphs</div>
            </section>
        """.trimIndent()

        val preview = parseSnapshot(writeSnapshot(snapshotHtml(card = card)).path).pumPreview!!
        val mediaCount = preview.bodyElements.sumOf {
            when (it) {
                is BodyElement.ImageElement -> 1
                is BodyElement.DcconRowElement -> it.urls.size
                is BodyElement.TextElement -> 0
            }
        }
        val textChars = preview.bodyElements.filterIsInstance<BodyElement.TextElement>().sumOf { it.text.length }

        assertTrue(preview.bodyTruncated)
        assertTrue(preview.bodyElements.size <= SNAPSHOT_PUM_MAX_BODY_ELEMENTS)
        assertTrue(mediaCount <= SNAPSHOT_PUM_MAX_MEDIA_URLS)
        assertTrue(textChars <= SNAPSHOT_PUM_MAX_TEXT_CHARS)
        assertEquals("https://dcimg5.dcinside.com/image/1.jpg", preview.thumbnailUrl)
    }

    @Test
    fun expandedPumBodyDoesNotComposeRepresentativeMediaTwice() {
        val preview = SnapshotPumPreview(
            status = PumSourceStatus.RESOLVED,
            thumbnailUrl = "https://dcimg5.dcinside.com/dccon.php?no=A",
            bodyElements = listOf(
                BodyElement.DcconRowElement(
                    listOf(
                        "https://dcimg5.dcinside.com/dccon.php?no=A",
                        "https://dcimg5.dcinside.com/dccon.php?no=B",
                    )
                ),
                BodyElement.TextElement("본문"),
            ),
        )

        assertEquals(
            listOf(
                BodyElement.ImageElement("https://dcimg5.dcinside.com/dccon.php?no=B", isDccon = true),
                BodyElement.TextElement("본문"),
            ),
            pumExpandedBodyElements(preview),
        )
    }

    @Test
    fun legacySnapshotAndCommentMetadataRemainUnchangedWithoutPumCard() {
        val html = snapshotHtml(outerBody = "<p>레거시 본문</p>", card = "", title = "레거시 제목")
            .replace("</body>", """
                <ul class="cmt_list"><li id="comment_li_9" class="ub-content"><div class="cmt_info">
                  <span class="gall_writer" data-nick="댓글러" data-ip="1.2"></span><span class="date_time">13:20</span>
                  <p class="usertxt">길이 회귀 댓글</p>
                </div></li></ul></body>
            """.trimIndent())
        val parsed = parseSnapshot(writeSnapshot(html).path)

        assertNull(parsed.pumPreview)
        assertEquals("레거시 제목", parsed.title)
        assertEquals("2026.06.28 12:00:00", parsed.date)
        assertEquals(listOf(BodyElement.TextElement("레거시 본문")), parsed.bodyElements)
        assertEquals("댓글러(1.2)", parsed.comments.single().author)
        assertEquals("길이 회귀 댓글", parsed.comments.single().content)
    }

    @Test
    fun numberedInitialAndLatestFilesParseIndependentPumSources() {
        val dir = kotlin.io.path.createTempDirectory("viewer_versions").toFile()
        val initial = File(dir, "armbandbot_244_initial_2.html")
        val latest = File(dir, "armbandbot_244_latest_2.html")
        initial.writeText(snapshotHtml(card = resolvedCard("MI/source_a/1", "source_a", "원문 A")))
        latest.writeText(snapshotHtml(card = resolvedCard("M/source_b/2", "source_b", "원문 B")))

        val paths = deriveSnapshotVersionPaths(latest.path)!!
        assertEquals("원문 A", parseSnapshot(paths.initialPath).pumPreview!!.title)
        assertEquals(PostKey("MI", "source_a", "1"), parseSnapshot(paths.initialPath).pumPreview!!.sourceKey)
        assertEquals("원문 B", parseSnapshot(paths.latestPath).pumPreview!!.title)
        assertEquals(PostKey("M", "source_b", "2"), parseSnapshot(paths.latestPath).pumPreview!!.sourceKey)
        dir.deleteRecursively()
    }

    @Test
    fun numberedInitialAndLatestPathsArePairedForSwitching() {
        val initial = File("/cache/snapshots_imported/armbandbot_244_initial_2.html")
        val latest = File("/cache/snapshots_imported/armbandbot_244_latest_2.html")

        assertEquals(SnapshotVersionPaths(initial.path, latest.path), deriveSnapshotVersionPaths(initial.path))
        assertEquals(SnapshotVersionPaths(initial.path, latest.path), deriveSnapshotVersionPaths(latest.path))
    }

    @Test
    fun parseSnapshotKeepsBodyAndCommentDcconsAsImageUrls() {
        val html = """
            <html><body>
                <div class="title_subject">제목</div>
                <div class="gall_writer" data-nick="작성자" data-uid="user1"></div>
                <span class="gall_date" title="2026.06.28 12:00:00"></span>
                <span class="gall_count">조회 1</span>
                <div class="write_div">
                    <p>본문 앞</p>
                    <p><img class="written_dccon" src="https://dcimg5.dcinside.com/dccon.php?no=BODYTOKEN" alt="본문콘"><img class="written_dccon" src="https://dcimg5.dcinside.com/dccon.php?no=BODYTOKEN" alt="본문콘"></p>
                    <p>본문 뒤</p>
                </div>
                <ul class="cmt_list">
                    <li id="comment_li_1" class="ub-content">
                        <div class="cmt_info">
                            <span class="gall_writer" data-nick="댓글러" data-uid="u2"></span>
                            <span class="date_time">12:01</span>
                            <p class="usertxt">댓글</p>
                            <img class="written_dccon" src="https://dcimg5.dcinside.com/dccon.php?no=COMMENTTOKEN" alt="댓글콘">
                        </div>
                    </li>
                </ul>
            </body></html>
        """.trimIndent()
        val file = File.createTempFile("snapshot_dccon", ".html").apply { writeText(html) }

        val parsed = parseSnapshot(file.absolutePath)

        val bodyDcconRow = parsed.bodyElements.filterIsInstance<BodyElement.DcconRowElement>().single()
        assertEquals(2, bodyDcconRow.urls.size)
        assertTrue(bodyDcconRow.urls.all { it == "https://dcimg5.dcinside.com/dccon.php?no=BODYTOKEN" })
        assertEquals(listOf("https://dcimg5.dcinside.com/dccon.php?no=COMMENTTOKEN"), parsed.comments.single().dcconUrls)
    }

    private fun writeSnapshot(html: String): File =
        File.createTempFile("snapshot_viewer", ".html").apply { writeText(html) }

    private fun snapshotHtml(
        outerBody: String = "<p>바깥 본문</p>",
        card: String = "",
        title: String = "바깥 제목",
    ) = """
        <html><body>
          <div class="title_subject">$title</div>
          <div class="gall_writer" data-nick="작성자" data-uid="outer"></div>
          <span class="gall_date" title="2026.06.28 12:00:00"></span>
          <span class="gall_count">조회 17</span>
          <div class="write_div">$outerBody$card</div>
        </body></html>
    """.trimIndent()

    private fun resolvedCard(key: String, gallery: String, title: String) = """
        <section class="armbandbot-pum-card" data-status="RESOLVED" data-source-key="$key">
          <div class="armbandbot-pum-gallery">$gallery 갤러리</div>
          <h3 class="armbandbot-pum-title">$title</h3>
          <div class="armbandbot-pum-body"><p>$title 본문</p></div>
        </section>
    """.trimIndent()
}
