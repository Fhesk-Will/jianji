package com.jianji.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jianji.app.ui.theme.Ink
import com.jianji.app.ui.theme.Sub
import com.jianji.app.util.Dates
import java.time.YearMonth

/** 月份切换条：‹ 2026年11月 ›（点月份文字弹年月选择器） */
@Composable
fun MonthBar(
    month: YearMonth,
    onPick: (YearMonth) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
) {
    var showPicker by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .padding(start = 2.dp, end = 6.dp, top = 2.dp),
    ) {
        IconButton(onClick = onPrev) {
            Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = "上个月")
        }
        TextButton(onClick = { showPicker = true }) {
            Text(
                Dates.monthTitle(month),
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = Ink,
            )
        }
        IconButton(onClick = onNext) {
            Icon(Icons.Filled.KeyboardArrowRight, contentDescription = "下个月")
        }
        Spacer(Modifier.weight(1f))
        trailing()
    }
    if (showPicker) {
        MonthPickerDialog(
            current = month,
            onDismiss = { showPicker = false },
            onSelect = {
                showPicker = false
                onPick(it)
            },
        )
    }
}

/**
 * 二段 / 三段切换（支出|收入 等）。
 * filled = true 时选中项为主色实底（记一笔页），否则白底浮起（列表页）。
 */
@Composable
fun SegTabs(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(3.dp),
    ) {
        options.forEachIndexed { i, opt ->
            val on = i == selected
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .height(34.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(
                        when {
                            on && filled -> MaterialTheme.colorScheme.primary
                            on -> MaterialTheme.colorScheme.surface
                            else -> Color.Transparent
                        },
                    )
                    .clickable { onSelect(i) },
            ) {
                Text(
                    opt,
                    fontSize = 13.5.sp,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                    color = when {
                        on && filled -> MaterialTheme.colorScheme.onPrimary
                        on -> Ink
                        else -> Sub
                    },
                )
            }
        }
    }
}

/** 分类 emoji 圆角底座 */
@Composable
fun EmojiBadge(
    emoji: String,
    modifier: Modifier = Modifier,
    size: Dp = 38.dp,
    bg: Color = MaterialTheme.colorScheme.primaryContainer,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(12.dp))
            .background(bg),
    ) {
        Text(emoji, fontSize = (size.value * 0.5f).sp)
    }
}
