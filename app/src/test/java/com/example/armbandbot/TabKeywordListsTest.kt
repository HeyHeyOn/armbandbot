package com.heyheyon.armbandbot

import org.junit.Assert.*
import org.junit.Test

class TabKeywordListsTest {
    private val key = PostKey("M", "laboratory1", "1")
    private fun match(rule: TabMoveRule, text: String) = matchingMove(listOf(rule), key, text, false, false, false)

    @Test fun legacySingleKeywordRemainsLiteralIncludingPunctuation() {
        val rules = parseTabMoveRules("""[{"id":"old","gallType":"M","gallId":"laboratory1","keyword":"C#","headtext":10,"label":"테스트"}]""")
        assertEquals(listOf("C#"), rules.single().normalKeywords)
        assertTrue(rules.single().bypassKeywords.isEmpty())
        assertNotNull(match(rules.single(), "C# 배우기"))
        assertNull(match(rules.single(), "C 배우기"))
        assertEquals(rules, parseTabMoveRules(encodeTabMoveRules(rules)))
    }

    @Test fun ordinaryKeywordsMatchLiteralSubstringsAndBypassUsesBannedWordRules() {
        val rule = TabMoveRule("new", "M", "laboratory1", listOf("사과", "ABC"), 10, "테스트", false, listOf("바나나"))
        assertNotNull(match(rule, "맛있는 사과"))
        assertNotNull(match(rule, "abc"))
        assertNull(match(rule, "사. 과"))
        assertNotNull(match(rule, "바 ! 나\n나"))
        assertNull(match(rule, "바가나나"))
        assertEquals(rule, parseTabMoveRules(encodeTabMoveRules(listOf(rule))).single())
    }

    @Test fun bypassOnlyRuleWorksAndEmptyRuleIsRejected() {
        val rule = TabMoveRule("bypass", "M", "laboratory1", emptyList(), 10, "테스트", false, listOf("test"))
        assertNotNull(match(rule, "t e.s!t"))
        assertThrows(IllegalArgumentException::class.java) { rule.copy(bypassKeywords = emptyList()) }
        assertEquals(listOf("사과", "바나나"), parseAutomationKeywords(" 사과\n\n바나나\r\n사과 "))
    }

    @Test fun ruleOrderAndProtectionStillApplyToBothLists() {
        val first = TabMoveRule("first", "M", "laboratory1", emptyList(), 10, "테스트", false, listOf("사과"))
        val second = TabMoveRule("second", "M", "laboratory1", "사과", 20, "제안", false)
        assertEquals(first, matchingMove(listOf(first, second), key, "사과", false, false, false))
        listOf(Triple(true,false,false), Triple(false,true,false), Triple(false,false,true)).forEach { (white,exempt,handled) ->
            assertNull(matchingMove(listOf(first), key, "사.과", white, exempt, handled))
        }
        assertNull(matchingMove(listOf(first), key.copy(gallId="other"), "사.과", false,false,false))
    }
}
