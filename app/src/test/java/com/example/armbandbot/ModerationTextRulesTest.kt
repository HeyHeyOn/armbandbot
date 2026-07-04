package com.heyheyon.armbandbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModerationTextRulesTest {
    @Test
    fun nicknameBypassBlacklistDetectsCharactersInsertedAroundKeyword() {
        val match = ModerationTextRules.findNicknameBypassMatch(
            nickname = "xx분♡탕yy",
            blockedNicknames = listOf("분탕")
        )

        assertEquals("분탕", match)
    }

    @Test
    fun nicknameExactBlacklistAndWhitelistRemainSeparate() {
        assertTrue(ModerationTextRules.isExactNicknameBlacklisted("분탕", listOf("분탕")))
        assertFalse(ModerationTextRules.isExactNicknameBlacklisted("xx분탕", listOf("분탕")))
        assertNull(
            ModerationTextRules.findNicknameBypassMatch(
                nickname = "분탕",
                blockedNicknames = listOf("분탕"),
                whitelistedNicknames = listOf("분탕")
            )
        )
    }

    @Test
    fun specialCharacterFilterAllowsKoreanLatinDigitsSpacesAndCommonPunctuation() {
        val text = "안녕하세요 ㄱㅎ ㅏㅣ ABC xyz 123 .,‘’-\"“”@&₩)(;:/[]{}#%^*+=\\_|~<>$£¥•\n"

        assertNull(ModerationTextRules.findDisallowedSpecialCharacter(text, emptySet()))
    }

    @Test
    fun specialCharacterFilterBlocksEmojiZeroWidthAndUnsupportedScripts() {
        assertEquals("😀", ModerationTextRules.findDisallowedSpecialCharacter("안녕😀", emptySet()))
        assertEquals("​", ModerationTextRules.findDisallowedSpecialCharacter("분​탕", emptySet()))
        assertEquals("あ", ModerationTextRules.findDisallowedSpecialCharacter("あ", emptySet()))
    }

    @Test
    fun specialCharacterWhitelistAllowsAdditionalCharacters() {
        assertNull(ModerationTextRules.findDisallowedSpecialCharacter("안녕😀", setOf("😀")))
    }
}
