package com.heyheyon.armbandbot

import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.heyheyon.armbandbot.ui.LocalIsDarkMode
import com.heyheyon.armbandbot.ui.PastelNavy
import com.heyheyon.armbandbot.ui.PastelNavyLight
import com.heyheyon.armbandbot.ui.botColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class)
@Composable
fun DbDashboardScreen(botId: String, onBack: () -> Unit) {
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val masterPref = context.getSharedPreferences("bot_master", Context.MODE_PRIVATE)

    // 🌟 다크모드 색상 팔레트 적용
    val isDarkMode = LocalIsDarkMode.current
    val bgColor = if (isDarkMode) Color(0xFF121212) else Color(0xFFF1F3F5)
    val topBarColor = if (isDarkMode) Color(0xFF1E2329) else Color.White
    val cardBgColor = if (isDarkMode) Color(0xFF1E2329) else Color.White
    val blockCardBgColor = if (isDarkMode) Color(0xFF3E2723) else Color(0xFFFFF0F0)
    val holdCardBgColor = if (isDarkMode) Color(0xFF3A2A12) else Color(0xFFFFF4E0)
    val holdOrange = if (isDarkMode) Color(0xFFFFB74D) else Color(0xFFF57C00)
    val textColor = if (isDarkMode) Color(0xFFE0E0E0) else Color(0xFF2C3E50)
    val subTextColor = if (isDarkMode) Color(0xFFAAAEB3) else Color.Gray
    val dividerColor = if (isDarkMode) Color(0xFF333333) else Color(0xFFEEEEEE)
    val warningRed = if (isDarkMode) Color(0xFFEF5350) else Color(0xFFD32F2F)

    var tabIndex by remember { mutableStateOf(0) }
    var galleries by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedGall by remember { mutableStateOf("ALL") }
    var selectedBlockType by remember { mutableStateOf("ALL") }

    var sortField by remember {
        mutableStateOf(masterPref.getString("db_sort_field", "CHECK") ?: "CHECK")
    }
    var isAscending by remember {
        mutableStateOf(masterPref.getBoolean("db_sort_ascending", false))
    }
    var isSortMenuExpanded by remember { mutableStateOf(false) }

    var searchQuery by remember { mutableStateOf("") }
    val keyboardController = LocalSoftwareKeyboardController.current

    var generalLimit by remember { mutableStateOf(100) }
    var blockLimit by remember { mutableStateOf(100) }
    var holdLimit by remember { mutableStateOf(100) }

    var generalPosts by remember { mutableStateOf<List<CheckedPost>>(emptyList()) }
    var blockPosts by remember { mutableStateOf<List<BlockHistory>>(emptyList()) }
    var holdPosts by remember { mutableStateOf<List<HoldHistory>>(emptyList()) }
    val generalListState = rememberLazyListState()
    val blockListState = rememberLazyListState()
    val holdListState = rememberLazyListState()

    var isGeneralRefreshing by remember { mutableStateOf(false) }
    var isBlockRefreshing by remember { mutableStateOf(false) }
    var isHoldRefreshing by remember { mutableStateOf(false) }

    var snapshotViewerPath by remember { mutableStateOf<String?>(null) }
    var showClearDbConfirm by remember { mutableStateOf(false) }
    var recordedPostCount by remember { mutableStateOf(0) }
    var pendingDeletePost by remember { mutableStateOf<CheckedPost?>(null) }
    var pendingDeleteBlock by remember { mutableStateOf<BlockHistory?>(null) }
    var pendingDeleteHold by remember { mutableStateOf<HoldHistory?>(null) }
    var webViewUrl by remember { mutableStateOf<String?>(null) }
    var openSwipeKey by remember { mutableStateOf<String?>(null) }

    val postDao = GlobalBotState.getDb()?.postDao()

    suspend fun loadGeneralData() {
        val data = withContext(Dispatchers.IO) {
            when {
                sortField == "CHECK" && !isAscending -> postDao?.getPostsCheckDesc(selectedGall, searchQuery, generalLimit, 0)
                sortField == "CHECK" && isAscending -> postDao?.getPostsCheckAsc(selectedGall, searchQuery, generalLimit, 0)
                sortField == "CREATE" && !isAscending -> postDao?.getPostsCreateDesc(selectedGall, searchQuery, generalLimit, 0)
                sortField == "CREATE" && isAscending -> postDao?.getPostsCreateAsc(selectedGall, searchQuery, generalLimit, 0)
                else -> emptyList()
            }
        }
        generalPosts = data ?: emptyList()
        recordedPostCount = withContext(Dispatchers.IO) { postDao?.getPostCount() ?: 0 }
    }

    suspend fun loadBlockData() {
        val data = withContext(Dispatchers.IO) {
            when {
                sortField == "CHECK" && !isAscending -> postDao?.getBlockHistoryCheckDesc(selectedBlockType, searchQuery, blockLimit, 0)
                sortField == "CHECK" && isAscending -> postDao?.getBlockHistoryCheckAsc(selectedBlockType, searchQuery, blockLimit, 0)
                sortField == "CREATE" && !isAscending -> postDao?.getBlockHistoryCreateDesc(selectedBlockType, searchQuery, blockLimit, 0)
                sortField == "CREATE" && isAscending -> postDao?.getBlockHistoryCreateAsc(selectedBlockType, searchQuery, blockLimit, 0)
                else -> emptyList()
            }
        }
        blockPosts = data ?: emptyList()
        recordedPostCount = withContext(Dispatchers.IO) { postDao?.getPostCount() ?: 0 }
    }

    suspend fun loadHoldData() {
        val data = withContext(Dispatchers.IO) {
            when {
                sortField == "CHECK" && !isAscending -> postDao?.getHoldHistoryCheckDesc(selectedBlockType, searchQuery, holdLimit, 0)
                sortField == "CHECK" && isAscending -> postDao?.getHoldHistoryCheckAsc(selectedBlockType, searchQuery, holdLimit, 0)
                sortField == "CREATE" && !isAscending -> postDao?.getHoldHistoryCreateDesc(selectedBlockType, searchQuery, holdLimit, 0)
                sortField == "CREATE" && isAscending -> postDao?.getHoldHistoryCreateAsc(selectedBlockType, searchQuery, holdLimit, 0)
                else -> emptyList()
            }
        }
        holdPosts = data ?: emptyList()
        recordedPostCount = withContext(Dispatchers.IO) { postDao?.getPostCount() ?: 0 }
    }

    LaunchedEffect(botId) {
        withContext(Dispatchers.IO) { galleries = postDao?.getGalleries() ?: emptyList() }
        loadGeneralData(); loadBlockData(); loadHoldData()
    }

    LaunchedEffect(tabIndex, selectedGall, selectedBlockType, sortField, isAscending, searchQuery, generalLimit, blockLimit, holdLimit) {
        when (tabIndex) {
            0 -> loadGeneralData()
            1 -> loadBlockData()
            else -> loadHoldData()
        }
    }

    val generalPullRefreshState = rememberPullRefreshState(
        refreshing = isGeneralRefreshing,
        onRefresh = {
            coroutineScope.launch {
                isGeneralRefreshing = true
                loadGeneralData()
                isGeneralRefreshing = false
            }
        }
    )
    val blockPullRefreshState = rememberPullRefreshState(
        refreshing = isBlockRefreshing,
        onRefresh = {
            coroutineScope.launch {
                isBlockRefreshing = true
                loadBlockData()
                isBlockRefreshing = false
            }
        }
    )
    val holdPullRefreshState = rememberPullRefreshState(
        refreshing = isHoldRefreshing,
        onRefresh = {
            coroutineScope.launch {
                isHoldRefreshing = true
                loadHoldData()
                isHoldRefreshing = false
            }
        }
    )

    if (snapshotViewerPath != null) {
        SnapshotViewerScreen(snapshotPath = snapshotViewerPath!!, onBack = { snapshotViewerPath = null })
        return@DbDashboardScreen
    }

    if (showClearDbConfirm) {
        AlertDialog(
            onDismissRequest = { showClearDbConfirm = false },
            title = { Text("DB 초기화", fontWeight = FontWeight.Bold) },
            text = { Text("공용 DB를 전체 초기화할까요?\n검사 기록과 차단 기록, 저장된 스냅샷이 모두 삭제됩니다.\n(기록된 게시글 수: ${recordedPostCount}개)") },
            confirmButton = {
                TextButton(
                    onClick = {
                        coroutineScope.launch {
                            withContext(Dispatchers.IO) {
                                postDao?.getAllSnapshotPaths()?.forEach { deleteSnapshotFiles(it) }
                                context.cacheDir.listFiles()
                                    ?.filter { it.isDirectory && it.name.startsWith("snapshots_") }
                                    ?.forEach { it.deleteRecursively() }
                                postDao?.clearAllPosts()
                                postDao?.clearAllBlockHistory()
                                postDao?.clearAllHoldHistory()
                            }
                            GlobalBotState.lastCheckedNumbers.clear()
                            generalPosts = emptyList()
                            blockPosts = emptyList()
                            holdPosts = emptyList()
                            galleries = emptyList()
                            recordedPostCount = 0
                            generalLimit = 100
                            blockLimit = 100
                            holdLimit = 100
                            showClearDbConfirm = false
                            Toast.makeText(context, "DB를 초기화했습니다.", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) { Text("초기화", color = warningRed, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDbConfirm = false }) { Text("취소") }
            }
        )
    }

    if (pendingDeletePost != null || pendingDeleteBlock != null || pendingDeleteHold != null) {
        val deleteTitle = when {
            pendingDeletePost != null -> "게시글 기록 삭제"
            pendingDeleteBlock != null -> "차단 기록 삭제"
            else -> "보류 기록 삭제"
        }
        val deleteMessage = pendingDeletePost?.let { "[${it.gallId}] ${it.postNum}번 글의 DB 정보와 스냅샷을 삭제할까요?" }
            ?: pendingDeleteBlock?.let { "[${it.gallId}] ${it.postNum}번 차단 기록과 스냅샷을 삭제할까요?" }
            ?: pendingDeleteHold?.let { "[${it.gallId}] ${it.postNum}번 보류 기록과 스냅샷을 삭제할까요?" }
            ?: "삭제할까요?"
        AlertDialog(
            onDismissRequest = { pendingDeletePost = null; pendingDeleteBlock = null; pendingDeleteHold = null },
            title = { Text(deleteTitle, fontWeight = FontWeight.Bold) },
            text = { Text(deleteMessage) },
            confirmButton = {
                TextButton(onClick = {
                    val targetPost = pendingDeletePost
                    val targetBlock = pendingDeleteBlock
                    val targetHold = pendingDeleteHold
                    coroutineScope.launch {
                        withContext(Dispatchers.IO) {
                            if (targetPost != null) {
                                deleteSnapshotFiles(targetPost.snapshotPath)
                                postDao?.deletePost(targetPost.gallType, targetPost.gallId, targetPost.postNum)
                            } else if (targetBlock != null) {
                                deleteSnapshotFiles(targetBlock.snapshotPath)
                                postDao?.deleteBlockHistoryById(targetBlock.id)
                            } else if (targetHold != null) {
                                deleteSnapshotFiles(targetHold.snapshotPath)
                                postDao?.deleteHoldHistoryById(targetHold.id)
                            }
                        }
                        pendingDeletePost = null
                        pendingDeleteBlock = null
                        pendingDeleteHold = null
                        openSwipeKey = null
                        withContext(Dispatchers.IO) { galleries = postDao?.getGalleries() ?: emptyList() }
                        loadGeneralData()
                        loadBlockData()
                        loadHoldData()
                        Toast.makeText(context, "삭제했습니다.", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("삭제", color = warningRed, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { pendingDeletePost = null; pendingDeleteBlock = null; pendingDeleteHold = null }) { Text("취소") } }
        )
    }

    fun buildDcinsidePostUrl(gallType: String, gallId: String, postNum: String, targetType: String = "POST", targetNo: String = ""): String {
        val basePath = when (gallType) {
            "M" -> "https://gall.dcinside.com/mgallery/board/view/"
            "MI" -> "https://gall.dcinside.com/mini/board/view/"
            else -> "https://gall.dcinside.com/board/view/"
        }
        val commentParam = if (targetType == "COMMENT" && targetNo.isNotBlank()) "&fcno=$targetNo" else ""
        return "${basePath}?id=$gallId&no=$postNum$commentParam"
    }

    BackHandler(enabled = webViewUrl != null) { webViewUrl = null }

    val activeWebViewUrl = webViewUrl
    if (activeWebViewUrl != null) {
        FullScreenPostWebView(
            url = activeWebViewUrl,
            isDarkMode = isDarkMode,
            textColor = textColor,
            topBarColor = topBarColor,
            onBack = { webViewUrl = null }
        )
        return@DbDashboardScreen
    }

    Column(modifier = Modifier.fillMaxSize().background(bgColor)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(topBarColor)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.ArrowBack,
                contentDescription = "뒤로",
                modifier = Modifier
                    .clickable { onBack() }
                    .padding(end = 16.dp),
                tint = PastelNavy
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "DB 대시보드",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = textColor
                )
                Text(
                    "모든 봇이 같은 기록을 공유합니다.",
                    fontSize = 11.sp,
                    color = subTextColor
                )
            }


            Spacer(modifier = Modifier.width(8.dp))
            Surface(
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable { showClearDbConfirm = true },
                color = if (isDarkMode) Color(0xFF3E2723) else Color(0xFFFFEBEE),
                contentColor = warningRed,
                shape = RoundedCornerShape(50)
            ) {
                Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Delete, contentDescription = "DB 초기화", modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("초기화", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        TabRow(selectedTabIndex = tabIndex, containerColor = topBarColor, contentColor = PastelNavy) {
            Tab(
                selected = tabIndex == 0,
                onClick = { tabIndex = 0 },
                text = {
                    Text(
                        "공용 모니터링 기록",
                        fontWeight = FontWeight.Bold,
                        color = if (tabIndex == 0) PastelNavy else subTextColor
                    )
                }
            )
            Tab(selected = tabIndex == 1, onClick = { tabIndex = 1 }, text = { Text("차단 상세 기록", fontWeight = FontWeight.Bold, color = if(tabIndex==1) warningRed else subTextColor) })
            Tab(selected = tabIndex == 2, onClick = { tabIndex = 2 }, text = { Text("보류 기록", fontWeight = FontWeight.Bold, color = if(tabIndex==2) holdOrange else subTextColor) })
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("글 번호, 제목, 작성자, 내용 검색...", fontSize = 14.sp) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = "검색", tint = Color.Gray) },
                trailingIcon = { if (searchQuery.isNotEmpty()) Icon(Icons.Filled.Close, contentDescription = "지우기", modifier = Modifier.clickable { searchQuery = ""; generalLimit=100; blockLimit=100; holdLimit=100 }, tint = Color.Gray) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboardController?.hide() }),
                modifier = Modifier.weight(1f).background(if(isDarkMode) Color(0xFF2C323A) else Color.White, RoundedCornerShape(8.dp)),
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = textColor, unfocusedTextColor = textColor, unfocusedBorderColor = Color.Transparent, focusedBorderColor = PastelNavy)
            )
            Box {
                Row(modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { isSortMenuExpanded = true }.padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (sortField == "CHECK") "검사" else "작성", fontSize = 12.sp, color = PastelNavy, fontWeight = FontWeight.Bold)
                    Icon(if (isAscending) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = PastelNavy, modifier = Modifier.size(20.dp))
                }
                DropdownMenu(expanded = isSortMenuExpanded, onDismissRequest = { isSortMenuExpanded = false }, modifier = Modifier.background(if(isDarkMode) Color(0xFF2C323A) else Color.White)) {
                    DropdownMenuItem(text = { Text("검사/차단일 최신순 (▼)", color = textColor) }, onClick = { sortField = "CHECK"; isAscending = false; masterPref.edit().putString("db_sort_field", sortField).putBoolean("db_sort_ascending", isAscending).apply(); isSortMenuExpanded = false; generalLimit = 100; blockLimit = 100 })
                    DropdownMenuItem(text = { Text("검사/차단일 과거순 (▲)", color = textColor) }, onClick = { sortField = "CHECK"; isAscending = true; masterPref.edit().putString("db_sort_field", sortField).putBoolean("db_sort_ascending", isAscending).apply(); isSortMenuExpanded = false; generalLimit = 100; blockLimit = 100 })
                    Divider(color = dividerColor)
                    DropdownMenuItem(text = { Text("작성일 최신순 (▼)", color = textColor) }, onClick = { sortField = "CREATE"; isAscending = false; masterPref.edit().putString("db_sort_field", sortField).putBoolean("db_sort_ascending", isAscending).apply(); isSortMenuExpanded = false; generalLimit = 100; blockLimit = 100 })
                    DropdownMenuItem(text = { Text("작성일 과거순 (▲)", color = textColor) }, onClick = { sortField = "CREATE"; isAscending = true; masterPref.edit().putString("db_sort_field", sortField).putBoolean("db_sort_ascending", isAscending).apply(); isSortMenuExpanded = false; generalLimit = 100; blockLimit = 100 })
                }
            }
        }

        LazyRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (tabIndex == 0) {
                item { FilterChip(selected = selectedGall == "ALL", onClick = { selectedGall = "ALL"; generalLimit = 100 }, label = { Text("전체 갤러리", color=if(selectedGall == "ALL") Color.White else textColor) }) }
                items(galleries) { gId -> FilterChip(selected = selectedGall == gId, onClick = { selectedGall = gId; generalLimit = 100 }, label = { Text(gId, color=if(selectedGall == gId) Color.White else textColor) }) }
            } else {
                val chipColor = if (tabIndex == 2) holdOrange else warningRed
                item { FilterChip(selected = selectedBlockType == "ALL", onClick = { selectedBlockType = "ALL"; blockLimit = 100; holdLimit = 100 }, label = { Text("전체 내역", color=if(selectedBlockType == "ALL") Color.White else textColor) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = chipColor, selectedLabelColor = Color.White)) }
                item { FilterChip(selected = selectedBlockType == "POST", onClick = { selectedBlockType = "POST"; blockLimit = 100; holdLimit = 100 }, label = { Text(if (tabIndex == 2) "게시글 보류" else "게시글 차단", color=if(selectedBlockType == "POST") Color.White else textColor) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = chipColor, selectedLabelColor = Color.White)) }
                item { FilterChip(selected = selectedBlockType == "COMMENT", onClick = { selectedBlockType = "COMMENT"; blockLimit = 100; holdLimit = 100 }, label = { Text(if (tabIndex == 2) "댓글 보류" else "댓글 차단", color=if(selectedBlockType == "COMMENT") Color.White else textColor) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = chipColor, selectedLabelColor = Color.White)) }
            }
        }

        Box(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            if (tabIndex == 0) {
                Box(modifier = Modifier.fillMaxSize().pullRefresh(generalPullRefreshState)) {
                if (generalPosts.isEmpty()) {
                    Text(
                        "조건에 맞는 공용 기록이 없습니다.",
                        modifier = Modifier.align(Alignment.Center),
                        color = subTextColor
                    )
                }
                else {
                    LazyColumn(state = generalListState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 80.dp)) {
                        items(generalPosts, key = { "post_${it.gallType}_${it.gallId}_${it.postNum}" }) { post ->
                            val checkTimeStr = SimpleDateFormat("yy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(post.checkTime))
                            val rowKey = "post_${post.gallType}_${post.gallId}_${post.postNum}"
                            SwipeDeleteDbRow(
                                rowKey = rowKey,
                                isOpen = openSwipeKey == rowKey,
                                onOpenChange = { isOpen -> openSwipeKey = if (isOpen) rowKey else null },
                                onOpenLinkClick = { webViewUrl = buildDcinsidePostUrl(post.gallType, post.gallId, post.postNum) },
                                onDeleteClick = { pendingDeletePost = post }
                            ) {
                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp)).clickable { if (post.snapshotPath != null) snapshotViewerPath = post.snapshotPath!! else Toast.makeText(context, "스냅샷이 없습니다.", Toast.LENGTH_SHORT).show() },
                                    colors = CardDefaults.cardColors(containerColor = cardBgColor)
                                ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                        Text("[${post.gallId}] 글 번호: ${post.postNum} (${post.gallType})", fontWeight = FontWeight.Bold, color = PastelNavy)
                                        Text("댓글: ${post.commentCount.toString().split('/').firstOrNull()?.trim() ?: post.commentCount.toString()}", fontSize = 12.sp, color = PastelNavy)
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    if (post.title != null) Text("제목: ${post.title}", fontSize = 14.sp, color = textColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    if (post.author != null) Text("작성자: ${post.author}", fontSize = 13.sp, color = subTextColor)

                                    Divider(color = dividerColor, modifier = Modifier.padding(vertical = 6.dp))
                                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                        Text("작성: ${post.creationDate ?: "정보 없음"}", fontSize = 11.sp, color = subTextColor)
                                        Text("검사: $checkTimeStr", fontSize = 11.sp, color = subTextColor)
                                    }
                                }
                                }
                            }
                        }
                        item { Button(onClick = { generalLimit += 100 }, modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp), colors = ButtonDefaults.buttonColors(containerColor = if(isDarkMode) Color(0xFF37474F) else PastelNavyLight, contentColor = if(isDarkMode) Color.White else PastelNavy)) { Text("더 보기 (현재 $generalLimit 개)") } }
                    }
                }
                PullRefreshIndicator(
                    refreshing = isGeneralRefreshing,
                    state = generalPullRefreshState,
                    modifier = Modifier.align(Alignment.TopCenter),
                    contentColor = PastelNavy
                )
                } // end generalPullRefreshBox
            } else if (tabIndex == 1) {
                Box(modifier = Modifier.fillMaxSize().pullRefresh(blockPullRefreshState)) {
                if (blockPosts.isEmpty()) {
                    Text(
                        "조건에 맞는 공용 차단 기록이 없습니다.",
                        modifier = Modifier.align(Alignment.Center),
                        color = subTextColor
                    )
                }
                else {
                    LazyColumn(state = blockListState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 80.dp)) {
                        items(blockPosts, key = { "block_${it.id}" }) { history ->
                            val blockTimeStr = SimpleDateFormat("yy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(history.blockTime))
                            val detailedReason = history.blockReason
                            val rowKey = "block_${history.id}"
                            SwipeDeleteDbRow(
                                rowKey = rowKey,
                                isOpen = openSwipeKey == rowKey,
                                onOpenChange = { isOpen -> openSwipeKey = if (isOpen) rowKey else null },
                                onOpenLinkClick = { webViewUrl = buildDcinsidePostUrl(history.gallType, history.gallId, history.postNum, history.targetType, history.targetNo) },
                                onDeleteClick = { pendingDeleteBlock = history }
                            ) {
                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp)).clickable { if (history.snapshotPath != null) snapshotViewerPath = history.snapshotPath!! else Toast.makeText(context, "차단 스냅샷이 없습니다.", Toast.LENGTH_SHORT).show() },
                                    colors = CardDefaults.cardColors(containerColor = blockCardBgColor)
                                ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(if (history.targetType == "POST") "[게시글]" else "[댓글]", fontWeight = FontWeight.Bold, color = warningRed)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("[${history.gallId}] 원본글: ${history.postNum}", fontSize = 12.sp, color = subTextColor)
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text("작성자: ${history.targetAuthor}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = subTextColor)
                                    Text(history.targetContent, fontSize = 13.sp, color = textColor, modifier = Modifier.padding(vertical = 4.dp).background(if(isDarkMode) Color(0xFF4E342E) else Color.White, RoundedCornerShape(4.dp)).padding(8.dp).fillMaxWidth())
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        detailedReason,
                                        fontSize = 12.sp,
                                        color = warningRed,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Divider(color = if(isDarkMode) Color(0xFF5D4037) else Color(0xFFE5D5D5), modifier = Modifier.padding(vertical = 6.dp))
                                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                        Text("작성: ${history.creationDate ?: "정보 없음"}", fontSize = 11.sp, color = subTextColor)
                                        Text("차단: $blockTimeStr", fontSize = 11.sp, color = subTextColor)
                                    }
                                }
                                }
                            }
                        }
                        item { Button(onClick = { blockLimit += 100 }, modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp), colors = ButtonDefaults.buttonColors(containerColor = if(isDarkMode) Color(0xFF5D4037) else Color(0xFFFFCDD2), contentColor = if(isDarkMode) Color.White else warningRed)) { Text("더 보기 (현재 $blockLimit 개)") } }
                    }
                }
                PullRefreshIndicator(
                    refreshing = isBlockRefreshing,
                    state = blockPullRefreshState,
                    modifier = Modifier.align(Alignment.TopCenter),
                    contentColor = PastelNavy
                )
                } // end blockPullRefreshBox
            } else {
                Box(modifier = Modifier.fillMaxSize().pullRefresh(holdPullRefreshState)) {
                    if (holdPosts.isEmpty()) {
                        Text("조건에 맞는 보류 기록이 없습니다.", modifier = Modifier.align(Alignment.Center), color = subTextColor)
                    } else {
                        LazyColumn(state = holdListState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 80.dp)) {
                            items(holdPosts, key = { "hold_${it.id}" }) { history ->
                                val holdTimeStr = SimpleDateFormat("yy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(history.holdTime))
                                val rowKey = "hold_${history.id}"
                                SwipeDeleteDbRow(rowKey = rowKey, isOpen = openSwipeKey == rowKey, onOpenChange = { isOpen -> openSwipeKey = if (isOpen) rowKey else null }, onOpenLinkClick = { webViewUrl = buildDcinsidePostUrl(history.gallType, history.gallId, history.postNum, history.targetType, history.targetNo) }, onDeleteClick = { pendingDeleteHold = history }) {
                                    Card(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp)).clickable { if (history.snapshotPath != null) snapshotViewerPath = history.snapshotPath!! else Toast.makeText(context, "보류 스냅샷이 없습니다.", Toast.LENGTH_SHORT).show() },
                                        colors = CardDefaults.cardColors(containerColor = holdCardBgColor)
                                    ) {
                                        Column(modifier = Modifier.padding(12.dp)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(if (history.targetType == "POST") "[게시글]" else "[댓글]", fontWeight = FontWeight.Bold, color = holdOrange)
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text("[${history.gallId}] 원본글: ${history.postNum}", fontSize = 12.sp, color = subTextColor)
                                            }
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text("작성자: ${history.targetAuthor}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = subTextColor)
                                            Text(history.targetContent, fontSize = 13.sp, color = textColor, modifier = Modifier.padding(vertical = 4.dp).background(if(isDarkMode) Color(0xFF4A3420) else Color.White, RoundedCornerShape(4.dp)).padding(8.dp).fillMaxWidth())
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(history.holdReason, fontSize = 12.sp, color = holdOrange, fontWeight = FontWeight.Bold)
                                            Divider(color = if(isDarkMode) Color(0xFF6D4C20) else Color(0xFFFFD8A8), modifier = Modifier.padding(vertical = 6.dp))
                                            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                                Text("작성: ${history.creationDate ?: "정보 없음"}", fontSize = 11.sp, color = subTextColor)
                                                Text("보류: $holdTimeStr", fontSize = 11.sp, color = subTextColor)
                                            }
                                        }
                                    }
                                }
                            }
                            item { Button(onClick = { holdLimit += 100 }, modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp), colors = ButtonDefaults.buttonColors(containerColor = if(isDarkMode) Color(0xFF6D4C20) else Color(0xFFFFE0B2), contentColor = if(isDarkMode) Color.White else holdOrange)) { Text("더 보기 (현재 $holdLimit 개)") } }
                        }
                    }
                    PullRefreshIndicator(refreshing = isHoldRefreshing, state = holdPullRefreshState, modifier = Modifier.align(Alignment.TopCenter), contentColor = holdOrange)
                } // end holdPullRefreshBox
            }
        }
    }
}

@Composable
private fun FullScreenPostWebView(
    url: String,
    isDarkMode: Boolean,
    textColor: Color,
    topBarColor: Color,
    onBack: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().background(if (isDarkMode) Color(0xFF121212) else Color.White)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(topBarColor)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.ArrowBack,
                contentDescription = "뒤로",
                modifier = Modifier.clickable { onBack() }.padding(end = 16.dp),
                tint = PastelNavy
            )
            Column(modifier = Modifier.weight(1f)) {
                Text("링크", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = textColor)
                Text(url, fontSize = 11.sp, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    webViewClient = WebViewClient()
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    loadUrl(url)
                }
            },
            update = { webView ->
                if (webView.url != url) webView.loadUrl(url)
            }
        )
    }
}

@Composable
private fun SwipeDeleteDbRow(
    rowKey: String,
    isOpen: Boolean,
    onOpenChange: (Boolean) -> Unit,
    onOpenLinkClick: () -> Unit,
    onDeleteClick: () -> Unit,
    content: @Composable () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val buttonWidth = 76.dp
    val buttonHeight = 58.dp
    val buttonGap = 8.dp
    val maxSwipePx = with(density) { -((buttonWidth * 2) + buttonGap * 3).toPx() }
    val swipeOffset = remember(rowKey) { androidx.compose.animation.core.Animatable(0f) }

    LaunchedEffect(isOpen) {
        if (!isOpen && swipeOffset.value != 0f) swipeOffset.animateTo(0f, androidx.compose.animation.core.tween(240))
        if (isOpen && swipeOffset.value == 0f) swipeOffset.animateTo(maxSwipePx, androidx.compose.animation.core.tween(240))
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = buttonGap, top = 4.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(buttonGap)
        ) {
            Box(
                modifier = Modifier
                    .size(buttonWidth, buttonHeight)
                    .clip(RoundedCornerShape(12.dp))
                    .background(PastelNavy)
                    .clickable { onOpenLinkClick() },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.OpenInNew, contentDescription = "링크", tint = Color.White, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.height(2.dp))
                    Text("링크", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
            Box(
                modifier = Modifier
                    .size(buttonWidth, buttonHeight)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFFD32F2F))
                    .clickable { onDeleteClick() },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Delete, contentDescription = "삭제", tint = Color.White, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.height(2.dp))
                    Text("삭제", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(swipeOffset.value.roundToInt(), 0) }
                .pointerInput(rowKey) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            coroutineScope.launch {
                                if (swipeOffset.value < maxSwipePx / 2) {
                                    swipeOffset.animateTo(maxSwipePx, androidx.compose.animation.core.tween(240))
                                    onOpenChange(true)
                                } else {
                                    swipeOffset.animateTo(0f, androidx.compose.animation.core.tween(240))
                                    onOpenChange(false)
                                }
                            }
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            coroutineScope.launch {
                                swipeOffset.snapTo((swipeOffset.value + dragAmount).coerceIn(maxSwipePx, 0f))
                            }
                        }
                    )
                }
        ) { content() }
    }
}