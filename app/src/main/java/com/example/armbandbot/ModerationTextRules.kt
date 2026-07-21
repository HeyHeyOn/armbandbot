package com.heyheyon.armbandbot

import java.text.Normalizer

object ModerationTextRules {
    private val commonAllowedPunctuation: Set<Int> = setOf(
        '.', ',', '\'', '‘', '’', '-', '"', '“', '”', '@', '&', '₩', ')', '(', ';', ':', '/',
        '[', ']', '{', '}', '#', '%', '^', '*', '+', '=', '\\', '_', '|', '~', '<', '>', '$', '£', '¥', '•',
        '!', '?'
    ).map { it.code }.toSet()

    fun isExactNicknameBlacklisted(nickname: String, blockedNicknames: List<String>): Boolean {
        return blockedNicknames.any { it == nickname }
    }

    fun findNicknameBypassMatch(
        nickname: String,
        blockedNicknames: List<String>,
        whitelistedNicknames: List<String> = emptyList()
    ): String? {
        if (whitelistedNicknames.any { it == nickname }) return null
        return blockedNicknames
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .firstOrNull { buildBypassRegex(it).containsMatchIn(nickname) }
    }

    fun buildBypassRegex(keyword: String): Regex = buildBypassRegex(keyword, ignoreLatinCase = false)

    private fun buildBypassRegex(keyword: String, ignoreLatinCase: Boolean): Regex {
        val cleanedKeyword = keyword.trim()
        if (cleanedKeyword.isEmpty()) return Regex("$^")

        val hasKorean = cleanedKeyword.any { it in '가'..'힣' }
        val hasUpperLatin = cleanedKeyword.any { it in 'A'..'Z' }
        val separator = if (hasKorean) {
            "[^가-힣]*"
        } else {
            "[^가-힣A-Za-z0-9]*"
        }

        val pattern = cleanedKeyword
            .toCharArray()
            .joinToString(separator) { character ->
                if (ignoreLatinCase && character in 'A'..'Z') {
                    "[${character}${character.lowercaseChar()}]"
                } else if (ignoreLatinCase && character in 'a'..'z') {
                    "[${character}${character.uppercaseChar()}]"
                } else {
                    Regex.escape(character.toString())
                }
            }

        // Keep the legacy rule when the keyword has no uppercase Latin letters.
        // For forced Latin folding, explicit ASCII character classes avoid
        // applying Unicode-wide case folding to other scripts.
        val options = if (hasUpperLatin) emptySet() else setOf(RegexOption.IGNORE_CASE)
        return Regex(pattern, options)
    }

    fun matchesBypassKeyword(
        text: String,
        keyword: String,
        ignoreLatinCase: Boolean,
        normalizeUnicode: Boolean
    ): Boolean {
        val comparableText = if (normalizeUnicode) normalizeBypassUnicode(text) else text
        val comparableKeyword = if (normalizeUnicode) normalizeBypassUnicode(keyword) else keyword
        return buildBypassRegex(comparableKeyword, ignoreLatinCase).containsMatchIn(comparableText)
    }

    fun normalizeBypassUnicode(value: String): String {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
        return buildString(normalized.length) {
            var index = 0
            while (index < normalized.length) {
                val codePoint = normalized.codePointAt(index)
                if (!isIgnoredFormatCodePoint(codePoint)) {
                    val mapped = confusableLatin(codePoint)
                    if (mapped != null) append(mapped) else appendCodePoint(codePoint)
                }
                index += Character.charCount(codePoint)
            }
        }
    }

    private fun isIgnoredFormatCodePoint(codePoint: Int): Boolean =
        Character.getType(codePoint) == Character.FORMAT.toInt() ||
            codePoint in 0xFE00..0xFE0F ||
            codePoint in 0xE0100..0xE01EF

    private fun confusableLatin(codePoint: Int): String? = when (codePoint) {
        // Cyrillic characters frequently substituted into Latin ad IDs.
        0x0410 -> "A"; 0x0430 -> "a"
        0x0412 -> "B"; 0x0432 -> "b"
        0x0415 -> "E"; 0x0435 -> "e"
        0x041A -> "K"; 0x043A -> "k"
        0x041C -> "M"; 0x043C -> "m"
        0x041D -> "H"; 0x043D -> "h"
        0x041E -> "O"; 0x043E -> "o"
        0x0420 -> "P"; 0x0440 -> "p"
        0x0421 -> "C"; 0x0441 -> "c"
        0x0422 -> "T"; 0x0442 -> "t"
        0x0425 -> "X"; 0x0445 -> "x"
        0x0406 -> "I"; 0x0456 -> "i"
        0x0408 -> "J"; 0x0458 -> "j"
        0x0405 -> "S"; 0x0455 -> "s"

        // Greek characters with close Latin display forms.
        0x0391 -> "A"; 0x03B1 -> "a"
        0x0392 -> "B"
        0x0395 -> "E"
        0x0396 -> "Z"
        0x0397 -> "H"
        0x0399 -> "I"; 0x03B9 -> "i"
        0x039A -> "K"
        0x039C -> "M"
        0x039D -> "N"
        0x039F -> "O"; 0x03BF -> "o"
        0x03A1 -> "P"; 0x03C1 -> "p"
        0x03A4 -> "T"
        0x03A5 -> "Y"
        0x03A7 -> "X"; 0x03C7 -> "x"

        // Cherokee capitals commonly used as Latin-looking ad identifiers.
        0x13AA -> "A"
        0x13F4 -> "B"
        0x13DF -> "C"
        0x13A0 -> "D"
        0x13AC -> "E"
        0x13C0 -> "G"
        0x13BB -> "H"
        0x13C6 -> "I"
        0x13AB -> "J"
        0x13E6 -> "K"
        0x13DE -> "L"
        0x13B7 -> "M"
        0x13C1 -> "N"
        0x13BE -> "O"
        0x13E2 -> "P"
        0x13A1 -> "R"
        0x13DA -> "S"
        0x13A2 -> "T"
        0x13D9 -> "V"
        else -> null
    }

    fun findDisallowedSpecialCharacter(text: String, whitelist: Set<String>): String? {
        if (text.isEmpty()) return null
        val whitelistCodePoints = whitelist.flatMap { it.codePoints().toArray().asIterable() }.toSet()
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            if (!isAllowedSpecialFilterCodePoint(codePoint, whitelistCodePoints)) {
                return String(Character.toChars(codePoint))
            }
            index += Character.charCount(codePoint)
        }
        return null
    }

    private fun isAllowedSpecialFilterCodePoint(codePoint: Int, whitelistCodePoints: Set<Int>): Boolean {
        if (codePoint in whitelistCodePoints) return true
        if (codePoint == '\n'.code || codePoint == '\r'.code || codePoint == '\t'.code || codePoint == ' '.code) return true
        if (codePoint in '0'.code..'9'.code) return true
        if (codePoint in 'A'.code..'Z'.code) return true
        if (codePoint in 'a'.code..'z'.code) return true
        if (codePoint in 'ㄱ'.code..'ㅎ'.code) return true
        if (codePoint in 'ㅏ'.code..'ㅣ'.code) return true
        if (codePoint in '가'.code..'힣'.code) return true
        if (codePoint in commonAllowedPunctuation) return true
        return false
    }
}
