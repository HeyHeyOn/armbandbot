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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.Layout
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

/** True while composing inside [SettingsGroup]; rows then render flat instead of as separate cards. */
internal val LocalInSettingsGroup = staticCompositionLocalOf { false }

/** Small uppercase-style label above a group of settings. */
@Composable
fun SettingsSectionHeader(title: String, colors: BotColorScheme, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(
        modifier = modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = colors.subText, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

/**
 * One rounded card holding several setting rows separated by inset dividers,
 * so related settings read as a single list instead of a stack of cards.
 */
@Composable
fun SettingsGroup(colors: BotColorScheme, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val boundaries = remember { mutableListOf<Int>() }
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colors.card),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        CompositionLocalProvider(LocalInSettingsGroup provides true) {
            Layout(
                content = content,
                modifier = Modifier.fillMaxWidth().drawWithContent {
                    drawContent()
                    val inset = 72.dp.toPx()
                    val stroke = 1.dp.toPx()
                    boundaries.forEach { y ->
                        drawLine(colors.divider, Offset(inset, y.toFloat()), Offset(size.width, y.toFloat()), stroke)
                    }
                },
            ) { measurables, constraints ->
                val placeables = measurables.map { it.measure(constraints.copy(minHeight = 0)) }
                val height = placeables.sumOf { it.height }
                layout(constraints.maxWidth, height) {
                    boundaries.clear()
                    var y = 0
                    var placedVisible = false
                    placeables.forEach { placeable ->
                        if (placeable.height > 0) {
                            if (placedVisible) boundaries.add(y)
                            placedVisible = true
                        }
                        placeable.place(0, y)
                        y += placeable.height
                    }
                }
            }
        }
    }
}

/** Rounded tinted square behind a setting icon. */
@Composable
fun SettingIconBadge(icon: ImageVector, colors: BotColorScheme, tint: Color = colors.iconTint, container: Color = colors.accentContainer) {
    Box(
        modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
    }
}

/** Compact colored label such as "켜짐" or "실행 중". */
@Composable
fun StatusPill(text: String, color: Color, container: Color, modifier: Modifier = Modifier, showDot: Boolean = true) {
    Row(
        modifier = modifier.clip(RoundedCornerShape(50)).background(container).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showDot) {
            Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(color))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
fun ReadOnlyTextCard(
    title: String,
    content: String,
    colors: BotColorScheme,
    headerAction: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(16.dp)).clickable(enabled = enabled) { onClick() },
        colors = CardDefaults.cardColors(containerColor = colors.card),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = colors.text, modifier = Modifier.weight(1f))
                headerAction?.invoke()
                Icon(Icons.Filled.Edit, contentDescription = "수정", tint = colors.accent, modifier = Modifier.size(18.dp))
            }
            Spacer(modifier = Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surfaceMuted, RoundedCornerShape(10.dp))
                    .padding(12.dp)
            ) {
                Text(
                    text = if (content.isBlank()) "등록된 내용이 없습니다." else content,
                    color = if (content.isBlank()) colors.subText.copy(alpha = 0.6f) else colors.text,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 14.sp
                )
            }
        }
    }
}

@Composable
fun ModernSettingsSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    colors: BotColorScheme,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        modifier = modifier.scale(0.85f),
        colors = modernSwitchColors(colors),
    )
}

@Composable
fun modernSwitchColors(colors: BotColorScheme) = SwitchDefaults.colors(
    checkedThumbColor = Color.White,
    checkedTrackColor = PastelNavy,
    checkedBorderColor = Color.Transparent,
    uncheckedThumbColor = colors.switchUncheckedThumb,
    uncheckedTrackColor = colors.switchUncheckedTrack,
    uncheckedBorderColor = Color.Transparent,
)

/** Inline settings with a header control and optional body; never navigates. */
@Composable
fun ModernSettingsBlock(
    title: String,
    subtitle: String,
    icon: ImageVector,
    colors: BotColorScheme,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val header: @Composable ColumnScope.() -> Unit = {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(colors.accentContainer), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = colors.iconTint, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                // Keep the text layout width equal to its allocated header column.
                Text(title, modifier = Modifier.fillMaxWidth(), fontWeight = FontWeight.Bold, fontSize = 15.sp, color = colors.text)
                if (subtitle.isNotEmpty()) Text(subtitle, modifier = Modifier.fillMaxWidth(), fontSize = 12.sp, color = colors.subText)
            }
            Spacer(Modifier.width(16.dp))
            trailing()
        }
        content()
    }
    if (LocalInSettingsGroup.current) {
        Column(modifier.fillMaxWidth().padding(16.dp), content = header)
    } else {
        Card(
            modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = colors.card),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Column(Modifier.padding(16.dp), content = header)
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
    val row: @Composable () -> Unit = {
        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min).clickable { onClick() }, verticalAlignment = Alignment.CenterVertically) {
            Row(modifier = Modifier.weight(1f).padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                SettingIconBadge(icon, colors)
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = colors.text)
                    if (subtitle.isNotEmpty()) Text(subtitle, fontSize = 12.sp, color = colors.subText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (isChecked != null && onCheckedChange != null) {
                Box(modifier = Modifier.width(1.dp).fillMaxHeight().padding(vertical = 16.dp).background(colors.divider))
                Box(modifier = Modifier.padding(horizontal = 12.dp)) {
                    ModernSettingsSwitch(isChecked, { onCheckedChange(it) }, colors)
                }
            } else {
                Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = colors.subText.copy(alpha = 0.7f), modifier = Modifier.padding(end = 14.dp))
            }
        }
    }
    if (LocalInSettingsGroup.current) {
        row()
    } else {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .clip(RoundedCornerShape(16.dp)),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = colors.card),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) { row() }
    }
}
