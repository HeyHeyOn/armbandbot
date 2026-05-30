package com.heyheyon.armbandbot

object DcLoginHeuristics {
    private val passwordChangeCampaignPhrases = listOf(
        "비밀번호 규칙이 변경",
        "비밀번호를 변경",
        "비밀번호 변경 캠페인",
        "30일 후 변경",
        "다음에 변경",
        "나중에 변경"
    )

    private val strongLoginPhrases = listOf(
        "로그아웃",
        "MY갤로그",
        "고정닉정보",
        "즐겨찾기 운영/가입",
        "스크랩 알림"
    )

    private val loginPagePhrases = listOf(
        "디시인사이드 로그인",
        "회원 로그인",
        "로그인이 필요합니다",
        "로그인 후 이용"
    )

    fun isPasswordChangeCampaign(text: String): Boolean {
        val normalized = normalizeText(text)
        return passwordChangeCampaignPhrases.count { normalized.contains(it, ignoreCase = true) } >= 2 ||
            (normalized.contains("비밀번호", ignoreCase = true) &&
                (normalized.contains("30일 후", ignoreCase = true) || normalized.contains("다음에 변경", ignoreCase = true)))
    }

    fun isLoginPageText(text: String): Boolean {
        val normalized = normalizeText(text)
        if (isPasswordChangeCampaign(normalized)) return false
        return loginPagePhrases.any { normalized.contains(it, ignoreCase = true) }
    }

    fun isFinalLoggedInPage(text: String, cookieHeader: String): Boolean {
        val normalized = normalizeText(text)
        if (isPasswordChangeCampaign(normalized)) return false
        if (strongLoginPhrases.any { normalized.contains(it, ignoreCase = true) }) return true

        val hasStrongerSessionCookie = listOf("dc_sess=", "user_id=", "mc_enc=", "unicro_id=")
            .any { cookieHeader.contains(it, ignoreCase = true) }
        val looksAnonymous = normalized.contains("로그인", ignoreCase = true) &&
            !normalized.contains("로그아웃", ignoreCase = true)
        return hasStrongerSessionCookie && !looksAnonymous
    }

    fun passwordChangeDeferClickScript(pageText: String = ""): String {
        val labels = if (pageText.contains("30일 후 변경")) {
            listOf("30일 후 변경", "다음에 변경", "나중에 변경")
        } else {
            listOf("다음에 변경", "나중에 변경", "30일 후 변경")
        }
        val labelArray = labels.joinToString(",") { "'${it.replace("'", "\\'")}'" }
        return """
            (function() {
              var labels = [$labelArray];
              var nodes = Array.prototype.slice.call(document.querySelectorAll('a,button,input[type="button"],input[type="submit"]'));
              for (var i = 0; i < labels.length; i++) {
                for (var j = 0; j < nodes.length; j++) {
                  var n = nodes[j];
                  var text = ((n.innerText || n.textContent || n.value || '') + '').trim();
                  if (text.indexOf(labels[i]) >= 0) {
                    n.click();
                    return 'clicked:' + labels[i];
                  }
                }
              }
              return 'not_found';
            })();
        """.trimIndent()
    }

    private fun normalizeText(text: String): String = text.replace(Regex("\\s+"), " ").trim()
}
