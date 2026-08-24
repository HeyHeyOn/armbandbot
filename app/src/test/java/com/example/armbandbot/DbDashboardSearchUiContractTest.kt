package com.heyheyon.armbandbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DbDashboardSearchUiContractTest {
    private val source by lazy {
        val candidates = listOf(
            File("app/src/main/java/com/example/armbandbot/DbDashboardScreen.kt"),
            File("src/main/java/com/example/armbandbot/DbDashboardScreen.kt"),
        )
        candidates.firstOrNull(File::isFile)?.readText()?.replace("\r\n", "\n")
            ?: error("DbDashboardScreen.kt not found from ${File(".").absolutePath}")
    }

    private fun sourceBetween(start: String, end: String): String {
        val startIndex = source.indexOf(start)
        require(startIndex >= 0) { "Start marker not found: $start" }
        val endIndex = source.indexOf(end, startIndex + start.length)
        require(endIndex >= 0) { "End marker not found after $start: $end" }
        return source.substring(startIndex, endIndex)
    }

    @Test
    fun searchFieldUsesShortPlaceholderAndAccessibleScopeButton() {
        assertTrue(source.contains("placeholder = { Text(\"검색\""))
        assertFalse(source.contains("글 번호, 작성자, 제목, 글·댓글 내용 검색..."))
        assertTrue(source.contains("Icons.Filled.FilterAlt"))
        assertTrue(source.contains("contentDescription = \"검색 범위 설정\""))
    }

    @Test
    fun scopeDialogAndCompactSharedBadgesAreTopLevelUiContracts() {
        assertTrue(source.contains("private fun DashboardSearchScopeDialog("))
        assertTrue(source.contains("private fun DashboardSearchMatchBadges("))
        assertTrue(source.contains("horizontalScroll(rememberScrollState())"))
        assertFalse(source.contains("Text(label, fontSize = 11.sp, color = searchMatchAccent"))
    }

    @Test
    fun allThreeDashboardCardsCallTheSharedBadgeComposable() {
        assertEquals(3, Regex("DashboardSearchMatchBadges\\s*\\(").findAll(source).count() - 1)
    }

    @Test
    fun queryDebounceIsSeparateFromImmediateScopeReload() {
        assertTrue(
            "A separate query snapshot must back dashboard loads",
            Regex(
                "var\\s+debouncedSearchQuery\\s+by\\s+remember\\s*\\{\\s*" +
                    "mutableStateOf\\(searchQuery\\)\\s*}",
            ).containsMatchIn(source),
        )

        val debounceEffect = sourceBetween(
            "LaunchedEffect(searchQuery, isClearingDb) {",
            "LaunchedEffect(tabIndex, selectedGall",
        )
        assertTrue("DB clear must cancel and suppress pending debounce work", "if (isClearingDb) return@LaunchedEffect" in debounceEffect)
        assertTrue(
            "Only raw query changes should wait for the 300 ms debounce; blank clears immediately",
            Regex(
                "if\\s*\\(searchQuery\\.isNotBlank\\(\\)\\)\\s*\\{?\\s*delay\\(300\\)\\s*}?\\s*" +
                    "debouncedSearchQuery\\s*=\\s*searchQuery",
            ).containsMatchIn(debounceEffect),
        )

        val dataLoadEffect = Regex(
            "LaunchedEffect\\(([^)]*activeSearchScopes[^)]*)\\)\\s*\\{([\\s\\S]*?)" +
                "\\r?\\n    }\\r?\\n\\r?\\n    val generalPullRefreshState",
        ).find(source) ?: error("Main dashboard data-load effect not found")
        val keys = dataLoadEffect.groupValues[1]
        val body = dataLoadEffect.groupValues[2]
        assertTrue("Scope changes must remain a direct reload key", "activeSearchScopes" in keys)
        assertTrue("Loads must be keyed by the settled query", "debouncedSearchQuery" in keys)
        assertTrue("Equal settled query values must still reload via generation", "debouncedSearchGeneration" in keys)
        assertTrue("DB clearing must cancel/restart the main effect", "isClearingDb" in keys)
        assertFalse("Raw typing must not directly restart the data-load effect", Regex("\\bsearchQuery\\b").containsMatchIn(keys))
        assertFalse("Scope-triggered reloads must not wait for query debounce", "delay(300)" in body)
        assertTrue("The main effect must not start a load while clearing", "if (isClearingDb) return@LaunchedEffect" in body)

        assertEquals(
            "All three loaders must snapshot the settled query",
            3,
            Regex("val\\s+query\\s*=\\s*debouncedSearchQuery").findAll(source).count(),
        )
        assertFalse(
            "No loader may snapshot the raw query",
            Regex("val\\s+query\\s*=\\s*searchQuery").containsMatchIn(source),
        )
    }

    @Test
    fun rawQueryInvalidatesCurrentTabBeforeDebounceDelay() {
        val effect = sourceBetween(
            "LaunchedEffect(searchQuery, isClearingDb) {",
            "LaunchedEffect(tabIndex, selectedGall",
        )
        val delayIndex = effect.indexOf("delay(300)")
        assertTrue("The query effect must still debounce non-blank input", delayIndex >= 0)
        val beforeDelay = effect.substring(0, delayIndex)

        assertTrue(
            "Raw typing must invalidate the current tab before waiting",
            Regex("when\\s*\\(tabIndex\\)\\s*\\{[\\s\\S]*generalLoadVersion\\+\\+[\\s\\S]*blockLoadVersion\\+\\+[\\s\\S]*holdLoadVersion\\+\\+")
                .containsMatchIn(beforeDelay),
        )
        assertTrue("General loading must be shown immediately", "isGeneralSearchLoading = true" in beforeDelay)
        assertTrue("Block loading must be shown immediately", "isBlockSearchLoading = true" in beforeDelay)
        assertTrue("Hold loading must be shown immediately", "isHoldSearchLoading = true" in beforeDelay)
        assertTrue("General errors must be cleared immediately", "generalLoadError = null" in beforeDelay)
        assertTrue("Block errors must be cleared immediately", "blockLoadError = null" in beforeDelay)
        assertTrue("Hold errors must be cleared immediately", "holdLoadError = null" in beforeDelay)
    }

    @Test
    fun everyCompletedDebounceAdvancesGenerationEvenWhenSettledValueIsEqual() {
        assertTrue(source.contains("var debouncedSearchGeneration by remember { mutableIntStateOf(0) }"))
        val effect = sourceBetween(
            "LaunchedEffect(searchQuery, isClearingDb) {",
            "LaunchedEffect(tabIndex, selectedGall",
        )
        val assignment = effect.indexOf("debouncedSearchQuery = searchQuery")
        val increment = effect.indexOf("debouncedSearchGeneration++")
        assertTrue("Generation must advance after every completed debounce", assignment >= 0 && increment > assignment)
    }

    @Test
    fun clearDatabaseInvalidatesEveryLoaderBeforeLaunchingDelete() {
        val clearDialog = sourceBetween(
            "if (showClearDbConfirm) {",
            "if (pendingDeletePost != null",
        )
        val onClick = clearDialog.substring(clearDialog.indexOf("onClick = {") + "onClick = {".length)
        val beforeLaunch = onClick.substringBefore("coroutineScope.launch {")

        assertTrue("Clear mode must begin synchronously", "isClearingDb = true" in beforeLaunch)
        assertTrue("Data epoch must advance synchronously", "dashboardDataEpoch++" in beforeLaunch)

        listOf("generalLoadVersion", "blockLoadVersion", "holdLoadVersion").forEach { version ->
            assertTrue("$version must be invalidated before launching DB deletion", "$version++" in beforeLaunch)
        }
        listOf("isGeneralSearchLoading", "isBlockSearchLoading", "isHoldSearchLoading").forEach { loading ->
            assertTrue("$loading must be reset before launching DB deletion", "$loading = false" in beforeLaunch)
        }
        listOf("generalLoadError", "blockLoadError", "holdLoadError").forEach { error ->
            assertTrue("$error must be cleared before launching DB deletion", "$error = null" in beforeLaunch)
        }
        val launchBody = onClick.substringAfter("coroutineScope.launch {")
        assertTrue("Clear mode must end only after empty-state/cache cleanup", launchBody.indexOf("isClearingDb = false") > launchBody.indexOf("recordedPostCount = 0"))
    }

    @Test
    fun allLoadersUseVersionQueryEpochAndClearingPublicationPolicy() {
        assertTrue(source.contains("var dashboardDataEpoch by remember { mutableIntStateOf(0) }"))
        assertTrue(source.contains("var isClearingDb by remember { mutableStateOf(false) }"))
        assertEquals(3, Regex("val\\s+requestDataEpoch\\s*=\\s*dashboardDataEpoch").findAll(source).count())
        assertEquals(6, Regex("shouldPublishDashboardSearchResult\\s*\\(").findAll(source).count())
        assertEquals(
            "Every success/error publication check must compare the captured and current scopes",
            6,
            Regex("requestDataEpoch,\\s*dashboardDataEpoch,\\s*enabledCodes,\\s*activeSearchScopes,\\s*isClearingDb")
                .findAll(source).count(),
        )
        assertEquals(3, Regex("if\\s*\\(isClearingDb\\)\\s*\\{[\\s\\S]*?SearchLoading\\s*=\\s*false[\\s\\S]*?return").findAll(source).count())
    }

    @Test
    fun applyingUnchangedScopesOnlyClosesDialogBeforeAnyInvalidation() {
        val onApply = sourceBetween(
            "onApply = { scopes ->",
            "            },\n        )",
        )
        val unchangedGuard = onApply.indexOf("if (scopes == activeSearchScopes) {")
        val firstInvalidation = onApply.indexOf("generalLoadVersion++")
        assertTrue(
            "Equal scopes must be guarded before any loader invalidation",
            unchangedGuard >= 0 && firstInvalidation > unchangedGuard,
        )

        val unchangedBranch = onApply
            .substring(unchangedGuard)
            .substringBefore("} else {")
        assertTrue("Equal scopes must close the dialog", "showSearchScopeDialog = false" in unchangedBranch)
        listOf(
            "activeSearchScopes =",
            "LoadVersion++",
            "Posts = emptyList()",
            "Matches = emptyMap()",
            "LoadError = null",
            "Limit = 100",
            "masterPref.edit()",
        ).forEach { forbiddenMutation ->
            assertFalse(
                "Equal scopes must not take the mutation/persistence path: $forbiddenMutation",
                forbiddenMutation in unchangedBranch,
            )
        }
    }

    @Test
    fun applyingScopesSynchronouslyInvalidatesEveryLoaderAndClearsDisplayedSearchResults() {
        val onApply = sourceBetween(
            "onApply = { scopes ->",
            "            },\n        )",
        )
        assertTrue("The new scopes must be installed in the same callback", "activeSearchScopes = scopes" in onApply)

        listOf("general", "block", "hold").forEach { loader ->
            assertTrue(
                "${loader}LoadVersion must be invalidated synchronously when scopes change",
                "${loader}LoadVersion++" in onApply,
            )
            assertTrue(
                "Old $loader rows must be removed synchronously when scopes change",
                "${loader}Posts = emptyList()" in onApply,
            )
            assertTrue(
                "Old $loader match badges must be removed synchronously when scopes change",
                "${loader}Matches = emptyMap()" in onApply,
            )
        }
    }

    @Test
    fun scopeDialogUsesToggleableCheckboxRowsWithPassiveIndicators() {
        assertTrue(source.contains("import androidx.compose.foundation.selection.toggleable"))
        assertTrue(source.contains("import androidx.compose.ui.semantics.Role"))

        val dialog = sourceBetween(
            "private fun DashboardSearchScopeDialog(",
            "private fun DashboardSearchMatchBadges(",
        )
        assertEquals(
            "The all row and the repeated item row must own checkbox semantics and touch handling",
            2,
            Regex("\\.toggleable\\s*\\([\\s\\S]*?role\\s*=\\s*Role\\.Checkbox[\\s\\S]*?onValueChange\\s*=")
                .findAll(dialog).count(),
        )
        assertEquals(
            "Checkbox children must be passive indicators so each row exposes one control",
            2,
            Regex("Checkbox\\s*\\([\\s\\S]*?onCheckedChange\\s*=\\s*null[\\s\\S]*?\\)").findAll(dialog).count(),
        )
        assertTrue("Both checkbox rows must fill the dialog width", Regex("\\.fillMaxWidth\\(\\)").findAll(dialog).count() >= 2)
        assertTrue(
            "The all-row toggle must preserve the ALL/empty contract",
            "draft = if (checked) ALL_DASHBOARD_SEARCH_MATCH_CODES else emptySet()" in dialog,
        )
        assertTrue(
            "Individual row toggles must preserve add/remove behavior",
            "draft = if (checked) draft + code else draft - code" in dialog,
        )
    }
}
