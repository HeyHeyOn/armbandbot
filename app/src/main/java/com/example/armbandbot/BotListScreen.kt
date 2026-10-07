package com.heyheyon.armbandbot

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.heyheyon.armbandbot.ui.LocalIsDarkMode
import com.heyheyon.armbandbot.ui.PastelNavy
import com.heyheyon.armbandbot.ui.botColors
import com.heyheyon.armbandbot.ui.BotColorScheme
import com.heyheyon.armbandbot.ui.SettingIconBadge
import com.heyheyon.armbandbot.ui.modernSwitchColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZoneId
import java.util.UUID
import kotlin.math.roundToInt

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun BotListScreen(onNavigateToSettings: (String) -> Unit, onThemeToggle: (Boolean) -> Unit) {
    val context = LocalContext.current
    val masterPref = context.getSharedPreferences("bot_master", Context.MODE_PRIVATE)
    val botIds = remember { mutableStateListOf<String>() }

    val isDarkMode = LocalIsDarkMode.current
    val colors = botColors(isDarkMode)
    val bgColor = colors.bg
    val cardColor = colors.dialogBg
    val textColor = colors.text
    val subTextColor = colors.subText
    val iconColor = colors.iconTint

    LaunchedEffect(Unit) {
        var botIdsStr = masterPref.getString("bot_ids_list", null)
        if (botIdsStr == null) {
            val oldSet = masterPref.getStringSet("bot_ids", setOf()) ?: setOf()
            botIdsStr = oldSet.joinToString(",")
            masterPref.edit().putString("bot_ids_list", botIdsStr).apply()
        }
        botIds.clear()
        botIds.addAll(botIdsStr.split(",").filter { it.isNotBlank() })
        migrateAllBotSettingsToCurrentVersion(context)
    }

    val savedIdsStr = masterPref.getString("bot_ids_list", "") ?: ""
    val savedIdsList = savedIdsStr.split(",").filter { it.isNotBlank() }
    if (botIds.toList() != savedIdsList && savedIdsList.size < botIds.size) {
        botIds.clear()
        botIds.addAll(savedIdsList)
    }

    var showAddDialog by remember { mutableStateOf(false) }
    var newBotName by remember { mutableStateOf("") }
    var botToDuplicate by remember { mutableStateOf<String?>(null) }
    var botToDelete by remember { mutableStateOf<String?>(null) }
    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var dragDy by remember { mutableStateOf(0f) }
    var swipedBotId by remember { mutableStateOf<String?>(null) }
    var pendingExportBotId by remember { mutableStateOf<String?>(null) }
    var showDbDashboard by remember { mutableStateOf(false) }
    var showHelpDialog by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            importBotSettingsAsNewBot(context, uri.toString())
        }.onSuccess {
            val savedIds = (masterPref.getString("bot_ids_list", "") ?: "")
                .split(",")
                .filter { id -> id.isNotBlank() }
            botIds.clear()
            botIds.addAll(savedIds)
            Toast.makeText(context, "설정 파일을 불러와 새 봇으로 추가했습니다.", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(context, it.message ?: "설정 파일 불러오기에 실패했습니다.", Toast.LENGTH_LONG).show()
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        val botId = pendingExportBotId
        pendingExportBotId = null
        if (uri == null || botId == null) return@rememberLauncherForActivityResult
        runCatching {
            writeBotSettingsJson(context, uri.toString(), exportBotSettings(context, botId))
        }.onSuccess {
            Toast.makeText(context, "JSON 설정 파일을 저장했습니다.", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(context, it.message ?: "JSON 설정 파일 저장에 실패했습니다.", Toast.LENGTH_LONG).show()
        }
    }


    val density = LocalDensity.current
    val itemHeightPx = remember(density) { with(density) { 60.dp.toPx() } }

    BackHandler(enabled = showDbDashboard) { showDbDashboard = false }

    AnimatedContent(
        targetState = showDbDashboard,
        transitionSpec = {
            if (targetState && !initialState) {
                slideInHorizontally { it } + fadeIn() togetherWith slideOutHorizontally { -it / 2 } + fadeOut()
            } else if (!targetState && initialState) {
                slideInHorizontally { -it / 2 } + fadeIn() togetherWith slideOutHorizontally { it } + fadeOut()
            } else {
                fadeIn() togetherWith fadeOut()
            }
        },
        label = "BotListDbDashboardAnimation"
    ) { showingDbDashboard ->
        if (showingDbDashboard) {
            DbDashboardScreen(botId = "GLOBAL", onBack = { showDbDashboard = false })
        } else {
    Scaffold(
        containerColor = bgColor,
        bottomBar = {
            Column(Modifier.background(colors.topBar)) {
                HorizontalDivider(color = colors.divider)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LobbyActionButton(Icons.Filled.FileDownload, "불러오기", colors) { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }
                    LobbyActionButton(Icons.Filled.Storage, "DB 기록", colors) { showDbDashboard = true }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = { showAddDialog = true },
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PastelNavy, contentColor = Color.White)
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("봇 추가", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(bgColor)
                .padding(innerPadding)
                .padding(16.dp)
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null
                ) { swipedBotId = null }
        ) {
            Row(modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("완장봇", fontWeight = FontWeight.Bold, fontSize = 28.sp, color = textColor)
                    Text("버전 $ARMBANDBOT_APP_VERSION", fontSize = 13.sp, color = subTextColor)
                }
                IconButton(onClick = { onThemeToggle(!isDarkMode) }) {
                    Icon(imageVector = if (isDarkMode) Icons.Filled.LightMode else Icons.Filled.DarkMode, contentDescription = if (isDarkMode) "라이트 모드" else "다크 모드", tint = colors.accent)
                }
                IconButton(onClick = { showHelpDialog = true }) {
                    Icon(Icons.Filled.HelpOutline, contentDescription = "도움말", tint = colors.accent)
                }
            }
            val runningCount = rememberRunningBotCount(context, botIds.toList())
            Text(
                if (botIds.isEmpty()) "아직 만든 봇이 없습니다" else "봇 ${botIds.size}개 · 실행 중 ${runningCount}개",
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = subTextColor,
                modifier = Modifier.padding(start = 6.dp, top = 16.dp, bottom = 8.dp)
            )

            if (botIds.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.card),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 36.dp, horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        SettingIconBadge(Icons.Filled.SmartToy, colors)
                        Spacer(Modifier.height(14.dp))
                        Text("생성된 봇이 없습니다.", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = textColor)
                        Spacer(Modifier.height(4.dp))
                        Text("아래 ‘봇 추가’를 눌러 첫 봇을 만들어 보세요.", fontSize = 13.sp, color = subTextColor, textAlign = TextAlign.Center)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 8.dp)
                ) {
                    items(botIds.size, key = { botIds[it] }) { index ->
                        val botId = botIds[index]
                        BotListItem(
                            index = index,
                            botId = botId,
                            draggingIndex = draggingIndex,
                            dragDy = dragDy,
                            isSwipedOpen = (swipedBotId == botId),
                            onSwipeStateChange = { isOpen -> swipedBotId = if (isOpen) botId else null },
                            onDragStart = { idx -> swipedBotId = null; draggingIndex = idx; dragDy = 0f },
                            onDragEnd = { draggingIndex = null; dragDy = 0f; masterPref.edit().putString("bot_ids_list", botIds.joinToString(",")).apply() },
                            onDrag = { dy ->
                                dragDy += dy
                                val currentIdx = draggingIndex
                                if (currentIdx != null) {
                                    if (dragDy > itemHeightPx * 0.5f && currentIdx < botIds.size - 1) {
                                        botIds[currentIdx] = botIds[currentIdx + 1].also { botIds[currentIdx + 1] = botIds[currentIdx] }
                                        draggingIndex = currentIdx + 1; dragDy -= itemHeightPx
                                    } else if (dragDy < -itemHeightPx * 0.5f && currentIdx > 0) {
                                        botIds[currentIdx] = botIds[currentIdx - 1].also { botIds[currentIdx - 1] = botIds[currentIdx] }
                                        draggingIndex = currentIdx - 1; dragDy += itemHeightPx
                                    }
                                }
                            },
                            onSettingsClick = { swipedBotId = null; onNavigateToSettings(botId) },
                            onExportRequest = {
                                swipedBotId = null
                                pendingExportBotId = botId
                                val botName = context.getSharedPreferences("bot_prefs_$botId", Context.MODE_PRIVATE)
                                    .getString("bot_name", "bot")
                                    ?.replace(Regex("[\\/:*?\"<>|]"), "_")
                                    ?.trim()
                                    ?.ifBlank { "bot" }
                                    ?: "bot"
                                exportLauncher.launch("${botName}_settings_$ARMBANDBOT_APP_VERSION.json")
                            },
                            onDuplicateRequest = { swipedBotId = null; botToDuplicate = botId },
                            onDeleteRequest = { swipedBotId = null; botToDelete = botId }
                        )
                    }
                }
            }
        }

        if (showAddDialog) {
            AlertDialog(
                containerColor = cardColor, titleContentColor = textColor, textContentColor = textColor,
                onDismissRequest = { showAddDialog = false }, title = { Text("새로운 봇 추가", fontWeight = FontWeight.Bold) },
                text = { OutlinedTextField(value = newBotName, onValueChange = { newBotName = it }, label = { Text("봇 이름") }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedTextColor = textColor, unfocusedTextColor = textColor)) },
                confirmButton = { Button(onClick = {
                    if (newBotName.isNotBlank()) {
                        val newBotId = "bot_${UUID.randomUUID()}"
                        val botPref = context.getSharedPreferences("bot_prefs_$newBotId", Context.MODE_PRIVATE)
                        botPref.edit()
                            .putString("bot_name", newBotName)
                            .putStringSet("url_whitelist", defaultBotUrlWhitelist())
                            .putInt(BOT_PREF_SCHEMA_VERSION_KEY, BOT_SETTINGS_CURRENT_SCHEMA_VERSION)
                            .putString(BOT_PREF_APP_VERSION_KEY, ARMBANDBOT_APP_VERSION)
                            .apply()
                        botIds.add(newBotId)
                        masterPref.edit().putString("bot_ids_list", botIds.joinToString(",")).apply()
                        newBotName = ""; showAddDialog = false
                    }
                }, colors = ButtonDefaults.buttonColors(containerColor = PastelNavy)) { Text("생성", color = Color.White) } },
                dismissButton = { TextButton(onClick = { showAddDialog = false }) { Text("취소", color = subTextColor) } }
            )
        }

        if (botToDuplicate != null) {
            val oldPref = context.getSharedPreferences("bot_prefs_${botToDuplicate!!}", Context.MODE_PRIVATE)
            val oldName = oldPref.getString("bot_name", "이름 없는 봇") ?: "이름 없는 봇"
            AlertDialog(
                containerColor = cardColor, titleContentColor = textColor, textContentColor = textColor,
                onDismissRequest = { botToDuplicate = null }, title = { Text("봇 복사", fontWeight = FontWeight.Bold) }, text = { Text("'$oldName'의 설정을 복사하시겠습니까?") },
                confirmButton = { Button(onClick = {
                    val newBotId = "bot_${UUID.randomUUID()}"
                    duplicateBotPref(context, botToDuplicate!!, newBotId, "$oldName 복사본")
                    botIds.add(newBotId)
                    masterPref.edit().putString("bot_ids_list", botIds.joinToString(",")).apply()
                    botToDuplicate = null; Toast.makeText(context, "복사되었습니다.", Toast.LENGTH_SHORT).show()
                }, colors = ButtonDefaults.buttonColors(containerColor = PastelNavy)) { Text("예", color = Color.White) } },
                dismissButton = { TextButton(onClick = { botToDuplicate = null }) { Text("취소", color = subTextColor) } }
            )
        }

        if (showHelpDialog) {
            AlertDialog(
                containerColor = cardColor,
                titleContentColor = textColor,
                textContentColor = textColor,
                onDismissRequest = { showHelpDialog = false },
                title = { Text("기본 안내", fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.AddCircle, contentDescription = null, tint = iconColor, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("하단 버튼 영역에서 봇을 추가하세요.", fontSize = 13.sp, color = subTextColor)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Menu, contentDescription = null, tint = iconColor, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("블록을 길게 눌러 순서를 변경할 수 있습니다.", fontSize = 13.sp, color = subTextColor)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = null, tint = iconColor, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("블록을 왼쪽으로 밀면 내보내기/복사/삭제가 가능합니다.", fontSize = 13.sp, color = subTextColor)
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showHelpDialog = false }) { Text("확인", color = colors.accent) } }
            )
        }

        if (botToDelete != null) {
            var deleteBotSnapshots by remember(botToDelete) { mutableStateOf(false) }
            var isDeletingBot by remember(botToDelete) { mutableStateOf(false) }
            val delPref = context.getSharedPreferences("bot_prefs_${botToDelete!!}", Context.MODE_PRIVATE)
            val delName = delPref.getString("bot_name", "이름 없는 봇") ?: "이름 없는 봇"
            AlertDialog(
                containerColor = cardColor, titleContentColor = if (isDarkMode) Color(0xFFEF5350) else Color(0xFFD32F2F), textContentColor = textColor,
                onDismissRequest = { if (!isDeletingBot) botToDelete = null },
                title = { Text("봇 삭제", fontWeight = FontWeight.Bold) },
                text = {
                    Column {
                        Text("'$delName' 봇을 삭제하시겠습니까?\n이 작업은 되돌릴 수 없습니다. 검사 기록과 스냅샷은 기본적으로 보존됩니다.")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = deleteBotSnapshots, onCheckedChange = { deleteBotSnapshots = it }, enabled = !isDeletingBot)
                            Text("독립 검사 기록과 미참조 스냅샷도 삭제")
                        }
                        Text("공용 검사 기록과 조치·보류 이력에서 참조하는 스냅샷은 보존됩니다.", fontSize = 12.sp)
                    }
                },
                confirmButton = { Button(enabled = !isDeletingBot, onClick = {
                    val deletingBotId = botToDelete ?: return@Button
                    val removeSnapshots = deleteBotSnapshots
                    isDeletingBot = true
                    coroutineScope.launch {
                        try {
                            context.startService(Intent(context, BotService::class.java).apply { putExtra("BOT_ID", deletingBotId); action = "STOP" })
                            withContext(Dispatchers.IO) {
                                GlobalBotState.withDatabaseMaintenanceLock {
                                    cleanupDeletedBotSnapshots(GlobalBotState.getDb()?.postDao(), context.cacheDir, deletingBotId, removeSnapshots)
                                    clearBotLogFile(context, deletingBotId)
                                }
                            }
                            delPref.edit().clear().apply()
                            GlobalBotState.logs.remove(deletingBotId)
                            botIds.remove(deletingBotId)
                            masterPref.edit().putString("bot_ids_list", botIds.joinToString(",")).apply()
                            botToDelete = null
                            Toast.makeText(context, "삭제되었습니다.", Toast.LENGTH_SHORT).show()
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            Toast.makeText(context, error.message ?: "삭제에 실패했습니다. 봇 설정은 유지됩니다.", Toast.LENGTH_LONG).show()
                        } finally {
                            isDeletingBot = false
                        }
                    }
                }, colors = ButtonDefaults.buttonColors(containerColor = if (isDarkMode) Color(0xFFEF5350) else Color(0xFFD32F2F))) { Text(if (isDeletingBot) "삭제 중…" else "삭제", color = Color.White) } },
                dismissButton = { TextButton(enabled = !isDeletingBot, onClick = { botToDelete = null }) { Text("취소", color = subTextColor) } }
            )
        }
        }
        }
    }
}

/** Called on Dispatchers.IO; a missing inventory must never authorize file removal. */
internal fun cleanupDeletedBotSnapshots(
    postDao: PostDao?,
    cacheRoot: File,
    botId: String,
    deleteBotSnapshots: Boolean,
) = GlobalBotState.withDatabaseMaintenanceLock {
    val dao = checkNotNull(postDao) { "DB를 사용할 수 없습니다. 봇 설정은 유지됩니다." }
    require(botId != GLOBAL_SCAN_SCOPE && Regex("[A-Za-z0-9._-]{1,96}").matches(botId)) { "안전하지 않은 봇 ID입니다." }
    if (deleteBotSnapshots) {
        // Resolve and validate before removing rows. Never interpret failed listing as empty.
        val root = cacheRoot.canonicalFile
        val directory = File(root, "snapshots_$botId")
        check(!isSymbolicLinkWithoutFollowing(directory)) { "스냅샷 폴더가 안전하지 않습니다." }
        if (directory.exists()) {
            check(directory.isDirectory && isCanonicalFileStrictlyInside(directory, listOf(root))) { "스냅샷 폴더가 안전하지 않습니다." }
        }
        if (root.listFiles() == null) throw java.io.IOException("스냅샷 루트를 읽을 수 없습니다.")
        val directories = snapshotDirectoriesForBot(root, botId)
        val candidates = directories.flatMap { candidateDirectory ->
            (candidateDirectory.listFiles() ?: throw java.io.IOException("스냅샷 폴더를 읽을 수 없습니다.")).toList()
        }
        dao.deletePostsForScope(botId)
        val survivingSnapshotPaths = dao.getAllSnapshotPaths()
        // The DAO inventory unions checked, block, and hold rows across every scope.
        val protectedPaths = survivingSnapshotPaths.filter { it.isNotBlank() }.flatMap { path ->
            val pair = deriveSnapshotVersionPaths(path)
            if (pair == null) listOf(path) else listOf(path, pair.initialPath, pair.latestPath)
        }.map { File(it).canonicalPath }.toSet()
        val removable = candidates.filter {
            it.extension.equals("html", ignoreCase = true) && it.isFile &&
                !isSymbolicLinkWithoutFollowing(it) && it.canonicalPath !in protectedPaths
        }
        directories.forEach { candidateDirectory ->
            deleteTrustedSnapshotDirectoryFiles(root, candidateDirectory, survivingSnapshotPaths = survivingSnapshotPaths)
        }
        // The shared helper reports a count, not failed deletes. Verify its postcondition so
        // permission/listing failures cannot remove the bot's preferences or list entry.
        if (removable.any { it.exists() }) throw java.io.IOException("일부 스냅샷을 삭제하지 못했습니다. 봇 설정은 유지됩니다.")
        if (directories.any { it.exists() && it.listFiles() == null }) {
            throw java.io.IOException("스냅샷 폴더를 확인할 수 없습니다.")
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BotListItem(
    index: Int, botId: String, draggingIndex: Int?, dragDy: Float,
    isSwipedOpen: Boolean, onSwipeStateChange: (Boolean) -> Unit,
    onDragStart: (Int) -> Unit, onDragEnd: () -> Unit, onDrag: (Float) -> Unit,
    onSettingsClick: () -> Unit, onExportRequest: () -> Unit, onDuplicateRequest: () -> Unit, onDeleteRequest: () -> Unit
) {
    val context = LocalContext.current
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()

    val botPref = context.getSharedPreferences("bot_prefs_$botId", Context.MODE_PRIVATE)
    val botName = botPref.getString("bot_name", "이름 없는 봇") ?: "이름 없는 봇"
    var isRunning by rememberBotRunning(botPref)
    var nowEpochMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowEpochMillis = System.currentTimeMillis()
            delay(60_000L - nowEpochMillis % 60_000L)
        }
    }
    val runSchedule = loadBotRunSchedule(botPref)
    val isLoggedIn = !botPref.getString("saved_cookie", "").isNullOrBlank()
    val statusText = botScheduleStatus(isLoggedIn, isRunning, nowEpochMillis, ZoneId.systemDefault(), runSchedule)

    val isDarkMode = LocalIsDarkMode.current
    val colors = botColors(isDarkMode)
    val cardBgColor = colors.card
    val textColor = colors.text
    val dividerColor = colors.divider
    val status = BotRunStatus(statusText, botRunTone(statusText, isLoggedIn))

    val currentIndex by rememberUpdatedState(index)
    val isDragging = draggingIndex == index
    val yOffset = if (isDragging) with(LocalDensity.current) { dragDy.toDp() } else 0.dp
    val zIndex = if (isDragging) 1f else 0f
    val density = LocalDensity.current

    val buttonSize = 58.dp
    val buttonGap = 8.dp
    val maxSwipePx = with(density) { -(buttonSize * 3 + buttonGap * 4).toPx() }
    val swipeOffset = remember { androidx.compose.animation.core.Animatable(0f) }

    LaunchedEffect(isSwipedOpen) {
        if (!isSwipedOpen && swipeOffset.value != 0f) {
            swipeOffset.animateTo(0f, androidx.compose.animation.core.tween(300))
        }
    }

    Box(modifier = Modifier.fillMaxWidth().offset(y = yOffset).zIndex(zIndex)) {
        Row(modifier = Modifier.matchParentSize().padding(end = buttonGap).background(Color.Transparent), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(buttonSize).clip(RoundedCornerShape(16.dp)).background(Color(0xFF2E7D6F)).clickable { onExportRequest() }, contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(androidx.compose.material.icons.Icons.Filled.FileUpload, contentDescription = "내보내기", tint = Color.White, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.height(2.dp))
                    Text("내보내기", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(modifier = Modifier.width(buttonGap))
            Box(modifier = Modifier.size(buttonSize).clip(RoundedCornerShape(16.dp)).background(Color(0xFF4A6583)).clickable { onDuplicateRequest() }, contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(androidx.compose.material.icons.Icons.Filled.ContentCopy, contentDescription = "복사", tint = Color.White, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.height(2.dp))
                    Text("복사", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(modifier = Modifier.width(buttonGap))
            Box(modifier = Modifier.size(buttonSize).clip(RoundedCornerShape(16.dp)).background(if(isDarkMode) Color(0xFFEF5350) else Color(0xFFD32F2F)).clickable { onDeleteRequest() }, contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(androidx.compose.material.icons.Icons.Filled.Delete, contentDescription = "삭제", tint = Color.White, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.height(2.dp))
                    Text("삭제", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth().offset { androidx.compose.ui.unit.IntOffset(swipeOffset.value.roundToInt(), 0) }
                .pointerInput(Unit) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); onDragStart(currentIndex) },
                        onDragEnd = { onDragEnd() }, onDragCancel = { onDragEnd() },
                        onDrag = { change, dragAmount -> change.consume(); onDrag(dragAmount.y) }
                    )
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            coroutineScope.launch {
                                if (swipeOffset.value < maxSwipePx / 2) { swipeOffset.animateTo(maxSwipePx, androidx.compose.animation.core.tween(300)); onSwipeStateChange(true) }
                                else { swipeOffset.animateTo(0f, androidx.compose.animation.core.tween(300)); onSwipeStateChange(false) }
                            }
                        },
                        onHorizontalDrag = { change, dragAmount -> change.consume(); coroutineScope.launch { swipeOffset.snapTo((swipeOffset.value + dragAmount).coerceIn(maxSwipePx, 0f)) } }
                    )
                },
            elevation = CardDefaults.cardElevation(defaultElevation = if (isDragging) 8.dp else 0.dp), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = cardBgColor)
        ) {
            Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min).clickable { if (isSwipedOpen) onSwipeStateChange(false) else onSettingsClick() }, verticalAlignment = Alignment.CenterVertically) {
                Spacer(modifier = Modifier.width(16.dp))
                Box(
                    modifier = Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(colors.accentContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Text(botName.trim().take(1).ifEmpty { "봇" }, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = colors.accent)
                }
                Spacer(modifier = Modifier.width(14.dp))
                Box(modifier = Modifier.weight(1f).fillMaxHeight().padding(vertical = 16.dp), contentAlignment = Alignment.CenterStart) {
                    Column {
                        Text(botName, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = textColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(modifier = Modifier.height(5.dp))
                        BotRunStatusPill(status, colors)
                    }
                }
                Box(modifier = Modifier.width(1.dp).fillMaxHeight().padding(vertical = 16.dp).background(dividerColor))
                Box(modifier = Modifier.padding(horizontal = 12.dp)) {
                    Switch(
                        checked = isRunning,
                        onCheckedChange = {
                            isRunning = it; botPref.edit().putBoolean("is_running", it).apply()
                            val serviceIntent = Intent(context, BotService::class.java).apply { putExtra("BOT_ID", botId); putExtra("COOKIE", botPref.getString("saved_cookie", "")); action = if (isRunning) "START" else "STOP" }
                            if (isRunning && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(serviceIntent) else context.startService(serviceIntent)
                        },
                        colors = modernSwitchColors(colors),
                        modifier = Modifier.scale(0.85f)
                    )
                }
            }
        }
    }
}
@Composable
private fun LobbyActionButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, colors: BotColorScheme, onClick: () -> Unit) {
    Row(
        modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(colors.accentContainer).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = colors.accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = colors.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}
