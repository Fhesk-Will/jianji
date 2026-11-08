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
import java.time.LocalDate
import java.time.YearMonth

data class CatShare(
    val categoryId: String,
    val name: String,
    val emoji: String,
    val totalMinor: Long,
    /** 0..1 占比 */
    val share: Float,
)

/** 柱状图单日的一段时间：颜色跟随当月分类占比列表，占比决定高度 */
data class DayBarSegment(
    /** 当月分类占比列表中的颜色序号 */
    val colorIndex: Int,
    /** 当天该分类占当天总额的比例 0..1 */
    val share: Float,
)

data class DayBar(
    val dayOfMonth: Int,
    val totalMinor: Long,
    /** 按占比从大到小排列（绘制时从上到下） */
    val segments: List<DayBarSegment> = emptyList(),
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
                    // 分类颜色与环形图/图例保持一致：按当月分类合计降序编号
                    val colorIndexById = cur.categoryTotals
                        .mapIndexed { i, c -> c.categoryId to i }
                        .toMap()
                    val catsByDay = cur.dailyCategoryTotals.groupBy { it.epochDay }
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
                        daily = cur.dailyTotals.map { day ->
                            val segments = catsByDay[day.epochDay].orEmpty()
                                .sortedByDescending { it.totalMinor }
                                .map { c ->
                                    DayBarSegment(
                                        colorIndex = colorIndexById[c.categoryId] ?: 0,
                                        share = if (day.totalMinor > 0) {
                                            c.totalMinor.toFloat() / day.totalMinor
                                        } else {
                                            0f
                                        },
                                    )
                                }
                            DayBar(
                                dayOfMonth = LocalDate.ofEpochDay(day.epochDay.toLong()).dayOfMonth,
                                totalMinor = day.totalMinor,
                                segments = segments,
                            )
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
