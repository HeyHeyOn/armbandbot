package com.heyheyon.armbandbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommentModerationTextTest {
    @Test
    fun mentionNicknameIsExcludedFromKeywordModerationText() {
        val memo = """
            <a href="javascript:focusComment(3541);" class="mention">@금지어닉</a> 안녕하세요
        """.trimIndent()

        val moderationText = CommentModerationText.extract(memo)

        assertEquals("안녕하세요", moderationText)
        assertFalse(moderationText.contains("금지어", ignoreCase = true))
    }

    @Test
    fun bodyKeywordAfterMentionRemainsInKeywordModerationText() {
        val memo = """
            <a href="javascript:focusComment(3541);" class="mention">@정상닉</a> 실제 금지어 입력
        """.trimIndent()

        val moderationText = CommentModerationText.extract(memo)

        assertTrue(moderationText.contains("금지어", ignoreCase = true))
    }

    @Test
    fun normalCommentTextIsUnchangedForKeywordModeration() {
        val memo = "일반 댓글 금지어"

        val moderationText = CommentModerationText.extract(memo)

        assertEquals("일반 댓글 금지어", moderationText)
    }
}
