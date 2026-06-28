package com.heyheyon.armbandbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DcconFilterTest {
    @Test
    fun extractsImageDcconTokenUrlAndLabelFromPostHtml() {
        val html = """
            <div class="write_div">
                <img class="written_dccon" src="https://dcimg5.dcinside.com/dccon.php?no=ABC123" conalt="찐" alt="찐" title="찐" detail="5678865">
            </div>
        """.trimIndent()

        val refs = DcconFilter.extractDcconRefs(html)

        assertEquals(1, refs.size)
        assertEquals("ABC123", refs[0].token)
        assertEquals("https://dcimg5.dcinside.com/dccon.php?no=ABC123", refs[0].url)
        assertEquals("찐", refs[0].label)
    }

    @Test
    fun extractsVideoDcconDataSrcAndNestedSourceTokensFromCommentMemo() {
        val html = """
            <video class="written_dccon" data-src="https://dcimg5.dcinside.com/dccon.php?no=VIDEOBASE" conalt="7" alt="7" title="7">
                <source src="https://dcimg5.dcinside.com/dccon.php?no=VIDEOBASEEXTRA" type="video/mp4">
            </video>
        """.trimIndent()

        val refs = DcconFilter.extractDcconRefs(html)

        assertEquals(listOf("VIDEOBASE", "VIDEOBASEEXTRA"), refs.map { it.token })
        assertEquals("7", refs.first().label)
    }

    @Test
    fun extractsDcconTokenEmbeddedInEventAttribute() {
        val html = """
            <span onmousedown="mp4_overlay_dccon('https://dcimg5.dcinside.com/dccon.php?no=EVENTTOKEN&amp;foo=bar')">재생</span>
        """.trimIndent()

        val refs = DcconFilter.extractDcconRefs(html)

        assertEquals(1, refs.size)
        assertEquals("EVENTTOKEN", refs[0].token)
    }

    @Test
    fun ignoresNormalImagesAndShortLabels() {
        val html = """
            <img src="https://dcimg5.dcinside.com/viewimage.php?id=test" alt="찐" title="찐">
        """.trimIndent()

        assertTrue(DcconFilter.extractDcconRefs(html).isEmpty())
        assertNull(DcconFilter.findBlockedDccon(html, listOf("찐")))
    }

    @Test
    fun normalizesFullPartialAndTokenOnlyBlacklistEntries() {
        assertEquals("ABC123", DcconFilter.normalizeBlacklistEntry("https://dcimg5.dcinside.com/dccon.php?no=ABC123&x=1"))
        assertEquals("ABC123", DcconFilter.normalizeBlacklistEntry("dcimg5.dcinside.com/dccon.php?no=ABC123"))
        assertEquals("ABC123", DcconFilter.normalizeBlacklistEntry("dccon.php?no=ABC123"))
        assertEquals("ABC123", DcconFilter.normalizeBlacklistEntry("ABC123"))
        assertNull(DcconFilter.normalizeBlacklistEntry(""))
    }

    @Test
    fun blocksByTokenButNotByLabel() {
        val html = "<img class=\"written_dccon\" src=\"https://dcimg5.dcinside.com/dccon.php?no=ABC123\" alt=\"찐\">"

        val blocked = DcconFilter.findBlockedDccon(html, listOf("ABC123"))
        val labelOnly = DcconFilter.findBlockedDccon(html, listOf("찐"))

        assertNotNull(blocked)
        assertEquals("ABC123", blocked!!.ref.token)
        assertNull(labelOnly)
    }

    @Test
    fun allowsLongPrefixMatchForVideoDcconButRejectsShortPrefixMatch() {
        val base = "1234567890123456789012345678901234567890"
        val html = "<source src=\"https://dcimg5.dcinside.com/dccon.php?no=${base}EXTRA\" type=\"video/mp4\">"

        assertNotNull(DcconFilter.findBlockedDccon(html, listOf(base)))
        assertNull(DcconFilter.findBlockedDccon(html, listOf("ABC")))
        assertFalse(DcconFilter.tokensMatch("ABC", "ABCDEF"))
    }

    @Test
    fun extractsDcconRefsFromDcinsideCommentApiMemos() {
        val json = """
            {
              "comments": [
                {"no":"1","memo":"<img class=\\\"written_dccon \\\" src=\\\"https:\\\\/\\\\/dcimg5.dcinside.com\\\\/dccon.php?no=COMMENT1\\\" alt=\\\"찐\\\">"},
                {"no":"2","memo":"<span>일반 댓글</span>"},
                {"no":"3","memo":"<video class=\\\"written_dccon\\\" data-src=\\\"https:\\\\/\\\\/dcimg5.dcinside.com\\\\/dccon.php?no=COMMENT2\\\" title=\\\"움짤\\\"></video>"}
              ]
            }
        """.trimIndent()

        val refs = DcconFilter.extractDcconRefsFromCommentApiJson(json)

        assertEquals(listOf("COMMENT1", "COMMENT2"), refs.map { it.token })
        assertEquals(listOf("comment:1", "comment:3"), refs.map { it.source })
    }

    @Test
    fun parsesPackageDetailJsonAndExpandsAllTokensForBlacklist() {
        val json = """
            {
              "info": {"package_idx":"27107", "title":"핑구콘", "icon_cnt":"2"},
              "detail": [
                {"title":"1", "path":"TOKEN1"},
                {"title":"2", "path":"TOKEN2"},
                {"title":"중복", "path":"TOKEN1"}
              ]
            }
        """.trimIndent()

        val detail = DcconFilter.parsePackageDetailJson(json)
        val merged = DcconFilter.mergePackageTokensIntoBlacklist("TOKEN0 #기존", detail!!)

        assertEquals("27107", detail.packageIdx)
        assertEquals("핑구콘", detail.title)
        assertEquals(listOf("TOKEN1", "TOKEN2"), detail.tokens)
        assertEquals("TOKEN0 #기존\nTOKEN1 #핑구콘\nTOKEN2 #핑구콘", merged)
    }

    @Test
    fun addsSingleDcconWithResolvedKoreanPackageTitle() {
        val detail = DcconPackageDetail("27107", "핑구콘", listOf("TOKEN1", "TOKEN2"))

        val merged = DcconFilter.addSingleTokenWithPackageTitle("TOKEN0 #기존", "TOKEN1", detail)

        assertEquals("TOKEN0 #기존\nTOKEN1 #핑구콘", merged)
    }

    @Test
    fun parsesPackageDetailUnicodeEscapedKoreanTitle() {
        val json = """
            {
              "info": {"package_idx":"27107", "title":"\u005cud551\u005cuad6c\u005cucf58", "icon_cnt":"1"},
              "detail": [
                {"title":"1", "path":"TOKEN1"}
              ]
            }
        """.trimIndent()

        val detail = DcconFilter.parsePackageDetailJson(json)
        val merged = DcconFilter.addSingleTokenWithPackageTitle("", "TOKEN1", detail)

        assertEquals("핑구콘", detail!!.title)
        assertEquals("TOKEN1 #핑구콘", merged)
    }

    @Test
    fun packageMergeUpdatesExistingUngroupedTokenWithoutCreatingDuplicates() {
        val detail = DcconPackageDetail("27107", "핑구콘", listOf("TOKEN1", "TOKEN2", "TOKEN3"))

        val merged = DcconFilter.mergePackageTokensIntoBlacklist("TOKEN1\nTOKEN2 #OLD27107", detail)

        assertEquals("TOKEN1 #핑구콘\nTOKEN2 #핑구콘\nTOKEN3 #핑구콘", merged)
    }

    @Test
    fun detectsBlockedDcconTokensForExtractionResultState() {
        val existing = "TOKEN1 #핑구콘\nhttps://dcimg5.dcinside.com/dccon.php?no=TOKEN2 #뭐야콘"

        assertTrue(DcconFilter.isTokenBlocked(existing, "TOKEN1"))
        assertTrue(DcconFilter.isTokenBlocked(existing, "TOKEN2"))
        assertFalse(DcconFilter.isTokenBlocked(existing, "TOKEN3"))
    }

    @Test
    fun extractsImageAltsWithoutDcconImagesSeparatelyFromDcconUrls() {
        val html = """
            <div class="write_div">
                <img src="https://example.com/normal.jpg" alt="광고이미지">
                <img class="written_dccon" src="https://dcimg5.dcinside.com/dccon.php?no=DCON" alt="디시콘라벨" conalt="디시콘라벨">
            </div>
        """.trimIndent()

        assertEquals(listOf("광고이미지"), DcconFilter.extractImageAltRefs(html))
        assertEquals(
            listOf(ImageAltRef("광고이미지", "https://example.com/normal.jpg")),
            DcconFilter.extractImageAltImageRefs(html)
        )
        assertEquals(listOf("DCON"), DcconFilter.extractDcconRefs(html).map { it.token })
    }

    @Test
    fun imageAltBlacklistEntriesKeepPreviewUrlButMatchOnlyAltText() {
        val existing = "광고이미지 #https://example.com/a.jpg\n텍스트만"

        val merged = DcconFilter.addImageAltBlacklistEntries(existing, "광고이미지 #https://example.com/new.jpg\n신규이미지 #https://example.com/b.jpg")

        assertEquals("광고이미지 #https://example.com/a.jpg\n텍스트만\n신규이미지 #https://example.com/b.jpg", merged)
        assertEquals(listOf("광고이미지", "텍스트만", "신규이미지"), DcconFilter.imageAltBlacklistValues(merged))
        assertEquals("광고이미지", DcconFilter.imageAltMatchValue("광고이미지 #https://example.com/a.jpg"))
    }

    @Test
    fun removesImageAltBlacklistEntriesByAltTextWithoutDependingOnPreviewUrl() {
        val existing = "광고이미지 #https://example.com/a.jpg\n텍스트만\n신규이미지 #https://example.com/b.jpg"

        val removed = DcconFilter.removeImageAltBlacklistEntries(existing, setOf("광고이미지", "신규이미지 #https://example.com/b.jpg"))

        assertEquals("텍스트만", removed)
    }

    @Test
    fun parsesMobileAndDesktopPostUrlsIntoDesktopLocators() {
        assertEquals(
            DcPostLocator("laboratory1", "2284", "M", "https://gall.dcinside.com/mgallery/board/view/?id=laboratory1&no=2284"),
            DcinsidePostUrls.parsePostLocator("https://m.dcinside.com/board/laboratory1/2284")
        )
        assertEquals(
            DcPostLocator("baseball_new11", "123", "G", "https://gall.dcinside.com/board/view/?id=baseball_new11&no=123"),
            DcinsidePostUrls.parsePostLocator("https://gall.dcinside.com/board/view/?id=baseball_new11&no=123")
        )
        assertEquals(
            DcPostLocator("miniroom", "55", "MI", "https://gall.dcinside.com/mini/board/view/?id=miniroom&no=55"),
            DcinsidePostUrls.parsePostLocator("https://gall.dcinside.com/mini/board/view/?id=miniroom&no=55")
        )
    }

    @Test
    fun buildsDcconImageUrlAndEditsBlacklistTokenListsWithoutLosingComments() {
        assertEquals("https://dcimg5.dcinside.com/dccon.php?no=ABC123", DcconFilter.buildImageUrl("ABC123"))

        val original = "ABC123 #기존\nDEF456 #다른콘"
        val added = DcconFilter.addBlacklistEntries(original, "https://dcimg5.dcinside.com/dccon.php?no=ABC123\nGHI789")
        assertEquals("ABC123 #기존\nDEF456 #다른콘\nGHI789", added)

        val removed = DcconFilter.removeBlacklistTokens(added, setOf("DEF456", "GHI789"))
        assertEquals("ABC123 #기존", removed)
    }

    @Test
    fun groupsBlockedDcconsByPackageNameCommentWithRepresentativeToken() {
        val text = """
            PINGU1 #핑구콘
            SOLO1
            PINGU2 #핑구콘
            MOYA1 #뭐야콘
        """.trimIndent()

        val groups = DcconFilter.groupBlacklistEntries(text)

        assertEquals(listOf("핑구콘", "개별 디시콘", "뭐야콘"), groups.map { it.packageName })
        assertEquals(listOf("PINGU1", "PINGU2"), groups[0].tokens)
        assertEquals("PINGU1", groups[0].representativeToken)
        assertFalse(groups[0].isUngrouped)
        assertEquals(listOf("SOLO1"), groups[1].tokens)
        assertTrue(groups[1].isUngrouped)
    }

    @Test
    fun togglesAllVisibleDcconTokensWithoutLosingOtherSelections() {
        val visibleTokens = listOf("A", "B", "C")

        assertEquals(
            setOf("OLD", "A", "B", "C"),
            DcconFilter.toggleVisibleTokenSelection(currentSelection = setOf("OLD", "A"), visibleTokens = visibleTokens, selectAll = true)
        )
        assertEquals(
            setOf("OLD"),
            DcconFilter.toggleVisibleTokenSelection(currentSelection = setOf("OLD", "A", "B"), visibleTokens = visibleTokens, selectAll = false)
        )
    }
}
