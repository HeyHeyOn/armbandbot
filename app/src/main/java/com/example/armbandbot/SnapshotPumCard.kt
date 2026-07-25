package com.heyheyon.armbandbot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.heyheyon.armbandbot.ui.PastelNavy

internal fun pumExpandedBodyElements(preview: SnapshotPumPreview): List<BodyElement> = buildList {
    val thumbnail = preview.thumbnailUrl
    preview.bodyElements.forEach { element ->
        when (element) {
            is BodyElement.TextElement -> add(element)
            is BodyElement.ImageElement -> if (element.url != thumbnail) add(element)
            is BodyElement.DcconRowElement -> {
                val remaining = element.urls.filterNot { it == thumbnail }
                when (remaining.size) {
                    0 -> Unit
                    1 -> add(BodyElement.ImageElement(remaining.first(), isDccon = true))
                    else -> add(BodyElement.DcconRowElement(remaining))
                }
            }
        }
    }
}

/** Small, isolated PUM viewer to keep SnapshotViewerScreen's method count and composition shallow. */
@Composable
internal fun SnapshotPumCard(
    preview: SnapshotPumPreview,
    isDarkMode: Boolean,
    snapshotIdentity: String,
) {
    var expanded by remember(snapshotIdentity, preview.sourceKey, preview.contentHash) { mutableStateOf(false) }
    val resolved = preview.status == PumSourceStatus.RESOLVED
    val background = when {
        !resolved && isDarkMode -> Color(0xFF3B3020)
        !resolved -> Color(0xFFFFF4D6)
        isDarkMode -> Color(0xFF202B3A)
        else -> Color(0xFFEAF1FA)
    }
    val foreground = if (isDarkMode) Color(0xFFE7ECF3) else Color(0xFF26384F)
    val secondary = if (isDarkMode) Color(0xFFB8C4D4) else Color(0xFF5F6F82)
    val uriHandler = LocalUriHandler.current

    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = background),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text("펌 원문", color = PastelNavy, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            if (preview.galleryLabel.isNotBlank()) {
                Text(preview.galleryLabel, color = secondary, fontSize = 12.sp)
            }
            Spacer(Modifier.height(6.dp))

            if (!resolved) {
                Text("원문을 불러오지 못했습니다", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                Text(pumStatusLabel(preview.status), color = secondary, fontSize = 12.sp)
                return@Column
            }

            if (preview.title.isNotBlank()) {
                Text(preview.title, color = foreground, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
            if (preview.author.isNotBlank()) {
                Text(preview.author, color = secondary, fontSize = 12.sp)
            }
            if (preview.previewText.isNotBlank()) {
                Text(
                    preview.previewText,
                    color = foreground,
                    fontSize = 13.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            preview.thumbnailUrl?.let { url ->
                SnapshotPumImage(
                    url = url,
                    referer = pumImageReferer(preview),
                    contentDescription = "펌 원문 대표 이미지",
                    modifier = Modifier.fillMaxWidth().height(180.dp).padding(top = 8.dp),
                )
            }
            if (preview.bodyTruncated) {
                Text(
                    "일부 내용이 생략되었습니다",
                    color = secondary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            if (preview.bodyElements.isNotEmpty()) {
                TextButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .semantics {
                            role = Role.Button
                            contentDescription = if (expanded) "펌 원문 내용 접기" else "펌 원문 내용 펼치기"
                            stateDescription = if (expanded) "펼쳐짐" else "접힘"
                        },
                ) {
                    Text(
                        if (expanded) "원문 내용 접기" else "원문 내용 보기",
                        color = PastelNavy,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                    )
                }
                // Keep the bounded source tree out of composition until explicitly requested.
                if (expanded) {
                    Column(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        pumExpandedBodyElements(preview).forEachIndexed { index, element ->
                            key("pum-body-$index-${element.hashCode()}") {
                                SnapshotPumBodyElement(element, pumImageReferer(preview), foreground)
                            }
                        }
                    }
                }
            }
            preview.sourceUrl?.let { url ->
                TextButton(
                    onClick = { uriHandler.openUri(url) },
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .semantics {
                            role = Role.Button
                            contentDescription = "펌 원문 링크 열기"
                        },
                ) {
                    Text(
                        "원본 링크",
                        color = PastelNavy,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun SnapshotPumBodyElement(element: BodyElement, referer: String, textColor: Color) {
    when (element) {
        is BodyElement.TextElement -> if (element.text.isBlank()) {
            Spacer(Modifier.height(8.dp))
        } else {
            Text(element.text, color = textColor, fontSize = 13.sp, lineHeight = 20.sp)
        }
        is BodyElement.ImageElement -> SnapshotPumImage(
            url = element.url,
            referer = referer,
            contentDescription = if (element.isDccon) "펌 원문 디시콘" else "펌 원문 이미지",
            modifier = if (element.isDccon) Modifier.size(80.dp) else Modifier.fillMaxWidth().height(240.dp),
        )
        is BodyElement.DcconRowElement -> LazyRow(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
            itemsIndexed(element.urls, key = { index, url -> "pum-dccon-$index-$url" }) { _, url ->
                SnapshotPumImage(url, referer, "펌 원문 디시콘", Modifier.size(80.dp))
            }
        }
    }
}

@Composable
private fun SnapshotPumImage(url: String, referer: String, contentDescription: String, modifier: Modifier) {
    val safeUrl = safeSnapshotMediaUrl(url) ?: return
    val context = LocalContext.current
    AsyncImage(
        model = ImageRequest.Builder(context)
            .data(safeUrl)
            .setHeader("Referer", referer)
            .setHeader("User-Agent", "Mozilla/5.0")
            .crossfade(true)
            .build(),
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = ContentScale.Fit,
    )
}
