package com.heyheyon.armbandbot

import org.junit.Assert.*
import org.junit.Test

class AutomationReviewRegressionTest {
    @Test fun redirectsAcceptOnlyRequiredGoogleExportHosts() {
        assertTrue(allowedRemoteRedirect("https://script.googleusercontent.com/macros/echo?x=1"))
        assertTrue(allowedRemoteRedirect("https://doc-0k-4k-sheets.googleusercontent.com/export/x"))
        assertFalse(allowedRemoteRedirect("https://unrelated.googleusercontent.com/list"))
        assertFalse(allowedRemoteRedirect("https://script.googleusercontent.com.evil.example/list"))
        assertFalse(allowedRemoteRedirect("http://script.googleusercontent.com/list"))
    }

    @Test fun differentElectedRulesShareOneClaimForTheSameGalleryPolicy() {
        val a = TabMoveRule("a", "M", "laboratory1", "keyword", 10, "테스트", false)
        val b = TabMoveRule("b", "M", "laboratory1", "keyword", 0, "일반", false)
        val first = linkedMapOf("botA" to encodeTabMoveRules(listOf(a)), "botB" to encodeTabMoveRules(listOf(b)))
        val reordered = first.entries.reversed().associate { it.toPair() }
        assertEquals(movePolicyClaimIdentity(first), movePolicyClaimIdentity(reordered))
        assertNotEquals(movePolicyClaimIdentity(first), movePolicyClaimIdentity(mapOf("botA" to encodeTabMoveRules(listOf(a)))))
    }
}
