package com.heyheyon.armbandbot

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class PumParserTest {
    private fun fixture(name: String) = javaClass.getResource("/pum/$name")!!.readText()

    @Test fun `structural marker is exact and title text or footer button is ignored`() {
        val pum = Jsoup.parse(fixture("pum_detail.html"))
        assertTrue(PumParser.hasListMarker(pum))
        val normal = Jsoup.parse(fixture("normal_title_contains_pum.html"))
        assertFalse(PumParser.hasListMarker(normal))
    }

    @Test fun `detail loader and marker produce confirmed detection`() {
        val detection = PumParser.parseDetail(Jsoup.parse(fixture("pum_detail.html")), listMarker = true)
        assertEquals(PumDetectionStatus.PUM_CONFIRMED, detection.status)
        assertEquals(PostKey("M", "laboratory1", "900"), detection.loader?.outerPost)
        assertEquals("https://gall.dcinside.com/ajax/pum_ajax/get_contents", detection.loader?.endpoint)
    }

    @Test fun `loader request data may safely reference literal javascript variables`() {
        val html = """<div class='write_div'><script>
            var gall_id = 'variable_gall'; var gall_no = '42'; var gall_type = 'G';
            $.ajax({url:'/ajax/pum_ajax/get_contents', data:{id:gall_id,no:gall_no,gallery_type:gall_type}});
        </script></div>"""
        val loader = PumParser.parseDetail(Jsoup.parse(html), false).loader
        assertEquals(PostKey("G", "variable_gall", "42"), loader?.outerPost)
    }

    @Test fun `marker-only and loader-only remain distinguishable`() {
        val normal = Jsoup.parse(fixture("normal_title_contains_pum.html"))
        assertEquals(PumDetectionStatus.PUM_MARKER_ONLY, PumParser.parseDetail(normal, true).status)
        val loader = Jsoup.parse(fixture("pum_detail.html"))
        assertEquals(PumDetectionStatus.PUM_LOADER_ONLY, PumParser.parseDetail(loader, false).status)
    }

    @Test fun `card parses final canonical source and missing notice`() {
        val resolved = PumParser.parseCard(fixture("pum_card_resolved.html"), PostKey("M", "laboratory1", "900"))
        assertEquals(PumCardStatus.RESOLVED, resolved.status)
        assertEquals(PostKey("MI", "tinygallery", "12345"), resolved.sourceKey)
        assertEquals("https://gall.dcinside.com/mini/board/view/?id=tinygallery&no=12345", resolved.sourceUrl)
        assertEquals(PumCardStatus.MISSING, PumParser.parseCard(fixture("pum_card_missing.html"), null).status)
    }

    @Test fun `regular minor mini urls normalize with correct referers`() {
        val cases = mapOf(
            "https://gall.dcinside.com/board/view/?id=test&no=1" to PostKey("G", "test", "1"),
            "https://gall.dcinside.com/mgallery/board/view/?id=test_m&no=2" to PostKey("M", "test_m", "2"),
            "https://gall.dcinside.com/mini/board/view/?id=test-mi&no=3" to PostKey("MI", "test-mi", "3")
        )
        cases.forEach { (url, key) ->
            val safe = DcinsidePostUrls.parseSafeCanonicalPostUrl(url, null)
            assertEquals(key, safe?.key)
            assertEquals(url, safe?.url)
            assertEquals(url, safe?.refererUrl)
        }
    }

    @Test fun `unsafe malformed and self source urls are rejected`() {
        val outer = PostKey("M", "laboratory1", "900")
        listOf(
            "http://gall.dcinside.com/board/view/?id=x&no=1",
            "https://evil.example/board/view/?id=x&no=1",
            "https://gall.dcinside.com.evil.example/board/view/?id=x&no=1",
            "https://gall.dcinside.com:444/board/view/?id=x&no=1",
            "https://gall.dcinside.com/board/lists/?id=x&no=1",
            "not a url",
            "https://gall.dcinside.com/mgallery/board/view/?id=laboratory1&no=900"
        ).forEach { assertNull(it, DcinsidePostUrls.parseSafeCanonicalPostUrl(it, outer)) }
    }

    @Test fun `repum card points directly at ultimate original`() {
        val card = PumParser.parseCard(fixture("repum_points_to_original.html"), PostKey("G", "outer", "8"))
        assertEquals(PostKey("G", "ultimate", "77"), card.sourceKey)
    }
}
