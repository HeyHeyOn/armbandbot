package com.heyheyon.armbandbot

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.core.util.Consumer
import com.heyheyon.armbandbot.ui.LocalIsDarkMode
import com.heyheyon.armbandbot.ui.theme.MyFirstAppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

// Colors and LocalIsDarkMode moved to ui/ package



fun deleteSnapshotFiles(path: String?) {
    if (path.isNullOrBlank()) return
    try {
        val file = java.io.File(path)
        val candidates = buildSet {
            add(file)
            val absolutePath = file.absolutePath
            if (absolutePath.endsWith("_latest.html")) add(java.io.File(absolutePath.replace("_latest.html", "_initial.html")))
            if (absolutePath.endsWith("_initial.html")) add(java.io.File(absolutePath.replace("_initial.html", "_latest.html")))
        }
        candidates.forEach { if (it.exists()) it.delete() }
    } catch (_: Exception) {
    }
}

object GlobalBotState {
    val logs = mutableMapOf<String, SnapshotStateList<BotLogEntry>>()
    val lastCheckedNumbers = mutableMapOf<String, Int>()
    private var db: AppDatabase? = null

    private val generalSnapshotInProgress = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val blockSnapshotInProgress = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    // 스냅샷 큐 워커: 장시간 운용 중 무제한 누적을 막기 위해 보수적으로 제한
    private const val MAX_SNAPSHOT_QUEUE_PENDING = 80
    private val snapshotPendingCount = java.util.concurrent.atomic.AtomicInteger(0)
    private val snapshotChannel = kotlinx.coroutines.channels.Channel<suspend () -> Unit>(
        capacity = MAX_SNAPSHOT_QUEUE_PENDING
    )
    private var snapshotWorkerStarted = false

    fun enqueueSnapshot(task: suspend () -> Unit): Boolean {
        val pending = snapshotPendingCount.incrementAndGet()
        if (pending > MAX_SNAPSHOT_QUEUE_PENDING) {
            snapshotPendingCount.decrementAndGet()
            return false
        }
        val result = snapshotChannel.trySend(task)
        if (result.isFailure) snapshotPendingCount.decrementAndGet()
        return result.isSuccess
    }

    fun getSnapshotQueuePending(): Int = snapshotPendingCount.get().coerceAtLeast(0)

    fun startSnapshotWorker(scope: kotlinx.coroutines.CoroutineScope) {
        if (snapshotWorkerStarted) return
        snapshotWorkerStarted = true
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            for (task in snapshotChannel) {
                try {
                    task()
                } catch (e: Exception) {
                    // 스냅샷 실패해도 큐 계속 처리
                } finally {
                    snapshotPendingCount.decrementAndGet()
                }
                kotlinx.coroutines.delay(2000L)
            }
        }
    }

    fun tryLockGeneralSnapshot(gallType: String, gallId: String, postNum: String): Boolean {
        return generalSnapshotInProgress.add(gallType + '_' + gallId + '_' + postNum)
    }
    fun unlockGeneralSnapshot(gallType: String, gallId: String, postNum: String) {
        generalSnapshotInProgress.remove(gallType + '_' + gallId + '_' + postNum)
    }
    fun tryLockBlockSnapshot(gallType: String, gallId: String, postNum: String): Boolean {
        return blockSnapshotInProgress.add(gallType + '_' + gallId + '_' + postNum)
    }
    fun unlockBlockSnapshot(gallType: String, gallId: String, postNum: String) {
        blockSnapshotInProgress.remove(gallType + '_' + gallId + '_' + postNum)
    }

    @Synchronized
    fun initDb(context: Context) {
        if (db == null) {
            db = AppDatabase.getDatabase(context)
        }
    }

    fun getDb() = db

    fun getCommentCount(gallType: String, gallId: String, postNum: String): Int {
        return try {
            db?.postDao()?.getPost(gallType, gallId, postNum)?.commentCount ?: -1
        } catch (e: Exception) {
            -1
        }
    }

    fun savePost(
        gallType: String,
        gallId: String,
        postNum: String,
        commentCount: Int,
        title: String? = null,
        author: String? = null,
        isBlocked: Boolean = false,
        blockReason: String? = null,
        snapshotPath: String? = null,
        creationDate: String? = null
    ) {
        try {
            db?.postDao()?.insertOrUpdate(
                CheckedPost(
                    gallType = gallType,
                    gallId = gallId,
                    postNum = postNum,
                    commentCount = commentCount,
                    title = title,
                    author = author,
                    isBlocked = isBlocked,
                    blockReason = blockReason,
                    snapshotPath = snapshotPath,
                    creationDate = creationDate
                )
            )
        } catch (e: Exception) {
        }
    }

    fun saveBlockHistory(
        gallType: String,
        gallId: String,
        postNum: String,
        targetType: String,
        targetAuthor: String,
        targetContent: String,
        blockReason: String,
        snapshotPath: String? = null,
        creationDate: String? = null
    ) {
        try {
            db?.postDao()?.insertBlockHistory(
                BlockHistory(
                    gallType = gallType,
                    gallId = gallId,
                    postNum = postNum,
                    targetType = targetType,
                    targetAuthor = targetAuthor,
                    targetContent = targetContent,
                    blockReason = blockReason,
                    snapshotPath = snapshotPath,
                    creationDate = creationDate
                )
            )
        } catch (e: Exception) {
        }
    }

    fun saveHoldHistory(
        gallType: String,
        gallId: String,
        postNum: String,
        targetType: String,
        targetNo: String,
        targetAuthor: String,
        targetContent: String,
        holdReason: String,
        snapshotPath: String? = null,
        creationDate: String? = null
    ): Boolean {
        return try {
            val rowId = db?.postDao()?.insertHoldHistory(
                HoldHistory(
                    gallType = gallType,
                    gallId = gallId,
                    postNum = postNum,
                    targetType = targetType,
                    targetNo = targetNo,
                    targetAuthor = targetAuthor,
                    targetContent = targetContent,
                    holdReason = holdReason,
                    snapshotPath = snapshotPath,
                    creationDate = creationDate
                )
            ) ?: -1L
            rowId != -1L
        } catch (e: Exception) {
            false
        }
    }

    fun hasHoldHistory(gallType: String, gallId: String, postNum: String, targetType: String, targetNo: String): Boolean {
        return try {
            db?.postDao()?.hasHoldHistory(gallType, gallId, postNum, targetType, targetNo) ?: false
        } catch (e: Exception) {
            false
        }
    }

    @Synchronized
    fun saveDb(context: Context) {}

    @Synchronized
    fun clearDb(context: Context) {
        Thread {
            try {
                val dao = db?.postDao()
                dao?.getAllSnapshotPaths()?.forEach { path -> deleteSnapshotFiles(path) }
                context.cacheDir.listFiles()
                    ?.filter { it.isDirectory && it.name.startsWith("snapshots_") }
                    ?.forEach { it.deleteRecursively() }
                dao?.clearAllPosts()
                dao?.clearAllBlockHistory()
                dao?.clearAllHoldHistory()
            } catch (_: Exception) {
            }
        }.start()
        lastCheckedNumbers.clear()
    }

    fun getHistoryCount(): Int {
        return try {
            db?.postDao()?.getPostCount() ?: 0
        } catch (e: Exception) {
            0
        }
    }

    fun getRecentPosts(): List<CheckedPost> {
        return try {
            db?.postDao()?.getRecentPosts() ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
}

fun getCurrentTimeStr(): String {
    return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
}

fun getBotLogFile(context: Context, botId: String): File {
    val dir = File(context.filesDir, "bot_logs")
    if (!dir.exists()) dir.mkdirs()
    return File(dir, "log_$botId.txt")
}

fun appendBotLogToFile(context: Context, botId: String, line: String, maxLines: Int = 5000) {
    try {
        val file = getBotLogFile(context, botId)
        val entry = parseBotLogEntry(line)
        file.appendText(entry.toJsonLine() + "\n")
        val lines = file.readLines()
        if (lines.size > maxLines) {
            file.writeText(lines.takeLast(maxLines).joinToString("\n") + "\n")
        }
    } catch (e: Exception) {
    }
}

fun loadBotLogsFromFile(context: Context, botId: String): List<BotLogEntry> {
    return try {
        val file = getBotLogFile(context, botId)
        if (file.exists()) file.readLines().filter { it.isNotBlank() }.map { botLogEntryFromLine(it) } else emptyList()
    } catch (e: Exception) {
        emptyList()
    }
}

fun clearBotLogFile(context: Context, botId: String) {
    try {
        val file = getBotLogFile(context, botId)
        if (file.exists()) file.delete()
    } catch (e: Exception) {
    }
}

fun duplicateBotPref(context: Context, oldBotId: String, newBotId: String, newName: String) {
    val oldPref = context.getSharedPreferences("bot_prefs_$oldBotId", Context.MODE_PRIVATE)
    val newPref = context.getSharedPreferences("bot_prefs_$newBotId", Context.MODE_PRIVATE)
    val editor = newPref.edit()
    oldPref.all.forEach { (key, value) ->
        when (value) {
            is String -> editor.putString(key, value)
            is Int -> editor.putInt(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Float -> editor.putFloat(key, value)
            is Long -> editor.putLong(key, value)
            is Set<*> -> { @Suppress("UNCHECKED_CAST") editor.putStringSet(key, value as Set<String>) }
        }
    }
    editor.putString("bot_name", newName)
    editor.putBoolean("is_running", false)
    editor.putBoolean("should_restore_after_restart", false)
    editor.putInt(BOT_PREF_SCHEMA_VERSION_KEY, BOT_SETTINGS_CURRENT_SCHEMA_VERSION)
    editor.putString(BOT_PREF_APP_VERSION_KEY, ARMBANDBOT_APP_VERSION)
    editor.apply()
    migrateBotSettingsToCurrentVersion(newPref)
}

fun copyToClipboard(context: Context, text: String, label: String = "복사된 텍스트") {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText(label, text)
    clipboard.setPrimaryClip(clip)
    Toast.makeText(context, "클립보드에 복사되었습니다.", Toast.LENGTH_SHORT).show()
}


fun getRestorableBotIds(context: Context): List<String> {
    val masterPref = context.getSharedPreferences("bot_master", Context.MODE_PRIVATE)
    val botIdsStr = masterPref.getString("bot_ids_list", "") ?: ""
    return botIdsStr.split(",").filter { it.isNotBlank() }
}

fun hasRestorableBots(context: Context): Boolean {
    return getRestorableBotIds(context).any { botId ->
        context.getSharedPreferences("bot_prefs_$botId", Context.MODE_PRIVATE)
            .getBoolean("should_restore_after_restart", false)
    }
}

fun restoreRunningBots(context: Context) {
    val botIds = getRestorableBotIds(context)

    Log.d("RestoreBots", "restoreRunningBots 시작 / botIds=$botIds")

    for (botId in botIds) {
        val botPref = context.getSharedPreferences("bot_prefs_$botId", Context.MODE_PRIVATE)
        val shouldRestore = botPref.getBoolean("should_restore_after_restart", false)
        val wasRunning = botPref.getBoolean("is_running", false)
        val savedCookie = botPref.getString("saved_cookie", "") ?: ""

        Log.d(
            "RestoreBots",
            "botId=$botId / shouldRestore=$shouldRestore / wasRunning=$wasRunning / hasCookie=${savedCookie.isNotBlank()}"
        )

        if (shouldRestore && wasRunning && savedCookie.isNotBlank()) {
            val serviceIntent = Intent(context, BotService::class.java).apply {
                putExtra("BOT_ID", botId)
                putExtra("COOKIE", savedCookie)
                action = "START"
            }

            Log.d("RestoreBots", "botId=$botId START 요청 전송")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}

fun requestRestoreRunningBots(
    context: Context,
    trigger: String,
    allowImmediateStart: Boolean
): Boolean {
    val masterPref = context.getSharedPreferences("bot_master", Context.MODE_PRIVATE)
    val hasRestorableBot = hasRestorableBots(context)

    masterPref.edit()
        .putBoolean("pending_restore_after_boot", hasRestorableBot)
        .apply()

    if (!hasRestorableBot) {
        AutoRestartReceiver.cancelWatchdog(context)
        Log.d("RestoreBots", "[$trigger] 복구 대상이 없어 pending/watchdog 정리")
        return false
    }

    if (!allowImmediateStart) {
        AutoRestartReceiver.scheduleWatchdog(context)
        Log.d("RestoreBots", "[$trigger] 즉시 복구를 건너뛰고 pending/watchdog만 유지")
        return false
    }

    return try {
        restoreRunningBots(context)
        masterPref.edit().putBoolean("pending_restore_after_boot", false).apply()
        Log.d("RestoreBots", "[$trigger] 즉시 복구 요청 완료")
        true
    } catch (e: Exception) {
        AutoRestartReceiver.scheduleWatchdog(context)
        masterPref.edit().putBoolean("pending_restore_after_boot", true).apply()
        Log.e("RestoreBots", "[$trigger] 즉시 복구 실패, pending/watchdog 유지", e)
        false
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val masterPref = getSharedPreferences("bot_master", Context.MODE_PRIVATE)
        migrateAllBotSettingsToCurrentVersion(this)
        val pendingRestoreAfterBoot = masterPref.getBoolean("pending_restore_after_boot", false)

        if (pendingRestoreAfterBoot) {
            requestRestoreRunningBots(
                context = this,
                trigger = "MainActivity.onCreate",
                allowImmediateStart = true
            )
        }

        enableEdgeToEdge()
        setContent {
            MyFirstAppTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding)) { MainApp() }
                }
            }
        }
    }
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun MainApp() {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    var currentScreen by remember { mutableStateOf("LOBBY") }
    var currentBotId by remember { mutableStateOf<String?>(null) }
    var openBlockLogTrigger by remember { mutableStateOf(false) }

    // 🌟 글로벌 다크모드 상태 끌어올리기 (App 단위)
    val masterPref = context.getSharedPreferences("bot_master", Context.MODE_PRIVATE)
    var isGlobalDarkMode by remember { mutableStateOf(masterPref.getBoolean("is_dark_mode", false)) }

    LaunchedEffect(Unit) { GlobalBotState.initDb(context) }

    DisposableEffect(activity) {
        val listener = Consumer<Intent> { intent ->
            val targetBotId = intent.getStringExtra("TARGET_BOT_ID")
            val targetAction = intent.getStringExtra("TARGET_ACTION")
            if (targetBotId != null && targetAction == "OPEN_BLOCK_LOG") {
                currentBotId = targetBotId
                currentScreen = "SETTINGS"
                openBlockLogTrigger = true
            }
        }
        activity?.addOnNewIntentListener(listener)
        activity?.intent?.let { listener.accept(it) }
        onDispose { activity?.removeOnNewIntentListener(listener) }
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            if (!isGranted) Toast.makeText(context, "알림 권한이 없어 상태창 및 차단 알림이 보이지 않을 수 있습니다.", Toast.LENGTH_SHORT).show()
        }
        LaunchedEffect(Unit) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    DisposableEffect(context) {
        val appContext = context.applicationContext

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val incomingBotId = intent?.getStringExtra("BOT_ID")
                val msg = intent?.getStringExtra("LOG_MSG")

                if (incomingBotId != null && msg != null) {
                    val list = GlobalBotState.logs.getOrPut(incomingBotId) { mutableStateListOf() }
                    list.add(parseBotLogEntry(msg))
                    while (list.size > 3000) list.removeAt(0)

                    // 파일 저장은 BotService.sendLog()에서 처리
                }
            }
        }

        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter("BOT_LOG_EVENT"),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        onDispose { appContext.unregisterReceiver(receiver) }
    }

    // 🌟 전역으로 다크모드 상태를 주입
    CompositionLocalProvider(LocalIsDarkMode provides isGlobalDarkMode) {
        AnimatedContent(
            targetState = currentScreen,
            transitionSpec = {
                if (targetState == "SETTINGS" && initialState == "LOBBY") {
                    slideInHorizontally { width -> width } + fadeIn() togetherWith slideOutHorizontally { width -> -width / 2 } + fadeOut()
                } else if (targetState == "LOBBY" && initialState == "SETTINGS") {
                    slideInHorizontally { width -> -width / 2 } + fadeIn() togetherWith slideOutHorizontally { width -> width } + fadeOut()
                } else {
                    fadeIn() togetherWith fadeOut()
                }
            }, label = "MainScreenAnimation"
        ) { screen ->
            when (screen) {
                "LOBBY" -> BotListScreen(
                    onNavigateToSettings = { botId -> currentBotId = botId; currentScreen = "SETTINGS" },
                    onThemeToggle = {
                        isGlobalDarkMode = it
                        masterPref.edit().putBoolean("is_dark_mode", it).apply()
                    }
                )
                "SETTINGS" -> if (currentBotId != null) {
                    BotDetailScreen(
                        botId = currentBotId!!,
                        openBlockLogTrigger = openBlockLogTrigger,
                        onTriggerConsumed = { openBlockLogTrigger = false },
                        onBack = { currentScreen = "LOBBY" }
                    )
                }
            }
        }
    }
}
