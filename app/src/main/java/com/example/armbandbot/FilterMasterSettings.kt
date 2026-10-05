package com.heyheyon.armbandbot

import android.content.SharedPreferences
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Lock
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.heyheyon.armbandbot.ui.*

@Composable
private fun rememberFilterMaster(p: SharedPreferences, key: String): Boolean {
    var checked by remember(p, key) { mutableStateOf(filterMasterEnabled(p, key)) }
    DisposableEffect(p, key) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changed ->
            if (changed == key) checked = filterMasterEnabled(p, key)
        }
        p.registerOnSharedPreferenceChangeListener(listener)
        onDispose { p.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return checked
}

@Composable
internal fun FilterMasterEntry(p: SharedPreferences, colors: BotColorScheme, key: String, onOpen: () -> Unit) {
    val word = key == WORD_FILTER_ENABLED_KEY
    val checked = rememberFilterMaster(p, key)
    Box(Modifier.testTag(if (word) "word-filter-entry" else "yudong-filter-entry")) {
        ModernSettingItem(if (word) "금지어 필터" else "유동 필터",
            if (word) "금지어 기반 차단 설정" else "비로그인 유저 이용 제한",
            if (word) Icons.Default.Create else Icons.Default.Lock, colors, checked,
            { setFilterMasterEnabled(p, key, it) }, onOpen)
    }
}

@Composable
internal fun FilterMasterSection(p: SharedPreferences, colors: BotColorScheme, key: String, content: @Composable (Boolean) -> Unit) {
    val word = key == WORD_FILTER_ENABLED_KEY
    val checked = rememberFilterMaster(p, key)
    ModernSettingsBlock(if (word) "금지어 필터 사용" else "유동 필터 사용", "끄면 하위 설정을 보관하고 필터를 중지합니다.",
        if (word) Icons.Default.Create else Icons.Default.Lock, colors, trailing = {
            ModernSettingsSwitch(checked, { setFilterMasterEnabled(p, key, it) }, colors,
                Modifier.testTag(if (word) "word-filter-master" else "yudong-filter-master"))
        })
    Spacer(Modifier.height(8.dp))
    Column(Modifier.fillMaxWidth().alpha(if (checked) 1f else 0.4f)
        .testTag(if (word) "word-filter-options" else "yudong-filter-options")) { content(checked) }
}
