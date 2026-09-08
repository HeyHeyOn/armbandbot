package com.heyheyon.armbandbot

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.heyheyon.armbandbot.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Pure UI fixture: no detail screen, preferences, database, or service. */
class ModernSettingsBlockUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun lightNarrowHeaderUsesModernTypographyAndTheme() = header(false)
    @Test fun darkNarrowHeaderUsesModernTypographyAndTheme() = header(true)

    @Test fun light320dpLargeFontHeader() = header(false, 288, 1.3f)
    @Test fun dark320dpLargeFontHeader() = header(true, 288, 1.3f)

    private fun header(dark: Boolean, cardWidth: Int = 280, fontScale: Float = 1f) {
        val checked = mutableStateOf(false)
        lateinit var palette: BotColorScheme
        compose.setContent { MaterialTheme {
            palette = botColors(dark)
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(
                    androidx.compose.ui.platform.LocalDensity.current.density, fontScale
                )
            ) {
            // A 320dp screen with the detail screen's 16dp side insets leaves 288dp.
            Column(Modifier.width(cardWidth.dp)) {
                ModernSettingsBlock(
                    title = "독립 검사 기록", subtitle = "다른 봇의 검사 완료 기록과 분리",
                    icon = Icons.Filled.Storage, colors = palette, modifier = Modifier.testTag("block"),
                    trailing = { ModernSettingsSwitch(checked.value, { checked.value = it }, palette,
                        Modifier.testTag("toggle")) },
                ) { Text("공용 검사 기록", Modifier.testTag("body")) }
            }
        } } }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("독립 검사 기록").assertIsDisplayed()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val title = layouts.single()
        assertEquals(15.sp, title.layoutInput.style.fontSize)
        assertEquals(FontWeight.Bold, title.layoutInput.style.fontWeight)
        assertEquals(palette.text, title.layoutInput.style.color)
        val geometry = buildString {
            append("title size=${title.size} constraints=${title.layoutInput.constraints} density=${title.layoutInput.density} ")
            append("paragraph=${title.multiParagraph.width}x${title.multiParagraph.height} overflowWidth=${title.didOverflowWidth} overflowHeight=${title.didOverflowHeight} ")
            append("titleBounds=${compose.onNodeWithText("독립 검사 기록").fetchSemanticsNode().boundsInRoot} ")
            append("blockBounds=${compose.onNodeWithTag("block").fetchSemanticsNode().boundsInRoot} toggleBounds=${compose.onNodeWithTag("toggle").fetchSemanticsNode().boundsInRoot} ")
            for (line in 0 until title.lineCount) append("line$line=[${title.getLineLeft(line)},${title.getLineTop(line)},${title.getLineRight(line)},${title.getLineBottom(line)}] ")
        }
        android.util.Log.i("HeaderGeometry", geometry)
        assertFalse(geometry, title.hasVisualOverflow)
        layouts.clear()
        compose.onNodeWithText("다른 봇의 검사 완료 기록과 분리")
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(12.sp, layouts.single().layoutInput.style.fontSize)
        assertEquals(palette.subText, layouts.single().layoutInput.style.color)
        assertFalse(layouts.single().hasVisualOverflow)
        val titleBounds = compose.onNodeWithText("독립 검사 기록").fetchSemanticsNode().boundsInRoot
        val toggleBounds = compose.onNodeWithTag("toggle").fetchSemanticsNode().boundsInRoot
        assertTrue(titleBounds.right <= toggleBounds.left)
        compose.onNodeWithTag("block").assertHasNoClickAction()
        compose.onNodeWithTag("body").assertIsDisplayed()
        compose.onNodeWithTag("toggle").assertIsOff().performClick().assertIsOn()
    }
}
