package com.heyheyon.armbandbot

import android.content.Context
import android.content.ContextWrapper
import android.os.Looper
import android.util.Log
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real Room/search/cache/publication; only parser timing and post-work failure are controlled. */
class DbDashboardLoaderUiTest {
    // These loader-only tests do not call Android Toast APIs. The default effect
    // interceptor permits the real withContext(IO) parser to run off Main.
    @get:Rule val compose = createComposeRule()

    @Test fun oldPullRefreshSuccessCannotPublishAfterABAWhileFreshSearchIsHeld() = staleCompletion(false)

    @Test fun oldPullRefreshLoaderErrorCannotPublishAfterABAWhileFreshSearchIsHeld() = staleCompletion(true)

    @Test fun freshLoaderErrorPublishesAndScopeSwitchClearsIt() = withFixture { f ->
        f.select(A)
        f.waitRow(BASELINE)
        val broken = f.replace(A, "fresh error", "41")
        val error = f.dependencies.arm(broken, loaderError = true)
        f.search()
        f.awaitEntry(error, A)
        f.assertLoading()
        error.open()
        f.awaitCaller(error)
        assertTrue(error.call().loaderErrorThrown)
        compose.onNodeWithText(ERROR).assertIsDisplayed()
        compose.onNodeWithText(LOADING).assertDoesNotExist()

        val b = f.dependencies.arm(f.bPath)
        f.select(B)
        f.awaitEntry(b, B)
        f.assertEmptyLoading("fresh error", BASELINE, B_TITLE)
        b.open()
        f.waitMatch(B_TITLE)

        val recovery = f.dependencies.arm(f.replace(A, FRESH, "42"))
        f.select(A)
        f.awaitEntry(recovery, A)
        f.assertEmptyLoading("fresh error", BASELINE, B_TITLE, FRESH)
        recovery.open()
        f.awaitCaller(recovery)
        f.assertOnlyFresh()
    }

    @Test fun delayedOrdinaryParserFailureIsAnEmptyDocumentNotALoaderError() = withFixture { f ->
        f.select(A)
        f.waitRow(BASELINE)
        val gate = f.dependencies.arm(f.replace(A, "parser failure", "51"), parserError = true)
        f.search()
        f.awaitEntry(gate, A)
        f.assertLoading()
        gate.open()
        f.awaitCaller(gate)
        assertTrue(gate.parserErrorThrown)
        assertTrue(gate.call().workReturned)
        assertFalse(gate.call().loaderErrorThrown)
        f.assertAbsent("parser failure", BASELINE, B_TITLE)
        f.badges().assertCountEquals(0)
        compose.onNodeWithText(ERROR).assertDoesNotExist()
        compose.onNodeWithText(LOADING).assertDoesNotExist()
        compose.onNodeWithText("조건에 맞는 모니터링 기록이 없습니다.").assertIsDisplayed()
    }

    private fun staleCompletion(loaderError: Boolean) = withFixture { f ->
        f.select(A)
        f.waitRow(BASELINE)
        f.search()
        f.waitMatch(BASELINE)
        val old = f.dependencies.arm(f.replace(A, OLD, "21"), loaderError = loaderError)
        // The real pull refresh launches in rememberCoroutineScope, NOT the keyed effect.
        compose.onNode(hasScrollToIndexAction() and hasAnyDescendant(hasText("제목: $BASELINE")))
            .performTouchInput {
                swipe(Offset(center.x, height * 0.05f), Offset(center.x, height * 0.95f), 800)
            }
        f.awaitEntry(old, A)
        assertTrue(old.call().job.isActive)

        val b = f.dependencies.arm(f.bPath)
        f.select(B)
        f.awaitEntry(b, B)
        f.assertEmptyLoading(BASELINE, OLD, B_TITLE)
        b.open()
        f.waitMatch(B_TITLE)
        f.assertAbsent(BASELINE, OLD)

        val fresh = f.dependencies.arm(f.replace(A, FRESH, "31"))
        f.select(A)
        f.awaitEntry(fresh, A)
        assertNotEquals(old.call().request.epoch, fresh.call().request.epoch)
        assertNotEquals(old.call().version, fresh.call().version)
        assertNotSame(old.call().job, fresh.call().job)
        f.assertEmptyLoading(BASELINE, OLD, B_TITLE, FRESH)
        assertTrue("Scope changes must not cancel old pull-refresh", old.call().job.isActive)

        old.open()
        // Completion is the original MAIN caller Job, after publication and refresh finally.
        f.awaitCaller(old)
        assertTrue(old.call().workReturned)
        assertEquals(loaderError, old.call().loaderErrorThrown)
        assertEquals("Newest parser must still be held", 1L, fresh.release.count)
        assertFalse(fresh.call().job.isCompleted)
        f.assertEmptyLoading(BASELINE, OLD, B_TITLE, FRESH)

        fresh.open()
        f.awaitCaller(fresh)
        f.assertOnlyFresh()
    }

    private fun withFixture(test: (Fixture) -> Unit) {
        val fixture = Fixture()
        try {
            fixture.show()
            test(fixture)
            fixture.dependencies.assertHealthy()
        } finally {
            fixture.close()
        }
    }

    private inner class Fixture {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "dashboard_loader_${UUID.randomUUID()}_"
        val prefs = Collections.synchronizedSet(mutableSetOf<String>())
        val root = File(base.cacheDir, prefix).apply { check(mkdirs()) }
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int) =
                base.getSharedPreferences(prefix + name, mode).also { prefs.add(prefix + name) }
            override fun getCacheDir() = root
        }
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val dependencies = ControlledDependencies()
        val visible = mutableStateOf(true)
        private var selectedLabel = "전체"
        val bPath: String
        init {
            context.getSharedPreferences("bot_master", 0).edit()
                .putString("bot_ids_list", "$A,$B")
                .putStringSet("db_search_scopes", setOf("POST_CONTENT")).commit()
            listOf(A, B).forEach { id ->
                context.getSharedPreferences("bot_prefs_$id", 0).edit()
                    .putString("bot_name", if (id == A) "A" else "B")
                    .putBoolean("independent_scan_state_enabled", true).commit()
            }
            replace(A, BASELINE, "11")
            bPath = replace(B, B_TITLE, "12")
        }
        fun replace(scope: String, title: String, number: String): String {
            val file = File(root, "snapshots_fixture/$number.html")
            file.parentFile!!.mkdirs()
            file.writeText("<html><body><div class=\"write_div\"><p>$QUERY</p></div></body></html>")
            db.runInTransaction {
                db.postDao().deletePostsForScope(scope)
                db.postDao().insertOrUpdate(CheckedPost(gallType = "M", gallId = "fixture-gallery",
                    postNum = number, commentCount = 0, scopeId = scope, title = title,
                    author = "fixture author", snapshotPath = file.canonicalPath))
            }
            return file.canonicalPath
        }
        fun show() {
            val registryOwner = object : ActivityResultRegistryOwner {
                override val activityResultRegistry = object : ActivityResultRegistry() {
                    override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                        error("Fixture must not launch external activities")
                    }
                }
            }
            compose.setContent {
                if (visible.value) CompositionLocalProvider(LocalContext provides context,
                    LocalActivityResultRegistryOwner provides registryOwner) {
                    MaterialTheme { DbDashboardScreen(GLOBAL_SCAN_SCOPE, {}, database = db, loaderDependencies = dependencies) }
                }
            }
            waitRow(BASELINE)
            compose.waitUntil(10_000) { !exists(LOADING) }
        }
        fun select(id: String) {
            val label = dashboardScopeOptions(listOf(DashboardScopeBot(id, if (id == A) "A" else "B", true)), emptySet())
                .single { it.scope == DashboardRecordScope.Exact(id) }.label
            compose.onNodeWithTag("record-filter-button").performClick()
            compose.onNodeWithTag("record-filter-scope-$id").performScrollTo().performClick().assertIsSelected()
            compose.onNodeWithTag("record-filter-apply").performClick()
            selectedLabel = label
        }
        fun search() {
            compose.onNode(hasSetTextAction()).performTextInput(QUERY)
            compose.onNode(hasSetTextAction()).performImeAction()
        }
        fun exists(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        fun waitRow(title: String) {
            compose.waitUntil(10_000) { exists("제목: $title") }
        }
        fun badges() = compose.onAllNodes(hasText("글 내용") and hasAnyAncestor(
            hasClickAction() and hasAnyDescendant(hasText("제목: ", substring = true))), useUnmergedTree = true)
        fun waitMatch(title: String) {
            compose.waitUntil(10_000) { exists("제목: $title") && !exists(LOADING) }
            compose.onNode(hasText("글 내용") and hasAnyAncestor(
                hasClickAction() and hasAnyDescendant(hasText("제목: $title"))), useUnmergedTree = true)
                .assertIsDisplayed()
            compose.onNodeWithText(ERROR).assertDoesNotExist()
        }
        fun assertAbsent(vararg titles: String) {
            titles.forEach { compose.onNodeWithText("제목: $it").assertDoesNotExist() }
        }
        fun assertLoading() {
            compose.onNodeWithText(LOADING).assertIsDisplayed()
            compose.onNodeWithText(ERROR).assertDoesNotExist()
        }
        fun assertEmptyLoading(vararg titles: String) {
            assertAbsent(*titles)
            badges().assertCountEquals(0)
            assertLoading()
        }
        fun assertOnlyFresh() {
            waitMatch(FRESH)
            assertAbsent(BASELINE, OLD, B_TITLE, "fresh error")
            badges().assertCountEquals(1)
            compose.onNodeWithText(LOADING).assertDoesNotExist()
        }
        fun awaitEntry(gate: Gate, scope: String) {
            compose.waitUntil(10_000) { gate.entered.count == 0L }
            dependencies.assertHealthy()
            assertEquals(DashboardRecordScope.Exact(scope), gate.call().request.scope)
            assertEquals(QUERY, gate.call().query)
            assertEquals("Parser entered before release: ${gate.path}", 1L, gate.release.count)
        }
        fun awaitCaller(gate: Gate) {
            compose.waitUntil(10_000) { gate.call().completed.count == 0L }
            compose.waitForIdle()
            dependencies.assertHealthy()
            assertNull("Caller canceled/failed: ${gate.call()}", gate.call().completionCause)
            assertTrue(gate.call().job.isCompleted)
        }
        fun close() {
            dependencies.gates.values.forEach { it.open() }
            try {
                compose.runOnIdle { visible.value = false }
                compose.waitForIdle()
                compose.waitUntil(10_000) { dependencies.calls.all { it.job.isCompleted } }
            } finally {
                db.close()
                root.deleteRecursively()
                prefs.toList().forEach { base.deleteSharedPreferences(it) }
            }
        }
    }

    private class Call(val request: DashboardScopeRequest, val version: Int, val query: String, val job: Job) {
        val completed = CountDownLatch(1)
        @Volatile var completionCause: Throwable? = null
        @Volatile var workReturned = false
        @Volatile var loaderErrorThrown = false
        @Volatile var failAfterWork = false
        override fun toString() = "$request version=$version query=$query"
    }

    private class Gate(val path: String, val loaderError: Boolean, val parserError: Boolean) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val caller = AtomicReference<Call>()
        @Volatile var parserErrorThrown = false
        fun call(): Call = checkNotNull(caller.get()) { "No parser entry: $path" }
        fun open() { release.countDown() }
    }

    private class ControlledDependencies : DashboardLoaderDependencies {
        val gates = ConcurrentHashMap<String, Gate>()
        val calls = CopyOnWriteArrayList<Call>()
        private val failures = CopyOnWriteArrayList<String>()
        private val current = ThreadLocal<Call>()
        fun arm(path: String, loaderError: Boolean = false, parserError: Boolean = false): Gate =
            Gate(File(path).canonicalPath, loaderError, parserError).also {
                check(gates.putIfAbsent(it.path, it) == null) { "Gate already armed: $path" }
            }
        fun assertHealthy() { assertTrue(failures.joinToString("\n"), failures.isEmpty()) }
        override suspend fun <T> generalWork(request: DashboardScopeRequest, version: Int, query: String, work: suspend () -> T): T {
            // Capture before withContext: IO child completion cannot prove publisher completion.
            val call = Call(request, version, query, checkNotNull(currentCoroutineContext()[Job]))
            calls.add(call)
            call.job.invokeOnCompletion { cause ->
                call.completionCause = cause
                Log.i(TAG, "caller-completed $call cause=$cause returned=${call.workReturned} injected=${call.loaderErrorThrown}")
                call.completed.countDown()
            }
            Log.i(TAG, "work-enter $call")
            val result = withContext(current.asContextElement(call)) { work() }
            call.workReturned = true
            if (call.failAfterWork) {
                call.loaderErrorThrown = true
                Log.i(TAG, "loader-error $call")
                throw IOException("R1 post-work loader failure: $call")
            }
            return result
        }
        override fun parseSnapshotFile(path: String): SnapshotData {
            Log.i(TAG, "parser-dispatch path=$path gates=${gates.keys} context=${current.get()}")
            val gate = gates[File(path).canonicalPath] ?: return parseSnapshot(path)
            val call = current.get() ?: run {
                failures.add("Parser missing request context: $path")
                throw IOException("Parser missing request context: $path")
            }
            check(Looper.myLooper() != Looper.getMainLooper()) { "Parser must never block Main" }
            if (!gate.caller.compareAndSet(null, call)) {
                failures.add("Unexpected duplicate parser entry: $path $call")
                throw IOException("Duplicate gate entry: $path")
            }
            call.failAfterWork = gate.loaderError
            Log.i(TAG, "parser-enter $path $call")
            gate.entered.countDown()
            if (!gate.release.await(45, TimeUnit.SECONDS)) {
                failures.add("Parser gate timed out: $path $call")
                throw IOException("Gate timed out: $path")
            }
            Log.i(TAG, "parser-release $path $call")
            if (gate.parserError) {
                gate.parserErrorThrown = true
                throw IOException("R1 ordinary parser failure: $path")
            }
            return parseSnapshot(path)
        }
    }

    private companion object {
        const val A = "loader-a"
        const val B = "loader-b"
        const val QUERY = "r1needle"
        const val BASELINE = "baseline alpha"
        const val OLD = "obsolete alpha"
        const val B_TITLE = "bravo snapshot"
        const val FRESH = "fresh alpha"
        const val LOADING = "검색 중..."
        const val ERROR = "모니터링 기록을 불러오지 못했습니다."
        const val TAG = "R1DashboardLoader"
    }
}
