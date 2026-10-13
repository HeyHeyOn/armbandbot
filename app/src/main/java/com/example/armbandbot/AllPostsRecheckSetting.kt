package com.heyheyon.armbandbot

import android.content.SharedPreferences
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.heyheyon.armbandbot.ui.BotColorScheme
import com.heyheyon.armbandbot.ui.modernSwitchColors

@Composable
internal fun AllPostsRecheckSetting(botId: String, preferences: SharedPreferences, colors: BotColorScheme) {
    var enabled by remember(botId, preferences) {
        mutableStateOf(preferences.getBoolean(ALL_POSTS_RECHECK_KEY, false))
    }
    HorizontalDivider(color = colors.divider, modifier = Modifier.padding(vertical = 8.dp))
    Row(
        modifier = Modifier.fillMaxWidth().testTag("all-posts-recheck").toggleable(
            value = enabled,
            role = Role.Switch,
            onValueChange = {
                enabled = it
                preferences.edit().putBoolean(ALL_POSTS_RECHECK_KEY, it).apply()
            },
        ).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("모든 게시물을 매 사이클마다 검사", fontWeight = FontWeight.Bold, color = colors.text)
            Text(
                "현재 탐색 범위의 글과 댓글을 변경 여부와 관계없이 다시 검사합니다. 검사량이 늘어 한 사이클이 길어질 수 있습니다.",
                fontSize = 12.sp,
                color = colors.subText,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(checked = enabled, onCheckedChange = null, colors = modernSwitchColors(colors))
    }
}
