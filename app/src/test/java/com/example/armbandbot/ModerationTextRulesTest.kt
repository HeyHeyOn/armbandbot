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
    fun bypassKeywordKeepsLegacyUppercaseCaseSensitivityWhenToggleIsOff() {
        assertFalse(
            ModerationTextRules.matchesBypassKeyword(
                text = "광고 a.b.c.d1234",
                keyword = "ABCD1234",
                ignoreLatinCase = false,
                normalizeUnicode = false
            )
        )
    }

    @Test
    fun bypassKeywordIgnoresLatinCaseWhenToggleIsOn() {
        assertTrue(
            ModerationTextRules.matchesBypassKeyword(
                text = "광고 a.b-C.d1234",
                keyword = "ABCD1234",
                ignoreLatinCase = true,
                normalizeUnicode = false
            )
        )
    }

    @Test
    fun caseToggleDoesNotFoldNonLatinScripts() {
        assertFalse(
            ModerationTextRules.matchesBypassKeyword(
                text = "aа",
                keyword = "AА",
                ignoreLatinCase = true,
                normalizeUnicode = false
            )
        )
    }

    @Test
    fun unicodeToggleNormalizesFullwidthKeywordCharacters() {
        assertFalse(
            ModerationTextRules.matchesBypassKeyword(
                text = "ＡＢＣＤ1234",
                keyword = "ABCD1234",
                ignoreLatinCase = false,
                normalizeUnicode = false
            )
        )
        assertTrue(
            ModerationTextRules.matchesBypassKeyword(
                text = "ＡＢＣＤ1234",
                keyword = "ABCD1234",
                ignoreLatinCase = false,
                normalizeUnicode = true
            )
        )
    }

    @Test
    fun unicodeToggleDetectsCyrillicAndCherokeeHomoglyphs() {
        assertFalse(
            ModerationTextRules.matchesBypassKeyword(
                text = "신작 А.Ꮩ 빠르게 올라오는 곳",
                keyword = "AV",
                ignoreLatinCase = false,
                normalizeUnicode = false
            )
        )
        assertTrue(
            ModerationTextRules.matchesBypassKeyword(
                text = "신작 А.Ꮩ 빠르게 올라오는 곳",
                keyword = "AV",
                ignoreLatinCase = false,
                normalizeUnicode = true
            )
        )
    }

    @Test
    fun unicodeToggleRemovesFormatCharactersBeforeMatching() {
        assertEquals("AB", ModerationTextRules.normalizeBypassUnicode("A\u180EB"))
        assertEquals("AB", ModerationTextRules.normalizeBypassUnicode("A\u200BB"))
        assertEquals("AB", ModerationTextRules.normalizeBypassUnicode("A\u2060B"))
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
