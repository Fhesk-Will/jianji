package com.jianji.app.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jianji.app.JianJiApp
import com.jianji.app.data.model.RecordType
import com.jianji.app.ui.components.EmojiBadge
import com.jianji.app.ui.components.MonthBar
import com.jianji.app.ui.components.SegTabs
import com.jianji.app.ui.theme.Accent
import com.jianji.app.ui.theme.Income
import com.jianji.app.ui.theme.Ink
import com.jianji.app.ui.theme.Sub
import com.jianji.app.util.Dates
import com.jianji.app.util.Money
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.time.YearMonth

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onAdd: () -> Unit,
    onEdit: (String) -> Unit,
    /** 新建记录保存后的落点日期（epochDay）；编辑、取消、删除时为 null */
    locateEpochDay: Int? = null,
    onLocateConsumed: () -> Unit = {},
) {
    val app = LocalContext.current.applicationContext as JianJiApp
    val vm: HomeViewModel = viewModel(initializer = { HomeViewModel(app.container.repo) })
    val state by vm.ui.collectAsStateWithLifecycle()
    var showFilter by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // 新建保存后：先切到该记录所在月份，再把当天分组顶到列表最上方
    LaunchedEffect(locateEpochDay) {
        val epochDay = locateEpochDay ?: return@LaunchedEffect
        val target = Dates.ofEpochDay(epochDay)
        val targetMonth = YearMonth.from(target)
        if (state.month != targetMonth) vm.setMonth(targetMonth)
        // 切月后数据异步到达；等该日分组出现再定位，找不到（如被筛选隐藏）则回顶
        val index = withTimeoutOrNull(1_000) {
            snapshotFlow { state.sections.indexOfFirst { it.date == target } }
                .first { it >= 0 }
        }
        listState.scrollToItem(index ?: 0)
        onLocateConsumed()
    }

    Column(Modifier.fillMaxSize()) {
        MonthBar(
            month = state.month,
            onPick = vm::setMonth,
            onPrev = vm::prevMonth,
            onNext = vm::nextMonth,
            trailing = {
                IconButton(onClick = { showFilter = true }) {
                    Icon(Icons.Filled.Search, contentDescription = "筛选")
                }
            },
        )

        // 本月汇总（始终整月口径）
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surface),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp),
        ) {
            Row(Modifier.padding(vertical = 14.dp)) {
                SummaryCell("支出", Money.yuan(state.monthExpenseMinor), Ink)
                SummaryCell("收入", Money.yuan(state.monthIncomeMinor), Income)
                SummaryCell("结余", Money.yuan(state.monthIncomeMinor - state.monthExpenseMinor), Ink)
            }
        }

        if (state.filter.active) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp),
            ) {
                Text("已按条件筛选", fontSize = 12.sp, color = Sub)
                TextButton(onClick = vm::clearFilter) { Text("清除") }
            }
        }

        Box(Modifier.weight(1f)) {
            if (state.sections.isEmpty() && !state.filter.active) {
                EmptyMonth(onAdd)
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    state.sections.forEach { section ->
                        item(key = "sec-${section.date}") {
                            Card(
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surface,
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 13.dp),
                            ) {
                                Column {
                                    // 日头
                                    Row(
                                        Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 2.dp),
                                    ) {
                                        Text(
                                            Dates.dayLabel(section.date),
                                            fontSize = 12.sp,
                                            color = Sub,
                                            fontWeight = FontWeight.Medium,
                                        )
                                        Spacer(Modifier.weight(1f))
                                        Text(
                                            dayTotalLabel(section),
                                            fontSize = 12.sp,
                                            color = Sub,
                                        )
                                    }
                                    section.rows.forEach { row ->
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { onEdit(row.id) }
                                                .padding(horizontal = 16.dp, vertical = 8.dp),
                                        ) {
                                            EmojiBadge(row.emoji)
                                            Column(
                                                Modifier
                                                    .weight(1f)
                                                    .padding(start = 12.dp),
                                            ) {
                                                Text(row.categoryName, fontSize = 14.sp, color = Ink)
                                                if (row.note.isNotBlank()) {
                                                    Text(
                                                        row.note,
                                                        fontSize = 11.5.sp,
                                                        color = Sub,
                                                        maxLines = 1,
                                                    )
                                                }
                                            }
                                            Text(
                                                Money.signed(row.amountMinor, row.isExpense),
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = if (row.isExpense) Ink else Income,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            ExtendedFloatingActionButton(
                onClick = onAdd,
                containerColor = Accent,
                contentColor = Ink,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.size(6.dp))
                Text("记一笔")
            }
        }
    }

    if (showFilter) {
        ModalBottomSheet(
            onDismissRequest = { showFilter = false },
            // 分类较多时直接全屏展开，配合内部滚动一屏可见更多分类
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            FilterSheet(
                state = state,
                vm = vm,
                onApply = { showFilter = false },
            )
        }
    }
}

@Composable
private fun RowScope.SummaryCell(label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.weight(1f),
    ) {
        Text(label, fontSize = 11.sp, color = Sub)
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = color)
    }
}

private fun dayTotalLabel(s: DaySection): String {
    val sb = StringBuilder()
    if (s.dayExpenseMinor > 0) sb.append("支出 ").append(Money.yuan(s.dayExpenseMinor))
    if (s.dayIncomeMinor > 0) {
        if (sb.isNotEmpty()) sb.append(" · ")
        sb.append("收入 ").append(Money.yuan(s.dayIncomeMinor))
    }
    return sb.toString()
}

@Composable
private fun EmptyMonth(onAdd: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize().padding(bottom = 80.dp),
    ) {
        Text("🧾", fontSize = 44.sp)
        Spacer(Modifier.height(8.dp))
        Text("本月还没有记录", fontSize = 14.sp, color = Sub)
        Spacer(Modifier.height(12.dp))
        Button(onClick = onAdd) { Text("记一笔") }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FilterSheet(state: HomeUiState, vm: HomeViewModel, onApply: () -> Unit) {
    val type = state.filter.type
    // 分组完全基于用户实际分类（支持改名、自建、排序），不依赖内置默认分类
    val parents = state.categories
        .filter { it.parentId == null && (type == null || it.type == type) }
        .sortedWith(compareBy({ it.type }, { it.sortOrder }, { it.name }))
    val leavesByParent = state.categories
        .filter { it.parentId != null && (type == null || it.type == type) }
        .groupBy { it.parentId }
    // 父级被整体删除后可能残留孤儿二级分类，兜底单独展示，避免筛不到
    val parentIds = parents.map { it.id }.toSet()
    val orphans = state.categories
        .filter { it.parentId != null && (type == null || it.type == type) && it.parentId !in parentIds }
        .sortedWith(compareBy({ it.type }, { it.sortOrder }, { it.name }))

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp),
    ) {
        Text("筛选", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Ink)

        Text("类型", fontSize = 12.sp, color = Sub, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
        SegTabs(
            options = listOf("全部", "支出", "收入"),
            selected = state.filter.type?.let { if (it == RecordType.EXPENSE) 1 else 2 } ?: 0,
            onSelect = { vm.setType(when (it) { 1 -> RecordType.EXPENSE; 2 -> RecordType.INCOME; else -> null }) },
        )

        Text("分类", fontSize = 12.sp, color = Sub, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = state.filter.categoryIds == null,
                onClick = vm::clearCategoryFilter,
                label = { Text("全部") },
            )
        }

        parents.forEach { parent ->
            val children = leavesByParent[parent.id].orEmpty()
                .sortedWith(compareBy({ it.sortOrder }, { it.name }))
            if (children.isEmpty()) return@forEach
            Text(
                "${parent.emoji} ${parent.name}",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = Ink,
                modifier = Modifier.padding(top = 10.dp, bottom = 6.dp),
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                children.forEach { cat ->
                    FilterChip(
                        selected = state.filter.categoryIds?.contains(cat.id) == true,
                        onClick = { vm.toggleCategory(cat.id) },
                        label = { Text("${cat.emoji} ${cat.name}") },
                    )
                }
            }
        }

        if (orphans.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 10.dp),
            ) {
                orphans.forEach { cat ->
                    FilterChip(
                        selected = state.filter.categoryIds?.contains(cat.id) == true,
                        onClick = { vm.toggleCategory(cat.id) },
                        label = { Text("${cat.emoji} ${cat.name}") },
                    )
                }
            }
        }

        OutlinedTextField(
            value = state.filter.keyword,
            onValueChange = vm::setKeyword,
            singleLine = true,
            placeholder = { Text("搜索备注关键词") },
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
        )

        Row(
            horizontalArrangement = Arrangement.End,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        ) {
            TextButton(onClick = vm::clearFilter) {
                Icon(Icons.Filled.Close, contentDescription = null, Modifier.size(14.dp))
                Spacer(Modifier.size(4.dp))
                Text("重置")
            }
            Button(onClick = onApply, modifier = Modifier.padding(start = 8.dp)) { Text("应用") }
        }
        Text(
            "时间范围默认跟随当前月份（顶部切换）",
            fontSize = 11.sp,
            color = androidx.compose.ui.graphics.Color(0xFF93A1B5),
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
    }
}
