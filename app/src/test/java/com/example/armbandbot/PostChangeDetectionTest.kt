package com.heyheyon.armbandbot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PostChangeDetectionTest {
    @Test
    fun alreadyHeldUnchangedBlockAllPumIsTerminalAtListAdmission() {
        assertTrue(
            shouldSkipPumHoldPreflight(
                isPumSourceFilterMode = true,
                pumBlockAllPosts = true,
                hasPumListMarker = true,
                rowUnchanged = true,
                effectiveActionIsHold = true,
                alreadyHeld = true,
            )
        )
    }

    @Test
    fun holdPreflightDoesNotSkipFirstHold() {
        assertFalse(shouldSkipPumHoldPreflight(true, true, true, true, true, alreadyHeld = false))
    }

    @Test
    fun holdPreflightDoesNotSkipBlockOrDeleteActions() {
        assertFalse(
            shouldSkipPumHoldPreflight(
                isPumSourceFilterMode = true,
                pumBlockAllPosts = true,
                hasPumListMarker = true,
                rowUnchanged = true,
                effectiveActionIsHold = false,
                alreadyHeld = true,
            )
        )
    }

    @Test
    fun holdPreflightDoesNotSkipChangedOrSnapshotBackfillRows() {
        data class RowState(
            val savedCommentCount: Int,
            val currentCommentCount: Int,
            val savedTitle: String,
            val currentTitle: String,
            val snapshotBackfillRequired: Boolean,
        )

        listOf(
            RowState(2, 3, "same", "same", false),
            RowState(3, 3, "old title", "new title", false),
            RowState(3, 3, "same", "same", true),
        ).forEach { row ->
            val rowUnchanged = row.savedCommentCount == row.currentCommentCount &&
                row.savedTitle.trim() == row.currentTitle.trim() &&
                !row.snapshotBackfillRequired
            assertFalse(rowUnchanged)
            assertFalse(shouldSkipPumHoldPreflight(true, true, true, rowUnchanged, true, true))
        }
    }

    @Test
    fun holdPreflightDoesNotSkipEveryCycleOnlyRows() {
        assertFalse(
            shouldSkipPumHoldPreflight(
                isPumSourceFilterMode = true,
                pumBlockAllPosts = false,
                hasPumListMarker = true,
                rowUnchanged = true,
                effectiveActionIsHold = true,
                alreadyHeld = true,
            )
        )
    }

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
    fun everyCycleRecheckIsIndependentOfLegacySourceToggle() {
        assertTrue(
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
    fun blockAllRechecksUnchangedPumRowsWhenMasterIsEnabled() {
        assertTrue(
            shouldRecheckPost(
                savedCommentCount = 3,
                currentCommentCount = 3,
                savedTitle = "같은 제목",
                currentTitle = "같은 제목",
                isPumSourceFilterMode = true,
                pumBlockAllPosts = true,
                pumRecheckEveryCycle = false,
                hasPumListMarker = true,
            )
        )
    }

    @Test
    fun blockAllRechecksWithoutLegacySourceToggle() {
        assertTrue(
            shouldRecheckPost(
                savedCommentCount = 3,
                currentCommentCount = 3,
                savedTitle = "같은 제목",
                currentTitle = "같은 제목",
                isPumSourceFilterMode = false,
                pumBlockAllPosts = true,
                pumRecheckEveryCycle = false,
                hasPumListMarker = true,
            )
        )
    }

    @Test
    fun disabledBlockAllAndEveryCycleKeepUnchangedPumRowsSkipped() {
        assertFalse(
            shouldRecheckPost(
                savedCommentCount = 3,
                currentCommentCount = 3,
                savedTitle = "같은 제목",
                currentTitle = "같은 제목",
                isPumSourceFilterMode = true,
                pumBlockAllPosts = false,
                pumRecheckEveryCycle = false,
                hasPumListMarker = true,
            )
        )
    }

    @Test
    fun blockAllDoesNotRecheckOrdinaryRowsOrTitleTextThatOnlyLooksLikePum() {
        listOf("같은 제목", "사용자가 입력한 (펌) 같은 제목").forEach { title ->
            assertFalse(
                shouldRecheckPost(
                    savedCommentCount = 3,
                    currentCommentCount = 3,
                    savedTitle = title,
                    currentTitle = title,
                    isPumSourceFilterMode = true,
                    pumBlockAllPosts = true,
                    pumRecheckEveryCycle = false,
                    hasPumListMarker = false,
                )
            )
        }
    }

    @Test
    fun blockAllKeepsExistingChangeAndForceReasonsIntact() {
        assertTrue(
            shouldRecheckPost(
                savedCommentCount = 3,
                currentCommentCount = 4,
                savedTitle = "일반 글",
                currentTitle = "일반 글",
                isPumSourceFilterMode = true,
                pumBlockAllPosts = true,
                hasPumListMarker = false,
            )
        )
        assertTrue(
            shouldRecheckPost(
                savedCommentCount = 3,
                currentCommentCount = 3,
                savedTitle = "이전 일반 글",
                currentTitle = "수정된 일반 글",
                isPumSourceFilterMode = true,
                pumBlockAllPosts = true,
                hasPumListMarker = false,
            )
        )
        assertTrue(
            shouldRecheckPost(
                savedCommentCount = -1,
                currentCommentCount = 0,
                savedTitle = null,
                currentTitle = "새 일반 글",
                isPumSourceFilterMode = true,
                pumBlockAllPosts = true,
                hasPumListMarker = false,
            )
        )
        assertTrue(
            shouldRecheckPost(
                savedCommentCount = 3,
                currentCommentCount = 3,
                savedTitle = "일반 글",
                currentTitle = "일반 글",
                isPumSourceFilterMode = true,
                pumBlockAllPosts = true,
                hasPumListMarker = false,
                snapshotBackfillRequired = true,
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
