package com.heyheyon.armbandbot

import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.heyheyon.armbandbot.ui.LocalIsDarkMode
import com.heyheyon.armbandbot.ui.PastelNavy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.File

sealed class BodyElement {
    data class TextElement(val text: String) : BodyElement()
    data class ImageElement(val url: String, val isDccon: Boolean = false) : BodyElement()
}

data class SnapshotComment(
    val author: String,
    val date: String,
    val content: String,
    val isReply: Boolean = false,
    val isBlocked: Boolean = false,
    val dcconUrls: List<String> = emptyList(),
    val commentIndex: Int = 0,
    val parentIndex: Int? = null
)

data class SnapshotData(
    val title: String = "",
    val author: String = "",
    val date: String = "",
    val viewCount: String = "",
    val bodyElements: List<BodyElement> = emptyList(),
    val comments: List<SnapshotComment> = emptyList()
)

enum class CommentSort { ORIGINAL, LATEST, REPLIES }

private fun sortComments(comments: List<SnapshotComment>, sort: CommentSort): List<SnapshotComment> {
    val depth0 = comments.filter { !it.isReply }
    val depth1 = comments.filter { it.isReply }
    val repliesByParent = depth1.groupBy { it.parentIndex }
    return when (sort) {
        CommentSort.ORIGINAL -> comments
        CommentSort.LATEST -> {
            val result = mutableListOf<SnapshotComment>()
            depth0.reversed().forEach { parent ->
                result.add(parent)
                repliesByParent[parent.commentIndex]?.forEach { result.add(it) }
            }
            result
        }
        CommentSort.REPLIES -> {
            val result = mutableListOf<SnapshotComment>()
            val sorted = depth0.sortedWith(
                compareByDescending<SnapshotComment> { repliesByParent[it.commentIndex]?.size ?: 0 }
                    .thenBy { it.commentIndex }
            )
            sorted.forEach { parent ->
                result.add(parent)
                repliesByParent[parent.commentIndex]?.forEach { result.add(it) }
            }
            result
        }
    }
}

private fun resolveImgSrc(img: Element): String? {
    val src = img.attr("src").takeIf { it.isNotEmpty() }
        ?: img.attr("data-original").takeIf { it.isNotEmpty() }
        ?: img.attr("data-src").takeIf { it.isNotEmpty() }
        ?: return null
    return when {
        src.startsWith("http") -> src
        src.startsWith("//") -> "https:$src"
        else -> null
    }
}

fun parseSnapshot(htmlPath: String): SnapshotData {
    val doc = Jsoup.parse(File(htmlPath), "UTF-8")

    val title = doc.select(".title_subject").text()

    val writerEl = doc.select(".gall_writer").first()
    val nick = writerEl?.attr("data-nick") ?: ""
    val uid = writerEl?.attr("data-uid") ?: ""
    val ip = writerEl?.attr("data-ip") ?: ""
    val author = when {
        uid.isNotEmpty() -> "$nick($uid)"
        ip.isNotEmpty() -> "$nick($ip)"
        else -> nick
    }

    val date = doc.select(".gall_date").first()?.attr("title") ?: ""
    val viewCount = Regex("[0-9,]+").find(doc.select(".gall_count").text())?.value ?: ""

    val bodyEl = doc.select(".write_div").first()
    val bodyElements: List<BodyElement> = buildList {
        bodyEl?.children()?.forEach { child ->
            if (child.hasClass("vr_player") || child.hasClass("vr_player_tag") ||
                child.hasClass("voice_wrap") ||
                child.select(".vr_player, .vr_player_tag, div.voice_wrap, iframe[src*=voice/player]").isNotEmpty() ||
                child.html().contains("voice/player")
            ) {
                add(BodyElement.TextElement("[보이스리플]"))
                return@forEach
            }
            val dccons = child.select("img.written_dccon")
            val allImgs = child.select("img")
            when {
                dccons.isNotEmpty() -> dccons.forEach { img ->
                    val alt = img.attr("alt").takeIf { it.isNotEmpty() } ?: "디시콘"
                    add(BodyElement.TextElement("[디시콘: $alt]"))
                }
                allImgs.isNotEmpty() -> allImgs.forEach { img ->
                    val src = img.attr("src").ifEmpty { img.attr("data-original") }
                        .ifEmpty { img.attr("data-src") }
                    if (src.contains("dccon.php")) {
                        val alt = img.attr("alt").takeIf { it.isNotEmpty() } ?: "디시콘"
                        add(BodyElement.TextElement("[디시콘: $alt]"))
                    } else {
                        resolveImgSrc(img)?.let { add(BodyElement.ImageElement(it, isDccon = false)) }
                    }
                }
                else -> {
                    // <br> 태그를 \n으로 변환하여 줄바꿈 보존
                    val rawHtml = child.html()
                        .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "⏎")
                    val tempBody = Jsoup.parseBodyFragment(rawHtml).body()
                    val text = tempBody?.text()?.replace("⏎", "\n") ?: child.text()
                    if (text.isNotBlank()) add(BodyElement.TextElement(text))
                    else add(BodyElement.TextElement("")) // 빈 단락 구분용
                }
            }
        }
    }

    var lastDepth0Index: Int? = null
    val comments = mutableListOf<SnapshotComment>()

    // DC 원본 구조 파싱: 상위댓글(cmt_info) + 답글(reply_info) 모두 수집
    // li.ub-content[id^=comment_li] → 상위댓글
    // li.ub-content[id^=reply_li] → 답글
    fun parseDcLi(li: Element, isReply: Boolean, idx: Int): SnapshotComment? {
        val infoDiv = li.selectFirst(".cmt_info, .reply_info") ?: return null
        val nickEl = infoDiv.selectFirst(".gall_writer")
        val nick = nickEl?.attr("data-nick") ?: infoDiv.selectFirst(".nickname em")?.text() ?: ""
        val uid = nickEl?.attr("data-uid") ?: ""
        val ip = nickEl?.attr("data-ip") ?: ""
        if (nick == "댓글돌이" && uid.isEmpty() && ip.isEmpty()) return null
        val ipUid = uid.ifEmpty { ip }
        val commentAuthor = if (ipUid.isNotEmpty()) "$nick($ipUid)" else nick
        val commentDate = infoDiv.selectFirst(".date_time")?.text() ?: ""
        val isBlocked = li.hasClass("dc-blocked-cmt")

        val hasVr = infoDiv.select("iframe[src*=voice], .voice_wrap").isNotEmpty()
        // a.mention은 p.usertxt 안에 있을 수 있으므로, p 전체 파싱 후 mention 따로 분리
        val pEl = infoDiv.selectFirst("p.usertxt")
        val mentionText: String
        val rawText: String
        if (pEl != null) {
            val pClone = pEl.clone()
            // mention 추출 후 제거해서 중복 방지
            val mentionEl = pClone.selectFirst("a.mention")
            mentionText = mentionEl?.text()?.trim() ?: ""
            mentionEl?.remove()
            val modHtml = pClone.html().replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "⏎")
            rawText = (Jsoup.parseBodyFragment(modHtml).body()?.text()?.replace("⏎", "\n") ?: pClone.text()).trim()
        } else {
            // p.usertxt 없는 경우 (디시콘만/보이스리플만): mention을 txtbox에서 직접 찾음
            mentionText = infoDiv.selectFirst("a.mention")?.text()?.trim() ?: ""
            rawText = ""
        }
        val textContent = when {
            mentionText.isNotEmpty() && rawText.isNotEmpty() -> "$mentionText $rawText"
            mentionText.isNotEmpty() -> mentionText
            else -> rawText
        }
        val dcconUrls = infoDiv.select("img.written_dccon, img[src*=dccon.php]").mapNotNull { img ->
            val src = img.attr("src").ifEmpty { img.attr("data-src") }
            when { src.startsWith("http") -> src; src.startsWith("//") -> "https:$src"; else -> null }
        }.distinct()
        val content = when {
            hasVr && textContent.isBlank() -> "[🔊 보이스리플]"
            hasVr -> "$textContent\n[🔊 보이스리플]"
            else -> textContent
        }
        return SnapshotComment(
            author = commentAuthor, date = commentDate, content = content,
            isReply = isReply, isBlocked = isBlocked,
            dcconUrls = dcconUrls, commentIndex = idx, parentIndex = if (isReply) lastDepth0Index else null
        )
    }

    var cmtIdx = 0
    // 상위댓글: ul.cmt_list > li[id^=comment_li]
    // 답글: ul.reply_list > li[id^=reply_li]
    doc.select(".cmt_list").forEach { cmt_list ->
        cmt_list.children().forEach { child ->
            // 상위댓글 li
            val commentLi = if (child.tagName() == "li" && child.id().startsWith("comment_li")) child else null
            if (commentLi != null) {
                parseDcLi(commentLi, false, cmtIdx)?.let {
                    lastDepth0Index = cmtIdx
                    comments.add(it)
                    cmtIdx++
                }
            }
            // 답글 묶음 li (div.reply.show 포함)
            val replyListLis = child.select("ul.reply_list > li.ub-content")
            replyListLis.forEach { rLi ->
                parseDcLi(rLi, true, cmtIdx)?.let {
                    comments.add(it)
                    cmtIdx++
                }
            }
        }
    }

    return SnapshotData(title, author, date, viewCount, bodyElements, comments)
}

private fun buildMentionAnnotatedString(text: String, textColor: Color): AnnotatedString = buildAnnotatedString {
    val mentionRegex = Regex("@\\S+")
    var lastIndex = 0
    for (match in mentionRegex.findAll(text)) {
        if (match.range.first > lastIndex) {
            withStyle(SpanStyle(color = textColor)) {
                append(text.substring(lastIndex, match.range.first))
            }
        }
        withStyle(SpanStyle(color = PastelNavy)) { append(match.value) }
        lastIndex = match.range.last + 1
    }
    if (lastIndex < text.length) {
        withStyle(SpanStyle(color = textColor)) { append(text.substring(lastIndex)) }
    }
}

@Composable
fun SnapshotViewerScreen(snapshotPath: String, onBack: () -> Unit) {
    val context = LocalContext.current
    var showWebView by remember { mutableStateOf(false) }

    val initialPath = remember(snapshotPath) {
        if (snapshotPath.endsWith("_latest.html"))
            snapshotPath.replace("_latest.html", "_initial.html")
        else null
    }
    val hasInitial = remember(initialPath) {
        initialPath?.let { File(it).exists() } ?: false
    }

    var currentPath by remember { mutableStateOf(snapshotPath) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/html")) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { it.write(File(currentPath).readBytes()) }
                Toast.makeText(context, "추출 완료", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "추출 실패: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    BackHandler(enabled = true) { onBack() }
    BackHandler(enabled = showWebView) { showWebView = false }

    val isDarkMode = LocalIsDarkMode.current
    val bgColor = if (isDarkMode) Color(0xFF121212) else Color(0xFFF1F3F5)
    val topBarColor = if (isDarkMode) Color(0xFF1E2329) else Color.White
    val textColor = if (isDarkMode) Color(0xFFE0E0E0) else Color(0xFF2C3E50)
    val subTextColor = if (isDarkMode) Color(0xFFAAAEB3) else Color.Gray
    val dividerColor = if (isDarkMode) Color(0xFF333333) else Color(0xFFEEEEEE)
    val commentBgColor = if (isDarkMode) Color(0xFF2C323A) else Color(0xFFF8F9FA)
    var data by remember { mutableStateOf<SnapshotData?>(null) }
    var sortOption by remember { mutableStateOf(CommentSort.ORIGINAL) }

    LaunchedEffect(currentPath) {
        data = null
        withContext(Dispatchers.IO) {
            data = try { parseSnapshot(currentPath) } catch (e: Exception) { null }
        }
    }

    val displayComments = remember(data, sortOption) {
        val comments = data?.comments ?: emptyList()
        sortComments(comments, sortOption)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().background(bgColor)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(topBarColor)
                    .padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.ArrowBack,
                    contentDescription = "뒤로",
                    modifier = Modifier.clickable { onBack() }.padding(end = 16.dp),
                    tint = PastelNavy
                )
                Text("스냅샷 뷰어", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = textColor)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = {
                    val suggestedFileName = File(currentPath).name
                    exportLauncher.launch(suggestedFileName)
                }) {
                    Icon(Icons.Filled.Save, contentDescription = "추출", tint = PastelNavy)
                }
                TextButton(onClick = { showWebView = true }) {
                    Text("원본 HTML", color = PastelNavy, fontSize = 13.sp)
                }
            }

            if (hasInitial) {
                val isShowingLatest = currentPath.endsWith("_latest.html")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(topBarColor)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { if (isShowingLatest) currentPath = initialPath!! },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (!isShowingLatest) PastelNavy else Color.Gray.copy(alpha = 0.2f),
                            contentColor = if (!isShowingLatest) Color.White else textColor
                        ),
                        modifier = Modifier.weight(1f)
                    ) { Text("최초 스냅샷", fontSize = 13.sp) }
                    Button(
                        onClick = { if (!isShowingLatest) currentPath = snapshotPath },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isShowingLatest) PastelNavy else Color.Gray.copy(alpha = 0.2f),
                            contentColor = if (isShowingLatest) Color.White else textColor
                        ),
                        modifier = Modifier.weight(1f)
                    ) { Text("최신 스냅샷", fontSize = 13.sp) }
                }
            }

            if (data == null) {
                Box(modifier = Modifier.fillMaxSize()) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = PastelNavy)
                }
            } else {
                val d = data!!
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        Text(d.title, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = textColor)
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(d.author, fontSize = 13.sp, color = subTextColor)
                            Text(d.date, fontSize = 13.sp, color = subTextColor)
                            if (d.viewCount.isNotBlank()) {
                                Text("조회 ${d.viewCount}", fontSize = 13.sp, color = subTextColor)
                            }
                        }
                        HorizontalDivider(color = dividerColor, modifier = Modifier.padding(vertical = 8.dp))
                    }

                    items(d.bodyElements) { element ->
                        when (element) {
                            is BodyElement.TextElement -> if (element.text.isEmpty()) {
                                Spacer(Modifier.height(8.dp))
                            } else {
                                Text(
                                    element.text,
                                    fontSize = 14.sp,
                                    lineHeight = 22.4.sp,
                                    color = textColor,
                                    softWrap = true
                                )
                            }
                            is BodyElement.ImageElement -> if (element.isDccon) {
                                AsyncImage(
                                    model = element.url,
                                    contentDescription = null,
                                    modifier = Modifier.size(80.dp),
                                    contentScale = ContentScale.Fit
                                )
                            } else {
                                AsyncImage(
                                    model = element.url,
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }

                    if (d.comments.isNotEmpty()) {
                        item {
                            HorizontalDivider(color = dividerColor, modifier = Modifier.padding(vertical = 8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "댓글 ${d.comments.size}개",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = textColor,
                                    modifier = Modifier.weight(1f)
                                )
                                listOf(
                                    CommentSort.ORIGINAL to "등록순",
                                    CommentSort.LATEST to "최신순",
                                    CommentSort.REPLIES to "답글순"
                                ).forEach { (sort, label) ->
                                    TextButton(
                                        onClick = { sortOption = sort },
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            label,
                                            fontSize = 12.sp,
                                            color = if (sortOption == sort) PastelNavy else subTextColor,
                                            fontWeight = if (sortOption == sort) FontWeight.Bold else FontWeight.Normal
                                        )
                                    }
                                }
                            }
                        }
                        items(displayComments) { comment ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Top
                            ) {
                                if (comment.isReply) {
                                    Spacer(Modifier.width(24.dp))
                                    Text(
                                        "ㄴ",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        color = PastelNavy,
                                        modifier = Modifier.padding(end = 4.dp, top = 10.dp)
                                    )
                                }
                                Row(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            if (comment.isBlocked) {
                                                if (isDarkMode) Color(0xFF3B1A1A) else Color(0xFFFFEBEE)
                                            } else commentBgColor
                                        )
                                ) {
                                    if (comment.isBlocked) {
                                        Box(
                                            modifier = Modifier
                                                .width(4.dp)
                                                .fillMaxHeight()
                                                .background(Color(0xFFD32F2F))
                                        )
                                    }
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(10.dp)
                                    ) {
                                        Row(
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text(comment.author, fontSize = 12.sp, color = if (isDarkMode) Color(0xFF8BB8F0) else PastelNavy, fontWeight = FontWeight.Bold)
                                            Text(comment.date, fontSize = 11.sp, color = subTextColor)
                                        }
                                        Spacer(Modifier.height(4.dp))
                                        if (comment.content.isNotBlank()) {
                                            Text(
                                                buildMentionAnnotatedString(
                                                    comment.content,
                                                    textColor
                                                ),
                                                fontSize = 13.sp
                                            )
                                        }
                                        if (comment.dcconUrls.isNotEmpty()) {
                                            Text(
                                                "[디시콘]",
                                                fontSize = 13.sp,
                                                color = Color(0xFF4A6583)
                                            )
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                }
            }
        }

        if (showWebView) {
            Box(modifier = Modifier.fillMaxSize().background(Color.White)) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                            settings.setSupportZoom(true)
                            settings.builtInZoomControls = true
                            settings.displayZoomControls = false
                            webViewClient = WebViewClient()
                            val html = try { File(currentPath).readText() } catch (e: Exception) { "<html><body>파일을 읽을 수 없습니다.</body></html>" }
                            val fileName = File(currentPath).name
                            val noSuffix = fileName.removeSuffix("_initial.html").removeSuffix("_latest.html")
                            val lastUnderscore = noSuffix.lastIndexOf('_')
                            val extractedGallId = if (lastUnderscore > 0) noSuffix.substring(0, lastUnderscore) else noSuffix
                            val extractedPostNum = if (lastUnderscore > 0) noSuffix.substring(lastUnderscore + 1) else ""
                            val baseUrl = "https://gall.dcinside.com/board/view/?id=$extractedGallId&no=$extractedPostNum"
                            loadDataWithBaseURL(baseUrl, html, "text/html", "UTF-8", null)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}
