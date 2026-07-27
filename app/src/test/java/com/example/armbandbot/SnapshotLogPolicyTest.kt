package com.heyheyon.armbandbot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotLogPolicyTest {
    @Test
    fun `general snapshot emits low-level performance log`() {
        assertTrue(SnapshotLogPolicy.shouldLogPerformance(blockedTs = null, blockedCommentNo = null))
    }

    @Test
    fun `post block evidence suppresses low-level performance log`() {
        assertFalse(SnapshotLogPolicy.shouldLogPerformance(blockedTs = "20260727_120000", blockedCommentNo = null))
    }

    @Test
    fun `comment block evidence suppresses low-level performance log`() {
        assertFalse(SnapshotLogPolicy.shouldLogPerformance(blockedTs = null, blockedCommentNo = "42"))
    }
}
