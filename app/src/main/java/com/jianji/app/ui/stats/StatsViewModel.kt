package com.jianji.app.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jianji.app.data.model.RecordType
import com.jianji.app.data.repository.LedgerRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.YearMonth

data class CatShare(
    val categoryId: String,
    val name: String,
    val emoji: String,
    val totalMinor: Long,
    /** 0..1 占比 */
    val share: Float,
)

data class DayBar(
    val dayOfMonth: Int,
    val totalMinor: Long,
)

data class StatsUiState(
    val month: YearMonth = YearMonth.now(),
    val type: RecordType = RecordType.EXPENSE,
    val monthExpenseMinor: Long = 0L,
    val monthIncomeMinor: Long = 0L,
    /** 当前口径总额 */
    val totalMinor: Long = 0L,
    val shares: List<CatShare> = emptyList(),
    val daily: List<DayBar> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModel(private val repo: LedgerRepository) : ViewModel() {

    private val month = MutableStateFlow(YearMonth.now())
    private val type = MutableStateFlow(RecordType.EXPENSE)

    val ui: StateFlow<StatsUiState> =
        combine(month, type) { m, t -> m to t }
            .flatMapLatest { (m, t) ->
                combine(
                    repo.statsFlow(m, t),
                    repo.statsFlow(m, RecordType.EXPENSE),
                    repo.statsFlow(m, RecordType.INCOME),
                ) { cur, exp, inc ->
                    val total = cur.totalMinor
                    StatsUiState(
                        month = m,
                        type = t,
                        monthExpenseMinor = exp.totalMinor,
                        monthIncomeMinor = inc.totalMinor,
                        totalMinor = total,
                        shares = cur.categoryTotals.map {
                            CatShare(
                                categoryId = it.categoryId,
                                name = it.name,
                                emoji = it.emoji,
                                totalMinor = it.totalMinor,
                                share = if (total > 0) it.totalMinor.toFloat() / total else 0f,
                            )
                        },
                        daily = cur.dailyTotals.map {
                            DayBar(dayOfMonth = java.time.LocalDate.ofEpochDay(it.epochDay.toLong()).dayOfMonth, totalMinor = it.totalMinor)
                        },
                    )
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState())

    fun prevMonth() {
        month.value = month.value.minusMonths(1)
    }

    fun nextMonth() {
        month.value = month.value.plusMonths(1)
    }

    /** 年月选择器直选 */
    fun setMonth(m: YearMonth) {
        month.value = m
    }

    fun setType(t: RecordType) {
        type.value = t
    }
}
