package com.heyheyon.armbandbot

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.heyheyon.armbandbot.ui.LocalIsDarkMode
import com.heyheyon.armbandbot.ui.PastelNavy
import com.heyheyon.armbandbot.ui.PastelNavyLight
import com.heyheyon.armbandbot.ui.botColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
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

    var generalPosts by remember { mutableStateOf<List<CheckedPost>>(emptyList()) }
    var blockPosts by remember { mutableStateOf<List<BlockHistory>>(emptyList()) }
    val generalListState = rememberLazyListState()
    val blockListState = rememberLazyListState()

    var htmlSnapshotPathToView by remember { mutableStateOf<String?>(null) }

    val postDao = GlobalBotState.getDb()?.postDao()

    fun loadGeneralData() {
        coroutineScope.launch(Dispatchers.IO) {
            val data = when {
                sortField == "CHECK" && !isAscending -> postDao?.getPostsCheckDesc(selectedGall, searchQuery, generalLimit, 0)
                sortField == "CHECK" && isAscending -> postDao?.getPostsCheckAsc(selectedGall, searchQuery, generalLimit, 0)
                sortField == "CREATE" && !isAscending -> postDao?.getPostsCreateDesc(selectedGall, searchQuery, generalLimit, 0)
                sortField == "CREATE" && isAscending -> postDao?.getPostsCreateAsc(selectedGall, searchQuery, generalLimit, 0)
                else -> emptyList()
            }
            generalPosts = data ?: emptyList()
        }
    }

    fun loadBlockData() {
        coroutineScope.launch(Dispatchers.IO) {
            val data = when {
                sortField == "CHECK" && !isAscending -> postDao?.getBlockHistoryCheckDesc(selectedBlockType, searchQuery, blockLimit, 0)
                sortField == "CHECK" && isAscending -> postDao?.getBlockHistoryCheckAsc(selectedBlockType, searchQuery, blockLimit, 0)
                sortField == "CREATE" && !isAscending -> postDao?.getBlockHistoryCreateDesc(selectedBlockType, searchQuery, blockLimit, 0)
                sortField == "CREATE" && isAscending -> postDao?.getBlockHistoryCreateAsc(selectedBlockType, searchQuery, blockLimit, 0)
                else -> emptyList()
            }
            blockPosts = data ?: emptyList()
        }
    }

    LaunchedEffect(botId) {
        withContext(Dispatchers.IO) { galleries = postDao?.getGalleries() ?: emptyList() }
        loadGeneralData(); loadBlockData()
    }

    LaunchedEffect(tabIndex, selectedGall, selectedBlockType, sortField, isAscending, searchQuery, generalLimit, blockLimit) {
        if (tabIndex == 0) loadGeneralData() else loadBlockData()
    }

    if (htmlSnapshotPathToView != null) {
        var isShowingInitial by remember { mutableStateOf(false) }
        val currentPath = remember(htmlSnapshotPathToView, isShowingInitial) {
            if (isShowingInitial && htmlSnapshotPathToView!!.endsWith("_latest.html")) htmlSnapshotPathToView!!.replace("_latest.html", "_initial.html") else htmlSnapshotPathToView!!
        }
        val initialFileExists = remember(htmlSnapshotPathToView) {
            if (htmlSnapshotPathToView!!.endsWith("_latest.html")) File(htmlSnapshotPathToView!!.replace("_latest.html", "_initial.html")).exists() else false
        }

        Dialog(onDismissRequest = { htmlSnapshotPathToView = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(modifier = Modifier.fillMaxSize(), color = if (isDarkMode) Color.Black else Color.White) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(modifier = Modifier.fillMaxWidth().background(Color(0xFF1E2329)).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Close, contentDescription = "닫기", modifier = Modifier.clickable { htmlSnapshotPathToView = null }.padding(end = 16.dp), tint = Color.White)
                        Text(text = if (isShowingInitial) "최초 스냅샷 확인" else "최신 스냅샷 뷰어", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 18.sp, modifier = Modifier.weight(1f))
                        if (initialFileExists) {
                            Button(
                                onClick = { isShowingInitial = !isShowingInitial },
                                colors = ButtonDefaults.buttonColors(containerColor = if (isShowingInitial) Color(0xFFD32F2F) else Color(0xFF7F8C8D)),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp), modifier = Modifier.height(32.dp)
                            ) { Text(text = if (isShowingInitial) "최신 스냅샷" else "최초 스냅샷", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                        }
                    }
                    AndroidView(
                        factory = { ctx ->
                            android.webkit.WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.useWideViewPort = true
                                settings.loadWithOverviewMode = true
                                settings.setSupportZoom(true)
                                settings.builtInZoomControls = true
                                settings.displayZoomControls = false
                                settings.mediaPlaybackRequiresUserGesture = false
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                                    settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                    android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                }
                                webChromeClient = android.webkit.WebChromeClient()
                                webViewClient = object : android.webkit.WebViewClient() {
                                    override fun shouldOverrideUrlLoading(view: android.webkit.WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                                        val url = request?.url?.toString() ?: return false
                                        if (url.startsWith("http")) { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))); return true }
                                        return false
                                    }
                                    override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                                        super.onPageFinished(view, url)
                                        view?.evaluateJavascript("""
                                            (function() {
                                                ['header.header','nav.nav','.side-box','.ad-wrap','.adv-group','.adv-groupno','.adv-groupin','.ad-md','.pwlink','.con-search-box','.outside-search-box','.view-btm-con','.reco-search','#singoPopup','#blockLayer','#voice_share','#sns_share'].forEach(function(s){document.querySelectorAll(s).forEach(function(e){e.style.display='none';});});
                                                document.body.style.maxWidth='100%';
                                                document.body.style.fontSize='14px';
                                                document.body.style.wordBreak='break-all';
                                                document.querySelectorAll('img,video').forEach(function(e){e.style.maxWidth='100%';e.style.height='auto';});
                                                document.querySelectorAll('.write_div').forEach(function(e){e.style.lineHeight='1.6';});
                                            })();
                                        """.trimIndent(), null)
                                    }
                                }
                            }
                        },
                        update = { webView ->
                            val file = File(currentPath)
                            if (file.exists()) {
                                val htmlData = file.readText()
                                val parts = file.nameWithoutExtension.split("_")
                                val spoofedBaseUrl = if (parts.size >= 2) "https://gall.dcinside.com/board/view/?id=${parts[0]}&no=${parts[1]}" else "https://gall.dcinside.com/"
                                webView.loadDataWithBaseURL(spoofedBaseUrl, htmlData, "text/html", "UTF-8", null)
                            } else webView.loadDataWithBaseURL(null, "<h3 style='padding:20px; text-align:center;'>스냅샷 파일이 삭제되었거나 존재하지 않습니다.</h3>", "text/html", "UTF-8", null)
                        },
                        modifier = Modifier.weight(1f).fillMaxWidth()
                    )
                }
            }
        }
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

            Box {
                Row(modifier = Modifier.clickable { isSortMenuExpanded = true }.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (sortField == "CHECK") "검사/차단 기준" else "작성일 기준", fontSize = 12.sp, color = PastelNavy, fontWeight = FontWeight.Bold)
                    Icon(if (isAscending) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = PastelNavy, modifier = Modifier.size(20.dp))
                }
                DropdownMenu(
                    expanded = isSortMenuExpanded,
                    onDismissRequest = { isSortMenuExpanded = false },
                    modifier = Modifier.background(if(isDarkMode) Color(0xFF2C323A) else Color.White)
                ) {
                    DropdownMenuItem(
                        text = { Text("검사/차단일 최신순 (▼)", color = textColor) },
                        onClick = {
                            sortField = "CHECK"
                            isAscending = false
                            masterPref.edit()
                                .putString("db_sort_field", sortField)
                                .putBoolean("db_sort_ascending", isAscending)
                                .apply()
                            isSortMenuExpanded = false
                            generalLimit = 100
                            blockLimit = 100
                        }
                    )

                    DropdownMenuItem(
                        text = { Text("검사/차단일 과거순 (▲)", color = textColor) },
                        onClick = {
                            sortField = "CHECK"
                            isAscending = true
                            masterPref.edit()
                                .putString("db_sort_field", sortField)
                                .putBoolean("db_sort_ascending", isAscending)
                                .apply()
                            isSortMenuExpanded = false
                            generalLimit = 100
                            blockLimit = 100
                        }
                    )

                    Divider(color = dividerColor)

                    DropdownMenuItem(
                        text = { Text("작성일 최신순 (▼)", color = textColor) },
                        onClick = {
                            sortField = "CREATE"
                            isAscending = false
                            masterPref.edit()
                                .putString("db_sort_field", sortField)
                                .putBoolean("db_sort_ascending", isAscending)
                                .apply()
                            isSortMenuExpanded = false
                            generalLimit = 100
                            blockLimit = 100
                        }
                    )

                    DropdownMenuItem(
                        text = { Text("작성일 과거순 (▲)", color = textColor) },
                        onClick = {
                            sortField = "CREATE"
                            isAscending = true
                            masterPref.edit()
                                .putString("db_sort_field", sortField)
                                .putBoolean("db_sort_ascending", isAscending)
                                .apply()
                            isSortMenuExpanded = false
                            generalLimit = 100
                            blockLimit = 100
                        }
                    )
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
        }

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("제목, 작성자, 내용 검색...", fontSize = 14.sp) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = "검색", tint = Color.Gray) },
            trailingIcon = { if (searchQuery.isNotEmpty()) Icon(Icons.Filled.Close, contentDescription = "지우기", modifier = Modifier.clickable { searchQuery = ""; generalLimit=100; blockLimit=100 }, tint = Color.Gray) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboardController?.hide() }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).background(if(isDarkMode) Color(0xFF2C323A) else Color.White, RoundedCornerShape(8.dp)),
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = textColor, unfocusedTextColor = textColor, unfocusedBorderColor = Color.Transparent, focusedBorderColor = PastelNavy)
        )

        LazyRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (tabIndex == 0) {
                item { FilterChip(selected = selectedGall == "ALL", onClick = { selectedGall = "ALL"; generalLimit = 100 }, label = { Text("전체 갤러리", color=if(selectedGall == "ALL") Color.White else textColor) }) }
                items(galleries) { gId -> FilterChip(selected = selectedGall == gId, onClick = { selectedGall = gId; generalLimit = 100 }, label = { Text(gId, color=if(selectedGall == gId) Color.White else textColor) }) }
            } else {
                item { FilterChip(selected = selectedBlockType == "ALL", onClick = { selectedBlockType = "ALL"; blockLimit = 100 }, label = { Text("전체 내역", color=if(selectedBlockType == "ALL") Color.White else textColor) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = warningRed, selectedLabelColor = Color.White)) }
                item { FilterChip(selected = selectedBlockType == "POST", onClick = { selectedBlockType = "POST"; blockLimit = 100 }, label = { Text("게시글 차단", color=if(selectedBlockType == "POST") Color.White else textColor) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = warningRed, selectedLabelColor = Color.White)) }
                item { FilterChip(selected = selectedBlockType == "COMMENT", onClick = { selectedBlockType = "COMMENT"; blockLimit = 100 }, label = { Text("댓글 차단", color=if(selectedBlockType == "COMMENT") Color.White else textColor) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = warningRed, selectedLabelColor = Color.White)) }
            }
        }

        Box(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            if (tabIndex == 0) {
                if (generalPosts.isEmpty()) {
                    Text(
                        "조건에 맞는 공용 기록이 없습니다.",
                        modifier = Modifier.align(Alignment.Center),
                        color = subTextColor
                    )
                }
                else {
                    LazyColumn(state = generalListState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 80.dp)) {
                        items(generalPosts) { post ->
                            val checkTimeStr = SimpleDateFormat("yy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(post.checkTime))
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp)).clickable { if (post.snapshotPath != null) htmlSnapshotPathToView = post.snapshotPath!! else Toast.makeText(context, "스냅샷이 기록되지 않은 게시물입니다.", Toast.LENGTH_SHORT).show() },
                                colors = CardDefaults.cardColors(containerColor = cardBgColor)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                        Text("[${post.gallId}] 글 번호: ${post.postNum} (${post.gallType})", fontWeight = FontWeight.Bold, color = PastelNavy)
                                        Text("댓글: ${post.commentCount}", fontSize = 12.sp, color = PastelNavy)
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
                        item { Button(onClick = { generalLimit += 100 }, modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp), colors = ButtonDefaults.buttonColors(containerColor = if(isDarkMode) Color(0xFF37474F) else PastelNavyLight, contentColor = if(isDarkMode) Color.White else PastelNavy)) { Text("더 보기 (현재 $generalLimit 개)") } }
                    }
                }
            } else {
                if (blockPosts.isEmpty()) {
                    Text(
                        "조건에 맞는 공용 차단 기록이 없습니다.",
                        modifier = Modifier.align(Alignment.Center),
                        color = subTextColor
                    )
                }
                else {
                    LazyColumn(state = blockListState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 80.dp)) {
                        items(blockPosts) { history ->
                            val blockTimeStr = SimpleDateFormat("yy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(history.blockTime))
                            val detailedReason = history.blockReason
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp)).clickable { if (history.snapshotPath != null) htmlSnapshotPathToView = history.snapshotPath!! else Toast.makeText(context, "스냅샷이 기록되지 않은 게시물입니다.", Toast.LENGTH_SHORT).show() },
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
                        item { Button(onClick = { blockLimit += 100 }, modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp), colors = ButtonDefaults.buttonColors(containerColor = if(isDarkMode) Color(0xFF5D4037) else Color(0xFFFFCDD2), contentColor = if(isDarkMode) Color.White else warningRed)) { Text("더 보기 (현재 $blockLimit 개)") } }
                    }
                }
            }
        }
    }
}