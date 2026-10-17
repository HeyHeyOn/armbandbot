package com.heyheyon.armbandbot

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

internal fun recommendationDetail(mode: String, type: String = "MI", no: String = "123") = Jsoup.parse("""
    <input id="no" value="$no"><input id="gallery_id" value="fixture"><div class="write_div">본문</div>
    <script id="${if(type == "M") "minor" else "mini"}_manager_view_buttons-tmpl" type="text/x-jquery-tmpl">
    <div class="mng_subject_sel"><li onclick="update_recom('$mode', $no);">관리</li></div></script>
""", "https://gall.dcinside.com/")

class RecommendationReleaseClientTest {
    private val key = PostKey("MI", "fixture", "123")
    @Test fun readsOfficialTemplateForBothGalleryKinds() {
        for (type in listOf("M", "MI")) for (mode in listOf("REL", "SET")) {
            assertEquals(if(mode == "REL") RecommendationState.RECOMMENDED else RecommendationState.ORDINARY,
                recommendationState(recommendationDetail(mode, type), key.copy(gallType = type)))
        }
    }
    @Test fun refusesWrongPostMissingPermissionAndUserPastedControls() {
        assertEquals(RecommendationState.UNKNOWN, recommendationState(recommendationDetail("REL", no = "124"), key))
        val doc = recommendationDetail("REL"); doc.select("script").remove()
        assertEquals(RecommendationState.UNKNOWN, recommendationState(doc, key))
        doc.selectFirst(".write_div")!!.append("""<div class="mng_subject_sel"><li onclick="update_recom('REL', 123)">가짜</li></div>""")
        assertEquals(RecommendationState.UNKNOWN, recommendationState(doc, key))
    }
    @Test fun successfulReleaseRequiresPositiveResponseAndOrdinaryReadback() {
        var gets = 0; var posts = 0; var claims = 0
        val client = RecommendationReleaseClient("ci_c=fixture", {}, { _, kind, call -> claims++; assertEquals("RECOMMEND_RELEASE", kind); call() },
            get = { recommendationDetail(if (gets++ == 0) "REL" else "SET") }, post = { _, payload -> posts++; assertEquals("REL", payload["mode"]); "{\"result\":\"success\"}" })
        assertEquals("개념글 해제 성공", client.release(key, {}) { false })
        assertEquals(2, gets); assertEquals(1, posts); assertEquals(1, claims)
    }
    @Test fun ordinaryAndProtectedPostsNeverAcquireClaimOrMutate() {
        for (protected in listOf(false, true)) {
            val client = RecommendationReleaseClient("ci_c=fixture", {}, { _, _, _ -> error("must not claim") },
                get = { recommendationDetail(if (protected) "REL" else "SET") }, post = { _, _ -> error("must not post") })
            assertEquals(if(protected) "skipped" else "ordinary", client.release(key, {}) { protected })
        }
    }
    @Test fun failedVerificationDoesNotBecomeSuccessOrRetry() {
        var posts = 0
        val client = RecommendationReleaseClient("ci_c=fixture", {}, { _, _, call -> call() },
            get = { recommendationDetail("REL") }, post = { _, _ -> posts++; "{\"result\":\"success\"}" })
        assertTrue(client.release(key, {}) { false }.contains("미확인")); assertEquals(1, posts)
    }
    @Test fun ambiguousServerResponseDoesNotRetry() {
        var gets = 0; var posts = 0
        val client = RecommendationReleaseClient("ci_c=fixture", {}, { _, _, call -> call() },
            get = { gets++; recommendationDetail("REL") }, post = { _, _ -> posts++; "<html>login</html>" })
        assertTrue(client.release(key, {}) { false }.contains("미확인")); assertEquals(1, gets); assertEquals(1, posts)
    }
    @Test fun competingBotClaimPreventsSecondMutation() {
        val client = RecommendationReleaseClient("ci_c=fixture", {}, { _, _, _ -> "{\"result\":\"skipped\"}" },
            get = { recommendationDetail("REL") }, post = { _, _ -> error("duplicate") })
        assertEquals("skipped", client.release(key, {}) { false })
    }
    @Test fun disabledAfterDetailReadNeverSendsRelease() {
        var active = true
        val client = RecommendationReleaseClient("ci_c=fixture", {}, { _, _, call -> call() },
            get = { active = false; recommendationDetail("REL") }, post = { _, _ -> error("disabled") })
        assertTrue(runCatching { client.release(key, { check(active) }) { false } }.isFailure)
    }
}
