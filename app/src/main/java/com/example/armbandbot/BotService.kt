package com.heyheyon.armbandbot

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import org.jsoup.Jsoup
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import android.app.AlarmManager

class BotService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private val activeBots = ConcurrentHashMap<String, Job>()
    private var wakeLock: PowerManager.WakeLock? = null
    private data class BotConfig(
        val isDebugMode: Boolean,
        val isExpertMode: Boolean,
        val snapshotKeepDays: Int,
        val isSnapshotBlocked: Boolean,
        val isSnapshotAll: Boolean,

        val blockDurationHours: Int,
        val blockReason: String,
        val deletePostOnBlock: Boolean,

        val isNotiMaster: Boolean,
        val notiKeyword: Boolean,
        val notiUser: Boolean,
        val notiNickname: Boolean,
        val notiYudong: Boolean,
        val notiKkang: Boolean,
        val notiUrl: Boolean,
        val notiImage: Boolean,
        val notiVoice: Boolean,
        val notiSpam: Boolean,

        val targetUrls: List<String>,

        val isKkangFilterMode: Boolean,
        val kkangPostMin: Int,
        val kkangCommentMin: Int,
        val isKkangPostBlock: Boolean,
        val isKkangCommentBlock: Boolean,
        val isKkangImageBlock: Boolean,
        val isKkangVoiceBlock: Boolean,

        val isSearchMode: Boolean,
        val searchType: String,
        val searchKeywords: List<String>,

        val isUserFilterMode: Boolean,
        val userBlacklist: List<String>,
        val userWhitelist: List<String>,

        val isNicknameFilterMode: Boolean,
        val nicknameBlacklist: List<String>,
        val nicknameWhitelist: List<String>,

        val isYudongPostBlock: Boolean,
        val isYudongCommentBlock: Boolean,
        val isYudongImageBlock: Boolean,
        val isYudongVoiceBlock: Boolean,

        val isUrlFilterMode: Boolean,
        val urlWhitelistList: List<String>,

        val isSpamCodeFilterMode: Boolean,
        val spamCodeLength: Int,

        val isImageFilterMode: Boolean,
        val imageFilterThreshold: Int,
        val imageAltBlacklist: List<String>,

        val isVoiceFilterMode: Boolean,
        val voiceBlacklist: List<String>,

        val scanPageCount: Int,
        val postMinMs: Long,
        val postMaxMs: Long,
        val pageMinMs: Long,
        val pageMaxMs: Long,
        val cycleMinMs: Long,
        val cycleMaxMs: Long,

        val normalWords: Array<String>,
        val bypassWords: Array<String>
    )
    private data class PostAnalysisResult(
        val isBadPost: Boolean,
        val isWhitelistedUser: Boolean,
        val isBlacklistedUserId: Boolean,
        val isBlacklistedUserNick: Boolean,
        val suspiciousUrlInPost: String? = null,
        val spamCodeMatchPost: String? = null,
        val matchedImageAlt: String? = null,
        val matchedVoiceIdPost: String? = null,
        val blockReasonPrefix: String? = null,
        val notiType: String? = null,
        val debugDetail: String? = null
    )

    private data class CommentAnalysisResult(
        val isBadComment: Boolean,
        val isWhitelistedUser: Boolean,
        val isBlacklistedUserId: Boolean,
        val isBlacklistedUserNick: Boolean,
        val suspiciousUrlInComment: String? = null,
        val spamCodeMatchComment: String? = null,
        val matchedVoiceIdComment: String? = null,
        val blockReasonPrefix: String? = null,
        val notiType: String? = null,
        val debugDetail: String? = null
    )

    private data class UserFilterResult(
        val isBlacklistedUserId: Boolean,
        val isBlacklistedUserNick: Boolean,
        val isWhitelistedUser: Boolean
    )

    private data class BlockExecutionResult(
        val blockReason: String?,
        val snapshotPath: String?
    )

    companion object {
        private const val ACTION_RESTART_BOTS = "com.heyheyon.armbandbot.ACTION_RESTART_BOTS"

        private val URL_REGEX = Regex("(?i)(?:https?://|www\\.)[-a-zA-Z0-9@:%._+~#=]{1,256}\\.[a-zA-Z0-9()]{1,6}\\b(?:[-a-zA-Z0-9()@:%_+.~#?&/=]*)")
        private val SEARCH_PARAM_CLEANER_REGEX = Regex("&s_type=[^&]*|&s_keyword=[^&]*|&search_pos=[^&]*")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WanjangBot::BackgroundLock")
        wakeLock?.acquire()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "bot_channel",
                "차단봇 24시간 감시망",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "봇이 하나 이상 실행 중일 때 표시되는 상시 알림"
                setShowBadge(false)
            }

            getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }
    }

    private fun sendLog(msg: String, botId: String) {
        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val formattedMsg = "[$timeStr] $msg"

        Log.d("BotService", "[$botId] $formattedMsg")

        // 1) 파일 저장
        appendBotLogToFile(applicationContext, botId, formattedMsg)

        // 2) UI 갱신용 브로드캐스트
        val intent = Intent("BOT_LOG_EVENT")
        intent.putExtra("LOG_MSG", formattedMsg)
        intent.putExtra("BOT_ID", botId)
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    private fun logBlock(
        botId: String,
        category: String,
        message: String
    ) {
        sendLog("[$category] $message", botId)
    }

    private fun sendBlockNotification(botId: String, botName: String, title: String, message: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "block_noti_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "차단 발생 알림", NotificationManager.IMPORTANCE_DEFAULT)
            notificationManager.createNotificationChannel(channel)
        }
        val clickIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("TARGET_BOT_ID", botId)
            putExtra("TARGET_ACTION", "OPEN_BLOCK_LOG")
        }
        val pendingIntent = PendingIntent.getActivity(this, botId.hashCode(), clickIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("[$botName] $title")
            .setContentText(message)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }

    private fun buildForegroundNotification(): android.app.Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, "bot_channel")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("실행 중")
            .setContentText("백그라운드에서 실행 중입니다.")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun scheduleAutoRestart() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, AutoRestartReceiver::class.java).apply {
            action = ACTION_RESTART_BOTS
        }

        val pendingIntent = PendingIntent.getBroadcast(
            this,
            1001,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val triggerAt = System.currentTimeMillis() + 3000L

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerAt,
                        pendingIntent
                    )
                    Log.d("BotService", "[복구 예약] exact alarm으로 3초 뒤 예약 완료")
                } else {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerAt,
                        pendingIntent
                    )
                    Log.d("BotService", "[복구 예약] exact alarm 권한 없음 → inexact alarm으로 대체 예약")
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    pendingIntent
                )
                Log.d("BotService", "[복구 예약] exact alarm으로 3초 뒤 예약 완료")
            } else {
                alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    pendingIntent
                )
                Log.d("BotService", "[복구 예약] legacy exact alarm으로 3초 뒤 예약 완료")
            }
        } catch (e: SecurityException) {
            Log.e("BotService", "[복구 예약] exact alarm 권한 부족, 일반 alarm으로 재시도", e)
            alarmManager.set(
                AlarmManager.RTC_WAKEUP,
                triggerAt,
                pendingIntent
            )
        }
    }

    private fun cancelAutoRestart() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, AutoRestartReceiver::class.java).apply {
            action = ACTION_RESTART_BOTS
        }

        val pendingIntent = PendingIntent.getBroadcast(
            this,
            1001,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        alarmManager.cancel(pendingIntent)
        Log.d("BotService", "[복구 예약] AutoRestartReceiver 예약 취소")
    }

    private fun finalizeBot(
        botId: String,
        botPref: android.content.SharedPreferences,
        botName: String,
        reason: String
    ) {
        val removedJob = activeBots.remove(botId)
        sendLog("[복구 점검] finalizeBot에서 Job 제거: ${removedJob != null}", botId)
        sendLog("[$botName] 종료: $reason", botId)

        if (activeBots.isEmpty()) {
            sendLog("[복구 점검] 남은 활성 Job 없음, 서비스 종료 절차 진행", botId)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                stopForeground(true)
            }
            stopSelf()
        }
    }

    private fun cleanupOldSnapshots(keepDays: Int, botId: String) {
        try {
            val cacheDir = File(cacheDir, "snapshots_$botId")
            if (!cacheDir.exists()) return

            val thresholdTime = System.currentTimeMillis() - (keepDays.toLong() * 24 * 60 * 60 * 1000)
            val oldFiles = cacheDir.listFiles()?.filter { it.lastModified() < thresholdTime }

            if (!oldFiles.isNullOrEmpty()) {
                var deletedCount = 0
                oldFiles.forEach { if (it.delete()) deletedCount++ }
                if (deletedCount > 0) sendLog("🧹 스냅샷 보관 기간($keepDays 일) 만료로 오래된 캐시 ${deletedCount}개 삭제 완료.", botId)
            }
        } catch (e: Exception) { /* 무시 */ }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, buildForegroundNotification())

        if (intent == null) {
            sendLog("[복구 점검] null intent로 서비스가 재시작됨. restoreRunningBots() 호출", "SYSTEM")
            restoreRunningBots(this)
            return START_STICKY
        }

        val botId = intent.getStringExtra("BOT_ID") ?: return START_STICKY
        val cookie = intent.getStringExtra("COOKIE") ?: ""
        val action = intent.action
        val botPref = getSharedPreferences("bot_prefs_$botId", Context.MODE_PRIVATE)
        val botName = botPref.getString("bot_name", "이름 없는 봇") ?: "이름 없는 봇"

        if (action == "STOP") {
            botPref.edit()
                .putBoolean("should_restore_after_restart", false)
                .putBoolean("is_running", false)
                .apply()

            activeBots[botId]?.cancel()
            activeBots.remove(botId)

            val masterPref = getSharedPreferences("bot_master", Context.MODE_PRIVATE)
            val botIds = (masterPref.getString("bot_ids_list", "") ?: "")
                .split(",")
                .filter { it.isNotBlank() }

            val hasRestorableBot = botIds.any { id ->
                getSharedPreferences("bot_prefs_$id", Context.MODE_PRIVATE)
                    .getBoolean("should_restore_after_restart", false)
            }

            if (!hasRestorableBot) {
                cancelAutoRestart()
            }

            return START_STICKY
        }

        val existingJob = activeBots[botId]
        if (existingJob != null && existingJob.isActive && !existingJob.isCancelled) {
            sendLog("[복구 점검] 이미 실행 중인 Job이 있어 START를 건너뜁니다.", botId)
            return START_STICKY
        }
        // 취소됐거나 완료된 잡은 정리 후 새 Job 생성
        activeBots.remove(botId)

        botPref.edit()
            .putBoolean("is_running", true)
            .putBoolean("should_restore_after_restart", true)
            .apply()

        sendLog("[복구 점검] 새 Job 생성을 시작합니다.", botId)
        scheduleAutoRestart()

        val job = serviceScope.launch {
            try {
                sendLog("[복구 점검] runBotLoop 진입", botId)
                runBotLoop(botId, botName, cookie, botPref)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("BotService", "[$botId] runBotLoop 치명적 오류", e)
                sendLog("[치명적 오류] ${e.message ?: "알 수 없는 오류"}", botId)
            } finally {
                finalizeBot(botId, botPref, botName, "봇 루프 종료")
            }
        }

        activeBots[botId] = job
        sendLog("[복구 점검] activeBots에 Job 등록 완료", botId)
        return START_STICKY
    }

    private fun String.removeCommentAndTrim() = this.substringBefore("#").trim()
    private fun convertToPcUrl(rawUrl: String): String {
        val url = rawUrl.trim()
        val idMatch = Regex("id=([^&]+)").find(url)
        if (idMatch != null) {
            val gallId = idMatch.groupValues[1]
            return if (url.contains("/mini/"))
                "https://gall.dcinside.com/mini/board/lists/?id=$gallId"
            else
                "https://gall.dcinside.com/mgallery/board/lists/?id=$gallId"
        }
        // id= 파라미터 없을 때: 경로 마지막 세그먼트를 gallId로 추출
        val pathMatch = Regex("gall\\.dcinside\\.com/([^?#]+)").find(url) ?: return url
        val segments = pathMatch.groupValues[1].trimEnd('/').split("/").filter { it.isNotEmpty() }
        if (segments.isEmpty()) return url
        val lastSegment = segments.last()
        return when {
            url.contains("/mini/") -> "https://gall.dcinside.com/mini/board/lists/?id=$lastSegment"
            url.contains("/mgallery/") || url.contains("/board/") -> "https://gall.dcinside.com/mgallery/board/lists/?id=$lastSegment"
            else -> url
        }
    }

    private fun parseTargetUrl(rawUrl: String): ParsedTargetUrl? {
        val idMatch = Regex("id=([^&]+)").find(rawUrl) ?: return null
        val gallId = idMatch.groupValues[1]
        val gallType = if (rawUrl.contains("/mini/")) "MI" else "M"
        return ParsedTargetUrl(
            gallId = gallId,
            gallType = gallType
        )
    }

    private fun hasManagerPermission(document: org.jsoup.nodes.Document): Boolean {
        return document.select("a[onclick*=listSearchHead(999)]").any {
            it.text().contains("매니저")
        }
    }
    private fun buildSnapshotUrl(gallType: String, gallId: String, postNum: String): String {
        return when (gallType) {
            "M" -> "https://m.dcinside.com/board/$gallId/$postNum"
            "MI" -> "https://m.dcinside.com/mini/$gallId/$postNum"
            else -> "https://m.dcinside.com/board/$gallId/$postNum"
        }
    }

    private fun isSessionValid(cookie: String): Boolean {
        return try {
            val sessionCheckDoc = Jsoup.connect("https://m.dcinside.com/")
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
                .header("Cookie", cookie)
                .get()

            sessionCheckDoc.text().contains("로그아웃")
        } catch (e: Exception) {
            true
        }
    }

    private fun getSuspiciousUrl(text: String, whitelist: List<String>): String? {
        val matches = URL_REGEX.findAll(text)
        for (match in matches) {
            var urlString = match.value.lowercase()
            if (!urlString.startsWith("http")) urlString = "http://$urlString"
            val hostMatch = Regex("https?://(?:www\\.)?([^/:]+)").find(urlString)
            val host = hostMatch?.groupValues?.get(1)
            if (host != null) {
                val isSafe = whitelist.any { safeDomain -> host == safeDomain || host.endsWith(".$safeDomain") }
                if (!isSafe) return match.value
            } else return match.value
        }
        return null
    }

    private fun getAltSimilarity(postAlt: String, blackAlt: String): Int {
        if (blackAlt.isEmpty()) return 0
        var matchCount = 0
        val minLen = Math.min(postAlt.length, blackAlt.length)
        for (i in 0 until minLen) { if (postAlt[i] == blackAlt[i]) matchCount++ else break }
        return ((matchCount.toDouble() / blackAlt.length) * 100).toInt()
    }

    private suspend fun CoroutineScope.runBotLoop(
        botId: String,
        botName: String,
        cookie: String,
        botPref: android.content.SharedPreferences
    ) {
        GlobalBotState.initDb(this@BotService)
        GlobalBotState.startSnapshotWorker(this)
        sendLog("[복구 점검] runBotLoop 시작 완료", botId)

        while (isActive) {
            val config = loadBotConfig(botPref)
            val blockDuration = config.blockDurationHours.toString()
            val blockReason = config.blockReason
            val delChk = if (config.deletePostOnBlock) "1" else "0"

            cleanupOldSnapshots(config.snapshotKeepDays, botId)

            val isNotiMaster = config.isNotiMaster
            val notiSettings = mapOf(
                "keyword" to config.notiKeyword,
                "user" to config.notiUser,
                "nickname" to config.notiNickname,
                "yudong" to config.notiYudong,
                "kkang" to config.notiKkang,
                "url" to config.notiUrl,
                "image" to config.notiImage,
                "voice" to config.notiVoice,
                "spam" to config.notiSpam
            )

            fun notifyIfEnabled(type: String, title: String, content: String) {
                if (isNotiMaster && notiSettings[type] == true) {
                    sendBlockNotification(botId, botName, title, content)
                }
            }

            val urlList = config.targetUrls

            if (urlList.isEmpty()) {
                sendLog("[$botName] 대상 URL이 없어 대기합니다.", botId)
                delay(10000)
                continue
            }

            sendLog("[백그라운드] $botName 사이클 시작!", botId)
            if (config.isDebugMode) {
                sendLog("[디버그][사이클] 대상 URL 목록 (${urlList.size}개): ${urlList.joinToString(", ")}", botId)
            }

            if (!isSessionValid(cookie)) {
                botPref.edit()
                    .putBoolean("should_restore_after_restart", false)
                    .putBoolean("is_running", false)
                    .apply()
                val autoLoginEnabled = botPref.getBoolean("auto_login_enabled", false)
                if (autoLoginEnabled) {
                    sendLog("🔄 세션이 만료되었습니다. 자동 로그인을 시도합니다.", botId)
                    val sessionExpiredIntent = Intent("BOT_SESSION_EXPIRED").apply {
                        putExtra("BOT_ID", botId)
                        setPackage(applicationContext.packageName)
                    }
                    sendBroadcast(sessionExpiredIntent)
                } else {
                    sendLog("🚨 로그인 세션이 만료되었습니다. 봇을 종료합니다.", botId)
                }
                break
            }

            val gallogCache = mutableMapOf<String, Pair<Int, Int>>()
            val pageMinMs = config.pageMinMs
            val pageMaxMs = config.pageMaxMs
            val cycleMinMs = config.cycleMinMs
            val cycleMaxMs = config.cycleMaxMs

            for ((urlIndex, rawUrl) in urlList.withIndex()) {
                if (!isActive) break
                if (config.isDebugMode) {
                    sendLog("[디버그][사이클] URL 처리 시작 (${urlIndex + 1}/${urlList.size}): $rawUrl", botId)
                }

                val canContinue = processTargetUrl(
                    config = config,
                    botId = botId,
                    cookie = cookie,
                    rawUrl = rawUrl,
                    gallogCache = gallogCache,
                    blockDuration = blockDuration,
                    blockReason = blockReason,
                    delChk = delChk,
                    notifyIfEnabled = ::notifyIfEnabled
                )

                if (!canContinue) {
                    botPref.edit()
                        .putBoolean("should_restore_after_restart", false)
                        .putBoolean("is_running", false)
                        .apply()
                    return
                }

                if (urlIndex < urlList.size - 1) {
                    delay(randomDelay(pageMinMs, pageMaxMs))
                }
            }
            GlobalBotState.saveDb(this@BotService)
            val randomCycleDelay = randomDelay(cycleMinMs, cycleMaxMs)
            sendLog("[$botName] 사이클 완료! ${String.format("%.1f", randomCycleDelay / 1000f)}초 대기.", botId)
            delay(randomCycleDelay)
        }
    }

    private suspend fun processPage(
        config: BotConfig,
        botId: String,
        cookie: String,
        gallType: String,
        gallId: String,
        pageUrl: String,
        ciTokenFallbackAllowed: Boolean = true,
        gallogCache: MutableMap<String, Pair<Int, Int>>,
        blockDuration: String,
        blockReason: String,
        delChk: String,
        notifyIfEnabled: (String, String, String) -> Unit
    ): PageProcessResult {
        if (config.isDebugMode) {
            sendLog("[디버그][페이지] 페이지 URL 접근 시작: $pageUrl", botId)
        }
        val document = Jsoup.connect(pageUrl)
            .userAgent("Mozilla/5.0")
            .header("Cookie", cookie)
            .get()
        val managerPermission = hasManagerPermission(document)
        if (config.isDebugMode) {
            sendLog("[디버그][페이지] 관리자 권한 확인 결과: ${if (managerPermission) "있음" else "없음"}", botId)
        }
        if (!managerPermission) {
            return PageProcessResult(
                firstPostNumOfThisPage = "",
                isPageEmpty = true,
                hiddenSearchPos = "",
                hasManagerPermission = false
            )
        }

        val ciToken = document.select("input[name=ci_t]").attr("value")
        val postRows = document.select(".ub-content")
        if (config.isDebugMode) {
            sendLog("[디버그][페이지] 발견한 게시글 수: ${postRows.size}", botId)
        }

        var firstPostNumOfThisPage = ""
        var isPageEmpty = true

        for (row in postRows) {
            val titleElement = row.selectFirst(".gall_tit a:not(.reply_numbox)") ?: continue
            val text = titleElement.text()
            val link = titleElement.attr("href")

            if (text.isNotBlank() && link.contains("no=")) {
                isPageEmpty = false

                val postNumStr = Regex("no=([0-9]+)").find(link)?.groupValues?.get(1) ?: "0"
                val postNumber = postNumStr.toIntOrNull() ?: 0

                val writerElement = row.selectFirst(".gall_writer")
                val postUid = writerElement?.attr("data-uid") ?: ""
                val postIp = writerElement?.attr("data-ip") ?: ""
                val postNick = writerElement?.attr("data-nick") ?: ""
                val postAuthor = postUid.ifEmpty { postIp }
                val postDisplayAuthor = if (postAuthor.isNotEmpty()) "$postNick($postAuthor)" else postNick

                val dateElement = row.selectFirst(".gall_date")
                val rawPostDate = dateElement?.attr("title")?.takeIf { it.isNotBlank() }
                    ?: dateElement?.text()
                    ?: ""
                val postDate = normalizeCreationDate(rawPostDate)

                if (firstPostNumOfThisPage.isEmpty()) {
                    firstPostNumOfThisPage = postNumStr
                }

                val replyBox = row.selectFirst(".reply_numbox")
                val currentCommentCount = replyBox?.text()
                    ?.split("/")?.firstOrNull()
                    ?.replace(Regex("[^0-9]"), "")
                    ?.toIntOrNull() ?: 0

                val savedCommentCount = GlobalBotState.getCommentCount(gallType, gallId, postNumStr)
                if (savedCommentCount != -1 && savedCommentCount == currentCommentCount) {
                    if (config.isDebugMode) {
                        sendLog("[디버그][페이지] 번호: $postNumStr / 댓글 수 변화 없음 (저장: $savedCommentCount, 현재: $currentCommentCount) → 스킵", botId)
                    }
                    continue
                }
                if (config.isDebugMode) {
                    sendLog("[디버그][페이지] 번호: $postNumStr / 댓글 수 변화 감지 (저장: $savedCommentCount, 현재: $currentCommentCount) → 처리 시작", botId)
                }

                try {
                    processSinglePost(
                        config = config,
                        botId = botId,
                        cookie = cookie,
                        gallType = gallType,
                        gallId = gallId,
                        postNumStr = postNumStr,
                        postNumber = postNumber,
                        text = text,
                        postUid = postUid,
                        postAuthor = postAuthor,
                        postNick = postNick,
                        postDisplayAuthor = postDisplayAuthor,
                        postDate = postDate,
                        currentCommentCount = currentCommentCount,
                        ciToken = ciToken,
                        gallogCache = gallogCache,
                        blockDuration = blockDuration,
                        blockReason = blockReason,
                        delChk = delChk,
                        notifyIfEnabled = notifyIfEnabled
                    )
                } catch (e: Exception) {
                    sendLog("[읽기 실패] 번호: $postNumStr", botId)
                }

                delay(randomDelay(config.postMinMs, config.postMaxMs))
            }
        }

        val hiddenSearchPos = if (ciTokenFallbackAllowed) {
            document.select("input[name=search_pos]").attr("value")
        } else {
            ""
        }

        return PageProcessResult(
            firstPostNumOfThisPage = firstPostNumOfThisPage,
            isPageEmpty = isPageEmpty,
            hiddenSearchPos = hiddenSearchPos
        )
    }

    private suspend fun processTargetUrl(
        config: BotConfig,
        botId: String,
        cookie: String,
        rawUrl: String,
        gallogCache: MutableMap<String, Pair<Int, Int>>,
        blockDuration: String,
        blockReason: String,
        delChk: String,
        notifyIfEnabled: (String, String, String) -> Unit
    ): Boolean {
        val parsedTarget = parseTargetUrl(rawUrl) ?: return true
        val gallId = parsedTarget.gallId
        val gallType = parsedTarget.gallType

        var cleanBaseUrl = rawUrl.replace(Regex("&page=[0-9]+"), "")
        if (config.isSearchMode) {
            cleanBaseUrl = cleanBaseUrl.replace(SEARCH_PARAM_CLEANER_REGEX, "")
        }

        val activeKeywords =
            if (config.isSearchMode && config.searchKeywords.isNotEmpty()) {
                config.searchKeywords
            } else {
                listOf("")
            }

        for ((keywordIndex, keyword) in activeKeywords.withIndex()) {
            if (!serviceScope.isActive) break

            val pageMatch = Regex("&page=([0-9]+)").find(rawUrl)
            var currentPage = pageMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
            var currentSearchPos = ""
            var logicalPageCount = 0
            var previousPageFirstPostNum = ""

            while (logicalPageCount < config.scanPageCount && serviceScope.isActive) {
                var pageUrl = "$cleanBaseUrl&page=$currentPage"

                if (config.isSearchMode) {
                    val encodedKeyword = java.net.URLEncoder.encode(keyword, "UTF-8")
                    pageUrl += "&s_type=${config.searchType}&s_keyword=$encodedKeyword"
                    if (currentSearchPos.isNotEmpty()) {
                        pageUrl += "&search_pos=$currentSearchPos"
                    }
                }

                try {
                    val pageResult = processPage(
                        config = config,
                        botId = botId,
                        cookie = cookie,
                        gallType = gallType,
                        gallId = gallId,
                        pageUrl = pageUrl,
                        ciTokenFallbackAllowed = config.isSearchMode,
                        gallogCache = gallogCache,
                        blockDuration = blockDuration,
                        blockReason = blockReason,
                        delChk = delChk,
                        notifyIfEnabled = notifyIfEnabled
                    )
                    if (!pageResult.hasManagerPermission) {
                        sendLog("🚨 [$gallId] 현재 로그인된 계정에 관리자 권한이 없습니다. 봇 작동을 중지합니다.", botId)
                        sendBlockNotification(
                            botId = botId,
                            botName = getSharedPreferences("bot_prefs_$botId", Context.MODE_PRIVATE)
                                .getString("bot_name", "이름 없는 봇") ?: "이름 없는 봇",
                            title = "관리 권한 없음",
                            message = "[$gallId] 갤러리의 관리자 권한이 확인되지 않아 봇을 중지했습니다."
                        )
                        return false
                    }

                    val firstPostNumOfThisPage = pageResult.firstPostNumOfThisPage
                    val isPageEmpty = pageResult.isPageEmpty
                    val hiddenPos = pageResult.hiddenSearchPos

                    if (config.isSearchMode &&
                        (isPageEmpty || (logicalPageCount > 0 && firstPostNumOfThisPage == previousPageFirstPostNum))
                    ) {
                        if (hiddenPos.isNotEmpty()) {
                            val nextPos = hiddenPos.toInt() + 10000
                            currentSearchPos = nextPos.toString()
                            currentPage = 1
                            previousPageFirstPostNum = ""
                            sendLog("[1만 개 단위 돌파!] 다음 검색 덩어리($nextPos)로 점프합니다.", botId)

                            delay(randomDelay(config.pageMinMs, config.pageMaxMs))
                            continue
                        } else {
                            break
                        }
                    }

                    previousPageFirstPostNum = firstPostNumOfThisPage
                } catch (e: Exception) {
                    sendLog("[$currentPage 페이지] 읽기 실패.", botId)
                }

                logicalPageCount++
                currentPage++

                if (logicalPageCount < config.scanPageCount) {
                    delay(randomDelay(config.pageMinMs, config.pageMaxMs))
                }
            }

            if (config.isSearchMode && keywordIndex < activeKeywords.size - 1) {
                delay(randomDelay(config.pageMinMs, config.pageMaxMs))
            }
        }
        return true
    }

    private fun captureBlockSnapshot(
        botId: String,
        gallType: String,
        gallId: String,
        postNumStr: String,
        cookie: String
    ): String? {
        val html = buildSnapshotHtml(
            botId = botId,
            gallType = gallType,
            gallId = gallId,
            postNumStr = postNumStr,
            cookie = cookie,
            debugLabel = "차단 증거 스냅샷"
        ) ?: return null

        return try {
            val cacheDir = File(cacheDir, "snapshots_$botId")
            if (!cacheDir.exists()) cacheDir.mkdirs()

            val timestamp = System.currentTimeMillis()
            val blockFile = File(cacheDir, "${gallId}_${postNumStr}_block_$timestamp.html")
            blockFile.writeText(html)
            blockFile.absolutePath
        } catch (e: Exception) {
            Log.e("BotService", "[$botId] block snapshot save failed", e)
            sendLog("[경고] 차단 증거 스냅샷 파일 저장 실패: ${e.javaClass.simpleName} / ${e.message ?: "원인 불명"}", botId)
            null
        }
    }

    private fun buildSnapshotHtml(
        botId: String,
        gallType: String,
        gallId: String,
        postNumStr: String,
        cookie: String,
        debugLabel: String = "스냅샷",
        existingDoc: org.jsoup.nodes.Document? = null
    ): String? {
        return try {
            val snapshotDoc = if (existingDoc != null) {
                existingDoc.clone()
            } else {
                val snapshotUrl = buildSnapshotUrl(gallType, gallId, postNumStr)
                sendLog("[디버그] $debugLabel 시도 URL: $snapshotUrl", botId)
                Jsoup.connect(snapshotUrl)
                    .userAgent("Mozilla/5.0 (Linux; Android 10; SM-G981B)")
                    .header("Cookie", cookie)
                    .get()
            }

            snapshotDoc.select(
                "header.header, nav.nav, footer.footer, .adv-group, .adv-groupno, .adv-groupin, .ad-md, .pwlink, .con-search-box, .outside-search-box, .view-btm-con, .reco-search, #singoPopup, #blockLayer, #voice_share, #sns_share"
            ).remove()

            snapshotDoc.head().append("<meta name=\"referrer\" content=\"unsafe-url\">")
            snapshotDoc.html()
        } catch (e: Exception) {
            Log.e("BotService", "[$botId] snapshot html build failed", e)
            sendLog("[경고] $debugLabel 생성 실패: ${e.javaClass.simpleName} / ${e.message ?: "원인 불명"}", botId)
            null
        }
    }

    private suspend fun processSinglePost(
        config: BotConfig,
        botId: String,
        cookie: String,
        gallType: String,
        gallId: String,
        postNumStr: String,
        postNumber: Int,
        text: String,
        postUid: String,
        postAuthor: String,
        postNick: String,
        postDisplayAuthor: String,
        postDate: String,
        currentCommentCount: Int,
        ciToken: String,
        gallogCache: MutableMap<String, Pair<Int, Int>>,
        blockDuration: String,
        blockReason: String,
        delChk: String,
        notifyIfEnabled: (String, String, String) -> Unit
    ) {
        if (config.isDebugMode) {
            sendLog("[디버그][게시글] 게시글 상세 접근 시작: 번호 $postNumStr", botId)
        }
        if (!config.isSearchMode) {
            synchronized(GlobalBotState) {
                val currentLast = GlobalBotState.lastCheckedNumbers[botId] ?: 0
                if (postNumber > currentLast) {
                    GlobalBotState.lastCheckedNumbers[botId] = postNumber
                }
            }
        }

        val pcPostDetailUrl =
            if (gallType == "M") {
                "https://gall.dcinside.com/mgallery/board/view/?id=$gallId&no=$postNumStr"
            } else {
                "https://gall.dcinside.com/mini/board/view/?id=$gallId&no=$postNumStr"
            }

        val postDoc = Jsoup.connect(pcPostDetailUrl)
            .userAgent("Mozilla/5.0")
            .header("Cookie", cookie)
            .get()

        val contentText = postDoc.select(".write_div").text()
        val postRawHtml = postDoc.select(".write_div").outerHtml()
        val postImageAlts = postDoc.select(".write_div img")
            .mapNotNull { it.attr("alt") }
            .filter { it.isNotBlank() }

        val freshCiToken = postDoc.select("input[name=ci_t]").attr("value")
        val esnoToken = postDoc.select("input[id=e_s_n_o]").attr("value")
        val tokenToUse = if (freshCiToken.isNotEmpty()) freshCiToken else ciToken

        val commentApiUrl = "https://gall.dcinside.com/board/comment/"
        val commentResponse = Jsoup.connect(commentApiUrl)
            .userAgent("Mozilla/5.0")
            .header("Cookie", cookie)
            .header("Referer", pcPostDetailUrl)
            .header("X-Requested-With", "XMLHttpRequest")
            .data("id", gallId)
            .data("no", postNumStr)
            .data("cmt_id", gallId)
            .data("cmt_no", postNumStr)
            .data("e_s_n_o", esnoToken)
            .data("comment_page", "1")
            .data("sort", "D")
            .data("_GALLTYPE_", gallType)
            .ignoreContentType(true)
            .method(org.jsoup.Connection.Method.POST)
            .execute()

        val commentsArray = org.json.JSONObject(commentResponse.body()).optJSONArray("comments")
        if (config.isDebugMode) {
            sendLog("[디버그][게시글] 번호: $postNumStr / 댓글 수 (API): ${commentsArray?.length() ?: 0}", botId)
        }
        val postText = "$text $contentText"

        val postAnalysis = analyzePost(
            config = config,
            botId = botId,
            postAuthor = postAuthor,
            postNick = postNick,
            postUid = postUid,
            postText = postText,
            postImageAlts = postImageAlts,
            postRawHtml = postRawHtml,
            gallogCache = gallogCache,
            tokenToUse = tokenToUse,
            cookie = cookie
        )
        if (config.isDebugMode) {
            if (postAnalysis.isBadPost) {
                sendLog("[디버그][게시글] 번호: $postNumStr / 분석 결과: 차단 대상 → ${postAnalysis.debugDetail}", botId)
            } else {
                sendLog("[디버그][게시글] 번호: $postNumStr / 분석 결과: 정상${if (postAnalysis.isWhitelistedUser) " (화이트리스트 통과)" else ""}", botId)
            }
        }

        val isBlacklistedUserId = postAnalysis.isBlacklistedUserId
        val isBlacklistedUserNick = postAnalysis.isBlacklistedUserNick
        val suspiciousUrlInPost = postAnalysis.suspiciousUrlInPost
        val spamCodeMatchPost = postAnalysis.spamCodeMatchPost
        val matchedImageAlt = postAnalysis.matchedImageAlt
        val matchedVoiceIdPost = postAnalysis.matchedVoiceIdPost
        val blockReasonPrefix = postAnalysis.blockReasonPrefix
        val notiType = postAnalysis.notiType

        var dbBlockReason: String? = null
        var dbSnapshotPath: String? = null
        var isPostBlocked = false

        fun saveSnapshotFromDoc(doc: org.jsoup.nodes.Document, comments: org.json.JSONArray? = null, blockedCommentNo: String? = null, blockedTs: String? = null): String? {
            if (!config.isExpertMode) return null
            sendLog("[디버그] postDoc 스냅샷 저장 시도: $postNumStr", botId)

            // 1. 광고/네비/헤더 등 불필요 요소 제거
            doc.select(
                "header.header, nav.nav, footer.footer, .adv-group, .adv-groupno, .adv-groupin, .ad-md, .pwlink, .con-search-box, .outside-search-box, .view-btm-con, .reco-search, #singoPopup, #blockLayer, #voice_share, #sns_share"
            ).remove()
            doc.head().append("<meta name=\"referrer\" content=\"unsafe-url\">")

            // 2. 외부 CSS 링크 제거 및 최소 필요 스타일 직접 삽입
            doc.select("link[rel=stylesheet]").remove()
            doc.head().append("""<style>
body{font-family:sans-serif;background:#fff;color:#333}
.view_comment,.comment_wrap,.cmt_list{display:block!important}
.cmt_list li{display:block!important;padding:8px 0;border-bottom:1px solid #eee}
.inner.clear{display:flex;flex-direction:column;gap:4px}
.info_lay{font-size:12px;color:#888}
.nickname em{font-weight:bold;color:#333;font-style:normal}
.ip{color:#aaa;margin-left:4px}
.date_time{color:#aaa;margin-left:8px}
.usertxt.ub-word{padding:4px 0}
p.usertxt{margin:0;line-height:1.5}
.voice-reple-text{display:inline-block;background:#f0f4ff;border-radius:4px;padding:2px 8px;font-size:12px;color:#4A6583}
.write_div{max-width:100%;overflow:hidden}
img{max-width:100%;height:auto}
.written_dccon{width:80px;height:80px}
.voice_wrap iframe{max-width:100%}
</style>""")

            // 3. 모든 script 제거 (JS 간섭 방지)
            doc.select("script").remove()

            // 4. 디시 댓글창 관련 요소 제거 (동적 댓글 로드 방지, #bot-comments / .view_comment는 제외)
            doc.select(".cmt_wrap, .cmt_write, #cmt_write, .reply_box, [class*=cmt_write], [class*=comment_list], .view_reply, #reply_w")
                .filter { el ->
                    el.id() != "bot-comments" &&
                    !el.hasClass("view_comment") &&
                    !el.parents().any { p -> p.id() == "bot-comments" }
                }
                .forEach { it.remove() }

            // 4. commentsArray로 디시 구조 HTML 블록 생성 (항상 view_comment 교체)
            run {
                // 디시 댓글 구조: div.view_comment#focus_cmt > div.comment_wrap > (div.comment_count + ul.cmt_list)
                val viewCommentDiv = org.jsoup.nodes.Element("div")
                viewCommentDiv.addClass("view_comment")
                viewCommentDiv.attr("id", "focus_cmt")

                val commentWrap = org.jsoup.nodes.Element("div")
                commentWrap.addClass("comment_wrap")

                val commentCount = org.jsoup.nodes.Element("div")
                commentCount.addClass("comment_count")
                commentCount.text("댓글 ${comments?.length() ?: 0}개")

                val cmtList = org.jsoup.nodes.Element("ul")
                cmtList.addClass("cmt_list")

                if (comments != null && comments.length() > 0) {
                for (i in 0 until comments.length()) {
                    val cmt = comments.getJSONObject(i)
                    val depth = cmt.optInt("depth", 0)
                    val nick = cmt.optString("name", "")
                    val uid = cmt.optString("user_id", "")
                    val ip = cmt.optString("ip", "")
                    val date = cmt.optString("reg_date", "")
                    val memo = cmt.optString("memo", "")
                    val vrPlayerTag = cmt.optString("vr_player_tag", "")
                    val isVoiceReple = memo.contains("voice_wrap") || memo.contains("voice/player") || vrPlayerTag.isNotEmpty()
                    val no = cmt.optString("no", "")
                    val isBlocked = blockedCommentNo != null && no == blockedCommentNo

                    // li.ub-content[data-no] (reply_cont 클래스: depth==1)
                    val li = org.jsoup.nodes.Element("li")
                    li.addClass("ub-content")
                    if (depth == 1) li.addClass("reply_cont")
                    li.attr("data-no", no)
                    if (isBlocked) {
                        li.attr("style", "background:#fff5f5;border-left:3px solid #D32F2F;")
                    }

                    val innerDiv = org.jsoup.nodes.Element("div")
                    innerDiv.addClass("inner clear")

                    // div.info_lay: span.nickname>em + span.ip + span.date_time
                    val infoLay = org.jsoup.nodes.Element("div")
                    infoLay.addClass("info_lay")

                    val nicknameSpan = org.jsoup.nodes.Element("span")
                    nicknameSpan.addClass("nickname")
                    val nickEm = org.jsoup.nodes.Element("em")
                    nickEm.text(nick)
                    nicknameSpan.appendChild(nickEm)

                    val ipSpan = org.jsoup.nodes.Element("span")
                    ipSpan.addClass("ip")
                    ipSpan.text(if (uid.isNotEmpty()) uid else ip)

                    val dateSpan = org.jsoup.nodes.Element("span")
                    dateSpan.addClass("date_time")
                    dateSpan.text(date)

                    infoLay.appendChild(nicknameSpan)
                    infoLay.appendChild(ipSpan)
                    infoLay.appendChild(dateSpan)

                    // div.usertxt.ub-word: p.usertxt + dccon img
                    val usertxtDiv = org.jsoup.nodes.Element("div")
                    usertxtDiv.addClass("usertxt ub-word")

                    // memo에서 dccon img 추출 후 텍스트와 이미지 분리
                    val memoDoc = org.jsoup.Jsoup.parseBodyFragment(memo)
                    val dcconImgs = memoDoc.select("img.written_dccon, img[src*=dccon.php]")
                    // @멘션 추출 (a.mention 또는 @로 시작하는 텍스트)
                    val mentionEls = memoDoc.select("a.mention")
                    val mentionTexts = mentionEls.map { el ->
                        val t = el.text().trim()
                        if (t.startsWith("@")) t else "@$t"
                    }
                    mentionEls.remove()
                    memoDoc.select("img").remove()

                    val textP = org.jsoup.nodes.Element("p")
                    textP.addClass("usertxt")
                    val bodyText = memoDoc.body().text().trim()
                    if (mentionTexts.isNotEmpty()) {
                        val mentionLine = mentionTexts.joinToString(" ")
                        textP.appendText(mentionLine)
                        if (bodyText.isNotEmpty()) {
                            textP.append("<br/>")
                            textP.appendText(bodyText)
                        }
                    } else {
                        textP.text(bodyText)
                    }
                    if (isBlocked) {
                        val blockedSpan = org.jsoup.nodes.Element("span")
                        blockedSpan.attr("style", "color:#D32F2F;font-weight:bold;margin-left:4px;")
                        blockedSpan.text("[차단됨]")
                        textP.appendChild(blockedSpan)
                    }
                    usertxtDiv.appendChild(textP)

                    // 보이스리플 span 삽입 (p.usertxt 다음)
                    if (isVoiceReple) {
                        val voiceSpan = org.jsoup.nodes.Element("span")
                        voiceSpan.addClass("voice-reple-text")
                        voiceSpan.attr("style", "display:inline-block;background:#f0f4ff;border-radius:4px;padding:2px 8px;font-size:12px;color:#4A6583;margin-top:4px;")
                        voiceSpan.text("[🔊 보이스리플]")
                        usertxtDiv.appendChild(voiceSpan)
                    }

                    // dccon 이미지 삽입 (p 다음에 img 태그)
                    if (dcconImgs.isNotEmpty()) {
                        dcconImgs.forEach { dcconImg ->
                            val rawSrc = dcconImg.attr("src")
                            if (rawSrc.isNotEmpty()) {
                                val src = if (rawSrc.startsWith("//")) "https:$rawSrc" else rawSrc
                                val img = org.jsoup.nodes.Element("img")
                                img.attr("src", src)
                                img.addClass("s-dccon")
                                usertxtDiv.appendChild(img)
                            }
                        }
                    } else if (vrPlayerTag.isNotEmpty()) {
                        org.jsoup.Jsoup.parseBodyFragment(vrPlayerTag).select("img").forEach { dcconImg ->
                            val rawSrc = dcconImg.attr("src")
                            if (rawSrc.contains("dccon.php") || rawSrc.contains("viewimage.php")) {
                                val src = if (rawSrc.startsWith("//")) "https:$rawSrc" else rawSrc
                                val img = org.jsoup.nodes.Element("img")
                                img.attr("src", src)
                                img.addClass("s-dccon")
                                usertxtDiv.appendChild(img)
                            }
                        }
                    }

                    innerDiv.appendChild(infoLay)
                    innerDiv.appendChild(usertxtDiv)
                    li.appendChild(innerDiv)
                    cmtList.appendChild(li)
                }
                } else {
                    val emptyLi = org.jsoup.nodes.Element("li")
                    emptyLi.text("댓글이 없습니다.")
                    cmtList.appendChild(emptyLi)
                }

                commentWrap.appendChild(commentCount)
                commentWrap.appendChild(cmtList)
                viewCommentDiv.appendChild(commentWrap)

                // 댓글창 display:block 인라인 스타일 강제 적용 (디시 CSS 오버라이드)
                viewCommentDiv.attr("style", "display:block;")
                commentWrap.attr("style", "display:block;")
                cmtList.attr("style", "display:block;")

                // 5. .view_comment 또는 #focus_cmt가 있으면 replaceWith, 없으면 기존 위치 로직
                val existingComment = doc.selectFirst(".view_comment") ?: doc.getElementById("focus_cmt")
                val gallExposure = doc.selectFirst("div.gall_exposure")
                val contentWrap = doc.selectFirst(".gallview_contents, .view_content_wrap")
                when {
                    existingComment != null -> existingComment.replaceWith(viewCommentDiv)
                    gallExposure != null -> gallExposure.after(viewCommentDiv)
                    contentWrap != null -> contentWrap.after(viewCommentDiv)
                    else -> doc.body()?.appendChild(viewCommentDiv)
                }
            }

            // 6. doc.html()을 직접 저장 (buildSnapshotHtml 호출 없음, 이미지 src 원본 그대로)
            return try {
                val cacheDir = File(cacheDir, "snapshots_$botId")
                if (!cacheDir.exists()) cacheDir.mkdirs()

                val html = doc.html()

                if (blockedTs != null) {
                    val blockedFile = File(cacheDir, "${gallId}_${postNumStr}_blocked_${blockedTs}.html")
                    blockedFile.writeText(html)
                    blockedFile.absolutePath
                } else {
                    val initialFile = File(cacheDir, "${gallId}_${postNumStr}_initial.html")
                    val latestFile = File(cacheDir, "${gallId}_${postNumStr}_latest.html")
                    latestFile.writeText(html)
                    if (!initialFile.exists()) {
                        initialFile.writeText(html)
                    }
                    latestFile.absolutePath.also { dbSnapshotPath = it }
                }
            } catch (e: Exception) {
                Log.e("BotService", "[$botId] snapshot save failed", e)
                sendLog("[경고] 스냅샷 파일 저장 실패: ${e.javaClass.simpleName} / ${e.message ?: "원인 불명"}", botId)
                null
            }
        }

        if (config.isExpertMode && config.isSnapshotAll) {
            if (GlobalBotState.tryLockGeneralSnapshot(gallType, gallId, postNumStr)) {
                try {
                    saveSnapshotFromDoc(postDoc, commentsArray)
                    if (dbSnapshotPath != null) {
                        GlobalBotState.getDb()?.postDao()
                            ?.updateSnapshotPath(gallType, gallId, postNumStr, dbSnapshotPath!!)
                    }
                } finally {
                    GlobalBotState.unlockGeneralSnapshot(gallType, gallId, postNumStr)
                }
            }
        }

        if (postAnalysis.isBadPost) {
            if (config.isDebugMode && !postAnalysis.debugDetail.isNullOrBlank()) {
                sendLog("[디버그][게시글 차단 상세] 번호: $postNumStr / ${postAnalysis.debugDetail}", botId)
            }

            isPostBlocked = true

            val postBlockResult = handleBadPost(
                config = config,
                botId = botId,
                gallType = gallType,
                gallId = gallId,
                postNumStr = postNumStr,
                postAuthor = postAuthor,
                postNick = postNick,
                postDisplayAuthor = postDisplayAuthor,
                postTitle = text,
                postDate = postDate,
                cookie = cookie,
                pcPostDetailUrl = pcPostDetailUrl,
                tokenToUse = tokenToUse,
                blockDuration = blockDuration,
                blockReasonText = blockReason,
                delChk = delChk,
                isBlacklistedUserId = isBlacklistedUserId,
                isBlacklistedUserNick = isBlacklistedUserNick,
                blockReasonPrefix = blockReasonPrefix,
                notiType = notiType,
                matchedVoiceIdPost = matchedVoiceIdPost,
                matchedImageAlt = matchedImageAlt,
                suspiciousUrlInPost = suspiciousUrlInPost,
                spamCodeMatchPost = spamCodeMatchPost,
                notifyIfEnabled = notifyIfEnabled,
                debugDetail = postAnalysis.debugDetail,
                saveSnapshotFn = {
                    val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                    saveSnapshotFromDoc(postDoc, commentsArray, blockedTs = ts)
                }
            )

            dbBlockReason = postBlockResult.blockReason
            dbSnapshotPath = postBlockResult.snapshotPath
        } else {
            var badCommentCount = 0

            if (commentsArray != null) {
                for (i in 0 until commentsArray.length()) {
                    val commentObj = commentsArray.getJSONObject(i)
                    val commentMemo = commentObj.optString("memo", "")
                    val commentNo = commentObj.optString("no", "")
                    val cmtUid = commentObj.optString("user_id", "")
                    val cmtIp = commentObj.optString("ip", "")
                    val cmtNick = commentObj.optString("name", "")
                    val cmtAuthor = cmtUid.ifEmpty { cmtIp }
                    val cmtDisplayAuthor = if (cmtAuthor.isNotEmpty()) "$cmtNick($cmtAuthor)" else cmtNick
                    val rawCommentDate = commentObj.optString("reg_date", commentObj.optString("date", ""))
                    val commentDate = normalizeCreationDate(rawCommentDate)

                    val commentAnalysis = analyzeComment(
                        config = config,
                        botId = botId,
                        cmtAuthor = cmtAuthor,
                        cmtNick = cmtNick,
                        cmtUid = cmtUid,
                        commentMemo = commentMemo,
                        gallogCache = gallogCache,
                        tokenToUse = tokenToUse,
                        cookie = cookie
                    )
                    if (config.isDebugMode) {
                        if (commentAnalysis.isBadComment) {
                            sendLog("[디버그][댓글] 작성자: $cmtDisplayAuthor / 분석 결과: 차단 대상 → ${commentAnalysis.debugDetail}", botId)
                        } else {
                            sendLog("[디버그][댓글] 작성자: $cmtDisplayAuthor / 분석 결과: 정상${if (commentAnalysis.isWhitelistedUser) " (화이트리스트 통과)" else ""}", botId)
                        }
                    }

                    val isBlacklistedCmtUserId = commentAnalysis.isBlacklistedUserId
                    val isBlacklistedCmtUserNick = commentAnalysis.isBlacklistedUserNick
                    val matchedVoiceIdComment = commentAnalysis.matchedVoiceIdComment
                    val suspiciousUrlInComment = commentAnalysis.suspiciousUrlInComment
                    val spamCodeMatchComment = commentAnalysis.spamCodeMatchComment
                    val blockReasonPrefixCmt = commentAnalysis.blockReasonPrefix
                    val notiTypeCmt = commentAnalysis.notiType

                    if (commentAnalysis.isBadComment) {
                        if (config.isDebugMode && !commentAnalysis.debugDetail.isNullOrBlank()) {
                            sendLog(
                                "[디버그][댓글 차단 상세] 번호: $postNumStr / 작성자: $cmtDisplayAuthor / ${commentAnalysis.debugDetail}",
                                botId
                            )
                        }

                        val commentBlockResult = handleBadComment(
                            config = config,
                            botId = botId,
                            gallType = gallType,
                            gallId = gallId,
                            postNumStr = postNumStr,
                            commentNo = commentNo,
                            cmtDisplayAuthor = cmtDisplayAuthor,
                            cmtNick = cmtNick,
                            commentMemo = commentMemo,
                            commentDate = commentDate,
                            cookie = cookie,
                            pcPostDetailUrl = pcPostDetailUrl,
                            tokenToUse = tokenToUse,
                            blockDuration = blockDuration,
                            blockReasonText = blockReason,
                            delChk = delChk,
                            isBlacklistedCmtUserId = isBlacklistedCmtUserId,
                            isBlacklistedCmtUserNick = isBlacklistedCmtUserNick,
                            blockReasonPrefixCmt = blockReasonPrefixCmt,
                            notiTypeCmt = notiTypeCmt,
                            matchedVoiceIdComment = matchedVoiceIdComment,
                            suspiciousUrlInComment = suspiciousUrlInComment,
                            spamCodeMatchComment = spamCodeMatchComment,
                            notifyIfEnabled = notifyIfEnabled,
                            debugDetail = commentAnalysis.debugDetail,
                            saveSnapshotFn = {
                                val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                                saveSnapshotFromDoc(postDoc, commentsArray, blockedCommentNo = commentNo, blockedTs = ts)
                            }
                        )

                        dbBlockReason = commentBlockResult.blockReason
                        dbSnapshotPath = commentBlockResult.snapshotPath
                        isPostBlocked = true
                        badCommentCount++
                    }
                }
            }

            if (badCommentCount > 0) {
                if (delChk == "1") {
                    sendLog("악플 ${badCommentCount}개 삭제 및 차단 완료!", botId)
                } else {
                    sendLog("악플 ${badCommentCount}개 차단 완료!", botId)
                }
            }
        }

        GlobalBotState.savePost(
            gallType = gallType,
            gallId = gallId,
            postNum = postNumStr,
            commentCount = currentCommentCount,
            title = text,
            author = postDisplayAuthor,
            isBlocked = isPostBlocked,
            blockReason = dbBlockReason,
            snapshotPath = dbSnapshotPath,
            creationDate = postDate
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()

        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }

        val masterPref = getSharedPreferences("bot_master", Context.MODE_PRIVATE)
        val botIds = (masterPref.getString("bot_ids_list", "") ?: "")
            .split(",")
            .filter { it.isNotBlank() }

        val hasRestorableBot = botIds.any { id ->
            getSharedPreferences("bot_prefs_$id", Context.MODE_PRIVATE)
                .getBoolean("should_restore_after_restart", false)
        }

        if (hasRestorableBot) {
            scheduleAutoRestart()
        } else {
            cancelAutoRestart()
        }
    }

    private fun buildSpamCodeRegex(config: BotConfig): Regex? {
        if (!config.isSpamCodeFilterMode || config.spamCodeLength <= 0) {
            return null
        }

        val codeChar = "[A-Z0-9]"
        val digitChar = "[0-9]"
        val separator = "[^a-zA-Z0-9가-힣]*"
        val excludeAllDigits =
            "(?!(?:$digitChar$separator){${config.spamCodeLength - 1}}$digitChar(?![a-zA-Z0-9]))"

        return Regex(
            "(?<![a-zA-Z0-9])$excludeAllDigits(?:$codeChar$separator){${config.spamCodeLength - 1}}$codeChar(?![a-zA-Z0-9])"
        )
    }

    private fun evaluateUserFilter(
        config: BotConfig,
        author: String,
        nick: String
    ): UserFilterResult {
        val isBlacklistedUserId =
            config.isUserFilterMode && config.userBlacklist.contains(author)

        val isBlacklistedUserNick =
            config.isNicknameFilterMode && config.nicknameBlacklist.contains(nick)

        val isWhitelistedUser =
            (config.isUserFilterMode && config.userWhitelist.contains(author)) ||
                    (config.isNicknameFilterMode && config.nicknameWhitelist.contains(nick))

        return UserFilterResult(
            isBlacklistedUserId = isBlacklistedUserId,
            isBlacklistedUserNick = isBlacklistedUserNick,
            isWhitelistedUser = isWhitelistedUser
        )
    }

    private fun buildBypassRegex(keyword: String): Regex {
        val cleanedKeyword = keyword.trim()
        if (cleanedKeyword.isEmpty()) return Regex("$^")

        val hasKorean = cleanedKeyword.any { it in '가'..'힣' }

        // 한글이 포함된 금지어:
        // 글자 사이에 "한글이 아닌 것"만 끼어들면 우회로 간주해서 잡음
        // -> 한글이 끼어들면 매치되지 않음
        //
        // 한글이 없는 금지어:
        // 글자 사이에 "한글/영문/숫자가 아닌 것"만 끼어들면 우회로 간주해서 잡음
        // -> 한글/영문/숫자가 끼어들면 매치되지 않음
        val separator = if (hasKorean) {
            "[^가-힣]*"
        } else {
            "[^가-힣A-Za-z0-9]*"
        }

        val pattern = cleanedKeyword
            .toCharArray()
            .joinToString(separator) { Regex.escape(it.toString()) }

        return Regex(pattern, RegexOption.IGNORE_CASE)
    }

    private fun getGallogStats(
        userId: String,
        gallogCache: MutableMap<String, Pair<Int, Int>>,
        tokenToUse: String,
        cookie: String,
        logTag: String,
        botId: String = "",
        isDebugMode: Boolean = false
    ): GallogStats {
        if (userId.isBlank()) {
            return GallogStats(postCount = 0, commentCount = 0)
        }

        gallogCache[userId]?.let { cached ->
            if (isDebugMode && botId.isNotEmpty()) {
                sendLog("[디버그][갤로그] userId: $userId / 캐시 결과 사용 → 글: ${cached.first}, 댓글: ${cached.second}", botId)
            }
            return GallogStats(
                postCount = cached.first,
                commentCount = cached.second
            )
        }

        return try {
            val res = Jsoup.connect("https://gall.dcinside.com/api/gallog_user_layer/gallog_content_reple/")
                .userAgent("Mozilla/5.0")
                .header("Cookie", cookie)
                .header("X-Requested-With", "XMLHttpRequest")
                .data("ci_t", tokenToUse)
                .data("user_id", userId)
                .method(org.jsoup.Connection.Method.POST)
                .ignoreContentType(true)
                .execute()

            val parts = res.body().split(",")
            val postCount = parts.getOrNull(0)?.toIntOrNull() ?: 100
            val commentCount = parts.getOrNull(1)?.toIntOrNull() ?: 100

            gallogCache[userId] = Pair(postCount, commentCount)

            if (isDebugMode && botId.isNotEmpty()) {
                sendLog("[디버그][갤로그] userId: $userId / API 결과 → 글: $postCount, 댓글: $commentCount", botId)
            }

            GallogStats(
                postCount = postCount,
                commentCount = commentCount
            )
        } catch (e: Exception) {
            Log.e("BotService", logTag, e)
            GallogStats(postCount = 100, commentCount = 100)
        }
    }

    private fun randomDelay(minMs: Long, maxMs: Long): Long {
        return if (minMs < maxMs) {
            Random.nextLong(minMs, maxMs)
        } else {
            minMs
        }
    }
    private fun normalizeCreationDate(raw: String): String {
        val value = raw.trim()
        if (value.isBlank()) return ""

        val fullDashPattern = Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}""")
        if (fullDashPattern.matches(value)) return value

        val mdDotPattern = Regex("""(\d{2})\.(\d{2}) (\d{2}:\d{2}:\d{2})""")
        val mdMatch = mdDotPattern.matchEntire(value)
        if (mdMatch != null) {
            val year = SimpleDateFormat("yyyy", Locale.getDefault()).format(Date())
            val month = mdMatch.groupValues[1]
            val day = mdMatch.groupValues[2]
            val time = mdMatch.groupValues[3]
            return "$year-$month-$day $time"
        }

        val ymdDotPattern = Regex("""(\d{4})\.(\d{2})\.(\d{2}) (\d{2}:\d{2}:\d{2})""")
        val ymdMatch = ymdDotPattern.matchEntire(value)
        if (ymdMatch != null) {
            val year = ymdMatch.groupValues[1]
            val month = ymdMatch.groupValues[2]
            val day = ymdMatch.groupValues[3]
            val time = ymdMatch.groupValues[4]
            return "$year-$month-$day $time"
        }

        return value
    }
    private fun loadBotConfig(botPref: android.content.SharedPreferences): BotConfig {
        val rawUrlsText = botPref.getString("target_urls", "") ?: ""
        val targetUrls = rawUrlsText
            .split("\n")
            .map { it.removeCommentAndTrim() }
            .filter { it.isNotEmpty() }
            .map { convertToPcUrl(it) }

        val postMinMs = (botPref.getFloat("delay_post_min_sec", 1.0f) * 1000).toLong()
        val postMaxMs = maxOf((botPref.getFloat("delay_post_max_sec", 2.5f) * 1000).toLong(), postMinMs + 1L)

        val pageMinMs = (botPref.getFloat("delay_page_min_sec", 2.0f) * 1000).toLong()
        val pageMaxMs = maxOf((botPref.getFloat("delay_page_max_sec", 4.0f) * 1000).toLong(), pageMinMs + 1L)

        val cycleMinMs = (botPref.getFloat("delay_cycle_min_sec", 45.0f) * 1000).toLong()
        val cycleMaxMs = maxOf((botPref.getFloat("delay_cycle_max_sec", 90.0f) * 1000).toLong(), cycleMinMs + 1L)

        return BotConfig(
            isDebugMode = botPref.getBoolean("is_debug_mode", false),
            isExpertMode = botPref.getBoolean("is_expert_mode", false),
            snapshotKeepDays = botPref.getInt("snapshot_keep_days", 7),
            isSnapshotBlocked = botPref.getBoolean("is_snapshot_blocked", true),
            isSnapshotAll = botPref.getBoolean("is_snapshot_all", false),

            blockDurationHours = botPref.getInt("block_duration_hours", 6),
            blockReason = botPref.getString("block_reason_text", "커뮤니티 규칙 위반") ?: "커뮤니티 규칙 위반",
            deletePostOnBlock = botPref.getBoolean("delete_post_on_block", true),

            isNotiMaster = botPref.getBoolean("noti_master", true),
            notiKeyword = botPref.getBoolean("noti_keyword", true),
            notiUser = botPref.getBoolean("noti_user", true),
            notiNickname = botPref.getBoolean("noti_nickname", true),
            notiYudong = botPref.getBoolean("noti_yudong", true),
            notiKkang = botPref.getBoolean("noti_kkang", true),
            notiUrl = botPref.getBoolean("noti_url", true),
            notiImage = botPref.getBoolean("noti_image", true),
            notiVoice = botPref.getBoolean("noti_voice", true),
            notiSpam = botPref.getBoolean("noti_spam", true),

            targetUrls = targetUrls,

            isKkangFilterMode = botPref.getBoolean("is_kkang_filter_mode", false),
            kkangPostMin = botPref.getInt("kkang_post_min", 5),
            kkangCommentMin = botPref.getInt("kkang_comment_min", 10),
            isKkangPostBlock = botPref.getBoolean("is_kkang_post_block", false),
            isKkangCommentBlock = botPref.getBoolean("is_kkang_comment_block", false),
            isKkangImageBlock = botPref.getBoolean("is_kkang_image_block", false),
            isKkangVoiceBlock = botPref.getBoolean("is_kkang_voice_block", false),

            isSearchMode = botPref.getBoolean("is_search_mode", false),
            searchType = botPref.getString("search_type", "search_subject_memo") ?: "search_subject_memo",
            searchKeywords = botPref.getStringSet("search_keywords", setOf())
                ?.map { it.removeCommentAndTrim() }
                ?.filter { it.isNotEmpty() }
                ?: emptyList(),

            isUserFilterMode = botPref.getBoolean("is_user_filter_mode", false),
            userBlacklist = botPref.getStringSet("user_blacklist", setOf())
                ?.map { it.removeCommentAndTrim() }
                ?.filter { it.isNotEmpty() }
                ?: emptyList(),
            userWhitelist = botPref.getStringSet("user_whitelist", setOf())
                ?.map { it.removeCommentAndTrim() }
                ?.filter { it.isNotEmpty() }
                ?: emptyList(),

            isNicknameFilterMode = botPref.getBoolean("is_nickname_filter_mode", false),
            nicknameBlacklist = botPref.getStringSet("nickname_blacklist", setOf())
                ?.map { it.removeCommentAndTrim() }
                ?.filter { it.isNotEmpty() }
                ?: emptyList(),
            nicknameWhitelist = botPref.getStringSet("nickname_whitelist", setOf())
                ?.map { it.removeCommentAndTrim() }
                ?.filter { it.isNotEmpty() }
                ?: emptyList(),

            isYudongPostBlock = botPref.getBoolean("is_yudong_post_block", false),
            isYudongCommentBlock = botPref.getBoolean("is_yudong_comment_block", false),
            isYudongImageBlock = botPref.getBoolean("is_yudong_image_block", false),
            isYudongVoiceBlock = botPref.getBoolean("is_yudong_voice_block", false),

            isUrlFilterMode = botPref.getBoolean("is_url_filter_mode", false),
            urlWhitelistList = botPref.getStringSet("url_whitelist", setOf())
                ?.map { it.removeCommentAndTrim().lowercase() }
                ?.filter { it.isNotEmpty() }
                ?: emptyList(),

            isSpamCodeFilterMode = botPref.getBoolean("is_spam_code_filter_mode", false),
            spamCodeLength = botPref.getInt("spam_code_length", 6),

            isImageFilterMode = botPref.getBoolean("is_image_filter_mode", false),
            imageFilterThreshold = botPref.getInt("image_filter_threshold", 80),
            imageAltBlacklist = botPref.getStringSet("image_alt_blacklist", setOf())
                ?.map { it.removeCommentAndTrim() }
                ?.filter { it.isNotEmpty() }
                ?: emptyList(),

            isVoiceFilterMode = botPref.getBoolean("is_voice_filter_mode", false),
            voiceBlacklist = botPref.getStringSet("voice_blacklist", setOf())
                ?.map { it.removeCommentAndTrim() }
                ?.filter { it.isNotEmpty() }
                ?: emptyList(),

            scanPageCount = botPref.getInt("scan_page_count", 1),
            postMinMs = postMinMs,
            postMaxMs = postMaxMs,
            pageMinMs = pageMinMs,
            pageMaxMs = pageMaxMs,
            cycleMinMs = cycleMinMs,
            cycleMaxMs = cycleMaxMs,

            normalWords = botPref.getStringSet("normal", setOf())
                ?.map { it.removeCommentAndTrim() }
                ?.filter { it.isNotEmpty() }
                ?.toTypedArray()
                ?: arrayOf(),

            bypassWords = botPref.getStringSet("bypass", setOf())
                ?.map { it.removeCommentAndTrim() }
                ?.filter { it.isNotEmpty() }
                ?.toTypedArray()
                ?: arrayOf()
        )
    }
    private fun analyzePost(
        config: BotConfig,
        botId: String = "",
        postAuthor: String,
        postNick: String,
        postUid: String,
        postText: String,
        postImageAlts: List<String>,
        postRawHtml: String,
        gallogCache: MutableMap<String, Pair<Int, Int>>,
        tokenToUse: String,
        cookie: String
    ): PostAnalysisResult {
        val userFilter = evaluateUserFilter(config, postAuthor, postNick)
        val isBlacklistedUserId = userFilter.isBlacklistedUserId
        val isBlacklistedUserNick = userFilter.isBlacklistedUserNick
        val isWhitelistedUser = userFilter.isWhitelistedUser

        if (config.isDebugMode && botId.isNotEmpty()) {
            sendLog("[디버그][필터/유저] 작성자: $postAuthor / 닉: $postNick → 블랙(ID): $isBlacklistedUserId, 블랙(닉): $isBlacklistedUserNick, 화이트: $isWhitelistedUser", botId)
        }

        var isBadPost = isBlacklistedUserId || isBlacklistedUserNick
        var suspiciousUrlInPost: String? = null
        var spamCodeMatchPost: String? = null
        var matchedImageAlt: String? = null
        var matchedVoiceIdPost: String? = null
        var blockReasonPrefix: String? = null
        var notiType: String? = null
        var debugDetail: String? = null

        if (isBlacklistedUserId) {
            debugDetail = "ID/IP 블랙리스트 일치 ($postAuthor)"
        } else if (isBlacklistedUserNick) {
            debugDetail = "닉네임 블랙리스트 일치 ($postNick)"
        }

        val spamCodeRegex = buildSpamCodeRegex(config)

        if (isWhitelistedUser && config.isDebugMode && botId.isNotEmpty()) {
            sendLog("[디버그][필터/화이트] 화이트리스트 유저 → 이후 모든 필터 통과", botId)
        }

        if (!isBadPost && !isWhitelistedUser) {
            val isYudong = postUid.isEmpty()
            if (config.isDebugMode && botId.isNotEmpty()) {
                sendLog("[디버그][필터/유동] 유동 여부: $isYudong", botId)
            }

            if (isYudong) {
                if (config.isYudongPostBlock) {
                    blockReasonPrefix = "유동 게시글 금지"
                    notiType = "yudong"
                    debugDetail = "유동 작성자 감지"
                } else if (config.isYudongImageBlock && postImageAlts.isNotEmpty()) {
                    blockReasonPrefix = "유동 이미지 첨부 금지"
                    notiType = "yudong"
                    debugDetail = "유동 작성자 + 이미지 첨부 감지"
                } else if (
                    config.isYudongVoiceBlock &&
                    (postRawHtml.contains("btn-voice") || postRawHtml.contains("voice/player"))
                ) {
                    blockReasonPrefix = "유동 보이스 첨부 금지"
                    notiType = "yudong"
                    debugDetail = "유동 작성자 + 보이스 첨부 감지"
                }
            } else if (
                config.isKkangFilterMode &&
                (config.isKkangPostBlock || config.isKkangImageBlock || config.isKkangVoiceBlock)
            ) {
                val gallogStats = getGallogStats(
                    userId = postUid,
                    gallogCache = gallogCache,
                    tokenToUse = tokenToUse,
                    cookie = cookie,
                    logTag = "깡계 판별용 gallog 조회 실패",
                    botId = botId,
                    isDebugMode = config.isDebugMode
                )

                val pCount = gallogStats.postCount
                val cCount = gallogStats.commentCount

                if (pCount < config.kkangPostMin || cCount < config.kkangCommentMin) {
                    if (config.isKkangPostBlock) {
                        blockReasonPrefix = "깡계 게시글 금지(글:$pCount/댓:$cCount)"
                        notiType = "kkang"
                        debugDetail = "깡계 기준 미달: 글=$pCount/${config.kkangPostMin}, 댓글=$cCount/${config.kkangCommentMin}"
                    } else if (config.isKkangImageBlock && postImageAlts.isNotEmpty()) {
                        blockReasonPrefix = "깡계 이미지 첨부 금지"
                        notiType = "kkang"
                        debugDetail = "깡계 기준 미달 + 이미지 첨부: 글=$pCount/${config.kkangPostMin}, 댓글=$cCount/${config.kkangCommentMin}"
                    } else if (
                        config.isKkangVoiceBlock &&
                        (postRawHtml.contains("btn-voice") || postRawHtml.contains("voice/player"))
                    ) {
                        blockReasonPrefix = "깡계 보이스 첨부 금지"
                        notiType = "kkang"
                        debugDetail = "깡계 기준 미달 + 보이스 첨부: 글=$pCount/${config.kkangPostMin}, 댓글=$cCount/${config.kkangCommentMin}"
                    }
                }
            }

            if (blockReasonPrefix != null) {
                isBadPost = true
            } else {
                val matchedNormalWord =
                    config.normalWords.firstOrNull { postText.contains(it, ignoreCase = true) }

                val matchedBypassWord =
                    config.bypassWords.firstOrNull { buildBypassRegex(it).containsMatchIn(postText) }

                suspiciousUrlInPost =
                    if (config.isUrlFilterMode) getSuspiciousUrl(postText, config.urlWhitelistList) else null

                spamCodeMatchPost = spamCodeRegex?.find(postText)?.value

                if (config.isDebugMode && botId.isNotEmpty()) {
                    sendLog("[디버그][필터/keyword] 일반금지어: ${matchedNormalWord ?: "없음"} / 우회금지어: ${matchedBypassWord ?: "없음"}", botId)
                    sendLog("[디버그][필터/url] 의심 URL: ${suspiciousUrlInPost ?: "없음"}", botId)
                    sendLog("[디버그][필터/spam] 스팸코드: ${spamCodeMatchPost ?: "없음"}", botId)
                }

                isBadPost =
                    (matchedNormalWord != null) ||
                            (matchedBypassWord != null) ||
                            (suspiciousUrlInPost != null) ||
                            (spamCodeMatchPost != null)

                if (matchedNormalWord != null) {
                    debugDetail = "일반 금지어 감지 ($matchedNormalWord)"
                } else if (matchedBypassWord != null) {
                    debugDetail = "우회 금지어 감지 ($matchedBypassWord)"
                } else if (suspiciousUrlInPost != null) {
                    debugDetail = "허용되지 않은 URL 감지 ($suspiciousUrlInPost)"
                } else if (spamCodeMatchPost != null) {
                    debugDetail = "스팸코드 감지 ($spamCodeMatchPost)"
                }

                if (!isBadPost && config.isImageFilterMode) {
                    for (postAlt in postImageAlts) {
                        for (blackAlt in config.imageAltBlacklist) {
                            if (getAltSimilarity(postAlt, blackAlt) >= config.imageFilterThreshold) {
                                isBadPost = true
                                matchedImageAlt = postAlt
                                debugDetail = "이미지 alt 유사도 차단 (감지='$postAlt', 기준='$blackAlt', 임계치=${config.imageFilterThreshold})"
                                break
                            }
                        }
                        if (isBadPost) break
                    }
                    if (config.isDebugMode && botId.isNotEmpty()) {
                        sendLog("[디버그][필터/image] 이미지 필터: ${if (matchedImageAlt != null) "차단 (alt=$matchedImageAlt)" else "통과"}", botId)
                    }
                }

                if (!isBadPost && config.isVoiceFilterMode) {
                    for (vid in config.voiceBlacklist) {
                        if (postRawHtml.contains(vid)) {
                            isBadPost = true
                            matchedVoiceIdPost = vid
                            debugDetail = "금지 보이스 ID 감지 ($vid)"
                            break
                        }
                    }
                    if (config.isDebugMode && botId.isNotEmpty()) {
                        sendLog("[디버그][필터/voice] 보이스 필터: ${if (matchedVoiceIdPost != null) "차단 (id=$matchedVoiceIdPost)" else "통과"}", botId)
                    }
                }
            }
        }

        return PostAnalysisResult(
            isBadPost = isBadPost,
            isWhitelistedUser = isWhitelistedUser,
            isBlacklistedUserId = isBlacklistedUserId,
            isBlacklistedUserNick = isBlacklistedUserNick,
            suspiciousUrlInPost = suspiciousUrlInPost,
            spamCodeMatchPost = spamCodeMatchPost,
            matchedImageAlt = matchedImageAlt,
            matchedVoiceIdPost = matchedVoiceIdPost,
            blockReasonPrefix = blockReasonPrefix,
            notiType = notiType,
            debugDetail = debugDetail
        )
    }

    private fun analyzeComment(
        config: BotConfig,
        botId: String = "",
        cmtAuthor: String,
        cmtNick: String,
        cmtUid: String,
        commentMemo: String,
        gallogCache: MutableMap<String, Pair<Int, Int>>,
        tokenToUse: String,
        cookie: String
    ): CommentAnalysisResult {
        val userFilter = evaluateUserFilter(config, cmtAuthor, cmtNick)
        val isBlacklistedUserId = userFilter.isBlacklistedUserId
        val isBlacklistedUserNick = userFilter.isBlacklistedUserNick
        val isWhitelistedUser = userFilter.isWhitelistedUser

        if (config.isDebugMode && botId.isNotEmpty()) {
            sendLog("[디버그][필터/유저] 댓글 작성자: $cmtAuthor / 닉: $cmtNick → 블랙(ID): $isBlacklistedUserId, 블랙(닉): $isBlacklistedUserNick, 화이트: $isWhitelistedUser", botId)
        }

        var isBadComment = isBlacklistedUserId || isBlacklistedUserNick
        var suspiciousUrlInComment: String? = null
        var spamCodeMatchComment: String? = null
        var matchedVoiceIdComment: String? = null
        var blockReasonPrefix: String? = null
        var notiType: String? = null
        var debugDetail: String? = null
        if (isBlacklistedUserId) {
            debugDetail = "댓글 작성자 ID/IP 블랙리스트 일치 ($cmtAuthor)"
        } else if (isBlacklistedUserNick) {
            debugDetail = "댓글 작성자 닉네임 블랙리스트 일치 ($cmtNick)"
        }

        val spamCodeRegex = buildSpamCodeRegex(config)

        if (isWhitelistedUser && config.isDebugMode && botId.isNotEmpty()) {
            sendLog("[디버그][필터/화이트] 화이트리스트 댓글 작성자 → 이후 모든 필터 통과", botId)
        }

        if (!isBadComment && !isWhitelistedUser) {
            val isYudongComment = cmtUid.isEmpty()
            if (config.isDebugMode && botId.isNotEmpty()) {
                sendLog("[디버그][필터/유동] 댓글 유동 여부: $isYudongComment", botId)
            }

            if (isYudongComment) {
                if (config.isYudongCommentBlock) {
                    blockReasonPrefix = "유동 댓글 작성 금지"
                    notiType = "yudong"
                    debugDetail = "유동 댓글 작성자 감지"
                } else if (
                    config.isYudongVoiceBlock &&
                    (commentMemo.contains("voice_wrap") || commentMemo.contains("voice/player"))
                ) {
                    blockReasonPrefix = "유동 보이스 첨부 금지"
                    notiType = "yudong"
                    debugDetail = "유동 댓글 작성자 + 보이스 첨부 감지"
                }
            } else if (config.isKkangFilterMode && (config.isKkangCommentBlock || config.isKkangVoiceBlock)) {
                val gallogStats = getGallogStats(
                    userId = cmtUid,
                    gallogCache = gallogCache,
                    tokenToUse = tokenToUse,
                    cookie = cookie,
                    logTag = "댓글 깡계 판별용 gallog 조회 실패",
                    botId = botId,
                    isDebugMode = config.isDebugMode
                )

                val pCount = gallogStats.postCount
                val cCount = gallogStats.commentCount

                if (pCount < config.kkangPostMin || cCount < config.kkangCommentMin) {
                    if (config.isKkangCommentBlock) {
                        blockReasonPrefix = "깡계 댓글 금지(글:$pCount/댓:$cCount)"
                        notiType = "kkang"
                        debugDetail = "깡계 댓글 기준 미달: 글=$pCount/${config.kkangPostMin}, 댓글=$cCount/${config.kkangCommentMin}"
                    } else if (
                        config.isKkangVoiceBlock &&
                        (commentMemo.contains("voice_wrap") || commentMemo.contains("voice/player"))
                    ) {
                        blockReasonPrefix = "깡계 보이스 첨부 금지"
                        notiType = "kkang"
                        debugDetail = "깡계 기준 미달 + 댓글 보이스 첨부: 글=$pCount/${config.kkangPostMin}, 댓글=$cCount/${config.kkangCommentMin}"
                    }
                }
            }

            if (blockReasonPrefix != null) {
                isBadComment = true
            } else {
                val matchedNormalWord =
                    config.normalWords.firstOrNull { commentMemo.contains(it, ignoreCase = true) }

                val matchedBypassWord =
                    config.bypassWords.firstOrNull { buildBypassRegex(it).containsMatchIn(commentMemo) }

                suspiciousUrlInComment =
                    if (config.isUrlFilterMode) getSuspiciousUrl(commentMemo, config.urlWhitelistList) else null

                spamCodeMatchComment = spamCodeRegex?.find(commentMemo)?.value

                if (config.isDebugMode && botId.isNotEmpty()) {
                    sendLog("[디버그][필터/keyword] 댓글 일반금지어: ${matchedNormalWord ?: "없음"} / 우회금지어: ${matchedBypassWord ?: "없음"}", botId)
                    sendLog("[디버그][필터/url] 댓글 의심 URL: ${suspiciousUrlInComment ?: "없음"}", botId)
                    sendLog("[디버그][필터/spam] 댓글 스팸코드: ${spamCodeMatchComment ?: "없음"}", botId)
                }

                isBadComment =
                    (matchedNormalWord != null) ||
                            (matchedBypassWord != null) ||
                            (suspiciousUrlInComment != null) ||
                            (spamCodeMatchComment != null)

                if (matchedNormalWord != null) {
                    debugDetail = "댓글 일반 금지어 감지 ($matchedNormalWord)"
                } else if (matchedBypassWord != null) {
                    debugDetail = "댓글 우회 금지어 감지 ($matchedBypassWord)"
                } else if (suspiciousUrlInComment != null) {
                    debugDetail = "댓글 허용되지 않은 URL 감지 ($suspiciousUrlInComment)"
                } else if (spamCodeMatchComment != null) {
                    debugDetail = "댓글 스팸코드 감지 ($spamCodeMatchComment)"
                }

                if (!isBadComment && config.isVoiceFilterMode) {
                    for (vid in config.voiceBlacklist) {
                        if (commentMemo.contains(vid)) {
                            isBadComment = true
                            matchedVoiceIdComment = vid
                            debugDetail = "댓글 금지 보이스 ID 감지 ($vid)"
                            break
                        }
                    }
                    if (config.isDebugMode && botId.isNotEmpty()) {
                        sendLog("[디버그][필터/voice] 댓글 보이스 필터: ${if (matchedVoiceIdComment != null) "차단 (id=$matchedVoiceIdComment)" else "통과"}", botId)
                    }
                }
            }
        }

        return CommentAnalysisResult(
            isBadComment = isBadComment,
            isWhitelistedUser = isWhitelistedUser,
            isBlacklistedUserId = isBlacklistedUserId,
            isBlacklistedUserNick = isBlacklistedUserNick,
            suspiciousUrlInComment = suspiciousUrlInComment,
            spamCodeMatchComment = spamCodeMatchComment,
            matchedVoiceIdComment = matchedVoiceIdComment,
            blockReasonPrefix = blockReasonPrefix,
            notiType = notiType,
            debugDetail = debugDetail
        )
    }

    private fun handleBadPost(
        config: BotConfig,
        botId: String,
        gallType: String,
        gallId: String,
        postNumStr: String,
        postAuthor: String,
        postNick: String,
        postDisplayAuthor: String,
        postTitle: String,
        postDate: String,
        cookie: String,
        pcPostDetailUrl: String,
        tokenToUse: String,
        blockDuration: String,
        blockReasonText: String,
        delChk: String,
        isBlacklistedUserId: Boolean,
        isBlacklistedUserNick: Boolean,
        blockReasonPrefix: String?,
        notiType: String?,
        matchedVoiceIdPost: String?,
        matchedImageAlt: String?,
        suspiciousUrlInPost: String?,
        spamCodeMatchPost: String?,
        notifyIfEnabled: (String, String, String) -> Unit,
        debugDetail: String?,
        saveSnapshotFn: (() -> String?)? = null,
    ): BlockExecutionResult {
        var dbBlockReason: String? = null
        var dbSnapshotPath: String? = null
        var blockHistorySnapshotPath: String? = null

        val presentation = buildPostBlockPresentation(
            postNumStr = postNumStr,
            postAuthor = postAuthor,
            postNick = postNick,
            postDisplayAuthor = postDisplayAuthor,
            isBlacklistedUserId = isBlacklistedUserId,
            isBlacklistedUserNick = isBlacklistedUserNick,
            blockReasonPrefix = blockReasonPrefix,
            notiType = notiType,
            matchedVoiceIdPost = matchedVoiceIdPost,
            matchedImageAlt = matchedImageAlt,
            suspiciousUrlInPost = suspiciousUrlInPost,
            spamCodeMatchPost = spamCodeMatchPost,
            debugDetail = debugDetail
        )

        dbBlockReason = presentation.detailedBlockReason
        logBlock(botId, presentation.logCategory, presentation.logMessage)
        notifyIfEnabled(
            presentation.notificationType,
            presentation.notificationTitle,
            presentation.notificationMessage
        )

        GlobalBotState.saveBlockHistory(
            gallType = gallType,
            gallId = gallId,
            postNum = postNumStr,
            targetType = "POST",
            targetAuthor = postDisplayAuthor,
            targetContent = postTitle,
            blockReason = dbBlockReason ?: "알 수 없음",
            snapshotPath = null,
            creationDate = postDate
        )

        if (config.isExpertMode && config.isSnapshotBlocked) {
            if (saveSnapshotFn != null && GlobalBotState.tryLockBlockSnapshot(gallType, gallId, postNumStr)) {
                try {
                    val lastId = GlobalBotState.getDb()?.postDao()?.getLastBlockHistoryId()
                    val path = saveSnapshotFn()
                    if (lastId != null && path != null) {
                        GlobalBotState.getDb()?.postDao()?.updateBlockHistorySnapshotPathById(lastId, path)
                        blockHistorySnapshotPath = path
                    }
                } finally {
                    GlobalBotState.unlockBlockSnapshot(gallType, gallId, postNumStr)
                }
            }
        }

        if (config.isDebugMode) {
            sendLog("[디버그][차단요청] 게시글 차단 요청 시작 → 번호: $postNumStr / 사유: ${dbBlockReason ?: blockReasonText}", botId)
        }
        executeBlockRequest(
            cookie = cookie,
            pcPostDetailUrl = pcPostDetailUrl,
            tokenToUse = tokenToUse,
            gallId = gallId,
            targetNo = postNumStr,
            parentPostNo = "",
            blockDuration = blockDuration,
            blockReasonText = blockReasonText,
            delChk = delChk,
            gallType = gallType
        )
        if (config.isDebugMode) {
            sendLog("[디버그][차단요청] 게시글 차단 요청 완료 → 번호: $postNumStr", botId)
        }

        return BlockExecutionResult(
            blockReason = dbBlockReason,
            snapshotPath = dbSnapshotPath
        )
    }

    private data class PageProcessResult(
        val firstPostNumOfThisPage: String,
        val isPageEmpty: Boolean,
        val hiddenSearchPos: String,
        val hasManagerPermission: Boolean = true
    )

    private data class ParsedTargetUrl(
        val gallId: String,
        val gallType: String
    )

    private data class GallogStats(
        val postCount: Int,
        val commentCount: Int
    )

    private data class BlockPresentation(
        val blockReason: String,
        val detailedBlockReason: String,
        val logCategory: String,
        val logMessage: String,
        val notificationType: String,
        val notificationTitle: String,
        val notificationMessage: String
    )

    private fun handleBadComment(
        config: BotConfig,
        botId: String,
        gallType: String,
        gallId: String,
        postNumStr: String,
        commentNo: String,
        cmtDisplayAuthor: String,
        cmtNick: String,
        commentMemo: String,
        commentDate: String,
        cookie: String,
        pcPostDetailUrl: String,
        tokenToUse: String,
        blockDuration: String,
        blockReasonText: String,
        delChk: String,
        isBlacklistedCmtUserId: Boolean,
        isBlacklistedCmtUserNick: Boolean,
        blockReasonPrefixCmt: String?,
        notiTypeCmt: String?,
        matchedVoiceIdComment: String?,
        suspiciousUrlInComment: String?,
        spamCodeMatchComment: String?,
        notifyIfEnabled: (String, String, String) -> Unit,
        debugDetail: String?,
        saveSnapshotFn: (() -> String?)? = null,
    ): BlockExecutionResult {
        var dbBlockReason: String? = null
        var dbSnapshotPath: String? = null
        var blockHistorySnapshotPath: String? = null

        val presentation = buildCommentBlockPresentation(
            cmtDisplayAuthor = cmtDisplayAuthor,
            cmtNick = cmtNick,
            isBlacklistedCmtUserId = isBlacklistedCmtUserId,
            isBlacklistedCmtUserNick = isBlacklistedCmtUserNick,
            blockReasonPrefixCmt = blockReasonPrefixCmt,
            notiTypeCmt = notiTypeCmt,
            matchedVoiceIdComment = matchedVoiceIdComment,
            suspiciousUrlInComment = suspiciousUrlInComment,
            spamCodeMatchComment = spamCodeMatchComment,
            debugDetail = debugDetail
        )

        dbBlockReason = presentation.detailedBlockReason
        logBlock(botId, presentation.logCategory, presentation.logMessage)
        notifyIfEnabled(
            presentation.notificationType,
            presentation.notificationTitle,
            presentation.notificationMessage
        )

        GlobalBotState.saveBlockHistory(
            gallType = gallType,
            gallId = gallId,
            postNum = postNumStr,
            targetType = "COMMENT",
            targetAuthor = cmtDisplayAuthor,
            targetContent = commentMemo,
            blockReason = dbBlockReason ?: "알 수 없음",
            snapshotPath = null,
            creationDate = commentDate
        )

        if (config.isExpertMode && config.isSnapshotBlocked) {
            if (saveSnapshotFn != null && GlobalBotState.tryLockBlockSnapshot(gallType, gallId, postNumStr)) {
                try {
                    val lastId = GlobalBotState.getDb()?.postDao()?.getLastBlockHistoryId()
                    val path = saveSnapshotFn()
                    if (lastId != null && path != null) {
                        GlobalBotState.getDb()?.postDao()?.updateBlockHistorySnapshotPathById(lastId, path)
                        blockHistorySnapshotPath = path
                    }
                } finally {
                    GlobalBotState.unlockBlockSnapshot(gallType, gallId, postNumStr)
                }
            }
        }

        if (config.isDebugMode) {
            sendLog("[디버그][차단요청] 댓글 차단 요청 시작 → 번호: $commentNo (게시글: $postNumStr) / 사유: ${dbBlockReason ?: blockReasonText}", botId)
        }
        executeBlockRequest(
            cookie = cookie,
            pcPostDetailUrl = pcPostDetailUrl,
            tokenToUse = tokenToUse,
            gallId = gallId,
            targetNo = commentNo,
            parentPostNo = postNumStr,
            blockDuration = blockDuration,
            blockReasonText = blockReasonText,
            delChk = delChk,
            gallType = gallType
        )
        if (config.isDebugMode) {
            sendLog("[디버그][차단요청] 댓글 차단 요청 완료 → 번호: $commentNo", botId)
        }

        return BlockExecutionResult(
            blockReason = dbBlockReason,
            snapshotPath = dbSnapshotPath
        )
    }

    private fun executeBlockRequest(
        cookie: String,
        pcPostDetailUrl: String,
        tokenToUse: String,
        gallId: String,
        targetNo: String,
        parentPostNo: String,
        blockDuration: String,
        blockReasonText: String,
        delChk: String,
        gallType: String
    ) {
        val blockUrl = "https://gall.dcinside.com/ajax/minor_manager_board_ajax/update_avoid_list"

        Jsoup.connect(blockUrl)
            .userAgent("Mozilla/5.0")
            .header("Cookie", cookie)
            .header("Referer", pcPostDetailUrl)
            .header("X-Requested-With", "XMLHttpRequest")
            .data("ci_t", tokenToUse)
            .data("id", gallId)
            .data("nos[]", targetNo)
            .data("parent", parentPostNo)
            .data("avoid_hour", blockDuration)
            .data("avoid_reason", "0")
            .data("avoid_reason_txt", blockReasonText)
            .data("del_chk", delChk)
            .data("_GALLTYPE_", gallType)
            .data("avoid_type_chk", "1")
            .ignoreContentType(true)
            .method(org.jsoup.Connection.Method.POST)
            .execute()
    }

    private fun buildPostBlockPresentation(
        postNumStr: String,
        postAuthor: String,
        postNick: String,
        postDisplayAuthor: String,
        isBlacklistedUserId: Boolean,
        isBlacklistedUserNick: Boolean,
        blockReasonPrefix: String?,
        notiType: String?,
        matchedVoiceIdPost: String?,
        matchedImageAlt: String?,
        suspiciousUrlInPost: String?,
        spamCodeMatchPost: String?,
        debugDetail: String?,
    ): BlockPresentation {
        return when {
            isBlacklistedUserId -> BlockPresentation(
                blockReason = "ID 블랙리스트 ($postAuthor)",
                detailedBlockReason = "ID/IP 블랙리스트 일치 ($postAuthor)",
                logCategory = "ID/IP 필터 차단!",
                logMessage = "번호: $postNumStr (작성자: $postDisplayAuthor)",
                notificationType = "user",
                notificationTitle = "유저 차단됨",
                notificationMessage = "ID/IP 블랙리스트에 의해 게시글이 차단되었습니다."
            )

            isBlacklistedUserNick -> BlockPresentation(
                blockReason = "닉네임 블랙리스트 ($postNick)",
                detailedBlockReason = "닉네임 블랙리스트 일치 ($postNick)",
                logCategory = "닉네임 필터 차단!",
                logMessage = "번호: $postNumStr (작성자: $postDisplayAuthor)",
                notificationType = "nickname",
                notificationTitle = "닉네임 차단됨",
                notificationMessage = "닉네임 블랙리스트에 의해 게시글이 차단되었습니다."
            )

            blockReasonPrefix != null -> BlockPresentation(
                blockReason = blockReasonPrefix,
                detailedBlockReason = debugDetail ?: blockReasonPrefix,
                logCategory = "$blockReasonPrefix 차단!",
                logMessage = "번호: $postNumStr",
                notificationType = notiType ?: "keyword",
                notificationTitle = "${notiType ?: "keyword"} 차단됨",
                notificationMessage = "$blockReasonPrefix 사유로 게시글이 차단되었습니다."
            )

            matchedVoiceIdPost != null -> BlockPresentation(
                blockReason = "금지 보플 첨부",
                detailedBlockReason = "금지 보이스 ID 감지 ($matchedVoiceIdPost)",
                logCategory = "보이스 필터 차단!",
                logMessage = "번호: $postNumStr",
                notificationType = "voice",
                notificationTitle = "보이스 차단됨",
                notificationMessage = "금지된 보플이 포함된 게시글이 차단되었습니다."
            )

            matchedImageAlt != null -> BlockPresentation(
                blockReason = "금지 이미지 첨부",
                detailedBlockReason = "금지 이미지 alt 감지 ($matchedImageAlt)",
                logCategory = "이미지 필터 차단!",
                logMessage = "번호: $postNumStr",
                notificationType = "image",
                notificationTitle = "이미지 차단됨",
                notificationMessage = "금지된 이미지가 포함된 게시글이 차단되었습니다."
            )

            suspiciousUrlInPost != null -> BlockPresentation(
                blockReason = "외부 URL 차단",
                detailedBlockReason = "허용되지 않은 URL 감지 ($suspiciousUrlInPost)",
                logCategory = "URL 필터 차단!",
                logMessage = "번호: $postNumStr / URL: $suspiciousUrlInPost",
                notificationType = "url",
                notificationTitle = "URL 차단됨",
                notificationMessage = "허용되지 않은 링크가 포함된 게시글이 차단되었습니다."
            )

            spamCodeMatchPost != null -> BlockPresentation(
                blockReason = "스팸코드 감지",
                detailedBlockReason = "스팸코드 감지값 ($spamCodeMatchPost)",
                logCategory = "스팸코드 필터 차단!",
                logMessage = "번호: $postNumStr",
                notificationType = "spam",
                notificationTitle = "스팸코드 차단됨",
                notificationMessage = "난독화 스팸코드가 감지되어 게시글이 차단되었습니다."
            )

            else -> BlockPresentation(
                blockReason = "금지어 포함",
                detailedBlockReason = debugDetail ?: "금지어 감지",
                logCategory = "금지어 필터 차단!",
                logMessage = "번호: $postNumStr",
                notificationType = "keyword",
                notificationTitle = "금지어 차단됨",
                notificationMessage = "금지어가 포함된 게시글이 차단되었습니다."
            )
        }
    }

    private fun buildCommentBlockPresentation(
        cmtDisplayAuthor: String,
        cmtNick: String,
        isBlacklistedCmtUserId: Boolean,
        isBlacklistedCmtUserNick: Boolean,
        blockReasonPrefixCmt: String?,
        notiTypeCmt: String?,
        matchedVoiceIdComment: String?,
        suspiciousUrlInComment: String?,
        spamCodeMatchComment: String?,
        debugDetail: String?
    ): BlockPresentation {
        return when {
            isBlacklistedCmtUserId -> BlockPresentation(
                blockReason = "악플(ID/IP 차단)",
                detailedBlockReason = "댓글 작성자 ID/IP 블랙리스트 일치 ($cmtDisplayAuthor)",
                logCategory = "ID/IP 필터 댓글 차단",
                logMessage = "작성자: $cmtDisplayAuthor",
                notificationType = "user",
                notificationTitle = "유저 차단됨",
                notificationMessage = "ID/IP 블랙리스트에 의해 댓글이 차단되었습니다."
            )

            isBlacklistedCmtUserNick -> BlockPresentation(
                blockReason = "악플(닉네임 차단)",
                detailedBlockReason = "댓글 작성자 닉네임 블랙리스트 일치 ($cmtNick)",
                logCategory = "닉네임 필터 댓글 차단",
                logMessage = "작성자: $cmtDisplayAuthor",
                notificationType = "nickname",
                notificationTitle = "닉네임 차단됨",
                notificationMessage = "닉네임 블랙리스트에 의해 댓글이 차단되었습니다."
            )

            blockReasonPrefixCmt != null -> BlockPresentation(
                blockReason = "악플($blockReasonPrefixCmt)",
                detailedBlockReason = debugDetail ?: blockReasonPrefixCmt,
                logCategory = "$blockReasonPrefixCmt 차단!",
                logMessage = "작성자: $cmtDisplayAuthor",
                notificationType = notiTypeCmt ?: "keyword",
                notificationTitle = "${notiTypeCmt ?: "keyword"} 차단됨",
                notificationMessage = "$blockReasonPrefixCmt 사유로 댓글이 차단되었습니다."
            )

            matchedVoiceIdComment != null -> BlockPresentation(
                blockReason = "악플(보이스 첨부)",
                detailedBlockReason = "댓글 금지 보이스 ID 감지 ($matchedVoiceIdComment)",
                logCategory = "보이스 필터 댓글 차단!",
                logMessage = "작성자: $cmtDisplayAuthor",
                notificationType = "voice",
                notificationTitle = "보이스 차단됨",
                notificationMessage = "금지된 보플이 포함된 댓글이 차단되었습니다."
            )

            suspiciousUrlInComment != null -> BlockPresentation(
                blockReason = "악플(URL 차단)",
                detailedBlockReason = "댓글 허용되지 않은 URL 감지 ($suspiciousUrlInComment)",
                logCategory = "URL 필터 댓글 차단!",
                logMessage = "작성자: $cmtDisplayAuthor / URL: $suspiciousUrlInComment",
                notificationType = "url",
                notificationTitle = "URL 차단됨",
                notificationMessage = "허용되지 않은 링크가 포함된 댓글이 차단되었습니다."
            )

            spamCodeMatchComment != null -> BlockPresentation(
                blockReason = "악플(스팸코드)",
                detailedBlockReason = "댓글 스팸코드 감지값 ($spamCodeMatchComment)",
                logCategory = "스팸코드 필터 댓글 차단!",
                logMessage = "작성자: $cmtDisplayAuthor",
                notificationType = "spam",
                notificationTitle = "스팸코드 차단됨",
                notificationMessage = "난독화 스팸코드가 포함된 댓글이 차단되었습니다."
            )

            else -> BlockPresentation(
                blockReason = "악플(금지어)",
                detailedBlockReason = debugDetail ?: "댓글 금지어 감지",
                logCategory = "금지어 필터 댓글 차단!",
                logMessage = "작성자: $cmtDisplayAuthor",
                notificationType = "keyword",
                notificationTitle = "금지어 차단됨",
                notificationMessage = "금지어가 포함된 댓글이 차단되었습니다."
            )
        }
    }
}