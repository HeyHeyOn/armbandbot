package com.heyheyon.armbandbot

import androidx.compose.ui.semantics.*
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule

/** Exercise real checkbox clicks; retain old single-filter regressions as subset cases. */
internal fun ComposeContentTestRule.selectOnlyRecordFilter(dimension: String, id: String): SemanticsNodeInteraction {
    val prefix = "record-filter-$dimension-"
    if (id == "ALL") return onNodeWithTag(prefix + id).performScrollTo().performClick()
    val nodes = onAllNodes(SemanticsMatcher("record filter $dimension choices") {
        val tag = it.config.getOrElse(SemanticsProperties.TestTag) { "" }
        tag.startsWith(prefix) && tag != prefix + "ALL"
    }).fetchSemanticsNodes()
    val tags = nodes.map { it.config[SemanticsProperties.TestTag] }
    tags.forEach { tag ->
        val node = onNodeWithTag(tag).performScrollTo()
        if (node.fetchSemanticsNode().config[SemanticsProperties.ToggleableState] == ToggleableState.On) node.performClick()
    }
    return onNodeWithTag(prefix + id).performScrollTo().performClick()
}
