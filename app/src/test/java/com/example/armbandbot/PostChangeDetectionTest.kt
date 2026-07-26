package com.heyheyon.armbandbot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PostChangeDetectionTest {
    @Test
    fun shouldRecheckPostWhenCommentCountChanged() {
        assertTrue(
            shouldRecheckPost(
                savedCommentCount = 3,
                currentCommentCount = 4,
                savedTitle = "같은 제목",
                currentTitle = "같은 제목"
            )
        )
    }

    @Test
    fun shouldRecheckPostWhenTitleChangedEvenIfCommentCountSame() {
        assertTrue(
            shouldRecheckPost(
                savedCommentCount = 3,
                currentCommentCount = 3,
                savedTitle = "정상 글 제목",
                currentTitle = "수정된 낚시 제목"
            )
        )
    }

    @Test
    fun shouldSkipPostWhenCommentCountAndTitleAreUnchanged() {
        assertFalse(
            shouldRecheckPost(
                savedCommentCount = 3,
                currentCommentCount = 3,
                savedTitle = "  같은 제목  ",
                currentTitle = "같은 제목"
            )
        )
    }

    @Test
    fun shouldRecheckUnknownPost() {
        assertTrue(
            shouldRecheckPost(
                savedCommentCount = -1,
                currentCommentCount = 0,
                savedTitle = null,
                currentTitle = "처음 본 글"
            )
        )
    }

    @Test
    fun everyCycleRechecksUnchangedPumRowsWhenSourceFilteringIsEnabled() {
        assertTrue(
            shouldRecheckPost(
                savedCommentCount = 3,
                currentCommentCount = 3,
                savedTitle = "(펌) 같은 제목",
                currentTitle = "(펌) 같은 제목",
                isPumSourceFilterMode = true,
                pumRecheckEveryCycle = true,
                hasPumListMarker = true
            )
        )
    }

    @Test
    fun everyCycleDoesNotForceUnchangedOrdinaryRows() {
        assertFalse(
            shouldRecheckPost(
                savedCommentCount = 3,
                currentCommentCount = 3,
                savedTitle = "같은 제목",
                currentTitle = "같은 제목",
                isPumSourceFilterMode = true,
                pumRecheckEveryCycle = true,
                hasPumListMarker = false
            )
        )
    }

    @Test
    fun everyCycleDoesNotForcePumRowsWhenSourceFilteringIsDisabled() {
        assertFalse(
            shouldRecheckPost(
                savedCommentCount = 3,
                currentCommentCount = 3,
                savedTitle = "(펌) 같은 제목",
                currentTitle = "(펌) 같은 제목",
                isPumSourceFilterMode = false,
                pumRecheckEveryCycle = true,
                hasPumListMarker = true
            )
        )
    }

    @Test
    fun disabledEveryCycleKeepsExistingChangeDetectionForPumRows() {
        assertFalse(
            shouldRecheckPost(
                savedCommentCount = 3,
                currentCommentCount = 3,
                savedTitle = "(펌) 같은 제목",
                currentTitle = "(펌) 같은 제목",
                isPumSourceFilterMode = true,
                pumRecheckEveryCycle = false,
                hasPumListMarker = true
            )
        )
        assertTrue(
            shouldRecheckPost(
                savedCommentCount = 3,
                currentCommentCount = 3,
                savedTitle = "(펌) 이전 제목",
                currentTitle = "(펌) 바뀐 제목",
                isPumSourceFilterMode = true,
                pumRecheckEveryCycle = false,
                hasPumListMarker = true
            )
        )
    }

    @Test
    fun missingRequiredSnapshotForcesBackfillButExistingOrDisabledSnapshotDoesNot() {
        assertTrue(
            shouldRecheckPost(
                savedCommentCount = 3,
                currentCommentCount = 3,
                savedTitle = "같은 제목",
                currentTitle = "같은 제목",
                snapshotBackfillRequired = true
            )
        )
        assertFalse(
            shouldRecheckPost(
                savedCommentCount = 3,
                currentCommentCount = 3,
                savedTitle = "같은 제목",
                currentTitle = "같은 제목",
                snapshotBackfillRequired = false
            )
        )
    }
}
