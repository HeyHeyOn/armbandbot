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

        val nestedInTitle = Jsoup.parse("""<tr class='ub-content'><td class='gall_tit ub-word'><a href='/board/view/?id=x&no=1'><span class='title'><span class='font_blue009'>(펌)</span></span></a></td></tr>""")
        val nestedInFooter = Jsoup.parse("""<tr class='ub-content'><td class='gall_tit ub-word'><a href='/board/view/?id=x&no=1'>title</a><footer><span class='font_blue009'>(펌)</span></footer></td></tr>""")
        assertFalse(PumParser.hasListMarker(nestedInTitle))
        assertFalse(PumParser.hasListMarker(nestedInFooter))
    }

    @Test fun `list marker decision is scoped to the current title link`() {
        val document = Jsoup.parse("""
            <table>
              <tr class='ub-content'><td class='gall_tit ub-word'>
                <a class='first' href='/board/view/?id=x&amp;no=1'>ordinary</a>
              </td></tr>
              <tr class='ub-content'><td class='gall_tit ub-word'>
                <a class='second' href='/board/view/?id=x&amp;no=2'><span class='font_blue009'>(펌)</span> copied</a>
              </td></tr>
            </table>
        """.trimIndent())

        assertFalse(PumParser.hasListMarker(document.selectFirst("a.first")!!))
        assertTrue(PumParser.hasListMarker(document.selectFirst("a.second")!!))
    }

    @Test fun `live sibling b marker is accepted only beside the current title link`() {
        val document = Jsoup.parse("""
            <table>
              <tr class='ub-content'><td class='gall_tit ub-word'>
                <a class='pum' href='/mgallery/board/view/?id=laboratory1&amp;no=2317'>펌 테스트2</a><b class='font_blue009'>(펌)</b>
              </td></tr>
              <tr class='ub-content'><td class='gall_tit ub-word'>
                <a class='ordinary' href='/mgallery/board/view/?id=laboratory1&amp;no=2318'>일반 글</a><span>다른 요소</span>
              </td></tr>
            </table>
        """.trimIndent())

        assertTrue(PumParser.hasListMarker(document))
        assertTrue(PumParser.hasListMarker(document.selectFirst("a.pum")!!))
        assertFalse(PumParser.hasListMarker(document.selectFirst("a.ordinary")!!))
    }

    @Test fun `detail loader and marker produce confirmed detection`() {
        val detection = PumParser.parseDetail(Jsoup.parse(fixture("pum_detail.html")), listMarker = true)
        assertEquals(PumDetectionStatus.PUM_CONFIRMED, detection.status)
        assertEquals(PostKey("MI", "tinygallery", "12345"), detection.loader?.outerPost)
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

    @Test fun `live loader resolves url and data variables numeric no null token and blank type`() {
        val html = """<div class='write_div'><script>
            var u = "https://gall.dcinside.com/ajax/pum_ajax/get_contents";
            var data = {"ci_t":null,"_GALLTYPE_":"","id":"laboratory1","no":2315};
            ${'$'}.ajax({url:u,type:"POST",data:data});
        </script></div>"""

        val loader = PumParser.parseDetail(Jsoup.parse(html), false).loader

        assertNotNull(loader)
        assertEquals("laboratory1", loader?.sourceHint?.gallId)
        assertEquals("2315", loader?.sourceHint?.postNo)
        assertNull(loader?.sourceHint?.gallType)
        assertEquals("", loader?.formData?.get("ci_t"))
        assertEquals("", loader?.formData?.get("_GALLTYPE_"))
        assertEquals("2315", loader?.formData?.get("no"))
    }

    @Test fun `real detail loader decodes escaped slashes and uses the detail title marker`() {
        val html = """<html><body>
            <div class='gallview_head ub-content'>
              <h3 class='title ub-word'><span class='title_subject'>ㅍ 테스트</span><b class='font_blue009'>(펌)</b></h3>
            </div>
            <div class='write_div'><script>
              (function(I){
                var u="https:\/\/gall.dcinside.com\/ajax\/pum_ajax\/get_contents";
                var data={"ci_t":null,"_GALLTYPE_":"","id":"laboratory1","no":2315};
                ${'$'}.ajax({url:u,type:"POST",data:data,dataType:"html"});
              })("pum_container");
            </script></div>
        </body></html>""".trimIndent()

        val detection = PumParser.parseDetail(Jsoup.parse(html))

        assertEquals(PumDetectionStatus.PUM_CONFIRMED, detection.status)
        assertEquals("https://gall.dcinside.com/ajax/pum_ajax/get_contents", detection.loader?.endpoint)
        assertEquals("laboratory1", detection.loader?.sourceHint?.gallId)
        assertEquals("2315", detection.loader?.sourceHint?.postNo)
    }

    @Test fun `non invoked and unreachable function loaders remain non executable`() {
        val cases = listOf(
            """function decoy() {
              var u="https:\/\/gall.dcinside.com\/ajax\/pum_ajax\/get_contents";
              var data={id:'direct_decoy',no:999};
              ${'$'}.ajax({url:u,data:data});
            }""",
            """function decoy() {
              (function() {
                var u="https:\/\/gall.dcinside.com\/ajax\/pum_ajax\/get_contents";
                var data={id:'nested_decoy',no:998};
                ${'$'}.ajax({url:u,data:data});
              })();
            }""",
            """if (false) {
              (function() {
                var u="https:\/\/gall.dcinside.com\/ajax\/pum_ajax\/get_contents";
                var data={id:'conditional_decoy',no:997};
                ${'$'}.ajax({url:u,data:data});
              })();
            }""",
            """if (false) (function() {
              var u="https:\/\/gall.dcinside.com\/ajax\/pum_ajax\/get_contents";
              var data={id:'unbraced_decoy',no:996};
              ${'$'}.ajax({url:u,data:data});
            })();""",
            """if (false) ${'$'}.ajax({
              url:"https:\/\/gall.dcinside.com\/ajax\/pum_ajax\/get_contents",
              data:{id:'direct_conditional_decoy',no:995}
            });""",
        )

        cases.forEach { code ->
            val html = "<div class='write_div'><script>$code</script></div>"
            assertNull(code, PumParser.parseDetail(Jsoup.parse(html), false).loader)
        }
    }

    @Test fun `iife loader fails closed when the surrounding script is malformed`() {
        val valid = """(function() {
            var u="https:\/\/gall.dcinside.com\/ajax\/pum_ajax\/get_contents";
            var data={id:'laboratory1',no:2315};
            ${'$'}.ajax({url:u,data:data});
        })();""".trimIndent()

        listOf("$valid {", "$valid }", "$valid \"", "$valid /*", "$valid var = ;").forEach { malformed ->
            val html = "<div class='write_div'><script>$malformed</script></div>"
            assertNull(malformed, PumParser.parseDetail(Jsoup.parse(html), false).loader)
        }
    }

    @Test fun `top level loader rejects surrounding invalid statements`() {
        val valid = """var u="https:\/\/gall.dcinside.com\/ajax\/pum_ajax\/get_contents";
            var data={id:'laboratory1',no:2315};
            ${'$'}.ajax({url:u,data:data});
        """.trimIndent()

        listOf("$valid var = ;", "*/; $valid").forEach { code ->
            val html = "<div class='write_div'><script>$code</script></div>"
            assertNull(code, PumParser.parseDetail(Jsoup.parse(html), false).loader)
        }
    }

    @Test fun `top level iife allows block comment between wrapper and function`() {
        val html = """<div class='write_div'><script>
            ( /* DC wrapper comment */ function() {
              var u="https:\/\/gall.dcinside.com\/ajax\/pum_ajax\/get_contents";
              var data={id:'laboratory1',no:2315};
              ${'$'}.ajax({url:u,data:data});
            })();
        </script></div>""".trimIndent()

        assertEquals("2315", PumParser.parseDetail(Jsoup.parse(html), false).loader?.sourceHint?.postNo)
    }

    @Test fun `loader variables only resolve from executable top level assignments`() {
        val html = """<div class='write_div'><script>
            var gall_id = 'real_gallery'; var gall_no = '42'; var gall_type = 'M';
            function neverCalled() {
                gall_id = 'function_decoy'; gall_no = '900'; gall_type = 'G';
            }
            if (false) {
                gall_id = 'block_decoy'; gall_no = '901'; gall_type = 'MI';
            }
            class Decoy {
                method() { gall_id = 'class_decoy'; gall_no = '902'; }
            }
            var objectDecoy = { mutate: function() { gall_id = 'object_decoy'; gall_no = '903'; } };
            $.ajax({url:'/ajax/pum_ajax/get_contents', data:{id:gall_id,no:gall_no,gallery_type:gall_type}});
        </script></div>"""

        val loader = PumParser.parseDetail(Jsoup.parse(html), false).loader

        assertEquals(PostKey("M", "real_gallery", "42"), loader?.outerPost)
    }

    @Test fun `ambiguous top level assignment scope does not produce a loader`() {
        val html = """<div class='write_div'><script>
            var gall_id = 'real_gallery'; var gall_no = '42';
            if (unknown) { gall_id = 'ambiguous';
            $.ajax({url:'/ajax/pum_ajax/get_contents', data:{id:gall_id,no:gall_no}});
        </script></div>"""

        assertNull(PumParser.parseDetail(Jsoup.parse(html), false).loader)
    }

    @Test fun `member assignments are not treated as bare loader bindings`() {
        val html = """<div class='write_div'><script>
            var obj = {};
            obj.url = '/ajax/pum_ajax/get_contents';
            obj.data = {id:'member_gallery',no:'42'};
            $.ajax({url:url, data:data});
        </script></div>"""

        assertNull(PumParser.parseDetail(Jsoup.parse(html), false).loader)
    }

    @Test fun `assignments nested in calls parentheses and arrays are not loader bindings`() {
        val cases = listOf(
            "configure(url = '/ajax/pum_ajax/get_contents', data = {id:'call_gallery',no:'1'});",
            "(url = '/ajax/pum_ajax/get_contents'); (data = {id:'paren_gallery',no:'2'});",
            "[url = '/ajax/pum_ajax/get_contents', data = {id:'array_gallery',no:'3'}];"
        )

        cases.forEach { assignments ->
            val html = "<div class='write_div'><script>$assignments $.ajax({url:url,data:data});</script></div>"
            assertNull(assignments, PumParser.parseDetail(Jsoup.parse(html), false).loader)
        }
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

    @Test fun `classless direct card link resolves only when it matches source hint`() {
        val hint = PumSourceHint(null, "laboratory1", "2315")
        val valid = "<div class='cloned_card_body'><a href='/mgallery/board/view/?id=laboratory1&no=2315'>원문</a></div>"
        val mismatch = "<div class='cloned_card_body'><a href='/mgallery/board/view/?id=laboratory1&no=9999'>다른 글</a></div>"
        val nested = "<div class='cloned_card_body'><div><a href='/mgallery/board/view/?id=laboratory1&no=2315'>중첩 링크</a></div></div>"

        assertEquals(PumCardStatus.RESOLVED, PumParser.parseCard(valid, PostKey("M", "laboratory1", "2317"), hint).status)
        assertEquals(PumCardStatus.INVALID, PumParser.parseCard(mismatch, PostKey("M", "laboratory1", "2317"), hint).status)
        assertEquals(PumCardStatus.INVALID, PumParser.parseCard(nested, PostKey("M", "laboratory1", "2317"), hint).status)
        assertEquals(PumCardStatus.INVALID, PumParser.parseCard(valid, PostKey("M", "laboratory1", "2317"), null).status)
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

    @Test fun `card only trusts documented card body link and requires one distinct safe source`() {
        val valid = "https://gall.dcinside.com/board/view/?id=good&no=1"
        assertEquals(PumCardStatus.INVALID, PumParser.parseCard("<a href='$valid'>outside</a><div class='cloned_card_body'>broken</div>", null).status)
        assertEquals(PumCardStatus.RESOLVED, PumParser.parseCard("<div class='cloned_card_body'><a class='source_link' href='$valid'>source</a><a href='https://gall.dcinside.com/board/view/?id=benign&no=9'>related post</a></div>", null).status)
        assertEquals(PumCardStatus.INVALID, PumParser.parseCard("<div class='cloned_card_body'><a class='source_link' href='$valid'>one</a><a class='source_link' href='https://gall.dcinside.com/board/view/?id=other&no=2'>two</a></div>", null).status)
        assertEquals(PumCardStatus.INVALID, PumParser.parseCard("<div class='cloned_card_body'><a href='$valid'>canonical but not source</a></div>", null).status)
        assertEquals(PumCardStatus.INVALID, PumParser.parseCard("<div class='cloned_card_body'><a class='source_link' href='javascript:alert(1)'>malformed source</a><a href='$valid'>benign</a></div>", null).status)
        assertEquals(PumCardStatus.RESOLVED, PumParser.parseCard("<div class='cloned_card_body'><a class='source_link' href='$valid'>one</a><a class='source_link' href='$valid&from=copy'>duplicate</a></div>", null).status)
        listOf(
            "<html><form action='/login'>login</form></html>",
            "<div class='cloned_card_body'>server error</div>",
            "<div class='cloned_card_body'><a href='https://evil.example/board/view/?id=x&no=1'>bad</a></div>",
            "<div class='cloned_card_body'><a href='javascript:alert(1)'>bad</a></div>"
        ).forEach { assertEquals(it, PumCardStatus.INVALID, PumParser.parseCard(it, null).status) }
    }

    @Test fun `loader extraction binds data to pum ajax block and endpoint is exact`() {
        val adversarial = """<div class='write_div'><script>
            $.ajax({url:'/unrelated', data:{gall_id:'wrong',gall_no:'1',gall_type:'G'}, complete:function(){ return {nested:'}'}; }});
            $.ajax({url:'/ajax/pum_ajax/get_contents', data:{gall_id:'right',gall_no:'2',gall_type:'M'}});
        </script></div>"""
        assertEquals(PostKey("M", "right", "2"), PumParser.parseDetail(Jsoup.parse(adversarial), false).loader?.outerPost)
        listOf(
            "https://gall.dcinside.com/ajax/pum_ajax/get_contents?x=1",
            "https://gall.dcinside.com/ajax/pum_ajax/get_contents#x",
            "https://gall.dcinside.com:444/ajax/pum_ajax/get_contents"
        ).forEach { endpoint ->
            val html = "<div class='write_div'><script>$.ajax({url:'$endpoint',data:{gall_id:'x',gall_no:'1'}});</script></div>"
            assertNull(endpoint, PumParser.parseDetail(Jsoup.parse(html), false).loader)
        }
    }

    @Test fun `loader scanner ignores comments strings templates and commented assignments`() {
        val html = """<div class='write_div'><script>
            // $.ajax({url:'/ajax/pum_ajax/get_contents',data:{gall_id:'comment',gall_no:'1'}});
            /* ajax({url:'/ajax/pum_ajax/get_contents',data:{gall_id:'block',gall_no:'2'}}); */
            var decoy = "$.ajax({url:'/ajax/pum_ajax/get_contents',data:{gall_id:'string',gall_no:'3'}})";
            var template = `ajax({url:'/ajax/pum_ajax/get_contents',data:{gall_id:'template',gall_no:'4'}})`;
            // var gall_id = 'commented';
            /* var gall_no = '999'; */
            var gall_id = 'real'; var gall_no = '5';
            var assignmentText = "var gall_id = 'stringed'; var gall_no = '777';";
            var assignmentTemplate = `gall_id = 'templated'; gall_no = '888';`;
            $.ajax({url:'/ajax/pum_ajax/get_contents',data:{gall_id:gall_id,gall_no:gall_no,gall_type:'M'}});
        </script></div>"""
        assertEquals(PostKey("M", "real", "5"), PumParser.parseDetail(Jsoup.parse(html), false).loader?.outerPost)

        val fakeOnly = """<div class='write_div'><script>const text = "ajax({url:'/ajax/pum_ajax/get_contents',data:{gall_id:'fake',gall_no:'8'}})";</script></div>"""
        assertNull(PumParser.parseDetail(Jsoup.parse(fakeOnly), false).loader)
    }

    @Test fun `loader requires top level url and data options`() {
        val nestedUrl = """<div class='write_div'><script>
            $.ajax({url:'/unrelated', beforeSend:function(){ return {url:'/ajax/pum_ajax/get_contents'}; }, data:{gall_id:'fake',gall_no:'1'}});
        </script></div>"""
        val nestedData = """<div class='write_div'><script>
            $.ajax({url:'/ajax/pum_ajax/get_contents', callbacks:{data:{gall_id:'fake',gall_no:'2'}}});
        </script></div>"""
        assertNull(PumParser.parseDetail(Jsoup.parse(nestedUrl), false).loader)
        assertNull(PumParser.parseDetail(Jsoup.parse(nestedData), false).loader)
    }
}
