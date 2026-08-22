package com.heyheyon.armbandbot

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DbDashboardSearchTest {
    private val testSnapshotAllowedRoots = listOf(
        File(requireNotNull(System.getProperty("java.io.tmpdir")))
    )

    private fun createTrustedSnapshotTempFile(prefix: String, suffix: String): File {
        val directory = kotlin.io.path.createTempDirectory("snapshots_$prefix").toFile()
        directory.deleteOnExit()
        return File.createTempFile(prefix, suffix, directory)
    }

    @Test
    fun sameIdentityChangedDirectTitleRebuildsIndexedDocument() = runBlocking {
        val index = DashboardRowDocumentIndex(capacity = 2)
        val original = CheckedPost("M", "g", "1", 0, title = "old title")
        val changed = CheckedPost("M", "g", "1", 0, title = "fresh needle")
        var snapshotBuilds = 0

        suspend fun search(row: CheckedPost, query: String) = searchDashboardRows(
            candidates = listOf(row), query = query, includeRow = { true },
            comparator = compareBy { it.postNum }, limit = 10,
            directDocument = CheckedPost::toDashboardSearchDocument,
            snapshotDocument = { snapshotBuilds++; DashboardSearchDocument() },
            documentIndex = index,
            rowIdentity = { Triple(it.gallType, it.gallId, it.postNum) },
        )

        assertTrue(search(original, "fresh needle").isEmpty())
        assertEquals(listOf(DashboardSearchMatchCode.POST_TITLE), search(changed, "fresh needle").single().matches)
        assertEquals(2, snapshotBuilds)
    }

    @Test
    fun sameIdentityChangedSnapshotPathOrFileSignatureRebuildsIndexedDocument() = runBlocking {
        val directory = kotlin.io.path.createTempDirectory("snapshots_dashboard_row_freshness").toFile()
        try {
            val first = File(directory, "first.html").apply { writeText("first body") }
            val second = File(directory, "second.html").apply { writeText("second needle body") }
            val index = DashboardRowDocumentIndex(capacity = 2)
            val cache = SnapshotSearchDocumentCache(testSnapshotAllowedRoots) { path ->
                SnapshotData(bodyElements = listOf(BodyElement.TextElement(File(path).readText())))
            }
            var snapshotBuilds = 0

            suspend fun search(path: String, query: String): List<DashboardMatched<CheckedPost>> {
                val row = CheckedPost("M", "g", "1", 0, snapshotPath = path)
                return searchDashboardRows(
                    candidates = listOf(row), query = query, includeRow = { true },
                    comparator = compareBy { it.postNum }, limit = 10,
                    directDocument = CheckedPost::toDashboardSearchDocument,
                    snapshotDocument = { snapshotBuilds++; cache.loadBlocking(it.snapshotPath) },
                    snapshotFreshness = { snapshotSearchFreshnessToken(it.snapshotPath, testSnapshotAllowedRoots) },
                    documentIndex = index,
                    rowIdentity = { Triple(it.gallType, it.gallId, it.postNum) },
                )
            }

            assertTrue(search(first.path, "needle").isEmpty())
            assertEquals(listOf(DashboardSearchMatchCode.POST_CONTENT), search(second.path, "needle").single().matches)
            second.writeText("signature changed and still has needle")
            assertEquals(listOf(DashboardSearchMatchCode.POST_CONTENT), search(second.path, "needle").single().matches)
            assertEquals(3, snapshotBuilds)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun rowIndexIsBoundedAndKeepsAStableResidentSetBeyondCapacity() {
        val index = DashboardRowDocumentIndex(capacity = 2)
        val builds = mutableMapOf<Int, Int>()

        repeat(2) {
            (1..3).forEach { identity ->
                index.getOrBuild(identity, freshness = identity) {
                    builds[identity] = builds.getOrDefault(identity, 0) + 1
                    DashboardSearchDocument(postNumbers = listOf(identity.toString()))
                }
                assertTrue(index.size <= 2)
            }
        }

        assertEquals(2, index.size)
        assertEquals(1, builds[1])
        assertEquals(1, builds[2])
        assertEquals(2, builds[3])
    }

    @Test
    fun buildStartedBeforeClearCannotRepublishIntoRowIndex() {
        val index = DashboardRowDocumentIndex(capacity = 1)
        val buildStarted = CountDownLatch(1)
        val allowBuildToFinish = CountDownLatch(1)
        val builds = AtomicInteger(0)
        val worker = Thread {
            index.getOrBuild("row", freshness = "v1") {
                builds.incrementAndGet()
                buildStarted.countDown()
                assertTrue(allowBuildToFinish.await(5, TimeUnit.SECONDS))
                DashboardSearchDocument(postTitles = listOf("stale"))
            }
        }

        worker.start()
        assertTrue(buildStarted.await(5, TimeUnit.SECONDS))
        index.clear()
        allowBuildToFinish.countDown()
        worker.join(5_000)
        assertFalse(worker.isAlive)
        assertEquals(0, index.size)

        index.getOrBuild("row", freshness = "v1") {
            builds.incrementAndGet()
            DashboardSearchDocument(postTitles = listOf("fresh"))
        }
        assertEquals(2, builds.get())
        assertEquals(1, index.size)
    }

    @Test
    fun pureSearchUnionsDirectAndSnapshotMatchesInFixedLabelOrder() = runBlocking {
        val row = CheckedPost("M", "gallery", "17", 0, title = "needle title")

        val result = searchDashboardRows(
            candidates = listOf(row),
            query = "needle",
            includeRow = { true },
            comparator = compareBy { it.postNum },
            limit = 100,
            directDocument = CheckedPost::toDashboardSearchDocument,
            snapshotDocument = {
                DashboardSearchDocument(
                    postContents = listOf("needle body"),
                    commentContents = listOf("needle comment"),
                )
            },
        )

        assertEquals(
            listOf(
                DashboardSearchMatchCode.POST_TITLE,
                DashboardSearchMatchCode.POST_CONTENT,
                DashboardSearchMatchCode.COMMENT_CONTENT,
            ),
            result.single().matches,
        )
        assertEquals("일치: 글 제목, 글 내용, 댓글 내용", dashboardSearchMatchLabel(result.single().matches))
    }

    @Test
    fun pureSearchFallsBackToDirectFieldsWhenSnapshotIsMissing() = runBlocking {
        val row = CheckedPost("M", "gallery", "55", 0, author = "needle author")

        val result = searchDashboardRows(
            candidates = listOf(row),
            query = "needle",
            includeRow = { true },
            comparator = compareBy { it.postNum },
            limit = 100,
            directDocument = CheckedPost::toDashboardSearchDocument,
            snapshotDocument = { DashboardSearchDocument() },
        )

        assertEquals(listOf(DashboardSearchMatchCode.POST_AUTHOR), result.single().matches)
    }

    @Test
    fun pureSearchFindsSnapshotMatchAfterFirstHundredBeforeApplyingLimit() = runBlocking {
        val rows = (1..101).map { number ->
            CheckedPost("M", "gallery", number.toString(), 0, checkTime = number.toLong())
        }

        val result = searchDashboardRows(
            candidates = rows,
            query = "deep needle",
            includeRow = { true },
            comparator = checkedPostDashboardComparator(sortField = "CHECK", ascending = false),
            limit = 100,
            directDocument = CheckedPost::toDashboardSearchDocument,
            snapshotDocument = { row ->
                if (row.postNum == "101") DashboardSearchDocument(postContents = listOf("deep needle"))
                else DashboardSearchDocument()
            },
        )

        assertEquals("101", result.single().row.postNum)
        assertEquals(listOf(DashboardSearchMatchCode.POST_CONTENT), result.single().matches)
    }

    @Test
    fun generalSearchAppliesGalleryThenCurrentSortPolicyThenLimit() = runBlocking {
        val rows = listOf(
            CheckedPost("M", "other", "1", 0, checkTime = 999, title = "needle"),
            CheckedPost("M", "chosen", "2", 0, checkTime = 20, title = "needle"),
            CheckedPost("M", "chosen", "3", 0, checkTime = 30, title = "needle"),
        )

        val result = searchDashboardRows(
            candidates = rows,
            query = "needle",
            includeRow = { it.gallId == "chosen" },
            comparator = checkedPostDashboardComparator("CHECK", ascending = false),
            limit = 1,
            directDocument = CheckedPost::toDashboardSearchDocument,
            snapshotDocument = { DashboardSearchDocument() },
        )

        assertEquals(listOf("3"), result.map { it.row.postNum })
    }

    @Test
    fun historySearchAppliesTargetFilterCreateSortAndLimitForBlockAndHold() = runBlocking {
        val blocks = listOf(
            BlockHistory(id = 1, gallType = "M", gallId = "g", postNum = "1", targetType = "COMMENT", targetAuthor = "needle", targetContent = "", blockReason = "", creationDate = "2026-01-03"),
            BlockHistory(id = 2, gallType = "M", gallId = "g", postNum = "2", targetType = "POST", targetAuthor = "needle", targetContent = "", blockReason = "", creationDate = "2026-01-02"),
            BlockHistory(id = 3, gallType = "M", gallId = "g", postNum = "3", targetType = "POST", targetAuthor = "needle", targetContent = "", blockReason = "", creationDate = "2026-01-01"),
        )
        val holds = listOf(
            HoldHistory(id = 1, gallType = "M", gallId = "g", postNum = "1", targetType = "COMMENT", targetNo = "", targetAuthor = "needle", targetContent = "", holdReason = "", creationDate = "2026-01-03"),
            HoldHistory(id = 2, gallType = "M", gallId = "g", postNum = "2", targetType = "POST", targetNo = "", targetAuthor = "needle", targetContent = "", holdReason = "", creationDate = "2026-01-02"),
            HoldHistory(id = 3, gallType = "M", gallId = "g", postNum = "3", targetType = "POST", targetNo = "", targetAuthor = "needle", targetContent = "", holdReason = "", creationDate = "2026-01-01"),
        )

        val blockResult = searchDashboardRows(
            candidates = blocks, query = "needle", includeRow = { it.targetType == "POST" },
            comparator = blockHistoryDashboardComparator("CREATE", ascending = true), limit = 1,
            directDocument = BlockHistory::toDashboardSearchDocument,
            snapshotDocument = { DashboardSearchDocument() },
        )
        val holdResult = searchDashboardRows(
            candidates = holds, query = "needle", includeRow = { it.targetType == "POST" },
            comparator = holdHistoryDashboardComparator("CREATE", ascending = true), limit = 1,
            directDocument = HoldHistory::toDashboardSearchDocument,
            snapshotDocument = { DashboardSearchDocument() },
        )

        assertEquals(listOf("3"), blockResult.map { it.row.postNum })
        assertEquals(listOf("3"), holdResult.map { it.row.postNum })
    }

    @Test
    fun blankPureSearchDoesNotReadDocumentsAndCarriesNoMatchLabel() = runBlocking {
        val rows = listOf(CheckedPost("M", "g", "2", 0), CheckedPost("M", "g", "1", 0))
        var documentReads = 0

        val result = searchDashboardRows(
            candidates = rows, query = "  ", includeRow = { true }, comparator = compareBy { it.postNum }, limit = 1,
            directDocument = { documentReads++; it.toDashboardSearchDocument() },
            snapshotDocument = { documentReads++; DashboardSearchDocument() },
        )

        assertEquals(listOf("1"), result.map { it.row.postNum })
        assertEquals(emptyList<DashboardSearchMatchCode>(), result.single().matches)
        assertNull(dashboardSearchMatchLabel(result.single().matches))
        assertEquals(0, documentReads)
    }

    @Test
    fun filteredOutRowsNeverBuildDirectOrSnapshotDocuments() = runBlocking {
        val rows = listOf(
            CheckedPost("M", "excluded", "1", 0, title = "needle"),
            CheckedPost("M", "included", "2", 0, title = "needle"),
        )
        val directReads = mutableListOf<String>()
        val snapshotReads = mutableListOf<String>()

        val result = searchDashboardRows(
            candidates = rows,
            query = "needle",
            includeRow = { it.gallId == "included" },
            comparator = compareBy { it.postNum },
            limit = 100,
            directDocument = { row -> directReads += row.postNum; row.toDashboardSearchDocument() },
            snapshotDocument = { row -> snapshotReads += row.postNum; DashboardSearchDocument() },
        )

        assertEquals(listOf("2"), result.map { it.row.postNum })
        assertEquals(listOf("2"), directReads)
        assertEquals(listOf("2"), snapshotReads)
    }

    @Test
    fun expandedCandidateScanStopsAfterCoroutineCancellation() = runBlocking {
        val rows = (1..100).map { CheckedPost("M", "g", it.toString(), 0, title = "needle") }
        var snapshotReads = 0
        lateinit var searchJob: Job

        searchJob = launch {
            searchDashboardRows(
                candidates = rows,
                query = "needle",
                includeRow = { true },
                comparator = compareBy { it.postNum },
                limit = 100,
                directDocument = CheckedPost::toDashboardSearchDocument,
                snapshotDocument = {
                    snapshotReads++
                    if (snapshotReads == 3) searchJob.cancel(CancellationException("superseded"))
                    DashboardSearchDocument()
                },
            )
        }

        searchJob.join()
        assertTrue(searchJob.isCancelled)
        assertEquals(3, snapshotReads)
    }

    @Test
    fun sessionDocumentIndexPreventsReparseWhenCandidateSetExceedsSnapshotLru() = runBlocking {
        val directory = kotlin.io.path.createTempDirectory("snapshots_dashboard_search_session_index").toFile()
        try {
            val rows = (1..3).map { number ->
                val snapshot = File(directory, "$number.html").apply { writeText("query-$number alternate") }
                CheckedPost("M", "g", number.toString(), 0, snapshotPath = snapshot.path)
            }
            var parseCount = 0
            val snapshotCache = SnapshotSearchDocumentCache(allowedRoots = testSnapshotAllowedRoots, capacity = 2) { path ->
                parseCount++
                SnapshotData(bodyElements = listOf(BodyElement.TextElement(File(path).readText())))
            }
            val index = DashboardRowDocumentIndex()

            fun key(row: CheckedPost): Any = Triple(row.gallType, row.gallId, row.postNum)
            searchDashboardRows(
                candidates = rows, query = "query", includeRow = { true },
                comparator = compareBy { it.postNum }, limit = 100,
                directDocument = CheckedPost::toDashboardSearchDocument,
                snapshotDocument = { snapshotCache.loadBlocking(it.snapshotPath) },
                documentIndex = index, rowIdentity = ::key,
            )
            searchDashboardRows(
                candidates = rows, query = "alternate", includeRow = { true },
                comparator = compareBy { it.postNum }, limit = 100,
                directDocument = CheckedPost::toDashboardSearchDocument,
                snapshotDocument = { snapshotCache.loadBlocking(it.snapshotPath) },
                documentIndex = index, rowIdentity = ::key,
            )

            assertEquals(3, parseCount)
            assertEquals(3, index.size)
            index.clear()
            assertEquals(0, index.size)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun matchCodesHaveTheOfficialLabelsInFixedDisplayOrder() {
        assertEquals(
            listOf(
                "POST_NUMBER" to "글 번호",
                "POST_AUTHOR" to "글 작성자",
                "POST_TITLE" to "글 제목",
                "POST_CONTENT" to "글 내용",
                "COMMENT_AUTHOR" to "댓글 작성자",
                "COMMENT_CONTENT" to "댓글 내용"
            ),
            DashboardSearchMatchCode.entries.map { it.name to it.label }
        )
    }

    @Test
    fun formatsSingleAndMultipleCategoryMatches() {
        val document = DashboardSearchDocument(
            postTitles = listOf("찾는 제목"),
            postContents = listOf("같은 검색어"),
            commentContents = listOf("검색어 포함 댓글")
        )

        assertEquals("일치: 글 제목", dashboardSearchMatchLabel(document, "찾는"))
        assertEquals("일치: 글 내용, 댓글 내용", dashboardSearchMatchLabel(document, "검색어"))
    }

    @Test
    fun matchingUsesFixedEnumOrderAndDeduplicatesRepeatedValues() {
        val document = DashboardSearchDocument(
            postNumbers = listOf("needle-1", "needle-1"),
            postAuthors = listOf("needle author", "NEEDLE AUTHOR"),
            postTitles = listOf("needle title", "needle title"),
            commentAuthors = listOf("needle commenter")
        )

        assertEquals(
            listOf(
                DashboardSearchMatchCode.POST_NUMBER,
                DashboardSearchMatchCode.POST_AUTHOR,
                DashboardSearchMatchCode.POST_TITLE,
                DashboardSearchMatchCode.COMMENT_AUTHOR
            ),
            dashboardSearchMatchCodes(document, "needle")
        )
    }

    @Test
    fun authorIpOrIdentifierMatchesOnlyItsAuthorCategory() {
        val document = DashboardSearchDocument(
            postAuthors = listOf("글쓴이 (fixed-id / 203.0.113.7)"),
            commentAuthors = listOf("댓글러 (comment-id / 198.51.100.8)")
        )

        assertEquals(
            listOf(DashboardSearchMatchCode.POST_AUTHOR),
            dashboardSearchMatchCodes(document, "203.0.113.7")
        )
        assertEquals(
            listOf(DashboardSearchMatchCode.COMMENT_AUTHOR),
            dashboardSearchMatchCodes(document, "COMMENT-ID")
        )
    }

    @Test
    fun matchingTrimsQueryAndUsesCaseInsensitiveSubstring() {
        val document = DashboardSearchDocument(postTitles = listOf("Prefix INDIGO Suffix"))

        assertEquals("일치: 글 제목", dashboardSearchMatchLabel(document, "  indigo  "))
        assertEquals("일치: 글 제목", dashboardSearchMatchLabel(document, "INDI"))
    }

    @Test
    fun matchingTreatsGreekSigmaAndFinalSigmaAsTheSameLetter() {
        val document = DashboardSearchDocument(postTitles = listOf("ΟΣ"))

        assertEquals("일치: 글 제목", dashboardSearchMatchLabel(document, "οσ"))
    }

    @Test
    fun blankOrMissingMatchHasNoCodesOrLabel() {
        val document = DashboardSearchDocument(postTitles = listOf("title"))

        assertEquals(emptyList<DashboardSearchMatchCode>(), dashboardSearchMatchCodes(document, " \t\n "))
        assertNull(dashboardSearchMatchLabel(document, " \t\n "))
        assertNull(dashboardSearchMatchLabel(document, "absent"))
    }

    @Test
    fun checkedPostMapsOnlyNumberAuthorAndTitleAndFiltersBlankValues() {
        val populated = CheckedPost(
            gallType = "M",
            gallId = "gallery",
            postNum = "101",
            commentCount = 0,
            title = "post title",
            author = "author (id / 203.0.113.1)",
            blockReason = "must not be searchable"
        )
        val blank = CheckedPost(
            gallType = "M",
            gallId = "gallery",
            postNum = " ",
            commentCount = 0,
            title = null,
            author = "\t",
            blockReason = "also excluded"
        )

        assertEquals(
            DashboardSearchDocument(
                postNumbers = listOf("101"),
                postAuthors = listOf("author (id / 203.0.113.1)"),
                postTitles = listOf("post title")
            ),
            populated.toDashboardSearchDocument()
        )
        assertEquals(DashboardSearchDocument(), blank.toDashboardSearchDocument())
    }

    @Test
    fun blockHistoryMapsTargetTypeCaseInsensitivelyWithoutReasonOrCommentTargetNumber() {
        val post = BlockHistory(
            gallType = "M",
            gallId = "gallery",
            postNum = "202",
            targetType = "post",
            targetNo = "post-target-no",
            targetAuthor = "post author",
            targetContent = "stored post title",
            blockReason = "excluded block reason"
        )
        val comment = BlockHistory(
            gallType = "M",
            gallId = "gallery",
            postNum = "203",
            targetType = "cOmMeNt",
            targetNo = "777",
            targetAuthor = "comment author (comment-id / 198.51.100.2)",
            targetContent = "comment body",
            blockReason = "excluded block reason"
        )

        assertEquals(
            DashboardSearchDocument(
                postNumbers = listOf("202"),
                postAuthors = listOf("post author"),
                postTitles = listOf("stored post title")
            ),
            post.toDashboardSearchDocument()
        )
        assertEquals(
            DashboardSearchDocument(
                postNumbers = listOf("203"),
                commentAuthors = listOf("comment author (comment-id / 198.51.100.2)"),
                commentContents = listOf("comment body")
            ),
            comment.toDashboardSearchDocument()
        )
        assertEquals(emptyList<DashboardSearchMatchCode>(), dashboardSearchMatchCodes(comment.toDashboardSearchDocument(), "777"))
        assertEquals(emptyList<DashboardSearchMatchCode>(), dashboardSearchMatchCodes(comment.toDashboardSearchDocument(), "excluded"))
    }

    @Test
    fun holdHistoryMapsPostAndCommentTargetsAndFiltersBlankValues() {
        val post = HoldHistory(
            gallType = "M",
            gallId = "gallery",
            postNum = "303",
            targetType = "POST",
            targetNo = "post-target-no",
            targetAuthor = "hold post author",
            targetContent = "hold stored title",
            holdReason = "excluded hold reason"
        )
        val commentWithBlanks = HoldHistory(
            gallType = "M",
            gallId = "gallery",
            postNum = "304",
            targetType = "COMMENT",
            targetNo = "888",
            targetAuthor = " ",
            targetContent = "\t",
            holdReason = "excluded hold reason"
        )

        assertEquals(
            DashboardSearchDocument(
                postNumbers = listOf("303"),
                postAuthors = listOf("hold post author"),
                postTitles = listOf("hold stored title")
            ),
            post.toDashboardSearchDocument()
        )
        assertEquals(
            DashboardSearchDocument(postNumbers = listOf("304")),
            commentWithBlanks.toDashboardSearchDocument()
        )
        assertEquals(emptyList<DashboardSearchMatchCode>(), dashboardSearchMatchCodes(commentWithBlanks.toDashboardSearchDocument(), "888"))
        assertEquals(emptyList<DashboardSearchMatchCode>(), dashboardSearchMatchCodes(post.toDashboardSearchDocument(), "excluded"))
    }

    @Test
    fun snapshotOuterFieldsMapWhilePumPreviewAndMediaAreExcluded() {
        val file = createTrustedSnapshotTempFile("dashboard_search_outer", ".html").apply {
            writeText(
                """
                <html><body>
                  <div class="title_subject">바깥 제목</div>
                  <div class="gall_writer" data-nick="바깥작성자" data-uid="outer-id"></div>
                  <div class="write_div">
                    <p>바깥 본문</p>
                    <img src="https://images.dcinside.com/search-must-not-see.jpg">
                    <img class="written_dccon" src="https://dcimg5.dcinside.com/dccon.php?no=BODY_MEDIA">
                    <div class="armbandbot-dc-movie"
                         data-movie-url="https://gall.dcinside.com/board/movie/movie_view?no=987654"
                         data-post-url="https://gall.dcinside.com/board/view/?id=source&amp;no=7">
                      <span>정적 동영상 라벨</span>
                    </div>
                    <section class="armbandbot-pum-card" data-status="RESOLVED" data-source-key="M/source/7">
                      <h3 class="armbandbot-pum-title">펌 미리보기 제목</h3>
                      <div class="armbandbot-pum-author">펌 미리보기 작성자</div>
                      <p class="armbandbot-pum-preview">펌 미리보기 문장</p>
                      <div class="armbandbot-pum-body"><p>펌 원문 본문</p><img src="https://images.dcinside.com/pum.jpg"></div>
                    </section>
                  </div>
                  <ul class="cmt_list"><li id="comment_li_1" class="ub-content"><div class="cmt_info">
                    <span class="gall_writer" data-nick="댓글작성자" data-ip="203.0.113.9"></span>
                    <p class="usertxt">댓글 본문</p>
                    <img class="written_dccon" src="https://dcimg5.dcinside.com/dccon.php?no=COMMENT_MEDIA">
                  </div></li></ul>
                </body></html>
                """.trimIndent()
            )
        }

        val document = SnapshotSearchDocumentCache(testSnapshotAllowedRoots).loadBlocking(file.path)

        assertEquals(
            DashboardSearchDocument(
                postAuthors = listOf("바깥작성자(outer-id)"),
                postTitles = listOf("바깥 제목"),
                postContents = listOf("바깥 본문"),
                commentAuthors = listOf("댓글작성자(203.0.113.9)"),
                commentContents = listOf("댓글 본문"),
            ),
            document,
        )
        assertEquals(listOf(DashboardSearchMatchCode.POST_AUTHOR), dashboardSearchMatchCodes(document, "OUTER-ID"))
        assertEquals(listOf(DashboardSearchMatchCode.COMMENT_AUTHOR), dashboardSearchMatchCodes(document, "203.0.113.9"))
        listOf(
            "펌 미리보기 제목", "펌 미리보기 작성자", "펌 미리보기 문장", "펌 원문 본문",
            "search-must-not-see.jpg", "pum.jpg", "COMMENT_MEDIA", "BODY_MEDIA",
            "movie_view?no=987654", "정적 동영상 라벨",
        ).forEach { excluded -> assertTrue(excluded, dashboardSearchMatchCodes(document, excluded).isEmpty()) }
        file.delete()
    }

    @Test
    fun initialAndLatestDocumentsMergeAndMatchCodesStayDeduplicated() {
        val directory = kotlin.io.path.createTempDirectory("snapshots_dashboard_search_versions").toFile()
        val initial = File(directory, "gallery_42_initial.html").apply { writeText("initial unique") }
        File(directory, "gallery_42_latest.html").writeText("latest unique")
        val cache = SnapshotSearchDocumentCache(testSnapshotAllowedRoots) { path ->
            SnapshotData(
                title = "same title",
                bodyElements = listOf(BodyElement.TextElement(File(path).readText())),
            )
        }

        val snapshotDocument = cache.loadBlocking(initial.path)
        val merged = mergeDashboardSearchDocuments(
            DashboardSearchDocument(postTitles = listOf("same title")),
            snapshotDocument,
        )

        assertEquals(listOf(DashboardSearchMatchCode.POST_CONTENT), dashboardSearchMatchCodes(merged, "initial unique"))
        assertEquals(listOf(DashboardSearchMatchCode.POST_CONTENT), dashboardSearchMatchCodes(merged, "latest unique"))
        assertEquals(listOf(DashboardSearchMatchCode.POST_TITLE), dashboardSearchMatchCodes(merged, "same title"))
        directory.deleteRecursively()
    }

    @Test
    fun missingOrCorruptSiblingDoesNotBlockValidSnapshot() {
        val directory = kotlin.io.path.createTempDirectory("snapshots_dashboard_search_partial").toFile()
        val initial = File(directory, "gallery_43_initial.html").apply { writeText("valid initial") }
        val latest = File(directory, "gallery_43_latest.html").apply { writeText("corrupt") }
        val cache = SnapshotSearchDocumentCache(testSnapshotAllowedRoots) { path ->
            File(path).readText().let { text ->
                if (text == "corrupt") error("bad snapshot")
                SnapshotData(bodyElements = listOf(BodyElement.TextElement(text)))
            }
        }

        assertEquals(listOf("valid initial"), cache.loadBlocking(latest.path).postContents)
        latest.delete()
        assertEquals(
            listOf("valid initial"),
            SnapshotSearchDocumentCache(testSnapshotAllowedRoots) { path ->
                SnapshotData(bodyElements = listOf(BodyElement.TextElement(File(path).readText())))
            }.loadBlocking(latest.path).postContents,
        )
        directory.deleteRecursively()
    }

    @Test
    fun legacySingleExistingPathIsSearchedAndTotalFailureIsEmpty() {
        val legacy = createTrustedSnapshotTempFile("legacy_snapshot", ".html").apply { writeText("legacy unique") }
        val cache = SnapshotSearchDocumentCache(testSnapshotAllowedRoots) { path ->
            SnapshotData(bodyElements = listOf(BodyElement.TextElement(File(path).readText())))
        }

        assertEquals(listOf("legacy unique"), cache.loadBlocking(legacy.path).postContents)
        assertEquals(DashboardSearchDocument(), cache.loadBlocking(File(legacy.parentFile, "missing.html").path))
        val corrupt = createTrustedSnapshotTempFile("legacy_corrupt", ".html").apply { writeText("bad") }
        assertEquals(
            DashboardSearchDocument(),
            SnapshotSearchDocumentCache(testSnapshotAllowedRoots) { error("corrupt") }.loadBlocking(corrupt.path),
        )
        legacy.delete()
        corrupt.delete()
    }

    @Test
    fun malformedCanonicalPathIsIsolatedAsAnEmptySnapshot() {
        val malformed = "bad" + 0.toChar() + "snapshot.html"
        val cache = SnapshotSearchDocumentCache(testSnapshotAllowedRoots) { error("parser must not run") }

        assertEquals(DashboardSearchDocument(), cache.loadBlocking(malformed))
        cache.invalidate(malformed)
        assertEquals(0, cache.cacheSize)
    }

    @Test
    fun cacheHitAvoidsReparseAndChangedSignatureReplacesSamePathEntry() {
        val file = createTrustedSnapshotTempFile("dashboard_search_cache", ".html").apply { writeText("first") }
        var parseCount = 0
        val cache = SnapshotSearchDocumentCache(testSnapshotAllowedRoots) { path ->
            parseCount++
            SnapshotData(bodyElements = listOf(BodyElement.TextElement(File(path).readText())))
        }

        assertEquals(listOf("first"), cache.loadBlocking(file.path).postContents)
        assertEquals(listOf("first"), cache.loadBlocking(file.absolutePath).postContents)
        assertEquals(1, parseCount)
        assertEquals(1, cache.cacheSize)

        file.writeText("second, changed length")
        assertEquals(listOf("second, changed length"), cache.loadBlocking(file.path).postContents)
        assertEquals(2, parseCount)
        assertEquals(1, cache.cacheSize)
        assertFalse(cache.loadBlocking(file.path).postContents.contains("first"))
        file.delete()
    }

    @Test(expected = IllegalArgumentException::class)
    fun cacheCapacityMustBePositive() {
        SnapshotSearchDocumentCache(allowedRoots = testSnapshotAllowedRoots, capacity = 0)
    }

    @Test(expected = AssertionError::class)
    fun parserErrorsAreNotSwallowedAsTransientFailures() {
        val file = createTrustedSnapshotTempFile("dashboard_search_error", ".html").apply {
            writeText("nonempty")
        }
        try {
            SnapshotSearchDocumentCache(testSnapshotAllowedRoots) { throw AssertionError("fatal parser error") }
                .loadBlocking(file.path)
        } finally {
            file.delete()
        }
    }

    @Test
    fun transientParserExceptionIsNotCachedAndUnchangedFileIsRetried() {
        val file = createTrustedSnapshotTempFile("dashboard_search_transient", ".html").apply {
            writeText("retry succeeds")
        }
        var parseCount = 0
        val cache = SnapshotSearchDocumentCache(testSnapshotAllowedRoots) { path ->
            parseCount++
            if (parseCount == 1) throw Exception("transient read failure")
            SnapshotData(bodyElements = listOf(BodyElement.TextElement(File(path).readText())))
        }

        assertEquals(DashboardSearchDocument(), cache.loadBlocking(file.path))
        assertEquals(0, cache.cacheSize)
        assertEquals(listOf("retry succeeds"), cache.loadBlocking(file.path).postContents)
        assertEquals(listOf("retry succeeds"), cache.loadBlocking(file.path).postContents)
        assertEquals(2, parseCount)
        assertEquals(1, cache.cacheSize)
        file.delete()
    }

    @Test
    fun cacheIsAccessOrderedBoundedLruAndUsesCanonicalPathKeys() {
        val directory = kotlin.io.path.createTempDirectory("snapshots_dashboard_search_lru").toFile()
        val first = File(directory, "first.html").apply { writeText("first") }
        val second = File(directory, "second.html").apply { writeText("second") }
        val third = File(directory, "third.html").apply { writeText("third") }
        val parseCounts = mutableMapOf<String, Int>()
        val cache = SnapshotSearchDocumentCache(allowedRoots = testSnapshotAllowedRoots, capacity = 2) { path ->
            val canonicalPath = File(path).canonicalPath
            parseCounts[canonicalPath] = parseCounts.getOrDefault(canonicalPath, 0) + 1
            SnapshotData(bodyElements = listOf(BodyElement.TextElement(File(path).readText())))
        }

        cache.loadBlocking(first.path)
        cache.loadBlocking(second.path)
        cache.loadBlocking(File(directory, ".${File.separator}first.html").path) // hit and newest
        cache.loadBlocking(third.path) // evicts second

        assertEquals(2, cache.cacheSize)
        assertEquals(1, parseCounts[first.canonicalPath])
        cache.loadBlocking(second.path)
        assertEquals(2, parseCounts[second.canonicalPath])
        assertEquals(2, cache.cacheSize)
        directory.deleteRecursively()
    }

    @Test
    fun invalidateAndClearRemovePublishedCanonicalEntries() {
        val directory = kotlin.io.path.createTempDirectory("snapshots_dashboard_search_invalidation").toFile()
        val first = File(directory, "first.html").apply { writeText("first") }
        val second = File(directory, "second.html").apply { writeText("second") }
        var parseCount = 0
        val cache = SnapshotSearchDocumentCache(testSnapshotAllowedRoots) { path ->
            parseCount++
            SnapshotData(bodyElements = listOf(BodyElement.TextElement(File(path).readText())))
        }

        cache.loadBlocking(first.path)
        cache.loadBlocking(second.path)
        cache.invalidate(File(directory, ".${File.separator}first.html").path)
        assertEquals(1, cache.cacheSize)
        cache.loadBlocking(first.path)
        assertEquals(3, parseCount)
        cache.clear()
        assertEquals(0, cache.cacheSize)
        cache.loadBlocking(second.path)
        assertEquals(4, parseCount)
        directory.deleteRecursively()
    }

    @Test
    fun fileChangedDuringParseIsReturnedButNotPublishedToCache() {
        val file = createTrustedSnapshotTempFile("dashboard_search_unstable", ".html").apply { writeText("before") }
        var parseCount = 0
        val cache = SnapshotSearchDocumentCache(testSnapshotAllowedRoots) { path ->
            parseCount++
            val parsedText = File(path).readText()
            if (parseCount == 1) File(path).writeText("after with changed length")
            SnapshotData(bodyElements = listOf(BodyElement.TextElement(parsedText)))
        }

        assertEquals(listOf("before"), cache.loadBlocking(file.path).postContents)
        assertEquals(0, cache.cacheSize)
        assertEquals(listOf("after with changed length"), cache.loadBlocking(file.path).postContents)
        assertEquals(2, parseCount)
        assertEquals(1, cache.cacheSize)
        file.delete()
    }

    @Test
    fun snapshotSearchRejectsReadableHtmlOutsideAllowedRootWithoutParsing() {
        val allowedRoot = kotlin.io.path.createTempDirectory("snapshots_dashboard_allowed_root").toFile()
        val outsideRoot = kotlin.io.path.createTempDirectory("snapshots_dashboard_outside_root").toFile()
        try {
            val untrusted = File(File(outsideRoot, "snapshots_imported").apply { mkdir() }, "legacy.HTML")
                .apply { writeText("must not parse") }
            val parses = AtomicInteger(0)
            val cache = SnapshotSearchDocumentCache(allowedRoots = listOf(allowedRoot)) {
                parses.incrementAndGet()
                error("untrusted path reached parser")
            }

            assertEquals(DashboardSearchDocument(), cache.loadBlocking(untrusted.path))
            assertEquals(emptyList<Any>(), snapshotSearchFreshnessToken(untrusted.path, listOf(allowedRoot)))
            assertEquals(0, parses.get())
        } finally {
            allowedRoot.deleteRecursively()
            outsideRoot.deleteRecursively()
        }
    }

    @Test
    fun snapshotSearchRejectsHtmlUnderAllowedRootWithWrongParentName() {
        val allowedRoot = kotlin.io.path.createTempDirectory("snapshots_dashboard_wrong_parent_root").toFile()
        try {
            val untrusted = File(File(allowedRoot, "other").apply { mkdir() }, "legacy.html")
                .apply { writeText("must not parse") }
            val parses = AtomicInteger(0)
            val cache = SnapshotSearchDocumentCache(allowedRoots = listOf(allowedRoot)) {
                parses.incrementAndGet()
                SnapshotData(title = "unexpected")
            }

            assertEquals(DashboardSearchDocument(), cache.loadBlocking(untrusted.path))
            assertEquals(emptyList<Any>(), snapshotSearchFreshnessToken(untrusted.path, listOf(allowedRoot)))
            assertEquals(0, parses.get())
        } finally {
            allowedRoot.deleteRecursively()
        }
    }

    @Test
    fun snapshotSearchAcceptsCurrentImportedAndLegacyHtmlNamesInTrustedDirectories() {
        val allowedRoot = kotlin.io.path.createTempDirectory("snapshots_dashboard_trusted_root").toFile()
        try {
            val currentDirectory = File(allowedRoot, "snapshots_bot-1").apply { mkdir() }
            val importedDirectory = File(allowedRoot, "snapshots_imported").apply { mkdir() }
            val current = File(currentDirectory, "gallery_7_initial.html").apply { writeText("current") }
            val latest = File(currentDirectory, "gallery_7_latest.html").apply { writeText("latest") }
            val legacy = File(importedDirectory, "arbitrary-legacy-name.HTML").apply { writeText("legacy") }
            val cache = SnapshotSearchDocumentCache(allowedRoots = listOf(allowedRoot)) { path ->
                SnapshotData(bodyElements = listOf(BodyElement.TextElement(File(path).readText())))
            }

            assertEquals(listOf("current", "latest"), cache.loadBlocking(current.path).postContents)
            assertEquals(listOf("legacy"), cache.loadBlocking(legacy.path).postContents)
            assertTrue((snapshotSearchFreshnessToken(current.path, listOf(allowedRoot)) as List<*>).isNotEmpty())
            assertTrue((snapshotSearchFreshnessToken(legacy.path, listOf(allowedRoot)) as List<*>).isNotEmpty())
        } finally {
            allowedRoot.deleteRecursively()
        }
    }

    @Test
    fun snapshotSearchRejectsSymlinkWhoseCanonicalTargetIsOutsideAllowedRootWhenSupported() {
        val allowedRoot = kotlin.io.path.createTempDirectory("snapshots_dashboard_symlink_root").toFile()
        val outsideRoot = kotlin.io.path.createTempDirectory("snapshots_dashboard_symlink_outside").toFile()
        try {
            val snapshots = File(allowedRoot, "snapshots_test").apply { mkdir() }
            val outside = File(outsideRoot, "outside.html").apply { writeText("outside") }
            val link = File(snapshots, "linked.html")
            try {
                java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
            } catch (_: UnsupportedOperationException) {
                return
            } catch (_: java.nio.file.FileSystemException) {
                return
            } catch (_: SecurityException) {
                return
            }
            val parses = AtomicInteger(0)
            val cache = SnapshotSearchDocumentCache(allowedRoots = listOf(allowedRoot)) {
                parses.incrementAndGet()
                SnapshotData(title = "unexpected")
            }

            assertEquals(DashboardSearchDocument(), cache.loadBlocking(link.path))
            assertEquals(emptyList<Any>(), snapshotSearchFreshnessToken(link.path, listOf(allowedRoot)))
            assertEquals(0, parses.get())
        } finally {
            allowedRoot.deleteRecursively()
            outsideRoot.deleteRecursively()
        }
    }

    @Test
    fun clearDuringSnapshotParsePreventsStaleResultFromRepopulatingCache() {
        val allowedRoot = kotlin.io.path.createTempDirectory("snapshots_dashboard_clear_race_root").toFile()
        try {
            val file = File(File(allowedRoot, "snapshots_test").apply { mkdir() }, "legacy.html")
                .apply { writeText("content") }
            val parseStarted = CountDownLatch(1)
            val allowParseToFinish = CountDownLatch(1)
            val parses = AtomicInteger(0)
            val cache = SnapshotSearchDocumentCache(allowedRoots = listOf(allowedRoot)) { path ->
                parses.incrementAndGet()
                parseStarted.countDown()
                assertTrue(allowParseToFinish.await(5, TimeUnit.SECONDS))
                SnapshotData(bodyElements = listOf(BodyElement.TextElement(File(path).readText())))
            }
            val worker = Thread { cache.loadBlocking(file.path) }

            worker.start()
            assertTrue(parseStarted.await(5, TimeUnit.SECONDS))
            cache.clear()
            allowParseToFinish.countDown()
            worker.join(5_000)
            assertFalse(worker.isAlive)
            assertEquals(0, cache.cacheSize)

            cache.loadBlocking(file.path)
            assertEquals(2, parses.get())
            assertEquals(1, cache.cacheSize)
        } finally {
            allowedRoot.deleteRecursively()
        }
    }
}
