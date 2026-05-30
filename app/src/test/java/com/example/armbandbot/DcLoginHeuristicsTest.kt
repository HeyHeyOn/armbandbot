package com.heyheyon.armbandbot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DcLoginHeuristicsTest {
    @Test
    fun passwordChangeCampaignIsNotFinalLoginSuccess() {
        val text = """
            비밀번호 규칙이 변경되었습니다.
            보안을 위해 비밀번호를 변경해 주시길 바랍니다.
            비밀번호 변경
            다음에 변경
            30일 후 변경
        """.trimIndent()

        assertTrue(DcLoginHeuristics.isPasswordChangeCampaign(text))
        assertFalse(DcLoginHeuristics.isFinalLoggedInPage(text, cookieHeader = "ci_c=abc"))
    }

    @Test
    fun ciCookieAloneIsNotLoginEvidence() {
        val anonymousHome = "디시인사이드 전체보기 로그인 검색어 입력 최근 방문 갤러리"

        assertFalse(DcLoginHeuristics.isFinalLoggedInPage(anonymousHome, cookieHeader = "ci_c=abc; PHPSESSID=def"))
    }

    @Test
    fun logoutTextIsStrongLoginEvidence() {
        val loggedInHome = "디시인사이드 전체보기 유저메뉴 MY갤로그 고정닉정보 로그아웃 검색어 입력"

        assertTrue(DcLoginHeuristics.isFinalLoggedInPage(loggedInHome, cookieHeader = "ci_c=abc"))
    }

    @Test
    fun findsDeferPasswordChangeJavascriptCandidates() {
        val text = "비밀번호 변경 다음에 변경 30일 후 변경"
        val script = DcLoginHeuristics.passwordChangeDeferClickScript(text)

        assertTrue(script.contains("30일 후 변경"))
        assertTrue(script.contains("다음에 변경"))
        assertTrue(script.contains("click"))
    }
}
