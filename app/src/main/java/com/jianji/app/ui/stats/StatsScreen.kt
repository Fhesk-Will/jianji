package com.jianji.app.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jianji.app.JianJiApp
import com.jianji.app.data.model.RecordType
import com.jianji.app.ui.components.EmojiBadge
import com.jianji.app.ui.components.MonthBar
import com.jianji.app.ui.components.SegTabs
import com.jianji.app.ui.theme.ChartColors
import com.jianji.app.ui.theme.Faint
import com.jianji.app.ui.theme.Income
import com.jianji.app.ui.theme.Ink
import com.jianji.app.ui.theme.Line
import com.jianji.app.ui.theme.Sub
import com.jianji.app.util.Money

/** 统计页：全部图表本地 Canvas 绘制，无网络、无第三方库 */
@Composable
fun StatsScreen() {
    val app = LocalContext.current.applicationContext as JianJiApp
    val vm: StatsViewModel = viewModel(initializer = { StatsViewModel(app.container.repo) })
    val state by vm.ui.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        MonthBar(
            month = state.month,
            onPick = vm::setMonth,
            onPrev = vm::prevMonth,
            onNext = vm::nextMonth,
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            SegTabs(
                options = listOf("支出", "收入"),
                selected = if (state.type == RecordType.EXPENSE) 0 else 1,
                onSelect = { vm.setType(if (it == 0) RecordType.EXPENSE else RecordType.INCOME) },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.size(10.dp))
            Text(
                if (state.type == RecordType.EXPENSE) "收入 ${Money.yuan(state.monthIncomeMinor)}"
                else "支出 ${Money.yuan(state.monthExpenseMinor)}",
                fontSize = 12.sp,
                color = if (state.type == RecordType.EXPENSE) Income else Ink,
            )
        }

        LazyColumn(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item { DonutCard(state) }
            item { TrendCard(state) }
            item { RankCard(state) }
        }
    }
}

// ---------- 环形图 ----------

@Composable
private fun DonutCard(state: StatsUiState) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 13.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("分类占比", fontSize = 12.5.sp, color = Sub, fontWeight = FontWeight.SemiBold)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 10.dp),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(128.dp)) {
                    // 深色适配：getter 只能在 Composable 上下文取值，Canvas lambda 外先捕获
                    val chartColors = ChartColors
                    val trackColor = Line
                    Canvas(Modifier.size(128.dp)) {
                        val stroke = 22.dp.toPx()
                        val inset = stroke / 2 + 1f
                        val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
                        // 底环
                        drawArc(
                            color = trackColor,
                            startAngle = 0f,
                            sweepAngle = 360f,
                            useCenter = false,
                            topLeft = Offset(inset, inset),
                            size = arcSize,
                            style = Stroke(width = stroke, cap = StrokeCap.Butt),
                        )
                        var start = -90f
                        state.shares.forEachIndexed { i, s ->
                            val sweep = s.share * 360f
                            if (sweep > 0.5f) {
                                drawArc(
                                    color = chartColors[i % chartColors.size],
                                    startAngle = start,
                                    sweepAngle = sweep - 1.2f, // 段间留缝
                                    useCenter = false,
                                    topLeft = Offset(inset, inset),
                                    size = arcSize,
                                    style = Stroke(width = stroke, cap = StrokeCap.Butt),
                                )
                            }
                            start += sweep
                        }
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            Money.yuan(state.totalMinor),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Ink,
                        )
                        Text(
                            if (state.type == RecordType.EXPENSE) "本月支出" else "本月收入",
                            fontSize = 10.sp,
                            color = Sub,
                        )
                    }
                }
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(start = 14.dp),
                ) {
                    state.shares.take(7).forEachIndexed { i, s ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(9.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(ChartColors[i % ChartColors.size]),
                            )
                            Text(
                                "  ${s.name}",
                                fontSize = 11.5.sp,
                                color = Ink,
                                fontWeight = FontWeight.Medium,
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                "${(s.share * 100).toInt()}%",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Ink,
                            )
                            Spacer(Modifier.size(6.dp))
                            Text(
                                Money.format(s.totalMinor),
                                fontSize = 11.sp,
                                color = Sub,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------- 每日柱状图 ----------

@Composable
private fun TrendCard(state: StatsUiState) {
    val textMeasurer = rememberTextMeasurer()
    // remember 的计算 lambda 不是 Composable 上下文，颜色需先捕获
    val faintColor = Faint
    val labelStyle = remember { TextStyle(fontSize = 9.sp, color = faintColor) }
    // 深色适配：Canvas lambda 外捕获颜色
    val chartColors = ChartColors
    val trackColor = Line
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 13.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("每日${if (state.type == RecordType.EXPENSE) "支出" else "收入"}", fontSize = 12.5.sp, color = Sub, fontWeight = FontWeight.SemiBold)
            Canvas(
                Modifier.fillMaxWidth().height(104.dp).padding(top = 8.dp),
            ) {
                val days = state.month.lengthOfMonth()
                val byDay = HashMap<Int, Long>()
                state.daily.forEach { byDay[it.dayOfMonth] = it.totalMinor }
                val max = byDay.values.maxOrNull() ?: 0L
                val chartBottom = size.height - 14.dp.toPx()
                val slot = size.width / days
                val barWidth = (slot - 2.dp.toPx()).coerceAtLeast(3.dp.toPx())
                byDay.forEach { (d, v) ->
                    if (v <= 0) return@forEach
                    val h = if (max > 0) (v.toFloat() / max) * (chartBottom - 6.dp.toPx()) else 0f
                    val x = (d - 1) * slot + (slot - barWidth) / 2
                    drawRoundRect(
                        color = if (v == max) chartColors[0] else trackColor,
                        topLeft = Offset(x, chartBottom - h),
                        size = Size(barWidth, h.coerceAtLeast(2.dp.toPx())),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.5.dp.toPx()),
                    )
                }
                // x 轴刻度
                val marks = listOf(1, 5, 10, 15, 20, 25, days).distinct()
                marks.forEach { d ->
                    val label = textMeasurer.measure("$d", labelStyle)
                    val x = (d - 1) * slot + slot / 2 - label.size.width / 2f
                    drawText(label, topLeft = Offset(x, size.height - label.size.height.toFloat() - 1f))
                }
            }
            Text(
                "点柱跳转当日明细（二期）",
                fontSize = 10.5.sp,
                color = Faint,
                modifier = Modifier.padding(start = 2.dp),
            )
        }
    }
}

// ---------- 分类排行 ----------

@Composable
private fun RankCard(state: StatsUiState) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 13.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("分类排行", fontSize = 12.5.sp, color = Sub, fontWeight = FontWeight.SemiBold)
            if (state.shares.isEmpty()) {
                Text(
                    "本月暂无数据",
                    fontSize = 12.5.sp,
                    color = Sub,
                    modifier = Modifier.padding(vertical = 18.dp),
                )
            } else {
                val maxTotal = state.shares.first().totalMinor.coerceAtLeast(1L)
                state.shares.forEachIndexed { i, s ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 5.dp),
                    ) {
                        Text(
                            "${i + 1}",
                            fontSize = 11.sp,
                            color = Faint,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.size(16.dp),
                        )
                        EmojiBadge(s.emoji, size = 30.dp)
                        Column(
                            Modifier
                                .weight(1f)
                                .padding(start = 10.dp),
                        ) {
                            Row {
                                Text(s.name, fontSize = 12.5.sp, color = Ink, fontWeight = FontWeight.Medium)
                                Spacer(Modifier.weight(1f))
                                Text(Money.yuan(s.totalMinor), fontSize = 12.5.sp, color = Ink, fontWeight = FontWeight.SemiBold)
                            }
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp)
                                    .height(5.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(Line),
                            ) {
                                Box(
                                    Modifier
                                        .fillMaxWidth(s.totalMinor.toFloat() / maxTotal)
                                        .height(5.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(ChartColors[i % ChartColors.size]),
                                )
                            }
                        }
                        Text(
                            "${(s.share * 100).toInt()}%",
                            fontSize = 10.5.sp,
                            color = Sub,
                            modifier = Modifier.padding(start = 10.dp),
                        )
                    }
                }
                Text(
                    "共 ${state.shares.size} 个分类",
                    fontSize = 11.sp,
                    color = Faint,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
