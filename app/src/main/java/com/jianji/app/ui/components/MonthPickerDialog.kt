package com.jianji.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jianji.app.ui.theme.Ink
import com.jianji.app.ui.theme.Sub
import java.time.YearMonth

/** 年月选择器：年份翻页 + 12 宫格月份，点选即回调 */
@Composable
fun MonthPickerDialog(
    current: YearMonth,
    onDismiss: () -> Unit,
    onSelect: (YearMonth) -> Unit,
) {
    var year by remember { mutableStateOf(current.year) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择月份", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { year-- }) {
                        Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = "上一年")
                    }
                    Text(
                        "${year}年",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Ink,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { year++ }) {
                        Icon(Icons.Filled.KeyboardArrowRight, contentDescription = "下一年")
                    }
                }
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 10.dp),
                ) {
                    (1..12).chunked(4).forEach { rowMonths ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            rowMonths.forEach { m ->
                                val ym = YearMonth.of(year, m)
                                val selected = ym == current
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(42.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(
                                            if (selected) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.surfaceVariant
                                            },
                                        )
                                        .clickable { onSelect(ym) },
                                ) {
                                    Text(
                                        "${m}月",
                                        fontSize = 13.5.sp,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (selected) {
                                            MaterialTheme.colorScheme.onPrimary
                                        } else {
                                            Ink
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                Text(
                    "当前：${current.year}年${current.monthValue}月",
                    fontSize = 11.sp,
                    color = Sub,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}
