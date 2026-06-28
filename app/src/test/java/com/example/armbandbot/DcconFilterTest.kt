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
}
