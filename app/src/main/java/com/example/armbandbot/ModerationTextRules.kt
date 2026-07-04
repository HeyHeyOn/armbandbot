package com.heyheyon.armbandbot

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

    fun buildBypassRegex(keyword: String): Regex {
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
            .joinToString(separator) { Regex.escape(it.toString()) }

        val options = if (hasUpperLatin) emptySet() else setOf(RegexOption.IGNORE_CASE)
        return Regex(pattern, options)
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
