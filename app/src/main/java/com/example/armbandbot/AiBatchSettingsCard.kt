package com.heyheyon.armbandbot

import android.content.SharedPreferences
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.heyheyon.armbandbot.ui.PastelNavy
import com.heyheyon.armbandbot.ui.PastelNavyLight

fun SharedPreferences.safeInt(key: String, defaultValue: Int): Int = when (val value = all[key]) {
    is Int -> value
    is String -> value.toIntOrNull() ?: defaultValue
    is Long -> value.toInt()
    is Float -> value.toInt()
    else -> defaultValue
}

@Composable
fun AiBatchSettingsCard(
    isDarkMode: Boolean,
    cardColor: Color,
    textColor: Color,
    subTextColor: Color,
    maxPostsText: String,
    maxWaitSecText: String,
    maxWeightText: String,
    timeoutSecText: String,
    onEdit: (type: String, value: String) -> Unit,
) {
    val buttonContainerColor = if (isDarkMode) Color(0xFF37474F) else PastelNavyLight
    val buttonContentColor = if (isDarkMode) Color.White else PastelNavy
    Card(
        colors = CardDefaults.cardColors(containerColor = cardColor),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.padding(bottom = 12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("배치 기준", fontWeight = FontWeight.Bold, color = textColor)
            Text("용량 중심 + 글 수/시간 보조 기준으로 배치를 발사합니다.", fontSize = 12.sp, color = subTextColor)
            Button(onClick = { onEdit("ai_filter_batch_max_posts", maxPostsText) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = buttonContainerColor, contentColor = buttonContentColor)) { Text("최대 글 수: $maxPostsText") }
            Button(onClick = { onEdit("ai_filter_batch_max_wait_sec", maxWaitSecText) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = buttonContainerColor, contentColor = buttonContentColor)) { Text("최대 대기 시간(초): $maxWaitSecText") }
            Button(onClick = { onEdit("ai_filter_batch_max_weight", maxWeightText) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = buttonContainerColor, contentColor = buttonContentColor)) { Text("최대 누적 용량: $maxWeightText") }
            Button(onClick = { onEdit("ai_filter_timeout_sec", timeoutSecText) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = buttonContainerColor, contentColor = buttonContentColor)) { Text("호출 타임아웃(초): $timeoutSecText") }
        }
    }
}
