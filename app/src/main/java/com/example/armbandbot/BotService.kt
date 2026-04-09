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
import org.jsoup.Connection
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

class BotService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private val activeBots = ConcurrentHashMap<String, Job>()
    private val aiBatchQueues = ConcurrentHashMap<String, AiBatchQueue>()
    private val aiBatchResults = ConcurrentHashMap<String, ConcurrentHashMap<String, AiFilterPostDecision>>()
    private var wakeLock: PowerManager.WakeLock? = null
    private val autoLoginCooldownMs = 10 * 60 * 1000L
    private val autoLoginMaxAttempts = 3
    private val dcUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
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
        val notiAi: Boolean,

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

        val isAiFilterMode: Boolean,
        val aiFilterProvider: String,
        val aiFilterEndpoint: String,
        val aiFilterApiKey: String,
        val aiFilterModel: String,
        val aiFilterUserPrompt: String,
        val aiFilterBatchMaxPosts: Int,
        val aiFilterBatchMaxWaitSec: Int,
        val aiFilterBatchMaxWeight: Int,

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
    private enum class PostModerationAction {
        ALLOW,
        REVIEW_ONLY,
        BLOCK_EXECUTE
    }

    private data class PostAnalysisResult(
        val action: PostModerationAction,
        val isWhitelistedUser: Boolean,
        val isBlacklistedUserId: Boolean,
        val isBlacklistedUserNick: Boolean,
        val suspiciousUrlInPost: String? = null,
        val spamCodeMatchPost: String? = null,
        val matchedImageAlt: String? = null,
        val matchedVoiceIdPost: String? = null,
        val aiDecision: AiFilterDecision? = null,
        val aiReviewReason: String? = null,
        val reviewReason: String? = null,
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

    private data class AiPostExecutionPlan(
        val postNo: String,
        val reason: String,
        val category: String,
        val confidence: Int
    )

    private data class AiCommentExecutionPlan(
        val postNo: String,
        val commentNo: String,
        val reason: String,
        val category: String,
        val confidence: Int
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


    private fun markStartupPhase(botId: String, phase: String, detail: String? = null) {
        val pref = getSharedPreferences("bot_prefs_$botId", Context.MODE_PRIVATE)
        pref.edit()
            .putString("last_startup_phase", phase)
            .putString("last_startup_detail", detail ?: "")
            .putLong("last_startup_at", System.currentTimeMillis())
            .apply()
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

    private fun scheduleAutoRestart(botId: String? = null) {
        val result = AutoRestartReceiver.scheduleWatchdog(this)
        if (result.scheduled) {
            val detailSuffix = result.detail?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
            botId?.let {
                runCatching {
                    when (result.mode) {
                        "inexact_fallback" -> sendLog("[복구 예약] exact alarm 권한이 없어 fallback 알람으로 진행합니다.$detailSuffix", it)
                        else -> sendLog("[복구 예약] watchdog 예약 완료 (${result.mode})$detailSuffix", it)
                    }
                }
            }
            Log.d("BotService", "[복구 예약] watchdog 예약 완료 mode=${result.mode} detail=${result.detail}")
        } else {
            botId?.let {
                val detail = result.detail ?: "unknown"
                markStartupPhase(it, "watchdog_schedule_failed", detail)
                runCatching { sendLog("[시작 보호] watchdog 예약 실패 - 복구 예약 없이 계속 진행합니다. ($detail)", it) }
            }
            Log.e("BotService", "[복구 예약] watchdog 예약 실패 detail=${result.detail}")
        }
    }

    private fun cancelAutoRestart() {
        AutoRestartReceiver.cancelWatchdog(this)
        Log.d("BotService", "[복구 예약] watchdog 예약 취소")
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
        try {
            startForeground(1, buildForegroundNotification())
        } catch (e: Exception) {
            val botIdForCrash = intent?.getStringExtra("BOT_ID") ?: "SYSTEM"
            markStartupPhase(botIdForCrash, "startForeground_failed", e.javaClass.simpleName + ": " + (e.message ?: ""))
            runCatching { sendLog("[시작 보호] startForeground 실패: ${e.javaClass.simpleName} / ${e.message ?: "알 수 없는 오류"}", botIdForCrash) }
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent == null) {
            sendLog("[복구 점검] null intent로 서비스가 재시작됨. restoreRunningBots() 호출", "SYSTEM")
            restoreRunningBots(this)
            return START_STICKY
        }

        val botId = intent.getStringExtra("BOT_ID") ?: return START_STICKY
        markStartupPhase(botId, "intent_received", "action=${intent.action}")
        val cookie = intent.getStringExtra("COOKIE") ?: ""
        val action = intent.action
        val botPref = getSharedPreferences("bot_prefs_$botId", Context.MODE_PRIVATE)
        val botName = botPref.getString("bot_name", "이름 없는 봇") ?: "이름 없는 봇"
        runCatching {
            val lastPhase = botPref.getString("last_startup_phase", "") ?: ""
            val lastDetail = botPref.getString("last_startup_detail", "") ?: ""
            if (lastPhase.isNotBlank() && lastPhase != "run_loop_entered") {
                sendLog("[시작 진단] 이전 시작 단계: $lastPhase / $lastDetail", botId)
            }
        }

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
        markStartupPhase(botId, "job_creating")
        scheduleAutoRestart(botId)

        val job = serviceScope.launch {
            try {
                sendLog("[복구 점검] runBotLoop 진입", botId)
                markStartupPhase(botId, "run_loop_entered")
                runBotLoop(botId, botName, cookie, botPref)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                markStartupPhase(botId, "run_loop_crash", e.javaClass.simpleName + ": " + (e.message ?: ""))
                Log.e("BotService", "[$botId] runBotLoop 치명적 오류", e)
                sendLog("[치명적 오류] ${e.javaClass.simpleName} / ${e.message ?: "알 수 없는 오류"}", botId)
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

    private fun parseQueryParams(url: String): Map<String, String> {
        val query = try {
            URI(url).rawQuery
        } catch (_: Exception) {
            null
        } ?: return emptyMap()

        return query.split("&")
            .mapNotNull { part ->
                if (part.isBlank()) return@mapNotNull null
                val pieces = part.split("=", limit = 2)
                val key = URLDecoder.decode(pieces[0], Charsets.UTF_8.name())
                val value = URLDecoder.decode(pieces.getOrElse(1) { "" }, Charsets.UTF_8.name())
                key to value
            }
            .toMap()
    }

    private fun buildUrlWithParams(baseUrl: String, params: Map<String, String>): String {
        val baseWithoutQuery = baseUrl.substringBefore("?")
        val query = params.entries.joinToString("&") { (key, value) ->
            "${java.net.URLEncoder.encode(key, "UTF-8")}=${java.net.URLEncoder.encode(value, "UTF-8")}"
        }
        return if (query.isBlank()) baseWithoutQuery else "$baseWithoutQuery?$query"
    }

    private fun buildSearchPageUrl(cleanBaseUrl: String, searchType: String, keyword: String, page: Int, searchPos: String? = null): String {
        val params = LinkedHashMap(parseQueryParams(cleanBaseUrl))
        params["page"] = page.toString()
        params["s_type"] = searchType
        params["s_keyword"] = keyword
        if (searchPos.isNullOrBlank()) params.remove("search_pos") else params["search_pos"] = searchPos
        return buildUrlWithParams(cleanBaseUrl, params)
    }

    private fun resolveAbsoluteUrl(baseUrl: String, href: String): String? {
        if (href.isBlank() || href.startsWith("javascript:", ignoreCase = true)) return null
        return try {
            URI(baseUrl).resolve(href).toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun normalizeSearchUrlForTraversal(candidateUrl: String, cleanBaseUrl: String, searchType: String, keyword: String): String {
        val params = LinkedHashMap(parseQueryParams(candidateUrl))
        params["page"] = params["page"]?.takeIf { it.isNotBlank() } ?: "1"
        params["s_type"] = searchType
        params["s_keyword"] = keyword
        if (params["search_pos"].isNullOrBlank()) params.remove("search_pos")
        return buildUrlWithParams(cleanBaseUrl, params)
    }

    private fun extractSearchNavigation(document: org.jsoup.nodes.Document, cleanBaseUrl: String, searchType: String, keyword: String): SearchNavigation {
        val currentPageUrl = normalizeSearchUrlForTraversal(document.location(), cleanBaseUrl, searchType, keyword)
        val currentParams = parseQueryParams(currentPageUrl)
        val currentPage = currentParams["page"]?.toIntOrNull() ?: 1
        val currentSearchPos = currentParams["search_pos"].orEmpty()

        val pagingLinks = document.select(".bottom_paging_box a[href]")
            .mapNotNull { anchor ->
                val resolved = resolveAbsoluteUrl(document.location(), anchor.attr("href")) ?: return@mapNotNull null
                val normalized = normalizeSearchUrlForTraversal(resolved, cleanBaseUrl, searchType, keyword)
                val params = parseQueryParams(normalized)
                SearchLinkCandidate(anchor.text().trim(), normalized, params["page"]?.toIntOrNull(), params["search_pos"].orEmpty())
            }
            .distinctBy { it.url }

        val nextPageUrl = pagingLinks
            .filter { it.page != null && it.page > currentPage && it.searchPos == currentSearchPos }
            .minByOrNull { it.page ?: Int.MAX_VALUE }
            ?.url

        val nextSearchChunkUrl = pagingLinks.firstOrNull { it.text.contains("다음 검색") }?.url
            ?: pagingLinks.firstOrNull { it.page == 1 && it.searchPos.isNotBlank() && it.searchPos != currentSearchPos }?.url

        return SearchNavigation(currentPageUrl, currentSearchPos, nextPageUrl, nextSearchChunkUrl)
    }

    private fun isSearchPageUrl(url: String): Boolean {
        if (url.isBlank()) return false
        val lowered = url.lowercase()
        return lowered.contains("s_type=") || lowered.contains("s_keyword=") || lowered.contains("search_pos=")
    }

    private fun isConfirmedLoginPage(document: org.jsoup.nodes.Document, requestedUrl: String? = null): Boolean {
        val location = document.location().lowercase()
        val requested = requestedUrl.orEmpty().lowercase()
        val bodyText = document.body()?.text().orEmpty().replace(Regex("\\s+"), " ")
        val loginFormExists = document.select("form[action*=login], input[name=user_id], input[name=pw], input[type=password]").isNotEmpty()
        val explicitLoginPhrases = listOf(
            "로그인이 필요합니다",
            "로그인 후 이용",
            "회원 로그인",
            "디시인사이드 로그인"
        )
        val explicitLoginText = explicitLoginPhrases.any { bodyText.contains(it, ignoreCase = true) }
        val isLoginUrl = location.contains("/login") || location.contains("msign.dcinside.com/login")
        val requestedSearchPage = isSearchPageUrl(requested)

        return if (requestedSearchPage) {
            isLoginUrl || loginFormExists || explicitLoginText
        } else {
            val loginIndicators = listOf("???", "login", "???", "????", "디시인사이드 로그인")
            isLoginUrl || loginFormExists || explicitLoginText || loginIndicators.count { bodyText.contains(it, ignoreCase = true) } >= 2
        }
    }

    private fun evaluateManagerPermission(document: org.jsoup.nodes.Document, requestedUrl: String? = null): ManagerPermissionStatus {
        val location = document.location().lowercase()
        val requested = requestedUrl.orEmpty().lowercase()
        val bodyText = document.body()?.text().orEmpty()
        val normalizedBodyText = bodyText.replace(Regex("\\s+"), " ")
        val isSearchPage = isSearchPageUrl(location) || isSearchPageUrl(requested)

        val managerSelectors = listOf(
            "a[onclick*=listSearchHead(999)]",
            "a[href*=manager]",
            "a[href*=minor_manager]",
            "button[onclick*=manager]",
            "form[action*=manager]",
            ".btn_admin",
            ".useradmin"
        )
        val managerEvidence = managerSelectors.any { selector ->
            document.select(selector).any { element ->
                val text = element.text().trim()
                text.contains("매니저") ||
                    text.contains("관리") ||
                    element.attr("href").contains("manager", ignoreCase = true) ||
                    element.attr("onclick").contains("manager", ignoreCase = true) ||
                    element.attr("action").contains("manager", ignoreCase = true)
            }
        }
        val ciTokenExists = document.select("input[name=ci_t]").attr("value").isNotBlank()

        if (managerEvidence || ciTokenExists) {
            return ManagerPermissionStatus.CONFIRMED
        }

        val explicitNoPermissionPhrases = listOf(
            "관리자 권한이 없습니다",
            "매니저 권한이 없습니다",
            "권한이 없습니다",
            "접근 권한이 없습니다",
            "잘못된 접근입니다",
            "권한이 없는",
            "매니저만",
            "관리자만"
        )
        if (explicitNoPermissionPhrases.any { normalizedBodyText.contains(it) }) {
            return ManagerPermissionStatus.NO_PERMISSION
        }

        if (isConfirmedLoginPage(document, requestedUrl)) {
            return ManagerPermissionStatus.LOGIN_REQUIRED
        }

        val hasPostRows = document.select(".ub-content").isNotEmpty()
        val hasSearchMarkers = document.select("input[name=search_pos], .bottom_paging_box, .sch_result, .sch_no_result").isNotEmpty() ||
            location.contains("s_keyword=") || location.contains("search_pos=") || requested.contains("s_keyword=") || requested.contains("search_pos=")

        return if (hasPostRows || hasSearchMarkers || isSearchPage) {
            ManagerPermissionStatus.AMBIGUOUS
        } else {
            ManagerPermissionStatus.NO_PERMISSION
        }
    }
    private fun buildSnapshotUrl(gallType: String, gallId: String, postNum: String): String {
        return when (gallType) {
            "M" -> "https://gall.dcinside.com/mgallery/board/view/?id=$gallId&no=$postNum"
            "MI" -> "https://gall.dcinside.com/mini/board/view/?id=$gallId&no=$postNum"
            else -> "https://gall.dcinside.com/board/view/?id=$gallId&no=$postNum"
        }
    }

    private fun isSessionValid(cookie: String): Boolean {
        if (cookie.isBlank()) return false
        return try {
            val sessionCheckDoc = Jsoup.connect("https://m.dcinside.com/")
                .userAgent(dcUserAgent)
                .header("Cookie", cookie)
                .get()

            sessionCheckDoc.text().contains("로그아웃")
        } catch (e: Exception) {
            true
        }
    }

    private fun mergeCookieStrings(vararg cookieSources: String?): String {
        val ordered = LinkedHashMap<String, String>()
        cookieSources.filterNotNull().forEach { source ->
            source.split(';')
                .map { it.trim() }
                .filter { it.contains('=') }
                .forEach { part ->
                    val index = part.indexOf('=')
                    if (index > 0) {
                        val key = part.substring(0, index).trim()
                        val value = part.substring(index + 1).trim()
                        if (key.isNotEmpty() && value.isNotEmpty()) {
                            ordered[key] = value
                        }
                    }
                }
        }
        return ordered.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private fun Map<String, String>.toCookieHeader(): String {
        return entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private fun canAttemptAutoLogin(botPref: android.content.SharedPreferences): Pair<Boolean, String?> {
        val autoLoginEnabled = botPref.getBoolean("auto_login_enabled", false)
        if (!autoLoginEnabled) return false to "자동 로그인이 꺼져 있습니다."

        val loginId = botPref.getString("auto_login_id", "")?.trim().orEmpty()
        val loginPw = botPref.getString("auto_login_pw", "")?.trim().orEmpty()
        if (loginId.isBlank() || loginPw.isBlank()) {
            return false to "저장된 자동 로그인 계정 정보가 없습니다."
        }

        val lastFailureAt = botPref.getLong("auto_login_last_failure_at", 0L)
        val failureCount = botPref.getInt("auto_login_failure_count", 0)
        val now = System.currentTimeMillis()
        if (failureCount >= autoLoginMaxAttempts && now - lastFailureAt < autoLoginCooldownMs) {
            val remainSec = ((autoLoginCooldownMs - (now - lastFailureAt)).coerceAtLeast(0L) / 1000L)
            return false to "자동 로그인 쿨다운 중입니다. ${remainSec}초 후 다시 시도합니다."
        }

        return true to null
    }

    private fun recordAutoLoginFailure(botPref: android.content.SharedPreferences) {
        val now = System.currentTimeMillis()
        val lastFailureAt = botPref.getLong("auto_login_last_failure_at", 0L)
        val nextCount = if (now - lastFailureAt > autoLoginCooldownMs) 1 else botPref.getInt("auto_login_failure_count", 0) + 1
        botPref.edit()
            .putInt("auto_login_failure_count", nextCount)
            .putLong("auto_login_last_failure_at", now)
            .apply()
    }

    private fun resetAutoLoginFailureState(botPref: android.content.SharedPreferences) {
        botPref.edit()
            .putInt("auto_login_failure_count", 0)
            .putLong("auto_login_last_failure_at", 0L)
            .apply()
    }

    private fun performAutoLogin(loginId: String, loginPw: String, botId: String? = null): String? {
        botId?.let { sendLog("[자동 로그인][1/4] 로그인 페이지 요청 시작", it) }
        val loginPageResponse = Jsoup.connect("https://msign.dcinside.com/login")
            .userAgent(dcUserAgent)
            .method(Connection.Method.GET)
            .execute()

        val loginDocument = loginPageResponse.parse()
        val initialConKey = loginDocument.select("input[name=conKey]").attr("value")
        val returnUrl = loginDocument.select("input[name=r_url]").attr("value").ifBlank { "https://m.dcinside.com" }
        val token = loginDocument.select("input[name=_token]").attr("value")
        val csrfToken = loginDocument.select("meta[name=csrf-token]").attr("content")
        val randCode = loginDocument.select("input[name=randCode]").attr("value")
        val captchaCodeName = loginDocument.select("input[name=captchaCode]").attr("name")
        val captchaCodeValue = loginDocument.select("input[name=captchaCode]").attr("value")

        botId?.let { sendLog("[자동 로그인][2/4] login/access 사전 검증 요청", it) }
        val accessResponse = Jsoup.connect("https://msign.dcinside.com/login/access")
            .userAgent(dcUserAgent)
            .referrer("https://msign.dcinside.com/login")
            .header("Origin", "https://msign.dcinside.com")
            .header("X-CSRF-TOKEN", csrfToken)
            .cookies(loginPageResponse.cookies())
            .data("token_verify", "dc_login")
            .data("conKey", initialConKey)
            .data("code", loginId)
            .data("randcode", randCode)
            .apply {
                if (captchaCodeName.isNotBlank()) {
                    data(captchaCodeName, captchaCodeValue)
                }
            }
            .method(Connection.Method.POST)
            .ignoreContentType(true)
            .execute()

        val accessJson = runCatching { org.json.JSONObject(accessResponse.body()) }.getOrNull()
        if (accessJson == null) {
            botId?.let { sendLog("[자동 로그인 실패][2/4] login/access 응답 JSON 파싱 실패", it) }
            return null
        }
        if (accessJson.optInt("result", 0) != 1) {
            val reason = accessJson.optString("reason").ifBlank { accessJson.optString("cause") }.ifBlank { "알 수 없는 사유" }
            botId?.let { sendLog("[자동 로그인 실패][2/4] login/access 거부: $reason", it) }
            return null
        }

        val verifiedConKey = accessJson.optString("Block_key").ifBlank { accessJson.optString("block_key") }.ifBlank { initialConKey }
        botId?.let { sendLog("[자동 로그인][3/4] login/access 통과, 실제 로그인 제출", it) }

        val loginResponse = Jsoup.connect("https://msign.dcinside.com/login")
            .userAgent(dcUserAgent)
            .referrer("https://msign.dcinside.com/login")
            .header("Origin", "https://msign.dcinside.com")
            .header("X-CSRF-TOKEN", csrfToken)
            .cookies(loginPageResponse.cookies() + accessResponse.cookies())
            .data("code", loginId)
            .data("password", loginPw)
            .data("loginCash", "on")
            .data("conKey", verifiedConKey)
            .data("r_url", returnUrl)
            .data("_token", token)
            .method(Connection.Method.POST)
            .followRedirects(true)
            .ignoreContentType(true)
            .execute()

        val mergedCookie = mergeCookieStrings(
            loginPageResponse.cookies().toCookieHeader(),
            accessResponse.cookies().toCookieHeader(),
            loginResponse.cookies().toCookieHeader()
        )

        if (mergedCookie.isBlank()) {
            botId?.let { sendLog("[자동 로그인 실패][4/4] 병합된 쿠키가 비어 있습니다.", it) }
            return null
        }

        val sessionValid = isSessionValid(mergedCookie)
        if (!sessionValid) {
            botId?.let { sendLog("[자동 로그인 실패][4/4] 로그인 제출 후에도 유효 세션 검증에 실패했습니다.", it) }
            return null
        }

        botId?.let { sendLog("[자동 로그인 성공][4/4] 유효 세션 확보 완료", it) }
        return mergedCookie
    }

    private fun persistRecoveredSession(
        botPref: android.content.SharedPreferences,
        cookie: String,
        currentCookie: String = ""
    ): String {
        val mergedCookie = mergeCookieStrings(currentCookie, cookie)
        botPref.edit()
            .putString("saved_cookie", mergedCookie)
            .putBoolean("session_login_required", false)
            .putBoolean("session_webview_fallback_pending", false)
            .apply()
        resetAutoLoginFailureState(botPref)
        return mergedCookie
    }

    private fun notifySessionRecoveryRequired(
        botId: String,
        botPref: android.content.SharedPreferences,
        reason: String,
        requireWebViewFallback: Boolean
    ) {
        botPref.edit()
            .putBoolean("session_login_required", true)
            .putBoolean("session_webview_fallback_pending", requireWebViewFallback)
            .putString("session_recovery_reason", reason)
            .apply()

        val sessionExpiredIntent = Intent("BOT_SESSION_EXPIRED").apply {
            putExtra("BOT_ID", botId)
            putExtra("RECOVERY_REASON", reason)
            putExtra("REQUIRE_WEBVIEW_FALLBACK", requireWebViewFallback)
            setPackage(applicationContext.packageName)
        }
        sendBroadcast(sessionExpiredIntent)
    }

    private fun tryRecoverSession(
        botId: String,
        botPref: android.content.SharedPreferences,
        reason: String,
        currentCookie: String
    ): String? {
        val (canAttempt, blockedReason) = canAttemptAutoLogin(botPref)
        if (!canAttempt) {
            sendLog("[자동 로그인 건너뜀] $blockedReason", botId)
            notifySessionRecoveryRequired(
                botId = botId,
                botPref = botPref,
                reason = blockedReason ?: reason,
                requireWebViewFallback = true
            )
            return null
        }

        val loginId = botPref.getString("auto_login_id", "")?.trim().orEmpty()
        val loginPw = botPref.getString("auto_login_pw", "")?.trim().orEmpty()

        return try {
            sendLog("[자동 로그인] $reason → 재로그인 시도", botId)
            sendLog("[자동 로그인 진단] enabled=${botPref.getBoolean("auto_login_enabled", false)} / hasId=${loginId.isNotBlank()} / hasPw=${loginPw.isNotBlank()} / hasCurrentCookie=${currentCookie.isNotBlank()}", botId)
            val refreshedCookie = performAutoLogin(loginId, loginPw, botId)
            if (refreshedCookie.isNullOrBlank()) {
                recordAutoLoginFailure(botPref)
                sendLog("[자동 로그인 실패] 로그인 응답은 받았지만 유효 세션을 확보하지 못했습니다. WebView 로그인으로 전환합니다.", botId)
                notifySessionRecoveryRequired(
                    botId = botId,
                    botPref = botPref,
                    reason = "$reason / 자동 로그인 실패",
                    requireWebViewFallback = true
                )
                null
            } else {
                val mergedCookie = persistRecoveredSession(
                    botPref = botPref,
                    cookie = refreshedCookie,
                    currentCookie = currentCookie
                )
                sendLog("[자동 로그인 성공] 새 세션을 저장했고 작업을 재개합니다.", botId)
                mergedCookie
            }
        } catch (e: Exception) {
            recordAutoLoginFailure(botPref)
            sendLog("[자동 로그인 오류] ${e.message ?: "알 수 없는 오류"}", botId)
            notifySessionRecoveryRequired(
                botId = botId,
                botPref = botPref,
                reason = "$reason / 자동 로그인 오류",
                requireWebViewFallback = true
            )
            null
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
        var currentCookie = cookie.ifBlank { botPref.getString("saved_cookie", "") ?: "" }
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
                "spam" to config.notiSpam,
                "ai" to config.notiAi
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

            if (config.isDebugMode) {
                sendLog("[세션 진단] runBotLoop 시작 / hasCookie=${currentCookie.isNotBlank()} / cookieLength=${currentCookie.length}", botId)
            }
            if (!isSessionValid(currentCookie)) {
                val recoveredCookie = tryRecoverSession(
                    botId = botId,
                    botPref = botPref,
                    reason = "세션 만료 감지",
                    currentCookie = currentCookie
                )
                if (recoveredCookie != null) {
                    currentCookie = recoveredCookie
                    continue
                }

                sendLog("[세션 진단] 시작 직후 세션 복구 실패. 즉시 종료 대신 WebView 로그인 대기 상태로 전환합니다.", botId)
                botPref.edit()
                    .putBoolean("should_restore_after_restart", false)
                    .putBoolean("is_running", false)
                    .apply()
                sendLog("🚨 로그인 세션이 만료되었고 자동 복구에 실패했습니다. WebView 로그인 대기로 전환합니다.", botId)
                notifySessionRecoveryRequired(
                    botId = botId,
                    botPref = botPref,
                    reason = "세션 만료 감지",
                    requireWebViewFallback = true
                )
                delay(500)
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

                val processOutcome = processTargetUrl(
                    config = config,
                    botId = botId,
                    cookie = currentCookie,
                    rawUrl = rawUrl,
                    gallogCache = gallogCache,
                    blockDuration = blockDuration,
                    blockReason = blockReason,
                    delChk = delChk,
                    notifyIfEnabled = ::notifyIfEnabled
                )

                when (processOutcome) {
                    UrlProcessOutcome.CONTINUE -> Unit
                    UrlProcessOutcome.LOGIN_REQUIRED -> {
                        val recoveredCookie = tryRecoverSession(
                            botId = botId,
                            botPref = botPref,
                            reason = "페이지 접근 중 로그인 필요 판정",
                            currentCookie = currentCookie
                        )
                        if (recoveredCookie != null) {
                            currentCookie = recoveredCookie
                            break
                        }
                        botPref.edit()
                            .putBoolean("should_restore_after_restart", false)
                            .putBoolean("is_running", false)
                            .apply()
                        sendLog("[인증 복구 실패] 로그인 필요 상태가 반복되어 WebView 로그인 대기로 전환합니다.", botId)
                        notifySessionRecoveryRequired(
                            botId = botId,
                            botPref = botPref,
                            reason = "페이지 접근 중 로그인 필요 반복",
                            requireWebViewFallback = true
                        )
                        return
                    }
                    UrlProcessOutcome.NO_PERMISSION -> {
                        botPref.edit()
                            .putBoolean("should_restore_after_restart", false)
                            .putBoolean("is_running", false)
                            .putBoolean("session_login_required", false)
                            .putBoolean("session_webview_fallback_pending", false)
                            .putString("session_recovery_reason", "매니저 권한 없음")
                            .apply()
                        sendLog("[권한 없음] 매니저 권한이 없어 작업을 중단합니다.", botId)
                        return
                    }
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
        searchBaseUrl: String? = null,
        searchKeyword: String? = null,
        gallogCache: MutableMap<String, Pair<Int, Int>>,
        blockDuration: String,
        blockReason: String,
        delChk: String,
        notifyIfEnabled: (String, String, String) -> Unit
    ): PageProcessResult {
        if (config.isDebugMode) {
            sendLog("[디버그][페이지] 처리 URL 접근 시작: $pageUrl", botId)
        }
        val document = Jsoup.connect(pageUrl)
            .userAgent("Mozilla/5.0")
            .header("Cookie", cookie)
            .get()
        val managerPermissionStatus = evaluateManagerPermission(document, pageUrl)
        if (config.isDebugMode) {
            sendLog("[디버그][페이지] 매니저 권한 상태: ${managerPermissionStatus.logLabel}", botId)
        }
        if (managerPermissionStatus == ManagerPermissionStatus.LOGIN_REQUIRED || managerPermissionStatus == ManagerPermissionStatus.NO_PERMISSION) {
            return PageProcessResult("", true, "", managerPermissionStatus = managerPermissionStatus)
        }

        val ciToken = document.select("input[name=ci_t]").attr("value")
        val postRows = document.select(".ub-content")
        if (config.isDebugMode) {
            sendLog("[디버그][페이지] 게시글 행 수: ${postRows.size}", botId)
        }

        var firstPostNumOfThisPage = ""
        var isPageEmpty = true

        for (row in postRows) {
            val titleElement = row.selectFirst(".gall_tit a:not(.reply_numbox)") ?: continue
            val text = titleElement.text()
            val link = titleElement.attr("href")
            if (text.isBlank() || !link.contains("no=")) continue
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
            val rawPostDate = dateElement?.attr("title")?.takeIf { it.isNotBlank() } ?: dateElement?.text() ?: ""
            val postDate = normalizeCreationDate(rawPostDate)
            if (firstPostNumOfThisPage.isEmpty()) firstPostNumOfThisPage = postNumStr
            val replyBox = row.selectFirst(".reply_numbox")
            val currentCommentCount = replyBox?.text()?.split("/")?.firstOrNull()?.replace(Regex("[^0-9]"), "")?.toIntOrNull() ?: 0
            val savedCommentCount = GlobalBotState.getCommentCount(gallType, gallId, postNumStr)
            if (savedCommentCount != -1 && savedCommentCount == currentCommentCount) {
                if (config.isDebugMode) sendLog("[디버그][페이지] 번호: $postNumStr / 댓글 수 변경 없음 (저장: $savedCommentCount, 현재: $currentCommentCount) → 건너뜀", botId)
                continue
            }
            if (config.isDebugMode) sendLog("[디버그][페이지] 번호: $postNumStr / 댓글 수 변경 감지 (저장: $savedCommentCount, 현재: $currentCommentCount) → 재확인 진행", botId)
            try {
                processSinglePost(config, botId, cookie, gallType, gallId, postNumStr, postNumber, text, postUid, postAuthor, postNick, postDisplayAuthor, postDate, currentCommentCount, ciToken, gallogCache, blockDuration, blockReason, delChk, notifyIfEnabled)
            } catch (e: Exception) {
                sendLog("[처리 오류] 번호: $postNumStr", botId)
            }
            delay(randomDelay(config.postMinMs, config.postMaxMs))
        }

        val searchNavigation = if (ciTokenFallbackAllowed && searchBaseUrl != null && searchKeyword != null) {
            extractSearchNavigation(document, searchBaseUrl, config.searchType, searchKeyword)
        } else null

        return PageProcessResult(
            firstPostNumOfThisPage = firstPostNumOfThisPage,
            isPageEmpty = isPageEmpty,
            hiddenSearchPos = searchNavigation?.currentSearchPos.orEmpty(),
            nextPageUrl = searchNavigation?.nextPageUrl,
            nextSearchChunkUrl = searchNavigation?.nextSearchChunkUrl,
            currentPageUrl = searchNavigation?.currentPageUrl
        )
    }

    private fun revalidateSearchLoginRequirement(
        botId: String,
        cookie: String,
        stableListUrl: String
    ): ManagerPermissionStatus {
        return try {
            val verifyUrl = if (stableListUrl.contains("page=")) stableListUrl else "$stableListUrl&page=1"
            val verifyDocument = Jsoup.connect(verifyUrl)
                .userAgent("Mozilla/5.0")
                .header("Cookie", cookie)
                .get()
            val verifyStatus = evaluateManagerPermission(verifyDocument, verifyUrl)
            sendLog("[\uC778\uC99D \uC7AC\uAC80\uC99D] \uAC80\uC0C9 \uD398\uC774\uC9C0 \uB85C\uADF8\uC778 \uD544\uC694 \uD310\uC815 ? \uC77C\uBC18 \uBAA9\uB85D \uD655\uC778 \uACB0\uACFC: ${verifyStatus.logLabel}", botId)
            verifyStatus
        } catch (e: Exception) {
            sendLog("[\uC778\uC99D \uC7AC\uAC80\uC99D] \uC77C\uBC18 \uBAA9\uB85D \uD655\uC778 \uC2E4\uD328: ${e.message ?: "\uC54C \uC218 \uC5C6\uB294 \uC624\uB958"}", botId)
            ManagerPermissionStatus.AMBIGUOUS
        }
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
    ): UrlProcessOutcome {
        val parsedTarget = parseTargetUrl(rawUrl) ?: return UrlProcessOutcome.CONTINUE
        val gallId = parsedTarget.gallId
        val gallType = parsedTarget.gallType

        var cleanBaseUrl = rawUrl.replace(Regex("&page=[0-9]+"), "")
        if (config.isSearchMode) cleanBaseUrl = cleanBaseUrl.replace(SEARCH_PARAM_CLEANER_REGEX, "")

        val activeKeywords = if (config.isSearchMode && config.searchKeywords.isNotEmpty()) config.searchKeywords else listOf("")

        for ((keywordIndex, keyword) in activeKeywords.withIndex()) {
            if (!serviceScope.isActive) break
            val pageMatch = Regex("[?&]page=([0-9]+)").find(rawUrl)
            var currentPage = pageMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
            var currentSearchPos = parseQueryParams(rawUrl)["search_pos"].orEmpty()
            var logicalPageCount = 0
            var previousPageFirstPostNum = ""
            var currentPageUrl = if (config.isSearchMode) buildSearchPageUrl(cleanBaseUrl, config.searchType, keyword, currentPage, currentSearchPos.ifBlank { null }) else "$cleanBaseUrl&page=$currentPage"
            val visitedSearchUrls = mutableSetOf<String>()

            while (logicalPageCount < config.scanPageCount && serviceScope.isActive) {
                val pageUrl = if (config.isSearchMode) normalizeSearchUrlForTraversal(currentPageUrl, cleanBaseUrl, config.searchType, keyword) else currentPageUrl
                if (config.isSearchMode && !visitedSearchUrls.add(pageUrl)) break
                try {
                    val pageResult = processPage(
                        config = config,
                        botId = botId,
                        cookie = cookie,
                        gallType = gallType,
                        gallId = gallId,
                        pageUrl = pageUrl,
                        ciTokenFallbackAllowed = config.isSearchMode,
                        searchBaseUrl = if (config.isSearchMode) cleanBaseUrl else null,
                        searchKeyword = if (config.isSearchMode) keyword else null,
                        gallogCache = gallogCache,
                        blockDuration = blockDuration,
                        blockReason = blockReason,
                        delChk = delChk,
                        notifyIfEnabled = notifyIfEnabled
                    )
                    when (pageResult.managerPermissionStatus) {
                        ManagerPermissionStatus.LOGIN_REQUIRED -> {
                            if (!config.isSearchMode) return UrlProcessOutcome.LOGIN_REQUIRED

                            val stableListStatus = revalidateSearchLoginRequirement(
                                botId = botId,
                                cookie = cookie,
                                stableListUrl = convertToPcUrl(rawUrl)
                            )
                            when (stableListStatus) {
                                ManagerPermissionStatus.LOGIN_REQUIRED -> return UrlProcessOutcome.LOGIN_REQUIRED
                                ManagerPermissionStatus.NO_PERMISSION -> return UrlProcessOutcome.NO_PERMISSION
                                ManagerPermissionStatus.AMBIGUOUS, ManagerPermissionStatus.CONFIRMED -> {
                                    sendLog("[인증 예외] 검색 페이지에서만 로그인 필요로 보여 이번 키워드 스캔은 건너뜁니다.", botId)
                                    break
                                }
                            }
                        }
                        ManagerPermissionStatus.NO_PERMISSION -> return UrlProcessOutcome.NO_PERMISSION
                        ManagerPermissionStatus.AMBIGUOUS, ManagerPermissionStatus.CONFIRMED -> Unit
                    }

                    val shouldAdvanceChunk = config.isSearchMode && (pageResult.isPageEmpty || (logicalPageCount > 0 && pageResult.firstPostNumOfThisPage == previousPageFirstPostNum))
                    if (shouldAdvanceChunk) {
                        val nextChunkUrl = pageResult.nextSearchChunkUrl
                        if (nextChunkUrl.isNullOrBlank()) break
                        currentPageUrl = nextChunkUrl
                        currentPage = parseQueryParams(nextChunkUrl)["page"]?.toIntOrNull() ?: 1
                        currentSearchPos = parseQueryParams(nextChunkUrl)["search_pos"].orEmpty()
                        previousPageFirstPostNum = ""
                    } else {
                        previousPageFirstPostNum = pageResult.firstPostNumOfThisPage
                        if (config.isSearchMode) {
                            val nextPageUrl = pageResult.nextPageUrl ?: pageResult.nextSearchChunkUrl
                            if (nextPageUrl.isNullOrBlank()) break
                            currentPageUrl = nextPageUrl
                            currentPage = parseQueryParams(nextPageUrl)["page"]?.toIntOrNull() ?: (currentPage + 1)
                            currentSearchPos = parseQueryParams(nextPageUrl)["search_pos"].orEmpty()
                        }
                    }
                } catch (e: Exception) {
                    sendLog("[$currentPage 페이지] 처리 실패.", botId)
                }

                logicalPageCount++
                if (!config.isSearchMode) {
                    currentPage++
                    currentPageUrl = "$cleanBaseUrl&page=$currentPage"
                }
                if (logicalPageCount < config.scanPageCount) delay(randomDelay(config.pageMinMs, config.pageMaxMs))
            }

            if (config.isSearchMode && keywordIndex < activeKeywords.size - 1) delay(randomDelay(config.pageMinMs, config.pageMaxMs))
        }
        return UrlProcessOutcome.CONTINUE
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

    private fun captureCommentBlockSnapshot(
        botId: String,
        gallType: String,
        gallId: String,
        postNumStr: String,
        commentNo: String,
        cookie: String,
        comments: List<AiFilterCommentInput>
    ): String? {
        val html = buildSnapshotHtml(
            botId = botId,
            gallType = gallType,
            gallId = gallId,
            postNumStr = postNumStr,
            cookie = cookie,
            debugLabel = "AI 댓글 차단 증거"
        ) ?: return null

        return try {
            val doc = Jsoup.parse(html)
            val commentIdsToKeep = comments.map { it.commentId }.toSet()
            doc.select("li.ub-content, li.reply").forEach { li ->
                val dataNo = li.attr("data-no")
                val id = li.id()
                val extractedCommentNo = when {
                    dataNo.isNotBlank() -> dataNo
                    id.startsWith("comment_li_") -> id.removePrefix("comment_li_")
                    id.startsWith("reply_li_") -> id.removePrefix("reply_li_")
                    else -> ""
                }
                if (extractedCommentNo.isNotBlank() && extractedCommentNo !in commentIdsToKeep) {
                    li.remove()
                }
                if (extractedCommentNo == commentNo) {
                    val currentStyle = li.attr("style")
                    li.attr("style", listOf(currentStyle, "border:2px solid #ff5252", "background:#fff3f3").filter { it.isNotBlank() }.joinToString("; "))
                }
            }

            val saveSnapshotFromDocMethod = this::class.java.getDeclaredMethod(
                "saveSnapshotFromDocCompat",
                BotConfig::class.java,
                String::class.java,
                String::class.java,
                String::class.java,
                org.jsoup.nodes.Document::class.java,
                JSONArray::class.java,
                String::class.java,
                String::class.java
            )
            saveSnapshotFromDocMethod.isAccessible = true

            val commentsJson = JSONArray().apply {
                comments.forEach { cmt ->
                    put(JSONObject().apply {
                        put("no", cmt.commentId)
                        put("name", cmt.nickname)
                        put("memo", cmt.body)
                        put("ip", cmt.authorIdOrIp)
                        put("user_id", "")
                        put("reg_date", "")
                        put("depth", 0)
                        put("vr_player_tag", "")
                    })
                }
            }

            saveSnapshotFromDocMethod.invoke(
                this,
                loadBotConfig(getSharedPreferences("bot_prefs_$botId", MODE_PRIVATE)),
                botId,
                gallId,
                postNumStr,
                doc,
                commentsJson,
                commentNo,
                System.currentTimeMillis().toString()
            ) as? String
        } catch (e: Exception) {
            Log.e("BotService", "[$botId] comment block snapshot save failed", e)
            sendLog("[오류] AI 댓글 차단 스냅샷 저장 실패: ${e.javaClass.simpleName} / ${e.message ?: "원인 불명"}", botId)
            null
        }
    }

    private fun saveSnapshotFromDocCompat(
        config: BotConfig,
        botId: String,
        gallId: String,
        postNumStr: String,
        doc: org.jsoup.nodes.Document,
        comments: JSONArray? = null,
        blockedCommentNo: String? = null,
        blockedTs: String? = null
    ): String? {
        if (!config.isExpertMode) return null

        doc.select(
            "header.header, nav.nav, footer.dcfoot, .adv-group, .adv-groupno, .adv-groupin, .ad-md, .pwlink, .con-search-box, .outside-search-box, .view-btm-con, .reco-search, #singoPopup, #blockLayer, #voice_share, #sns_share, #bottom_listwrap, .section.right_content, .right_content, .stickyunit"
        ).remove()
        doc.head().append("<meta name=\"referrer\" content=\"unsafe-url\">")
        doc.select("script").remove()

        val cacheDir = File(cacheDir, "snapshots_$botId")
        if (!cacheDir.exists()) cacheDir.mkdirs()

        val html = doc.html()
        return if (blockedTs != null) {
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
            latestFile.absolutePath
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
                    .userAgent(dcUserAgent)
                    .header("Cookie", cookie)
                    .get()
            }

            val redirectScript = snapshotDoc.select("script").eachText().joinToString("\n")
            val hasRealContent = snapshotDoc.select(".write_div, .view_content_wrap, .view_content, .cmt_list, #comment_box").isNotEmpty()
            if (!hasRealContent && redirectScript.contains("m.dcinside.com")) {
                sendLog("[오류] $debugLabel 실패: 모바일 리다이렉트 HTML이 반환되어 스냅샷 저장을 중단합니다.", botId)
                return null
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
        val aiPostPlans = mutableListOf<AiPostExecutionPlan>()
        val aiPostPlanNos = mutableSetOf<String>()
        val aiCommentPlans = mutableListOf<AiCommentExecutionPlan>()
        val aiCommentPlanKeys = mutableSetOf<String>()

        val postAnalysis = analyzePost(
            config = config,
            botId = botId,
            postAuthor = postAuthor,
            postNick = postNick,
            postUid = postUid,
            postTitle = text,
            postText = postText,
            postImageAlts = postImageAlts,
            postRawHtml = postRawHtml,
            gallogCache = gallogCache,
            tokenToUse = tokenToUse,
            cookie = cookie
        )
        if (config.isDebugMode) {
            if (postAnalysis.action == PostModerationAction.BLOCK_EXECUTE) {
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
        var aiDecision = postAnalysis.aiDecision
        var aiReviewReason = postAnalysis.aiReviewReason
        var blockReasonPrefix = postAnalysis.blockReasonPrefix
        var notiType = postAnalysis.notiType

        val cachedAiPostDecision = aiBatchResults[botId]?.remove(postNumStr)
        if (cachedAiPostDecision != null) {
            aiDecision = cachedAiPostDecision.decision
            if (config.isDebugMode && botId.isNotEmpty()) {
                sendLog("[AI 결과][게시글] 번호: $postNumStr / decision=${aiDecision?.type} / reason=${aiDecision?.reason ?: "없음"} / category=${aiDecision?.category ?: "other"} / confidence=${aiDecision?.confidence ?: 0}", botId)
                cachedAiPostDecision.commentDecisions.forEach { commentDecision ->
                    sendLog("[AI 결과][댓글] 글번호: $postNumStr / comment=${commentDecision.commentId} / decision=${commentDecision.decision.type} / reason=${commentDecision.decision.reason} / category=${commentDecision.decision.category} / confidence=${commentDecision.decision.confidence}", botId)
                }
            }
            if (aiDecision?.type == AiFilterDecisionType.BLOCK && aiPostPlanNos.add(postNumStr)) {
                aiPostPlans += AiPostExecutionPlan(
                    postNo = postNumStr,
                    reason = aiDecision?.reason ?: "AI 판단 사유 없음",
                    category = aiDecision?.category ?: "other",
                    confidence = aiDecision?.confidence ?: 0
                )
            }
            cachedAiPostDecision.commentDecisions
                .filter { it.decision.type == AiFilterDecisionType.BLOCK }
                .forEach { commentDecision ->
                    val commentKey = "$postNumStr:${commentDecision.commentId}"
                    if (aiCommentPlanKeys.add(commentKey)) {
                        aiCommentPlans += AiCommentExecutionPlan(
                            postNo = postNumStr,
                            commentNo = commentDecision.commentId,
                            reason = commentDecision.decision.reason,
                            category = commentDecision.decision.category,
                            confidence = commentDecision.decision.confidence
                        )
                    }
                }
            if (aiDecision?.type == AiFilterDecisionType.REVIEW) {
                aiReviewReason = aiDecision?.reason
                blockReasonPrefix = "AI 필터 검토 필요"
                notiType = "ai"
            }
        }

        if (config.isAiFilterMode) {
            if (config.isDebugMode && botId.isNotEmpty()) {
                sendLog("[AI 배치] AI 필터 활성 / 글 번호: $postNumStr / 댓글 수: ${commentsArray?.length() ?: 0}", botId)
            }
            runCatching {
                val aiComments = buildList {
                    if (commentsArray != null) {
                        for (i in 0 until commentsArray.length()) {
                            val commentObj = commentsArray.optJSONObject(i) ?: continue
                            val commentNo = commentObj.optString("no", "").trim()
                            if (commentNo.isBlank()) continue
                            val cmtUid = commentObj.optString("user_id", "")
                            val cmtIp = commentObj.optString("ip", "")
                            val cmtNick = commentObj.optString("name", "")
                            val cmtAuthor = cmtUid.ifEmpty { cmtIp }
                            val commentMemo = commentObj.optString("memo", "")
                            add(
                                AiFilterCommentInput(
                                    commentId = commentNo,
                                    authorIdOrIp = cmtAuthor,
                                    nickname = cmtNick,
                                    body = commentMemo,
                                )
                            )
                        }
                    }
                }

                val queue = aiBatchQueues.getOrPut(botId) {
                    AiBatchQueue(
                        maxPosts = config.aiFilterBatchMaxPosts.coerceAtLeast(1),
                        maxWaitMs = config.aiFilterBatchMaxWaitSec.coerceAtLeast(1) * 1000L,
                        maxWeight = config.aiFilterBatchMaxWeight.coerceAtLeast(1000),
                    )
                }

                val queueItem = AiBatchQueueItem(
                    postNo = postNumStr,
                    postInput = AiFilterPostInput(
                        postNo = postNumStr,
                        title = text,
                        authorIdOrIp = postAuthor,
                        nickname = postNick,
                        body = postText,
                        mediaSources = postImageAlts,
                        comments = aiComments,
                    )
                )
                queue.addOrReplace(queueItem)

                if (config.isDebugMode && botId.isNotEmpty()) {
                    sendLog("[AI 배치] 후보 적재 / 글 번호: $postNumStr / 추정 용량: ${queueItem.estimatedWeight}", botId)
                }

                val isOversizeSingle = queueItem.estimatedWeight >= config.aiFilterBatchMaxWeight.coerceAtLeast(1000)
                val shouldFlushNow = queue.shouldFlush()
                if (config.isDebugMode && botId.isNotEmpty()) {
                    sendLog("[AI 배치] flush 판정 / 글 번호: $postNumStr / oversize=$isOversizeSingle / shouldFlush=$shouldFlushNow", botId)
                }

                if (isOversizeSingle || shouldFlushNow) {
                    val flushItems = if (isOversizeSingle) listOf(queueItem) else queue.drainFlushable()
                    if (config.isDebugMode && botId.isNotEmpty()) {
                        sendLog("[AI 배치] 호출 시작 / 묶음 ${flushItems.size}건 / postNos=${flushItems.joinToString(",") { it.postNo }}", botId)
                    }
                    val aiBatchEvaluation = AiFilterClient(
                        config = AiFilterConfig(
                            enabled = true,
                            provider = when {
                                config.aiFilterProvider.equals("gemini_direct", ignoreCase = true) -> AiFilterProvider.GEMINI_DIRECT
                                config.aiFilterProvider.equals("groq", ignoreCase = true) -> AiFilterProvider.GROQ
                                else -> AiFilterProvider.OPENAI_COMPATIBLE
                            },
                            endpoint = config.aiFilterEndpoint,
                            apiKey = config.aiFilterApiKey,
                            model = config.aiFilterModel,
                            userPrompt = config.aiFilterUserPrompt,
                            reviewMode = false,
                        ),
                        logger = { if (botId.isNotEmpty()) sendLog("[AI 배치] $it", botId) }
                    ).evaluateBatch(
                        AiFilterBatchRequest(posts = flushItems.map { it.postInput })
                    )

                    if (aiBatchEvaluation.failureReason != null) {
                        flushItems.forEach { queue.addOrReplace(it) }
                        if (botId.isNotEmpty()) {
                            sendLog("[AI 배치] 검사 실패로 묶음 ${flushItems.size}건 재큐", botId)
                        }
                    }

                    val resultCache = aiBatchResults.getOrPut(botId) { ConcurrentHashMap() }
                    aiBatchEvaluation.postDecisions.forEach { decision ->
                        resultCache[decision.postNo] = decision

                        if (decision.decision.type == AiFilterDecisionType.BLOCK && aiPostPlanNos.add(decision.postNo)) {
                            aiPostPlans += AiPostExecutionPlan(
                                postNo = decision.postNo,
                                reason = decision.decision.reason,
                                category = decision.decision.category,
                                confidence = decision.decision.confidence
                            )
                        }

                        if (decision.postNo == postNumStr) {
                            decision.commentDecisions
                                .filter { it.decision.type == AiFilterDecisionType.BLOCK }
                                .forEach { commentDecision ->
                                    val commentKey = "$postNumStr:${commentDecision.commentId}"
                                    if (aiCommentPlanKeys.add(commentKey)) {
                                        aiCommentPlans += AiCommentExecutionPlan(
                                            postNo = postNumStr,
                                            commentNo = commentDecision.commentId,
                                            reason = commentDecision.decision.reason,
                                            category = commentDecision.decision.category,
                                            confidence = commentDecision.decision.confidence
                                        )
                                    }
                                }
                        }
                    }

                    val immediatePostExecutions = aiBatchEvaluation.postDecisions.filter {
                        it.postNo != postNumStr && it.decision.type == AiFilterDecisionType.BLOCK
                    }
                    immediatePostExecutions.forEach { decision ->
                        val targetInput = flushItems.firstOrNull { it.postNo == decision.postNo }?.postInput
                        if (targetInput == null) {
                            if (config.isDebugMode && botId.isNotEmpty()) {
                                sendLog("[AI 즉시집행] 글 번호 ${decision.postNo} / flush input 누락으로 즉시 집행 생략", botId)
                            }
                            return@forEach
                        }
                        if (config.isDebugMode && botId.isNotEmpty()) {
                            sendLog("[AI 즉시집행] 글 번호 ${decision.postNo} / reason=${decision.decision.reason} / confidence=${decision.decision.confidence}", botId)
                        }
                        runCatching {
                            handleBadPost(
                                config = config,
                                botId = botId,
                                gallType = gallType,
                                gallId = gallId,
                                postNumStr = decision.postNo,
                                postAuthor = targetInput.authorIdOrIp,
                                postNick = targetInput.nickname,
                                postDisplayAuthor = if (targetInput.authorIdOrIp.isNotBlank()) "${targetInput.nickname}(${targetInput.authorIdOrIp})" else targetInput.nickname,
                                postTitle = targetInput.title,
                                postDate = "",
                                cookie = cookie,
                                pcPostDetailUrl = if (gallType == "M") {
                                    "https://gall.dcinside.com/mgallery/board/view/?id=$gallId&no=${decision.postNo}"
                                } else {
                                    "https://gall.dcinside.com/mini/board/view/?id=$gallId&no=${decision.postNo}"
                                },
                                tokenToUse = tokenToUse,
                                blockDuration = blockDuration,
                                blockReasonText = blockReason,
                                delChk = delChk,
                                isBlacklistedUserId = false,
                                isBlacklistedUserNick = false,
                                blockReasonPrefix = "AI 필터 차단",
                                notiType = "ai",
                                matchedVoiceIdPost = null,
                                matchedImageAlt = null,
                                aiDecision = decision.decision,
                                aiReviewReason = null,
                                suspiciousUrlInPost = null,
                                spamCodeMatchPost = null,
                                notifyIfEnabled = notifyIfEnabled,
                                debugDetail = "AI 배치 즉시집행",
                                saveSnapshotFn = {
                                    if (config.isDebugMode && botId.isNotEmpty()) {
                                        sendLog("[AI 즉시집행] 글 번호 ${decision.postNo} / 차단 스냅샷 저장 시도", botId)
                                    }
                                    captureBlockSnapshot(
                                        botId = botId,
                                        gallType = gallType,
                                        gallId = gallId,
                                        postNumStr = decision.postNo,
                                        cookie = cookie
                                    )
                                },
                            )
                            resultCache.remove(decision.postNo)
                        }.onFailure {
                            if (config.isDebugMode && botId.isNotEmpty()) {
                                sendLog("[AI 즉시집행] 글 번호 ${decision.postNo} / 실패: ${it.message ?: "원인 불명"}", botId)
                            }
                        }
                    }

                    val immediateCommentExecutions = aiBatchEvaluation.postDecisions.filter { it.postNo != postNumStr }
                    immediateCommentExecutions.forEach { postDecision ->
                        val targetInput = flushItems.firstOrNull { it.postNo == postDecision.postNo }?.postInput
                        if (targetInput == null) {
                            if (config.isDebugMode && botId.isNotEmpty()) {
                                sendLog("[AI 댓글 즉시집행] 글 번호 ${postDecision.postNo} / flush input 누락으로 생략", botId)
                            }
                            return@forEach
                        }
                        postDecision.commentDecisions
                            .filter { it.decision.type == AiFilterDecisionType.BLOCK }
                            .forEach { commentDecision ->
                                val targetComment = targetInput.comments.firstOrNull { it.commentId == commentDecision.commentId }
                                if (targetComment == null) {
                                    if (config.isDebugMode && botId.isNotEmpty()) {
                                        sendLog("[AI 댓글 즉시집행] 글번호 ${postDecision.postNo} / comment=${commentDecision.commentId} / 원본 댓글 입력 누락으로 생략", botId)
                                    }
                                    return@forEach
                                }
                                if (config.isDebugMode && botId.isNotEmpty()) {
                                    sendLog("[AI 댓글 즉시집행] 글번호 ${postDecision.postNo} / comment=${commentDecision.commentId} / reason=${commentDecision.decision.reason} / confidence=${commentDecision.decision.confidence}", botId)
                                }
                                runCatching {
                                    handleBadComment(
                                        config = config,
                                        botId = botId,
                                        gallType = gallType,
                                        gallId = gallId,
                                        postNumStr = postDecision.postNo,
                                        commentNo = commentDecision.commentId,
                                        cmtDisplayAuthor = if (targetComment.authorIdOrIp.isNotBlank()) "${targetComment.nickname}(${targetComment.authorIdOrIp})" else targetComment.nickname,
                                        cmtNick = targetComment.nickname,
                                        commentMemo = targetComment.body,
                                        commentDate = "",
                                        cookie = cookie,
                                        pcPostDetailUrl = if (gallType == "M") {
                                            "https://gall.dcinside.com/mgallery/board/view/?id=$gallId&no=${postDecision.postNo}"
                                        } else {
                                            "https://gall.dcinside.com/mini/board/view/?id=$gallId&no=${postDecision.postNo}"
                                        },
                                        tokenToUse = tokenToUse,
                                        blockDuration = blockDuration,
                                        blockReasonText = blockReason,
                                        delChk = delChk,
                                        isBlacklistedCmtUserId = false,
                                        isBlacklistedCmtUserNick = false,
                                        blockReasonPrefixCmt = "AI 댓글 차단",
                                        notiTypeCmt = "ai",
                                        matchedVoiceIdComment = null,
                                        suspiciousUrlInComment = null,
                                        spamCodeMatchComment = null,
                                        notifyIfEnabled = notifyIfEnabled,
                                        debugDetail = "AI 댓글 배치 즉시집행",
                                        saveSnapshotFn = {
                                            if (config.isDebugMode && botId.isNotEmpty()) {
                                                sendLog("[AI 댓글 즉시집행] 글번호 ${postDecision.postNo} / comment=${commentDecision.commentId} / 차단 스냅샷 저장 시도", botId)
                                            }
                                            captureCommentBlockSnapshot(
                                                botId = botId,
                                                gallType = gallType,
                                                gallId = gallId,
                                                postNumStr = postDecision.postNo,
                                                commentNo = commentDecision.commentId,
                                                cookie = cookie,
                                                comments = targetInput.comments
                                            )
                                        }
                                    )
                                }.onFailure {
                                    if (config.isDebugMode && botId.isNotEmpty()) {
                                        sendLog("[AI 댓글 즉시집행] 글번호 ${postDecision.postNo} / comment=${commentDecision.commentId} / 실패: ${it.message ?: "원인 불명"}", botId)
                                    }
                                }
                            }
                    }

                    val batchPostDecision = resultCache.remove(postNumStr)
                    if (batchPostDecision != null && config.isDebugMode && botId.isNotEmpty()) {
                        sendLog("[AI 결과][게시글] 번호: $postNumStr / decision=${batchPostDecision.decision.type} / reason=${batchPostDecision.decision.reason} / category=${batchPostDecision.decision.category} / confidence=${batchPostDecision.decision.confidence}", botId)
                        batchPostDecision.commentDecisions.forEach { commentDecision ->
                            sendLog("[AI 결과][댓글] 글번호: $postNumStr / comment=${commentDecision.commentId} / decision=${commentDecision.decision.type} / reason=${commentDecision.decision.reason} / category=${commentDecision.decision.category} / confidence=${commentDecision.decision.confidence}", botId)
                        }
                    }

                    if (config.isDebugMode && botId.isNotEmpty()) {
                        val postBlockCount = aiBatchEvaluation.postDecisions.count { it.decision.type == AiFilterDecisionType.BLOCK }
                        val postReviewCount = aiBatchEvaluation.postDecisions.count { it.decision.type == AiFilterDecisionType.REVIEW }
                        val commentBlockCount = aiBatchEvaluation.postDecisions.sumOf { decision -> decision.commentDecisions.count { it.decision.type == AiFilterDecisionType.BLOCK } }
                        val commentReviewCount = aiBatchEvaluation.postDecisions.sumOf { decision -> decision.commentDecisions.count { it.decision.type == AiFilterDecisionType.REVIEW } }
                        val currentPostAiCommentPlans = aiCommentPlans.filter { it.postNo == postNumStr }
                        sendLog("[AI 배치] 검사 완료 / 묶음 ${flushItems.size}건 / post(block=${postBlockCount}, review=${postReviewCount}) / comment(block=${commentBlockCount}, review=${commentReviewCount}) / 글 AI 차단 후보 ${aiPostPlans.size}건 / 현재 글 댓글 AI 차단 후보 ${currentPostAiCommentPlans.size}건", botId)
                    }
                }
            }.onFailure {
                if (botId.isNotEmpty()) {
                    val msg = it.message ?: "원인 불명"
                    sendLog("[AI 배치] 댓글 검사 실패: $msg", botId)
                    if (msg.contains("HTTP 503") || msg.contains("HTTP 429") || msg.contains("HTTP 500") || msg.contains("HTTP 502") || msg.contains("HTTP 504")) {
                        sendLog("[AI 배치] 일시적 서버 오류로 판단되어 다음 사이클에서 재시도합니다.", botId)
                    }
                }
            }
        }

        var dbBlockReason: String? = null
        var dbSnapshotPath: String? = null
        var isPostBlocked = false

        val aiPostExecutionPlan = aiPostPlans.firstOrNull { it.postNo == postNumStr }
        if (postAnalysis.action != PostModerationAction.BLOCK_EXECUTE) {
            when {
                aiPostExecutionPlan != null -> {
                    isPostBlocked = true
                    blockReasonPrefix = "AI 필터 차단"
                    notiType = "ai"
                    if (aiDecision == null) {
                        aiDecision = AiFilterDecision(
                            type = AiFilterDecisionType.BLOCK,
                            reason = aiPostExecutionPlan.reason,
                            category = aiPostExecutionPlan.category,
                            confidence = aiPostExecutionPlan.confidence,
                            rawJson = ""
                        )
                    }
                }
                aiDecision?.type == AiFilterDecisionType.BLOCK -> {
                    isPostBlocked = true
                    blockReasonPrefix = "AI 필터 차단"
                    notiType = "ai"
                }
                aiDecision?.type == AiFilterDecisionType.REVIEW -> {
                    if (aiReviewReason.isNullOrBlank()) aiReviewReason = aiDecision?.reason
                    if (blockReasonPrefix.isNullOrBlank()) blockReasonPrefix = "AI 필터 검토 필요"
                    if (notiType.isNullOrBlank()) notiType = "ai"
                }
            }
        }

        fun saveSnapshotFromDoc(doc: org.jsoup.nodes.Document, comments: org.json.JSONArray? = null, blockedCommentNo: String? = null, blockedTs: String? = null): String? {
            if (!config.isExpertMode) return null
            sendLog("[디버그] postDoc 스냅샷 저장 시도: $postNumStr", botId)

            // 1. 광고/네비/헤더/푸터/사이드바 등 불필요 요소 제거
            doc.select(
                "header.header, nav.nav, footer.dcfoot, .adv-group, .adv-groupno, .adv-groupin, .ad-md, .pwlink, .con-search-box, .outside-search-box, .view-btm-con, .reco-search, #singoPopup, #blockLayer, #voice_share, #sns_share, #bottom_listwrap, .section.right_content, .right_content, .stickyunit"
            ).remove()
            doc.head().append("<meta name=\"referrer\" content=\"unsafe-url\">")

            // 2. 모든 script 제거 (JS 간섭 방지)
            doc.select("script").remove()

            // 3. 댓글창 + 댓글창 이후 불필요 요소 제거
            // 기준 앵커: #jquery_jplayer (댓글창 바로 앞 요소) 또는 .view_comment
            val commentParent = doc.selectFirst(".view_content_wrap, article.gallview_contents, .gallview_contents")
            val commentAnchor = doc.selectFirst(".view_comment, #focus_cmt")
            val jplayer = doc.getElementById("jquery_jplayer")

            // jplayer 제거
            jplayer?.remove()

            // 댓글창 이후 형제 요소 제거 (글쓰기 버튼 영역 등)
            if (commentAnchor != null) {
                var next = commentAnchor.nextElementSibling()
                while (next != null) {
                    val toRemove = next
                    next = next.nextElementSibling()
                    toRemove.remove()
                }
                commentAnchor.remove()
            } else {
                doc.select(".view_comment, #focus_cmt, .view_bottom_btnbox").remove()
            }

            // 4. commentsArray로 실제 DC 렌더링 구조로 댓글 블록 생성 후 body 끝에 append
            run {
                // 차단된 댓글 강조 + 기타 최소 보정 스타일만 head에 삽입
                doc.head().append("""<style>
.view_comment{display:block!important}
.comment_wrap.show{display:block!important}
.comment_box{display:block!important}
.cmt_list{display:block!important}
.cmt_list li.ub-content{display:block!important}
.reply.show{display:block!important}
.reply_list{display:block!important}
.reply_list li.ub-content{display:block!important}
/* 차단 강조는 스냅샷 뷰어에서만 표시 */
.voice_wrap iframe{max-width:100%}
img.written_dccon{max-width:80px;max-height:80px}
</style>""")

                // 실제 DC 구조로 view_comment 블록 생성
                // 구조: div.view_comment#focus_cmt > div.comment_wrap.show > div.comment_box > ul.cmt_list
                // 상위댓글: ul.cmt_list > li.ub-content > div.cmt_info.clear
                // 답글: ul.cmt_list 다음 li > div.reply.show > div.reply_box > ul.reply_list > li.ub-content > div.reply_info.clear
                val viewCommentDiv = org.jsoup.nodes.Element("div")
                viewCommentDiv.addClass("view_comment")
                viewCommentDiv.attr("id", "focus_cmt")
                viewCommentDiv.attr("style", "display:block")

                val commentWrap = org.jsoup.nodes.Element("div")
                commentWrap.addClass("comment_wrap show")
                commentWrap.attr("style", "display:block")

                val countDiv = org.jsoup.nodes.Element("div")
                countDiv.addClass("comment_count")
                val numBox = org.jsoup.nodes.Element("div")
                numBox.addClass("fl num_box")
                numBox.html("전체 댓글 <em class=\"font_red\">${comments?.length() ?: 0}</em>개")
                countDiv.appendChild(numBox)
                commentWrap.appendChild(countDiv)

                val commentBox = org.jsoup.nodes.Element("div")
                commentBox.addClass("comment_box")
                commentBox.attr("style", "display:block")

                val cmtList = org.jsoup.nodes.Element("ul")
                cmtList.addClass("cmt_list add")
                cmtList.attr("style", "display:block")

                // 헬퍼: 닉네임 span 생성
                fun makeNickSpan(nick: String, uid: String, ip: String): org.jsoup.nodes.Element {
                    val writerSpan = org.jsoup.nodes.Element("span")
                    writerSpan.addClass("gall_writer ub-writer")
                    writerSpan.attr("data-nick", nick)
                    writerSpan.attr("data-uid", uid)
                    writerSpan.attr("data-ip", ip)
                    val nickSpan = org.jsoup.nodes.Element("span")
                    nickSpan.addClass("nickname")
                    val nickEm = org.jsoup.nodes.Element("em")
                    nickEm.text(nick)
                    nickSpan.appendChild(nickEm)
                    if (ip.isNotEmpty()) {
                        val ipSpan = org.jsoup.nodes.Element("span")
                        ipSpan.addClass("ip")
                        ipSpan.text("($ip)")
                        nickSpan.appendChild(ipSpan)
                    } else if (uid.isNotEmpty()) {
                        val ipSpan = org.jsoup.nodes.Element("span")
                        ipSpan.addClass("ip")
                        ipSpan.text("($uid)")
                        nickSpan.appendChild(ipSpan)
                    }
                    writerSpan.appendChild(nickSpan)
                    return writerSpan
                }

                // 헬퍼: 텍스트+디시콘+보이스리플 콘텐츠 div 생성 (cmt_txtbox)
                fun makeTxtBox(memo: String, vrPlayerTag: String, isBlocked: Boolean, isReply: Boolean): org.jsoup.nodes.Element {
                    val txtBox = org.jsoup.nodes.Element("div")
                    txtBox.addClass("clear cmt_txtbox" + if (isReply) " btn_re_reply_write_all" else "")

                    val memoDoc = org.jsoup.Jsoup.parseBodyFragment(memo)
                    val isVoiceReple = memo.contains("voice/player") || vrPlayerTag.contains("voice/player")

                    if (isVoiceReple) {
                        // 보이스리플: voice_wrap > iframe
                        val vrIframes = if (vrPlayerTag.isNotEmpty())
                            org.jsoup.Jsoup.parseBodyFragment(vrPlayerTag).select("iframe[src*=voice]")
                        else
                            memoDoc.select("iframe[src*=voice]")
                        if (vrIframes.isNotEmpty()) {
                            val voiceWrap = org.jsoup.nodes.Element("div")
                            voiceWrap.addClass("voice_wrap")
                            vrIframes.forEach { iframe ->
                                val src = iframe.attr("src").let { if (it.startsWith("//")) "https:$it" else it }
                                val iframeEl = org.jsoup.nodes.Element("iframe")
                                iframeEl.attr("src", src)
                                iframeEl.attr("width", "280px")
                                iframeEl.attr("height", "54px")
                                iframeEl.attr("frameborder", "0")
                                iframeEl.attr("scrolling", "no")
                                voiceWrap.appendChild(iframeEl)
                            }
                            txtBox.appendChild(voiceWrap)
                        }
                    }

                    // 디시콘 처리 (img.written_dccon 또는 video.written_dccon → img로 변환)
                    val dcconEls = memoDoc.select("img.written_dccon, img[src*=dccon.php], video.written_dccon")
                    if (dcconEls.isNotEmpty()) {
                        val mentionEl = memoDoc.select("a.mention").first()
                        if (mentionEl != null) {
                            val mentionA = org.jsoup.nodes.Element("a")
                            mentionA.addClass("mention deco")
                            mentionA.attr("href", "javascript:;")
                            mentionA.text(mentionEl.text())
                            txtBox.appendChild(mentionA)
                        }
                        val dcconWrap = org.jsoup.nodes.Element("div")
                        dcconWrap.addClass("comment_dccon clear")
                        val dcconImgWrap = org.jsoup.nodes.Element("div")
                        dcconImgWrap.addClass("coment_dccon_img")
                        dcconEls.forEach { el ->
                            val rawSrc = el.attr("src").ifEmpty { el.attr("data-src") }
                            if (rawSrc.isNotEmpty()) {
                                val src = if (rawSrc.startsWith("//")) "https:$rawSrc" else rawSrc
                                val img = org.jsoup.nodes.Element("img")
                                img.addClass("written_dccon")
                                img.attr("src", src)
                                val alt = el.attr("conalt").ifEmpty { el.attr("alt") }
                                img.attr("alt", alt)
                                img.attr("title", alt)
                                dcconImgWrap.appendChild(img)
                            }
                        }
                        dcconWrap.appendChild(dcconImgWrap)
                        txtBox.appendChild(dcconWrap)
                    } else if (!isVoiceReple) {
                        // 일반 텍스트
                        val mentionEls = memoDoc.select("a.mention")
                        val mentionTexts = mentionEls.map { el ->
                            val t = el.text().trim()
                            if (t.startsWith("@")) t else "@$t"
                        }
                        mentionEls.remove()
                        memoDoc.select("img, video, iframe").remove()
                        val bodyText = memoDoc.body()?.html()?.trim() ?: ""
                        val pEl = org.jsoup.nodes.Element("p")
                        pEl.addClass("usertxt ub-word")
                        if (mentionTexts.isNotEmpty()) {
                            val mentionA = org.jsoup.nodes.Element("a")
                            mentionA.addClass("mention")
                            mentionA.attr("href", "javascript:;")
                            mentionA.text(mentionTexts.joinToString(" "))
                            pEl.appendChild(mentionA)
                            if (bodyText.isNotEmpty()) pEl.append(" $bodyText")
                        } else {
                            pEl.html(bodyText)
                        }
                        // 차단 표시는 HTML에 넣지 않음 (뷰어에서만 표시)
                        txtBox.appendChild(pEl)
                    }
                    return txtBox
                }

                if (comments != null && comments.length() > 0) {
                    var i = 0
                    while (i < comments.length()) {
                        val cmt = comments.getJSONObject(i)
                        val depth = cmt.optInt("depth", 0)
                        if (depth != 0) { i++; continue } // 상위 댓글만 여기서 처리

                        val nick = cmt.optString("name", "")
                        val uid = cmt.optString("user_id", "")
                        val ip = cmt.optString("ip", "")
                        val date = cmt.optString("reg_date", "")
                        val memo = cmt.optString("memo", "")
                        val vrPlayerTag = cmt.optString("vr_player_tag", "")
                        val no = cmt.optString("no", "")
                        val isBlocked = blockedCommentNo != null && no == blockedCommentNo

                        // li.ub-content > div.cmt_info.clear
                        val li = org.jsoup.nodes.Element("li")
                        li.addClass("ub-content")
                        // 차단 강조 HTML 표시 제거
                        li.attr("id", "comment_li_$no")
                        li.attr("style", "display:block")

                        val cmt_info = org.jsoup.nodes.Element("div")
                        cmt_info.addClass("cmt_info clear")
                        cmt_info.attr("data-no", no)

                        val addbox = org.jsoup.nodes.Element("div")
                        addbox.addClass("addbox")

                        val nickbox = org.jsoup.nodes.Element("div")
                        nickbox.addClass("cmt_nickbox")
                        nickbox.appendChild(makeNickSpan(nick, uid, ip))
                        addbox.appendChild(nickbox)
                        addbox.appendChild(makeTxtBox(memo, vrPlayerTag, isBlocked, false))
                        cmt_info.appendChild(addbox)

                        val frDiv = org.jsoup.nodes.Element("div")
                        frDiv.addClass("fr clear")
                        val dateSpan = org.jsoup.nodes.Element("span")
                        dateSpan.addClass("date_time")
                        dateSpan.text(date)
                        frDiv.appendChild(dateSpan)
                        cmt_info.appendChild(frDiv)
                        li.appendChild(cmt_info)
                        cmtList.appendChild(li)

                        // 이 댓글에 속한 답글 수집 (다음 depth==1 댓글들)
                        val replies = mutableListOf<org.json.JSONObject>()
                        var j = i + 1
                        while (j < comments.length() && comments.getJSONObject(j).optInt("depth", 0) == 1) {
                            replies.add(comments.getJSONObject(j))
                            j++
                        }

                        if (replies.isNotEmpty()) {
                            // li > div.reply.show > div.reply_box > ul.reply_list
                            val replyOuterLi = org.jsoup.nodes.Element("li")
                            replyOuterLi.attr("style", "display:block")
                            val replyDiv = org.jsoup.nodes.Element("div")
                            replyDiv.addClass("reply show")
                            replyDiv.attr("style", "display:block")
                            val replyBox = org.jsoup.nodes.Element("div")
                            replyBox.addClass("reply_box")
                            val replyList = org.jsoup.nodes.Element("ul")
                            replyList.addClass("reply_list")
                            replyList.attr("id", "reply_list_$no")
                            replyList.attr("style", "display:block")

                            replies.forEach { rep ->
                                val rNick = rep.optString("name", "")
                                val rUid = rep.optString("user_id", "")
                                val rIp = rep.optString("ip", "")
                                val rDate = rep.optString("reg_date", "")
                                val rMemo = rep.optString("memo", "")
                                val rVrTag = rep.optString("vr_player_tag", "")
                                val rNo = rep.optString("no", "")
                                val rBlocked = blockedCommentNo != null && rNo == blockedCommentNo

                                val rLi = org.jsoup.nodes.Element("li")
                                rLi.addClass("ub-content")
                                // 차단 강조 HTML 표시 제거
                                rLi.attr("id", "reply_li_$rNo")
                                rLi.attr("style", "display:block")

                                val repInfo = org.jsoup.nodes.Element("div")
                                repInfo.addClass("reply_info clear")
                                repInfo.attr("data-no", rNo)

                                val rAddbox = org.jsoup.nodes.Element("div")
                                rAddbox.addClass("addbox")
                                val rNickbox = org.jsoup.nodes.Element("div")
                                rNickbox.addClass("cmt_nickbox")
                                rNickbox.appendChild(makeNickSpan(rNick, rUid, rIp))
                                rAddbox.appendChild(rNickbox)
                                rAddbox.appendChild(makeTxtBox(rMemo, rVrTag, rBlocked, true))
                                repInfo.appendChild(rAddbox)

                                val rFr = org.jsoup.nodes.Element("div")
                                rFr.addClass("fr clear")
                                val rDate2 = org.jsoup.nodes.Element("span")
                                rDate2.addClass("date_time")
                                rDate2.text(rDate)
                                rFr.appendChild(rDate2)
                                repInfo.appendChild(rFr)
                                rLi.appendChild(repInfo)
                                replyList.appendChild(rLi)
                            }
                            replyBox.appendChild(replyList)
                            replyDiv.appendChild(replyBox)
                            replyOuterLi.appendChild(replyDiv)
                            cmtList.appendChild(replyOuterLi)
                            i = j
                        } else {
                            i++
                        }
                    }
                }

                commentBox.appendChild(cmtList)
                commentWrap.appendChild(commentBox)
                viewCommentDiv.appendChild(commentWrap)

                // 원본 댓글창 위치(view_content_wrap 내부)에 삽입, 없으면 body 끝
                if (commentParent != null) {
                    commentParent.appendChild(viewCommentDiv)
                } else {
                    doc.body()?.appendChild(viewCommentDiv)
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

        if (postAnalysis.action == PostModerationAction.BLOCK_EXECUTE || aiDecision?.type == AiFilterDecisionType.BLOCK) {
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
                aiDecision = aiDecision,
                aiReviewReason = aiReviewReason,
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
        } else if (postAnalysis.action == PostModerationAction.REVIEW_ONLY || aiDecision?.type == AiFilterDecisionType.REVIEW) {
            val reviewReason = aiReviewReason ?: postAnalysis.reviewReason ?: postAnalysis.aiReviewReason ?: "AI 검토 필요"
            sendLog("[AI 검토] 번호: $postNumStr / $reviewReason", botId)
            if (config.isNotiMaster) {
                sendBlockNotification(botId, botName = botId, title = "AI 검토 필요", message = "글 번호 $postNumStr / $reviewReason")
            }
            GlobalBotState.saveBlockHistory(
                gallType = gallType,
                gallId = gallId,
                postNum = postNumStr,
                targetType = "POST_REVIEW",
                targetAuthor = postDisplayAuthor,
                targetContent = text,
                blockReason = reviewReason,
                snapshotPath = null,
                creationDate = postDate
            )
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

                    val aiCommentPlan = aiCommentPlans.firstOrNull { it.postNo == postNumStr && it.commentNo == commentNo }
                    val isBlacklistedCmtUserId = commentAnalysis.isBlacklistedUserId
                    val isBlacklistedCmtUserNick = commentAnalysis.isBlacklistedUserNick
                    val matchedVoiceIdComment = commentAnalysis.matchedVoiceIdComment
                    val suspiciousUrlInComment = commentAnalysis.suspiciousUrlInComment
                    val spamCodeMatchComment = commentAnalysis.spamCodeMatchComment
                    val blockReasonPrefixCmt = aiCommentPlan?.let { "AI 댓글 차단" } ?: commentAnalysis.blockReasonPrefix
                    val notiTypeCmt = aiCommentPlan?.let { "ai" } ?: commentAnalysis.notiType

                    if (commentAnalysis.isBadComment || aiCommentPlan != null) {
                        if (config.isDebugMode && aiCommentPlan != null) {
                            sendLog("[AI 댓글 실행후보] 글번호: $postNumStr / comment=$commentNo / reason=${aiCommentPlan.reason} / category=${aiCommentPlan.category} / confidence=${aiCommentPlan.confidence}", botId)
                        }
                        if (config.isDebugMode) {
                            val detail = aiCommentPlan?.let { "AI BLOCK (${it.category}/${it.confidence}) ${it.reason}" } ?: commentAnalysis.debugDetail
                            if (!detail.isNullOrBlank()) {
                                sendLog(
                                    "[디버그][댓글 차단 상세] 번호: $postNumStr / 작성자: $cmtDisplayAuthor / $detail",
                                    botId
                                )
                            }
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
            notiAi = botPref.getBoolean("noti_ai", true),

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

            isAiFilterMode = botPref.getBoolean("is_ai_filter_mode", false),
            aiFilterProvider = botPref.getString("ai_filter_provider", "openai_compatible")?.trim().orEmpty(),
            aiFilterEndpoint = botPref.getString("ai_filter_endpoint", "https://api.openai.com/v1/chat/completions")?.trim().orEmpty(),
            aiFilterApiKey = botPref.getString("ai_filter_api_key", "")?.trim().orEmpty(),
            aiFilterModel = botPref.getString("ai_filter_model", "gpt-4o-mini")?.trim().orEmpty(),
            aiFilterUserPrompt = botPref.getString("ai_filter_user_prompt", "")?.trim().orEmpty(),
            aiFilterBatchMaxPosts = botPref.getInt("ai_filter_batch_max_posts", 5),
            aiFilterBatchMaxWaitSec = botPref.getInt("ai_filter_batch_max_wait_sec", 5),
            aiFilterBatchMaxWeight = botPref.getInt("ai_filter_batch_max_weight", 20000),

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
        postTitle: String,
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

        var shouldBlockExecute = isBlacklistedUserId || isBlacklistedUserNick
        var shouldReviewOnly = false
        var suspiciousUrlInPost: String? = null
        var spamCodeMatchPost: String? = null
        var matchedImageAlt: String? = null
        var matchedVoiceIdPost: String? = null
        var aiDecision: AiFilterDecision? = null
        var aiReviewReason: String? = null
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

        if (!shouldBlockExecute && !isWhitelistedUser) {
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
                shouldBlockExecute = true
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

                shouldBlockExecute =
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

                if (!shouldBlockExecute && config.isImageFilterMode) {
                    for (postAlt in postImageAlts) {
                        for (blackAlt in config.imageAltBlacklist) {
                            if (getAltSimilarity(postAlt, blackAlt) >= config.imageFilterThreshold) {
                                shouldBlockExecute = true
                                matchedImageAlt = postAlt
                                debugDetail = "이미지 alt 유사도 차단 (감지='$postAlt', 기준='$blackAlt', 임계치=${config.imageFilterThreshold})"
                                break
                            }
                        }
                        if (shouldBlockExecute) break
                    }
                    if (config.isDebugMode && botId.isNotEmpty()) {
                        sendLog("[디버그][필터/image] 이미지 필터: ${if (matchedImageAlt != null) "차단 (alt=$matchedImageAlt)" else "통과"}", botId)
                    }
                }

                if (!shouldBlockExecute && config.isVoiceFilterMode) {
                    for (vid in config.voiceBlacklist) {
                        if (postRawHtml.contains(vid)) {
                            shouldBlockExecute = true
                            matchedVoiceIdPost = vid
                            debugDetail = "금지 보이스 ID 감지 ($vid)"
                            break
                        }
                    }
                    if (config.isDebugMode && botId.isNotEmpty()) {
                        sendLog("[디버그][필터/voice] 보이스 필터: ${if (matchedVoiceIdPost != null) "차단 (id=$matchedVoiceIdPost)" else "통과"}", botId)
                    }
                }

                // AI 2차 판단은 processSinglePost()의 배치 큐 경로에서만 처리한다.
            }
        }

        val action = when {
            shouldBlockExecute -> PostModerationAction.BLOCK_EXECUTE
            shouldReviewOnly -> PostModerationAction.REVIEW_ONLY
            else -> PostModerationAction.ALLOW
        }

        return PostAnalysisResult(
            action = action,
            isWhitelistedUser = isWhitelistedUser,
            isBlacklistedUserId = isBlacklistedUserId,
            isBlacklistedUserNick = isBlacklistedUserNick,
            suspiciousUrlInPost = suspiciousUrlInPost,
            spamCodeMatchPost = spamCodeMatchPost,
            matchedImageAlt = matchedImageAlt,
            matchedVoiceIdPost = matchedVoiceIdPost,
            aiDecision = aiDecision,
            aiReviewReason = aiReviewReason,
            reviewReason = if (action == PostModerationAction.REVIEW_ONLY) (debugDetail ?: aiReviewReason ?: blockReasonPrefix) else null,
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
        aiDecision: AiFilterDecision?,
        aiReviewReason: String?,
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
            aiDecision = aiDecision,
            aiReviewReason = aiReviewReason,
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

    private enum class ManagerPermissionStatus(val logLabel: String) {
        CONFIRMED("\uC788\uC74C"),
        AMBIGUOUS("\uBD88\uBA85\uD655"),
        LOGIN_REQUIRED("\uB85C\uADF8\uC778 \uD544\uC694"),
        NO_PERMISSION("\uC5C6\uC74C")
    }

    private data class PageProcessResult(
        val firstPostNumOfThisPage: String,
        val isPageEmpty: Boolean,
        val hiddenSearchPos: String,
        val nextPageUrl: String? = null,
        val nextSearchChunkUrl: String? = null,
        val currentPageUrl: String? = null,
        val managerPermissionStatus: ManagerPermissionStatus = ManagerPermissionStatus.CONFIRMED
    )

    private data class SearchNavigation(
        val currentPageUrl: String,
        val currentSearchPos: String,
        val nextPageUrl: String?,
        val nextSearchChunkUrl: String?
    )

    private data class SearchLinkCandidate(
        val text: String,
        val url: String,
        val page: Int?,
        val searchPos: String
    )

    private data class ParsedTargetUrl(
        val gallId: String,
        val gallType: String
    )

    private data class GallogStats(
        val postCount: Int,
        val commentCount: Int
    )

    private enum class UrlProcessOutcome {
        CONTINUE,
        LOGIN_REQUIRED,
        NO_PERMISSION
    }

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
        aiDecision: AiFilterDecision?,
        aiReviewReason: String?,
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

            aiDecision != null || aiReviewReason != null -> BlockPresentation(
                blockReason = if (aiDecision?.type == AiFilterDecisionType.BLOCK) "AI 필터 차단" else "AI 필터 검토 필요",
                detailedBlockReason = debugDetail ?: aiReviewReason ?: aiDecision?.reason ?: "AI 필터 검토 필요",
                logCategory = if (aiDecision?.type == AiFilterDecisionType.BLOCK) "AI 배치 차단!" else "AI 필터 REVIEW!",
                logMessage = "번호: $postNumStr",
                notificationType = "ai",
                notificationTitle = if (aiDecision?.type == AiFilterDecisionType.BLOCK) "AI 차단됨" else "AI 검토 필요",
                notificationMessage = (aiReviewReason ?: aiDecision?.reason ?: "AI 필터가 검토 대상으로 분류했습니다.")
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

            notiTypeCmt == "ai" -> BlockPresentation(
                blockReason = "AI 댓글 차단",
                detailedBlockReason = debugDetail ?: blockReasonPrefixCmt ?: "AI 댓글 차단",
                logCategory = "AI 댓글 차단!",
                logMessage = "작성자: $cmtDisplayAuthor",
                notificationType = "ai",
                notificationTitle = "AI 댓글 차단됨",
                notificationMessage = debugDetail ?: "AI 필터 사유로 댓글이 차단되었습니다."
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
