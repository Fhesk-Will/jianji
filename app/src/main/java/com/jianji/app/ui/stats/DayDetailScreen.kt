package com.jianji.app.ui.stats

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jianji.app.JianJiApp
import com.jianji.app.data.model.RecordType
import com.jianji.app.data.repository.LedgerRepository
import com.jianji.app.ui.components.EmojiBadge
import com.jianji.app.ui.theme.Income
import com.jianji.app.ui.theme.Ink
import com.jianji.app.ui.theme.Sub
import com.jianji.app.util.Dates
import com.jianji.app.util.Money
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate

data class DayDetailRow(
    val id: String,
    val emoji: String,
    val categoryName: String,
    val note: String,
    val amountMinor: Long,
    val isExpense: Boolean,
)

data class DayDetailUiState(
    val date: LocalDate = LocalDate.now(),
    val rows: List<DayDetailRow> = emptyList(),
    val expenseMinor: Long = 0L,
    val incomeMinor: Long = 0L,
)

class DayDetailViewModel(repo: LedgerRepository, epochDay: Int) : ViewModel() {

    val ui: StateFlow<DayDetailUiState> =
        repo.dayPageFlow(epochDay)
            .map { page ->
                val catById = page.categories.associateBy { it.id }
                DayDetailUiState(
                    date = Dates.ofEpochDay(epochDay),
                    rows = page.records.map { r ->
                        val cat = catById[r.categoryId]
                        DayDetailRow(
                            id = r.id,
                            emoji = cat?.emoji ?: "✨",
                            categoryName = cat?.name ?: "未分类",
                            note = r.note,
                            amountMinor = r.amountMinor,
                            isExpense = r.type == RecordType.EXPENSE,
                        )
                    },
                    expenseMinor = page.records
                        .filter { it.type == RecordType.EXPENSE }
                        .sumOf { it.amountMinor },
                    incomeMinor = page.records
                        .filter { it.type == RecordType.INCOME }
                        .sumOf { it.amountMinor },
                )
            }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                DayDetailUiState(date = Dates.ofEpochDay(epochDay)),
            )
}

/** 统计页点柱后进入：展示某一天的收支明细 */
@Composable
fun DayDetailScreen(
    epochDay: Int,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
) {
    val app = LocalContext.current.applicationContext as JianJiApp
    val vm: DayDetailViewModel = viewModel(
        key = "day-detail-$epochDay",
        initializer = { DayDetailViewModel(app.container.repo, epochDay) },
    )
    val state by vm.ui.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 4.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Column(Modifier.padding(start = 2.dp)) {
                Text(
                    Dates.dayLabel(state.date),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = Ink,
                )
                Text(summaryText(state), fontSize = 11.5.sp, color = Sub)
            }
        }

        if (state.rows.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("当天没有记录", fontSize = 13.sp, color = Sub)
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 13.dp),
                    ) {
                        Column {
                            state.rows.forEach { row ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onEdit(row.id) }
                                        .padding(horizontal = 16.dp, vertical = 9.dp),
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
}

private fun summaryText(state: DayDetailUiState): String {
    val sb = StringBuilder()
    if (state.expenseMinor > 0) sb.append("支出 ").append(Money.yuan(state.expenseMinor))
    if (state.incomeMinor > 0) {
        if (sb.isNotEmpty()) sb.append(" · ")
        sb.append("收入 ").append(Money.yuan(state.incomeMinor))
    }
    return sb.toString().ifEmpty { "无收支" }
}
