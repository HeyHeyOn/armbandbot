package com.heyheyon.armbandbot

import android.content.ContextWrapper
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.asAndroidBitmap
import com.heyheyon.armbandbot.ui.LocalIsDarkMode
import androidx.room.Room
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.RootMatchers
import androidx.test.espresso.matcher.ViewMatchers
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Collections
import java.util.UUID
import org.hamcrest.Matcher
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DbDashboardRecordFilterUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun draftCancelApplyAndReopenKeepTheCommittedPair() {
        var gallery by mutableStateOf<Set<String>?>(null)
        var scope by mutableStateOf<DashboardRecordScope>(DashboardRecordScope.All)
        var open by mutableStateOf(true)
        var calls = 0
        val options = dashboardScopeOptions(listOf(DashboardScopeBot("private-id", "동일", true), DashboardScopeBot("other-id", "동일", true)), emptySet())
        compose.setContent { MaterialTheme {
            if (open) DashboardRecordFilterDialog(gallery, scope, listOf("real-gallery"), options, true,
                onDismiss = { open = false }, onApply = { g, s -> gallery = g; scope = s; calls++; open = false })
        } }
        fun draft() {
            compose.selectOnlyRecordFilter("gallery", "real-gallery")
            compose.selectOnlyRecordFilter("scope", "private-id")
        }
        draft()
        compose.runOnIdle { assertNull(gallery); assertEquals(DashboardRecordScope.All, scope); assertEquals(0, calls) }
        compose.onNodeWithTag("record-filter-cancel").performClick()
        compose.runOnIdle { open = true }
        compose.onNodeWithTag("record-filter-gallery-ALL").assertIsOn()
        compose.onNodeWithTag("record-filter-scope-ALL").performScrollTo().assertIsOn()
        draft()
        compose.onNodeWithTag("record-filter-apply").performClick()
        compose.runOnIdle { assertEquals(setOf("real-gallery"), gallery); assertEquals(DashboardRecordScope.Exact("private-id"), scope); assertEquals(1, calls); open = true }
        compose.onNodeWithTag("record-filter-gallery-real-gallery").assertIsOn()
        compose.onNodeWithTag("record-filter-scope-private-id").performScrollTo().assertIsOn()
    }

    @Test fun popupDraftBackDismissalKeepsAppliedSummaryResultsAndReopenSelections() =
        exerciseDashboardDismissal {
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        }

    @Test fun popupDraftOutsideDismissalKeepsAppliedSummaryResultsAndReopenSelections() =
        exerciseDashboardDismissal { tapOutsideObservedDialog() }

    @androidx.test.filters.SdkSuppress(minSdkVersion = 26)
    @Test fun darkAppFilterUsesVisibleLocalColorsDespiteLightMaterialTheme() =
        exerciseDashboardDismissal(dark = true) { InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK) }

    @androidx.test.filters.SdkSuppress(minSdkVersion = 26)
    @Test fun lightAppFilterUsesLocalColorsDespiteDarkMaterialTheme() =
        exerciseDashboardDismissal(dark = false) { InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK) }

    private fun assertRenderedColor(tag: String, color: androidx.compose.ui.graphics.Color, minimum: Int = 8) {
        val image = compose.onNodeWithTag(tag).captureToImage()
        val evidence = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "filter-theme").apply { mkdirs() }
        File(evidence, "$tag-${color.value}.png").outputStream().use {
            image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        val pixels = image.toPixelMap()
        var matching = 0
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            val pixel = pixels[x, y]
            if (kotlin.math.abs(pixel.red - color.red) < 0.025f &&
                kotlin.math.abs(pixel.green - color.green) < 0.025f &&
                kotlin.math.abs(pixel.blue - color.blue) < 0.025f) matching++
        }
        assertTrue("$tag must render $color; found $matching pixels, expected >= $minimum", matching >= minimum)
    }

    private fun exerciseDashboardDismissal(dark: Boolean? = null, dismiss: () -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "dashboard_dismissal_${UUID.randomUUID()}_"
        val prefs = Collections.synchronizedSet(mutableSetOf<String>())
        val root = File(base.cacheDir, prefix).apply { check(mkdirs()) }
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int) =
                base.getSharedPreferences(prefix + name, mode).also { prefs.add(prefix + name) }
            override fun getCacheDir() = root
        }
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        var visible by mutableStateOf(true)
        val registryOwner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry = object : ActivityResultRegistry() {
                override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                    error("Dismissal fixture must not launch external activities")
                }
            }
        }
        val appliedTitle = "dismissal applied row"
        val excludedTitles = listOf("dismissal scope-only row", "dismissal gallery-only row", "dismissal draft row")
        fun exists(title: String) = compose.onAllNodesWithText("제목: $title").fetchSemanticsNodes().isNotEmpty()
        fun assertAppliedResults() {
            compose.waitUntil(10_000) { exists(appliedTitle) && excludedTitles.none { exists(it) } }
            compose.onNodeWithTag("record-filter-summary").assertTextEquals("applied-gallery, 공용")
            compose.onNodeWithText("제목: $appliedTitle").assertIsDisplayed()
            excludedTitles.forEach { compose.onNodeWithText("제목: $it").assertDoesNotExist() }
        }
        try {
            context.getSharedPreferences("bot_master", 0).edit()
                .putString("bot_ids_list", "dismissal-private").commit()
            context.getSharedPreferences("bot_prefs_dismissal-private", 0).edit()
                .putString("bot_name", "Dismissal private")
                .putBoolean("independent_scan_state_enabled", true).commit()
            // Cross-product rows detect accidental application of either dimension alone.
            listOf(
                Triple("applied-gallery", GLOBAL_SCAN_SCOPE, appliedTitle),
                Triple("applied-gallery", "dismissal-private", excludedTitles[0]),
                Triple("draft-gallery", GLOBAL_SCAN_SCOPE, excludedTitles[1]),
                Triple("draft-gallery", "dismissal-private", excludedTitles[2]),
            ).forEachIndexed { index, (gallery, scope, title) ->
                db.postDao().insertOrUpdate(CheckedPost(gallType = "M", gallId = gallery,
                    postNum = index.toString(), commentCount = 0, checkTime = 100L - index,
                    scopeId = scope, title = title))
            }
            compose.setContent {
                if (visible) CompositionLocalProvider(LocalContext provides context,
                    LocalActivityResultRegistryOwner provides registryOwner,
                    LocalIsDarkMode provides (dark ?: false)) {
                    MaterialTheme(colorScheme = if (dark == false) darkColorScheme() else lightColorScheme()) {
                        DbDashboardScreen(GLOBAL_SCAN_SCOPE, {}, database = db)
                    }
                }
            }
            compose.waitUntil(10_000) { exists(appliedTitle) }
            if (dark != null) assertRenderedColor("record-filter-button", if (dark) Color(0xFFAAAEB3) else Color.Gray)
            compose.onNodeWithTag("record-filter-button").performClick()
            if (dark != null) {
                assertRenderedColor("record-filter-dialog", if (dark) Color(0xFF222A33) else Color.White, 500)
                assertRenderedColor("record-filter-gallery-ALL", if (dark) Color(0xFFE6E9ED) else Color(0xFF1C2733))
                assertRenderedColor("record-filter-gallery-ALL", if (dark) Color(0xFF9DB4CF) else Color(0xFF4A6583))
                compose.onNodeWithTag("record-filter-gallery-applied-gallery").performClick().assertIsOff()
                assertRenderedColor("record-filter-gallery-applied-gallery", if (dark) Color(0xFFA0A8B3) else Color(0xFF66717E))
            }
            saveBeta4UiEvidence("filter-local-theme-$dark")
            compose.selectOnlyRecordFilter("gallery", "applied-gallery")
            compose.selectOnlyRecordFilter("scope", "$GLOBAL_SCAN_SCOPE")
            compose.onNodeWithTag("record-filter-apply").performClick()
            assertAppliedResults()

            compose.onNodeWithTag("record-filter-button").performClick()
            compose.selectOnlyRecordFilter("gallery", "draft-gallery").assertIsOn()
            compose.selectOnlyRecordFilter("scope", "dismissal-private").assertIsOn()
            // Draft changes must not leak into the actual dashboard, even before dismissal.
            compose.onNodeWithTag("record-filter-summary").assertTextEquals("applied-gallery, 공용")
            compose.onNodeWithText("제목: $appliedTitle").assertExists()
            excludedTitles.forEach { compose.onNodeWithText("제목: $it").assertDoesNotExist() }
            compose.waitForIdle()
            dismiss()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag("record-filter-dialog").fetchSemanticsNodes().isEmpty()
            }
            compose.onAllNodes(isDialog()).assertCountEquals(0)
            assertAppliedResults()

            compose.onNodeWithTag("record-filter-button").performClick()
            compose.onNodeWithTag("record-filter-gallery-applied-gallery").performScrollTo().assertIsOn()
            compose.onNodeWithTag("record-filter-gallery-draft-gallery").performScrollTo().assertIsOff()
            compose.onNodeWithTag("record-filter-gallery-ALL").performScrollTo().assertIsOff()
            compose.onNodeWithTag("record-filter-scope-$GLOBAL_SCAN_SCOPE").performScrollTo().assertIsOn()
            compose.onNodeWithTag("record-filter-scope-dismissal-private").performScrollTo().assertIsOff()
            compose.onNodeWithTag("record-filter-scope-ALL").performScrollTo().assertIsOff()
            compose.onNodeWithTag("record-filter-cancel").performClick()
            assertAppliedResults()
            assertEquals(4, db.postDao().getAllPostsForBackupMerge().size)
        } finally {
            compose.runOnIdle { visible = false }
            compose.waitForIdle()
            db.close()
            root.deleteRecursively()
            prefs.forEach { base.deleteSharedPreferences(it) }
        }
    }

    private fun tapOutsideObservedDialog() {
        val bounds = Rect()
        val display = Rect()
        // Read Android's real dialog window in screen coordinates, not a hard-coded
        // emulator size or Compose coordinates belonging to a different root.
        onView(ViewMatchers.isRoot()).inRoot(RootMatchers.isDialog()).perform(object : ViewAction {
            override fun getConstraints(): Matcher<View> = ViewMatchers.isRoot()
            override fun getDescription() = "capture the current dialog window and visible display bounds"
            override fun perform(uiController: UiController, view: View) {
                uiController.loopMainThreadUntilIdle()
                val location = IntArray(2)
                view.getLocationOnScreen(location)
                bounds.set(location[0], location[1], location[0] + view.width, location[1] + view.height)
                view.getWindowVisibleDisplayFrame(display)
            }
        })
        assertFalse("Dialog window bounds must be observed", bounds.isEmpty)
        val point = when {
            bounds.left > display.left + 2 -> Pair((display.left + bounds.left) / 2f, bounds.exactCenterY())
            bounds.right < display.right - 2 -> Pair((bounds.right + display.right) / 2f, bounds.exactCenterY())
            bounds.top > display.top + 2 -> Pair(bounds.exactCenterX(), (display.top + bounds.top) / 2f)
            bounds.bottom < display.bottom - 2 -> Pair(bounds.exactCenterX(), (bounds.bottom + display.bottom) / 2f)
            else -> throw AssertionError("No visible outside target: dialog=$bounds display=$display")
        }
        assertTrue("Outside tap must stay on the visible display", display.contains(point.first.toInt(), point.second.toInt()))
        assertFalse("Outside tap must miss the dialog window", bounds.contains(point.first.toInt(), point.second.toInt()))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val downTime = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, point.first, point.second, 0)
            try {
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                instrumentation.sendPointerSync(event)
            } finally {
                event.recycle()
            }
        }
        instrumentation.waitForIdleSync()
    }

    @Test fun botLocalScopeCannotBeEdited() {
        val fixed = DashboardRecordScope.Exact("private-id")
        var applied: DashboardRecordScope? = null
        compose.setContent { MaterialTheme {
            DashboardRecordFilterDialog(null, fixed, listOf("real-gallery"), dashboardScopeOptions(emptyList(), setOf("private-id")), false, {}, { _, s -> applied = s })
        } }
        compose.onNodeWithTag("record-filter-scope-GLOBAL").assertDoesNotExist()
        compose.selectOnlyRecordFilter("gallery", "real-gallery")
        compose.onNodeWithTag("record-filter-apply").performClick()
        compose.runOnIdle { assertEquals(fixed, applied) }
    }
}
