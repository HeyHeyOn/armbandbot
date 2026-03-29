package com.heyheyon.armbandbot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun DelayInputRow(
    label: String,
    minText: String,
    maxText: String,
    unit: String,
    onMinChange: (String) -> Unit,
    onMaxChange: (String) -> Unit,
    colors: BotColorScheme
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 14.sp, modifier = Modifier.weight(1f), color = colors.text)
        OutlinedTextField(
            value = minText,
            onValueChange = { if (it.isEmpty() || it.matches(Regex("^\\d*\\.?\\d*$"))) onMinChange(it) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.Center),
            modifier = Modifier.width(70.dp).height(50.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = colors.text, unfocusedTextColor = colors.text)
        )
        Text(" ~ ", fontSize = 16.sp, modifier = Modifier.padding(horizontal = 4.dp), color = colors.subText)
        OutlinedTextField(
            value = maxText,
            onValueChange = { if (it.isEmpty() || it.matches(Regex("^\\d*\\.?\\d*$"))) onMaxChange(it) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.Center),
            modifier = Modifier.width(70.dp).height(50.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = colors.text, unfocusedTextColor = colors.text)
        )
        Text(" $unit", fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp).width(30.dp), color = colors.subText)
    }
}

@Composable
fun ReadOnlyTextCard(
    title: String,
    content: String,
    colors: BotColorScheme,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = colors.card),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontWeight = FontWeight.Bold, color = PastelNavy, modifier = Modifier.weight(1f))
                Icon(Icons.Filled.Edit, contentDescription = "수정", tint = Color.Gray, modifier = Modifier.size(18.dp))
            }
            Spacer(modifier = Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.bg, RoundedCornerShape(8.dp))
                    .padding(12.dp)
            ) {
                Text(
                    text = if (content.isBlank()) "등록된 내용이 없습니다." else content,
                    color = if (content.isBlank()) Color.LightGray else colors.text,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 14.sp
                )
            }
        }
    }
}

@Composable
fun ModernSettingItem(
    title: String,
    subtitle: String,
    icon: ImageVector,
    colors: BotColorScheme,
    isChecked: Boolean? = null,
    onCheckedChange: ((Boolean) -> Unit)? = null,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = colors.card),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
            Row(modifier = Modifier.weight(1f).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = colors.iconTint, modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = colors.text)
                    if (subtitle.isNotEmpty()) Text(subtitle, fontSize = 12.sp, color = colors.subText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (isChecked != null && onCheckedChange != null) {
                Box(modifier = Modifier.width(1.dp).fillMaxHeight().padding(vertical = 12.dp).background(colors.divider))
                Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Switch(
                        checked = isChecked,
                        onCheckedChange = { onCheckedChange(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = PastelNavy,
                            uncheckedThumbColor = colors.switchUncheckedThumb,
                            uncheckedTrackColor = colors.switchUncheckedTrack,
                            uncheckedBorderColor = Color.Transparent
                        ),
                        modifier = Modifier.scale(0.8f)
                    )
                }
            } else {
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = Color.LightGray, modifier = Modifier.padding(end = 16.dp))
            }
        }
    }
}
