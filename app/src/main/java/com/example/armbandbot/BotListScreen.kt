package com.heyheyon.armbandbot

import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.heyheyon.armbandbot.ui.LocalIsDarkMode
import com.heyheyon.armbandbot.ui.PastelNavy
import com.heyheyon.armbandbot.ui.botColors
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.roundToInt

@Composable
fun BotListScreen(onNavigateToSettings: (String) -> Unit, onThemeToggle: (Boolean) -> Unit) {
    val context = LocalContext.current
    val masterPref = context.getSharedPreferences("bot_master", Context.MODE_PRIVATE)
    val botIds = remember { mutableStateListOf<String>() }

    val isDarkMode = LocalIsDarkMode.current
    val bgColor = if (isDarkMode) Color(0xFF121212) else Color(0xFFF8F9FA)
    val cardColor = if (isDarkMode) Color(0xFF1E2329) else Color.White
    val textColor = if (isDarkMode) Color(0xFFE0E0E0) else Color(0xFF2C3E50)
    val subTextColor = if (isDarkMode) Color(0xFFAAAEB3) else Color.DarkGray
    val iconColor = if (isDarkMode) Color(0xFF90A4AE) else PastelNavy

    LaunchedEffect(Unit) {
        var botIdsStr = masterPref.getString("bot_ids_list", null)
        if (botIdsStr == null) {
            val oldSet = masterPref.getStringSet("bot_ids", setOf()) ?: setOf()
            botIdsStr = oldSet.joinToString(",")
            masterPref.edit().putString("bot_ids_list", botIdsStr).apply()
        }
        botIds.clear()
        botIds.addAll(botIdsStr.split(",").filter { it.isNotBlank() })
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

    val density = LocalDensity.current
    val itemHeightPx = remember(density) { with(density) { 60.dp.toPx() } }

    Scaffold(
        containerColor = bgColor,
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {
                FloatingActionButton(
                    onClick = { onThemeToggle(!isDarkMode) },
                    containerColor = if (isDarkMode) Color(0xFF333333) else Color.White,
                    contentColor = if (isDarkMode) Color(0xFFFFD54F) else PastelNavy,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(imageVector = if (isDarkMode) Icons.Filled.LightMode else Icons.Filled.DarkMode, contentDescription = "다크모드 전환")
                }
                Spacer(modifier = Modifier.height(16.dp))
                FloatingActionButton(onClick = { showAddDialog = true }, containerColor = PastelNavy, contentColor = Color.White) {
                    Icon(Icons.Filled.Add, contentDescription = "추가")
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
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = PastelNavy), shape = RoundedCornerShape(16.dp)) {
                Row(modifier = Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Settings, contentDescription = null, tint = Color.White, modifier = Modifier.size(40.dp))
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text("완장봇", fontWeight = FontWeight.Bold, fontSize = 22.sp, color = Color.White)
                        Text("버전: 1.0.5", fontSize = 13.sp, color = Color.White.copy(alpha = 0.8f))
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))

            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cardColor), shape = RoundedCornerShape(12.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.AddCircle, contentDescription = null, tint = iconColor, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("우측 하단 버튼으로 봇을 추가하세요.", fontSize = 13.sp, color = subTextColor)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Menu, contentDescription = null, tint = iconColor, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("블록을 길게 눌러 순서를 변경할 수 있습니다.", fontSize = 13.sp, color = subTextColor)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = null, tint = iconColor, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("블록을 왼쪽으로 밀면 복사/삭제가 가능합니다.", fontSize = 13.sp, color = subTextColor)
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))

            if (botIds.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("생성된 봇이 없습니다.", color = subTextColor) }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(botIds.size) { index ->
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
                        botPref.edit().putString("bot_name", newBotName).putStringSet("url_whitelist", setOf("dcinside.com", "dcinside.kr", "youtube.com", "youtu.be")).apply()
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

        if (botToDelete != null) {
            val delPref = context.getSharedPreferences("bot_prefs_${botToDelete!!}", Context.MODE_PRIVATE)
            val delName = delPref.getString("bot_name", "이름 없는 봇") ?: "이름 없는 봇"
            AlertDialog(
                containerColor = cardColor, titleContentColor = if (isDarkMode) Color(0xFFEF5350) else Color(0xFFD32F2F), textContentColor = textColor,
                onDismissRequest = { botToDelete = null },
                title = { Text("봇 삭제", fontWeight = FontWeight.Bold) },
                text = { Text("'$delName' 봇을 완전히 삭제하시겠습니까?\n이 작업은 되돌릴 수 없습니다.") },
                confirmButton = { Button(onClick = {
                    context.startService(Intent(context, BotService::class.java).apply { putExtra("BOT_ID", botToDelete); action = "STOP" })
                    delPref.edit().clear().apply()
                    clearBotLogFile(context, botToDelete!!)
                    GlobalBotState.logs.remove(botToDelete)
                    botIds.remove(botToDelete)
                    masterPref.edit().putString("bot_ids_list", botIds.joinToString(",")).apply()
                    botToDelete = null
                    Toast.makeText(context, "삭제되었습니다.", Toast.LENGTH_SHORT).show()
                }, colors = ButtonDefaults.buttonColors(containerColor = if (isDarkMode) Color(0xFFEF5350) else Color(0xFFD32F2F))) { Text("삭제", color = Color.White) } },
                dismissButton = { TextButton(onClick = { botToDelete = null }) { Text("취소", color = subTextColor) } }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BotListItem(
    index: Int, botId: String, draggingIndex: Int?, dragDy: Float,
    isSwipedOpen: Boolean, onSwipeStateChange: (Boolean) -> Unit,
    onDragStart: (Int) -> Unit, onDragEnd: () -> Unit, onDrag: (Float) -> Unit,
    onSettingsClick: () -> Unit, onDuplicateRequest: () -> Unit, onDeleteRequest: () -> Unit
) {
    val context = LocalContext.current
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()

    val botPref = context.getSharedPreferences("bot_prefs_$botId", Context.MODE_PRIVATE)
    val botName = botPref.getString("bot_name", "이름 없는 봇") ?: "이름 없는 봇"
    var isRunning by remember { mutableStateOf(botPref.getBoolean("is_running", false)) }

    val isDarkMode = LocalIsDarkMode.current
    val cardBgColor = if (isDarkMode) Color(0xFF1E2329) else Color.White
    val textColor = if (isDarkMode) Color(0xFFE0E0E0) else Color(0xFF2C3E50)
    val dividerColor = if (isDarkMode) Color(0xFF333333) else Color(0xFFEEEEEE)

    val isDragging = draggingIndex == index
    val yOffset = if (isDragging) with(LocalDensity.current) { dragDy.toDp() } else 0.dp
    val zIndex = if (isDragging) 1f else 0f
    val density = LocalDensity.current

    val buttonSize = 58.dp
    val buttonGap = 8.dp
    val maxSwipePx = with(density) { -(buttonSize * 2 + buttonGap * 3).toPx() }
    val swipeOffset = remember { androidx.compose.animation.core.Animatable(0f) }

    LaunchedEffect(isSwipedOpen) {
        if (!isSwipedOpen && swipeOffset.value != 0f) {
            swipeOffset.animateTo(0f, androidx.compose.animation.core.tween(300))
        }
    }

    Box(modifier = Modifier.fillMaxWidth().offset(y = yOffset).zIndex(zIndex)) {
        Row(modifier = Modifier.matchParentSize().padding(end = buttonGap).background(Color.Transparent), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(buttonSize).clip(RoundedCornerShape(12.dp)).background(Color(0xFF4A6583)).clickable { onDuplicateRequest() }, contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(androidx.compose.material.icons.Icons.Filled.ContentCopy, contentDescription = "복사", tint = Color.White, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.height(2.dp))
                    Text("복사", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(modifier = Modifier.width(buttonGap))
            Box(modifier = Modifier.size(buttonSize).clip(RoundedCornerShape(12.dp)).background(if(isDarkMode) Color(0xFFEF5350) else Color(0xFFD32F2F)).clickable { onDeleteRequest() }, contentAlignment = Alignment.Center) {
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
                        onDragStart = { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); onDragStart(index) },
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
            elevation = CardDefaults.cardElevation(defaultElevation = if (isDragging) 8.dp else 1.dp), shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = cardBgColor)
        ) {
            Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min).clickable { if (isSwipedOpen) onSwipeStateChange(false) else onSettingsClick() }, verticalAlignment = Alignment.CenterVertically) {
                Spacer(modifier = Modifier.width(20.dp))
                Box(modifier = Modifier.weight(1f).fillMaxHeight().padding(vertical = 20.dp), contentAlignment = Alignment.CenterStart) {
                    Text(botName, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = textColor)
                }
                Box(modifier = Modifier.width(1.dp).fillMaxHeight().padding(vertical = 12.dp).background(dividerColor))
                Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Switch(
                        checked = isRunning,
                        onCheckedChange = {
                            isRunning = it; botPref.edit().putBoolean("is_running", it).apply()
                            val serviceIntent = Intent(context, BotService::class.java).apply { putExtra("BOT_ID", botId); putExtra("COOKIE", botPref.getString("saved_cookie", "")); action = if (isRunning) "START" else "STOP" }
                            if (isRunning && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(serviceIntent) else context.startService(serviceIntent)
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PastelNavy, uncheckedThumbColor = if(isDarkMode) Color.LightGray else Color.White, uncheckedTrackColor = if(isDarkMode) Color(0xFF555555) else Color.LightGray, uncheckedBorderColor = Color.Transparent),
                        modifier = Modifier.scale(0.8f)
                    )
                }
            }
        }
    }
}