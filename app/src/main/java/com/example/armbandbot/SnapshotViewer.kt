package com.heyheyon.armbandbot

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.heyheyon.armbandbot.ui.LocalIsDarkMode
import com.heyheyon.armbandbot.ui.PastelNavy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.io.File

sealed class BodyElement {
    data class TextElement(val text: String) : BodyElement()
    data class ImageElement(val url: String) : BodyElement()
}

data class SnapshotComment(
    val author: String,
    val date: String,
    val content: String,
    val isReply: Boolean = false,
    val dcconUrls: List<String> = emptyList()
)

data class SnapshotData(
    val title: String,
    val author: String,
    val date: String,
    val viewCount: String = "",
    val bodyText: String,
    val imageUrls: List<String>,
    val comments: List<SnapshotComment>,
    val bodyElements: List<BodyElement> = emptyList()
)

fun parseSnapshot(htmlPath: String): SnapshotData {
    val doc = Jsoup.parse(File(htmlPath), "UTF-8")

    val title = doc.select(".title_subject").text()

    val writerEl = doc.select(".gall_writer").first()
    val nick = writerEl?.attr("data-nick") ?: ""
    val uid = writerEl?.attr("data-uid") ?: ""
    val ip = writerEl?.attr("data-ip") ?: ""
    val author = if (uid.isNotEmpty()) "$nick($uid)" else if (ip.isNotEmpty()) "$nick($ip)" else nick

    val date = doc.select(".gall_date").first()?.attr("title") ?: ""
    val viewCount = Regex("[0-9,]+").find(doc.select(".gall_count").text())?.value ?: ""

    val bodyEl = doc.select(".write_div").first()
    val bodyText = bodyEl?.text() ?: ""

    val imageUrls = bodyEl?.select("img")?.mapNotNull { img ->
        val src = img.attr("src").ifEmpty { img.attr("data-original") }
        src.takeIf { it.startsWith("http") }
    } ?: emptyList()

    val bodyElements: List<BodyElement> = bodyEl?.children()?.flatMap { child ->
        val imgs = child.select("img")
        if (imgs.isNotEmpty()) {
            imgs.mapNotNull { img ->
                val src = img.attr("src").ifEmpty { img.attr("data-original") }
                src.takeIf { it.startsWith("http") }?.let { BodyElement.ImageElement(it) }
            }
        } else {
            val text = child.text()
            if (text.isNotBlank()) listOf(BodyElement.TextElement(text)) else emptyList()
        }
    } ?: emptyList()

    val comments = doc.select("#snapshot-comments .s-cmt").map { el ->
        val isReply = el.hasClass("s-cmt-reply")
        val dcconUrls = el.select("img").filter { img ->
            val src = img.attr("src")
            src.contains("dccon.php") || src.contains("dcimg")
        }.mapNotNull { img ->
            val src = img.attr("src")
            when {
                src.startsWith("http") -> src
                src.startsWith("//") -> "https:$src"
                else -> null
            }
        }
        val contentEl = el.select(".s-cmt-text").first()
        val content = contentEl?.let { p ->
            p.select("a.mention").forEach { a -> a.replaceWith(org.jsoup.nodes.TextNode(a.text())) }
            p.text()
        } ?: ""
        SnapshotComment(
            author = el.select(".s-cmt-nick").text(),
            date = el.select(".s-cmt-date").text(),
            content = content,
            isReply = isReply,
            dcconUrls = dcconUrls
        )
    }

    return SnapshotData(title, author, date, viewCount, bodyText, imageUrls, comments, bodyElements)
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
        withStyle(SpanStyle(color = PastelNavy)) {
            append(match.value)
        }
        lastIndex = match.range.last + 1
    }
    if (lastIndex < text.length) {
        withStyle(SpanStyle(color = textColor)) {
            append(text.substring(lastIndex))
        }
    }
}

@Composable
fun SnapshotViewerScreen(snapshotPath: String, onBack: () -> Unit) {
    val isDarkMode = LocalIsDarkMode.current
    val bgColor = if (isDarkMode) Color(0xFF121212) else Color(0xFFF1F3F5)
    val topBarColor = if (isDarkMode) Color(0xFF1E2329) else Color.White
    val textColor = if (isDarkMode) Color(0xFFE0E0E0) else Color(0xFF2C3E50)
    val subTextColor = if (isDarkMode) Color(0xFFAAAEB3) else Color.Gray
    val dividerColor = if (isDarkMode) Color(0xFF333333) else Color(0xFFEEEEEE)
    val commentBgColor = if (isDarkMode) Color(0xFF2C323A) else Color(0xFFF8F9FA)

    val initialPath = remember(snapshotPath) {
        if (snapshotPath.endsWith("_latest.html"))
            snapshotPath.replace("_latest.html", "_initial.html")
        else null
    }
    val hasInitial = remember(initialPath) {
        initialPath?.let { File(it).exists() } ?: false
    }

    var currentPath by remember { mutableStateOf(snapshotPath) }
    var data by remember { mutableStateOf<SnapshotData?>(null) }

    LaunchedEffect(currentPath) {
        data = null
        withContext(Dispatchers.IO) {
            data = try { parseSnapshot(currentPath) } catch (e: Exception) { null }
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
                modifier = Modifier.clickable { onBack() }.padding(end = 16.dp),
                tint = PastelNavy
            )
            Text("스냅샷 뷰어", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = textColor)
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
                ) {
                    Text("최초 스냅샷", fontSize = 13.sp)
                }
                Button(
                    onClick = { if (!isShowingLatest) currentPath = snapshotPath },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isShowingLatest) PastelNavy else Color.Gray.copy(alpha = 0.2f),
                        contentColor = if (isShowingLatest) Color.White else textColor
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("최신 스냅샷", fontSize = 13.sp)
                }
            }
        }

        if (data == null) {
            Box(modifier = Modifier.fillMaxSize()) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = PastelNavy
                )
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

                if (d.bodyElements.isNotEmpty()) {
                    items(d.bodyElements) { element ->
                        when (element) {
                            is BodyElement.TextElement -> Text(
                                element.text,
                                fontSize = 14.sp,
                                lineHeight = 22.4.sp,
                                color = textColor
                            )
                            is BodyElement.ImageElement -> AsyncImage(
                                model = element.url,
                                contentDescription = null,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                } else {
                    item {
                        Text(d.bodyText, fontSize = 14.sp, lineHeight = 22.4.sp, color = textColor)
                    }
                    items(d.imageUrls) { url ->
                        AsyncImage(
                            model = url,
                            contentDescription = null,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                if (d.comments.isNotEmpty()) {
                    item {
                        HorizontalDivider(color = dividerColor, modifier = Modifier.padding(vertical = 8.dp))
                        Text(
                            "댓글 ${d.comments.size}개",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = textColor,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                    items(d.comments) { comment ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = if (comment.isReply) 24.dp else 0.dp)
                                .background(commentBgColor, RoundedCornerShape(8.dp))
                                .padding(10.dp)
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(comment.author, fontSize = 12.sp, color = PastelNavy, fontWeight = FontWeight.Bold)
                                Text(comment.date, fontSize = 11.sp, color = subTextColor)
                            }
                            Spacer(Modifier.height(4.dp))
                            if (comment.dcconUrls.isNotEmpty()) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier.padding(bottom = 4.dp)
                                ) {
                                    comment.dcconUrls.forEach { url ->
                                        AsyncImage(
                                            model = url,
                                            contentDescription = null,
                                            modifier = Modifier.size(32.dp)
                                        )
                                    }
                                }
                            }
                            if (comment.content.isNotBlank()) {
                                Text(
                                    buildMentionAnnotatedString(comment.content, textColor),
                                    fontSize = 13.sp
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
        }
    }
}
