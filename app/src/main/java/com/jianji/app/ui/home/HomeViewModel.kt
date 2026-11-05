package com.jianji.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jianji.app.data.entity.CategoryEntity
import com.jianji.app.data.model.RecordType
import com.jianji.app.data.repository.LedgerRepository
import com.jianji.app.util.Dates
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.YearMonth

/** categoryIds: null = 不过滤；空列表 = 选了筛选但无选中项（结果为空） */
data class HomeFilter(
    val type: RecordType? = null,
    val keyword: String = "",
    val categoryIds: List<String>? = null,
) {
    val active: Boolean get() = this != HomeFilter()
}

data class HomeRow(
    val id: String,
    val emoji: String,
    val categoryName: String,
    val note: String,
    val amountMinor: Long,
    val isExpense: Boolean,
)

data class DaySection(
    val date: LocalDate,
    val dayExpenseMinor: Long,
    val dayIncomeMinor: Long,
    val rows: List<HomeRow>,
)

data class HomeUiState(
    val month: YearMonth = YearMonth.now(),
    val sections: List<DaySection> = emptyList(),
    /** 月度汇总不受筛选影响（与原型一致：汇总卡始终显示整月） */
    val monthExpenseMinor: Long = 0L,
    val monthIncomeMinor: Long = 0L,
    val filter: HomeFilter = HomeFilter(),
    val categories: List<CategoryEntity> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(private val repo: LedgerRepository) : ViewModel() {

    private val month = MutableStateFlow(YearMonth.now())
    private val filter = MutableStateFlow(HomeFilter())

    val ui: StateFlow<HomeUiState> =
        combine(month, filter) { m, f -> m to f }
            .flatMapLatest { (m, f) ->
                repo.monthPageFlow(m, f.type, f.keyword, f.categoryIds)
                    .map { page -> buildState(m, f, page) }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    private fun buildState(
        m: YearMonth,
        f: HomeFilter,
        page: LedgerRepository.MonthPage,
    ): HomeUiState {
        val catById = page.categories.associateBy { it.id }
        val sections = page.records
            .groupBy { it.dateEpochDay }
            .toSortedMap(compareByDescending { it })
            .map { (day, recs) ->
                DaySection(
                    date = Dates.ofEpochDay(day),
                    dayExpenseMinor = recs.filter { it.type == RecordType.EXPENSE }.sumOf { it.amountMinor },
                    dayIncomeMinor = recs.filter { it.type == RecordType.INCOME }.sumOf { it.amountMinor },
                    rows = recs.map { r ->
                        val cat = catById[r.categoryId]
                        HomeRow(
                            id = r.id,
                            emoji = cat?.emoji ?: "✨",
                            categoryName = cat?.name ?: "未分类",
                            note = r.note,
                            amountMinor = r.amountMinor,
                            isExpense = r.type == RecordType.EXPENSE,
                        )
                    },
                )
            }
        return HomeUiState(
            month = m,
            sections = sections,
            monthExpenseMinor = page.expenseMinor,
            monthIncomeMinor = page.incomeMinor,
            filter = f,
            categories = page.categories,
        )
    }

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

    fun setType(t: RecordType?) {
        filter.value = filter.value.copy(type = t)
    }

    fun setKeyword(k: String) {
        filter.value = filter.value.copy(keyword = k)
    }

    fun toggleCategory(id: String) {
        val cur = filter.value.categoryIds
        filter.value = filter.value.copy(
            categoryIds = when {
                cur == null -> listOf(id)
                id in cur -> if (cur.size == 1) null else cur - id
                else -> cur + id
            },
        )
    }

    fun clearCategoryFilter() {
        filter.value = filter.value.copy(categoryIds = null)
    }

    fun clearFilter() {
        filter.value = HomeFilter()
    }
}
