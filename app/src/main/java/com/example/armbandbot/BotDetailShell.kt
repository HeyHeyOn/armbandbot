package com.heyheyon.armbandbot

import android.content.SharedPreferences
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.AutoMode
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.heyheyon.armbandbot.ui.*
import kotlinx.coroutines.delay
import java.time.ZoneId

internal const val BOT_TAB_HOME = 0
internal const val BOT_TAB_FILTERS = 1
internal const val BOT_TAB_AUTOMATION = 2
internal const val BOT_TAB_LOG = 3
internal const val BOT_TAB_SETTINGS = 4

private class BotTabSpec(val label: String, val icon: ImageVector, val selectedIcon: ImageVector, val tag: String)

private val botTabs = listOf(
    BotTabSpec("홈", Icons.Outlined.Home, Icons.Filled.Home, "bot-tab-home"),
    BotTabSpec("필터", Icons.Outlined.Shield, Icons.Filled.Shield, "bot-tab-filters"),
    BotTabSpec("자동화", Icons.Outlined.AutoMode, Icons.Filled.AutoMode, "bot-tab-automation"),
    BotTabSpec("로그", Icons.Outlined.Terminal, Icons.Filled.Terminal, "bot-tab-log"),
    BotTabSpec("설정", Icons.Outlined.Settings, Icons.Filled.Settings, "bot-tab-settings"),
)

/** Recomposes callers whenever any value in [p] changes. */
@Composable
internal fun rememberPreferenceRevision(p: SharedPreferences): Int {
    var revision by remember(p) { mutableIntStateOf(0) }
    DisposableEffect(p) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> revision++ }
        p.registerOnSharedPreferenceChangeListener(listener)
        onDispose { p.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return revision
}

internal enum class BotRunTone { RUNNING, WAITING, STOPPED, ATTENTION }

internal class BotRunStatus(val label: String, val tone: BotRunTone)

internal fun botRunTone(label: String, loggedIn: Boolean): BotRunTone = when {
    !loggedIn || label.contains("오류") -> BotRunTone.ATTENTION
    label == "실행 중" -> BotRunTone.RUNNING
    label.startsWith("예약 대기") -> BotRunTone.WAITING
    else -> BotRunTone.STOPPED
}

/** The same status line the bot list shows, refreshed every minute. */
@Composable
internal fun rememberBotRunStatus(p: SharedPreferences, isRunning: Boolean): BotRunStatus {
    val revision = rememberPreferenceRevision(p)
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(60_000L - now % 60_000L)
        }
    }
    return remember(revision, isRunning, now) {
        val loggedIn = !p.getString("saved_cookie", "").isNullOrBlank()
        val label = botScheduleStatus(loggedIn, isRunning, now, ZoneId.systemDefault(), loadBotRunSchedule(p))
        BotRunStatus(label, botRunTone(label, loggedIn))
    }
}

@Composable
internal fun BotRunStatusPill(status: BotRunStatus, colors: BotColorScheme, modifier: Modifier = Modifier) {
    val (fg, bg) = when (status.tone) {
        BotRunTone.RUNNING -> colors.success to colors.successContainer
        BotRunTone.WAITING -> colors.pending to colors.pendingContainer
        BotRunTone.ATTENTION -> colors.warningRed to colors.blockCard
        BotRunTone.STOPPED -> colors.subText to colors.surfaceMuted
    }
    StatusPill(status.label, fg, bg, modifier)
}

@Composable
internal fun BotDetailTopBar(
    botName: String,
    status: BotRunStatus,
    isRunning: Boolean,
    colors: BotColorScheme,
    onBack: () -> Unit,
    onEditName: () -> Unit,
    onRunningChange: (Boolean) -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(colors.topBar)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "뒤로가기", tint = colors.accent)
            }
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onEditName)) {
                    Text(botName, fontWeight = FontWeight.Bold, fontSize = 19.sp, color = colors.text, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Filled.Edit, contentDescription = "이름 수정", tint = colors.subText, modifier = Modifier.size(15.dp))
                }
                Spacer(Modifier.height(4.dp))
                BotRunStatusPill(status, colors)
            }
            Spacer(Modifier.width(8.dp))
            ModernSettingsSwitch(isRunning, onRunningChange, colors, Modifier.testTag("bot-running-switch"))
        }
        HorizontalDivider(color = colors.divider)
    }
}

@Composable
internal fun BotDetailBottomBar(selected: Int, colors: BotColorScheme, onSelect: (Int) -> Unit) {
    Column {
        HorizontalDivider(color = colors.divider)
        NavigationBar(containerColor = colors.topBar, tonalElevation = 0.dp, windowInsets = WindowInsets(0, 0, 0, 0)) {
            botTabs.forEachIndexed { index, tab ->
                val isSelected = selected == index
                NavigationBarItem(
                    selected = isSelected,
                    onClick = { onSelect(index) },
                    icon = { Icon(if (isSelected) tab.selectedIcon else tab.icon, contentDescription = null) },
                    label = { Text(tab.label, fontSize = 12.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium) },
                    modifier = Modifier.testTag(tab.tag),
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = colors.accent,
                        selectedTextColor = colors.accent,
                        indicatorColor = colors.accentContainer,
                        unselectedIconColor = colors.subText,
                        unselectedTextColor = colors.subText,
                    ),
                )
            }
        }
    }
}

/** Large title plus one line of guidance at the top of each tab. */
@Composable
internal fun BotTabIntro(title: String, description: String, colors: BotColorScheme) {
    Column(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 20.dp, bottom = 4.dp)) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = colors.text)
        Spacer(Modifier.height(4.dp))
        Text(description, fontSize = 13.sp, color = colors.subText)
    }
}

private class HomeFilter(val label: String, val enabled: Boolean)

private fun homeFilters(p: SharedPreferences): List<HomeFilter> {
    fun on(key: String) = p.getBoolean(key, false)
    return listOf(
        HomeFilter("금지어", filterMasterEnabled(p, WORD_FILTER_ENABLED_KEY)),
        HomeFilter("펌", on("pum_block_all_posts")),
        HomeFilter("유저 ID/IP", on("is_user_filter_mode")),
        HomeFilter("닉네임", on("is_nickname_filter_mode")),
        HomeFilter("유동", filterMasterEnabled(p, YUDONG_FILTER_ENABLED_KEY)),
        HomeFilter("해외 IP", on("is_overseas_ip_filter_mode")),
        HomeFilter("깡계", on("is_kkang_filter_mode")),
        HomeFilter("도배 방지", on("is_spam_burst_protection_enabled")),
        HomeFilter("URL", on("is_url_filter_mode")),
        HomeFilter("이미지", on("is_image_filter_mode")),
        HomeFilter("디시콘", on("is_dccon_filter_mode")),
        HomeFilter("보이스", on("is_voice_filter_mode")),
        HomeFilter("AI", on("is_ai_filter_mode")),
        HomeFilter("스팸코드", on("is_spam_code_filter_mode")),
        HomeFilter("특수문자", on("is_special_char_filter_mode")),
    )
}

private fun homeGalleries(p: SharedPreferences): List<String> = (p.getString("target_urls", "") ?: "")
    .lineSequence()
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .map { url -> parseManagedGalleryUrl(url)?.second ?: url }
    .distinct()
    .toList()

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BotHomeTab(
    botId: String,
    p: SharedPreferences,
    colors: BotColorScheme,
    scrollState: ScrollState,
    onOpenTab: (Int) -> Unit,
    onOpenSubScreen: (String) -> Unit,
    onExport: () -> Unit,
) {
    val revision = rememberPreferenceRevision(p)
    val galleries = remember(revision) { homeGalleries(p) }
    val filters = remember(revision) { homeFilters(p) }
    val activeFilters = filters.filter { it.enabled }
    var lastChecked by remember(botId) { mutableIntStateOf(GlobalBotState.lastCheckedNumbers[botId] ?: 0) }
    LaunchedEffect(botId) {
        while (true) {
            lastChecked = GlobalBotState.lastCheckedNumbers[botId] ?: 0
            delay(3_000L)
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(scrollState).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
        Spacer(Modifier.height(16.dp))
        // 상태 카드
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = colors.card),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            modifier = Modifier.fillMaxWidth().testTag("bot-home-status"),
        ) {
            Column(Modifier.padding(20.dp)) {
                Text("관리 중인 갤러리", fontSize = 13.sp, color = colors.subText)
                Spacer(Modifier.height(2.dp))
                if (galleries.isEmpty()) {
                    Text("아직 설정되지 않았습니다", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = colors.text)
                } else {
                    Text(galleries.joinToString(", "), fontWeight = FontWeight.Bold, fontSize = 20.sp, color = colors.text,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HomeStat("최근 확인한 글", if (lastChecked > 0) "#$lastChecked" else "-", colors, Modifier.weight(1f))
                    HomeStat("사용 중인 필터", "${activeFilters.size}개", colors, Modifier.weight(1f))
                }
                if (galleries.isEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    Button(
                        onClick = { onOpenSubScreen("TARGET") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PastelNavy, contentColor = Color.White),
                    ) { Text("갤러리 설정하기", fontWeight = FontWeight.SemiBold) }
                }
            }
        }

        SettingsSectionHeader("한눈에 보기", colors)
        HomeSummaryCard(
            icon = Icons.Filled.Shield,
            title = "차단 필터",
            summary = if (activeFilters.isEmpty()) "켜진 필터가 없습니다" else "${filters.size}개 중 ${activeFilters.size}개 사용 중",
            colors = colors,
            onClick = { onOpenTab(BOT_TAB_FILTERS) },
        ) {
            if (activeFilters.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    activeFilters.forEach { StatusPill(it.label, colors.accent, colors.accentContainer, showDot = false) }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        val automation = remember(revision) {
            listOf(
                "작동 시간대" to ((loadBotRunSchedule(p) as? BotRunScheduleLoadResult.Valid)?.schedule?.enabled == true),
                "갤러리 설정 자동 갱신" to p.getBoolean("gallery_setting_refresh_enabled", false),
                "예약 끌올" to p.getBoolean(BUMP_ENABLED_KEY, false),
                "자동 탭 분류" to p.getBoolean(MOVE_ENABLED_KEY, false),
            )
        }
        HomeSummaryCard(
            icon = Icons.Filled.AutoMode,
            title = "자동화",
            summary = "${automation.count { it.second }}개 사용 중",
            colors = colors,
            onClick = { onOpenTab(BOT_TAB_AUTOMATION) },
        ) {
            Spacer(Modifier.height(8.dp))
            automation.forEach { (label, on) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, fontSize = 14.sp, color = colors.text, modifier = Modifier.weight(1f))
                    Text(if (on) "켜짐" else "꺼짐", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        color = if (on) colors.success else colors.subText)
                }
            }
        }

        SettingsSectionHeader("바로가기", colors)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HomeQuickAction(Icons.Filled.Terminal, "활동 로그", colors, Modifier.weight(1f)) { onOpenTab(BOT_TAB_LOG) }
            HomeQuickAction(Icons.Filled.Storage, "DB 기록", colors, Modifier.weight(1f)) { onOpenSubScreen("DB_DASHBOARD") }
            HomeQuickAction(Icons.Filled.FileUpload, "설정 내보내기", colors, Modifier.weight(1f), onExport)
        }
    }
}

@Composable
private fun HomeStat(label: String, value: String, colors: BotColorScheme, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(colors.surfaceMuted).padding(horizontal = 14.dp, vertical = 12.dp)) {
        Text(label, fontSize = 12.sp, color = colors.subText)
        Spacer(Modifier.height(2.dp))
        Text(value, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun HomeSummaryCard(
    icon: ImageVector,
    title: String,
    summary: String,
    colors: BotColorScheme,
    onClick: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colors.card),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SettingIconBadge(icon, colors)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = colors.text)
                    Text(summary, fontSize = 12.sp, color = colors.subText)
                }
                Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = colors.subText.copy(alpha = 0.7f))
            }
            content()
        }
    }
}

@Composable
private fun HomeQuickAction(icon: ImageVector, label: String, colors: BotColorScheme, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(colors.card)
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SettingIconBadge(icon, colors)
        Spacer(Modifier.height(8.dp))
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = colors.text, maxLines = 1)
    }
}
