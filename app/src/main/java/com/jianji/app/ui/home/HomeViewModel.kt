package com.jianji.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jianji.app.data.entity.CategoryEntity
import com.jianji.app.data.entity.RecordEntity
import com.jianji.app.data.model.RecordType
import com.jianji.app.data.repository.LedgerRepository
import com.jianji.app.util.Dates
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.YearMonth
import java.util.TreeSet

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

/** 明细页扁平化后的列表项：年表头 / 月表头 / 日分组 */
sealed interface HomeListItem {
    data class YearHeader(val year: Int, val expanded: Boolean) : HomeListItem

    data class MonthHeader(
        val month: YearMonth,
        val expenseMinor: Long,
        val incomeMinor: Long,
        val expanded: Boolean,
    ) : HomeListItem

    data class Day(val section: DaySection) : HomeListItem
}

data class HomeUiState(
    val items: List<HomeListItem> = emptyList(),
    /** 本月汇总不受筛选影响（与原型一致：汇总卡始终显示整月） */
    val monthExpenseMinor: Long = 0L,
    val monthIncomeMinor: Long = 0L,
    val filter: HomeFilter = HomeFilter(),
    val categories: List<CategoryEntity> = emptyList(),
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val endReached: Boolean = false,
)

/**
 * 明细页：按「年 → 月 → 日」层级无限下拉，当月默认展开，历史年份/月份默认折叠。
 * 数据按月份分块向后加载（每次 [CHUNK_MONTHS] 个月），避免一次性读取全部历史。
 */
class HomeViewModel(private val repo: LedgerRepository) : ViewModel() {

    private data class LoadedMonth(val month: YearMonth, val records: List<RecordEntity>)

    private val _ui = MutableStateFlow(HomeUiState())
    val ui: StateFlow<HomeUiState> = _ui.asStateFlow()

    private var filter = HomeFilter()
    private var categories: List<CategoryEntity> = emptyList()

    /** 已取过数据的月份（含空月份），降序，first = 最新，last = 最旧 */
    private val fetchedMonths = TreeSet<YearMonth>(compareByDescending { it })
    private var loadedMonths: List<LoadedMonth> = emptyList()

    private var expandedYears: Set<Int> = setOf(YearMonth.now().year)
    private var expandedMonths: Set<YearMonth> = setOf(YearMonth.now())

    private var startMonth: YearMonth = YearMonth.now()
    private var earliestMonth: YearMonth? = null
    private var endReached = false
    private var ready = false

    private val mutex = Mutex()
    private var filterJob: Job? = null

    /** 筛选版本号：筛选变化后让进行中的滚动加载尽快让出，避免旧条件继续读库 */
    private var filterVersion = 0

    init {
        // 分类或记录变化都触发刷新：分类变化只需重算展示，记录变化需重读已加载分块
        viewModelScope.launch {
            combine(repo.categoriesFlow(), repo.lastRecordChangeFlow()) { cats, change -> cats to change }
                .distinctUntilChanged()
                .collect { (cats, _) ->
                    categories = cats
                    syncFromDb()
                }
        }
        viewModelScope.launch {
            repo.monthTotalsFlow(YearMonth.now()).collect { (expense, income) ->
                _ui.update { it.copy(monthExpenseMinor = expense, monthIncomeMinor = income) }
            }
        }
    }

    // ---------- 加载 ----------

    private suspend fun syncFromDb() {
        mutex.withLock {
            val latest = repo.latestRecordDay()?.let { YearMonth.from(Dates.ofEpochDay(it)) }
            val earliest = repo.earliestRecordDay()?.let { YearMonth.from(Dates.ofEpochDay(it)) }
            earliestMonth = earliest
            val start = maxOf(YearMonth.now(), latest ?: YearMonth.now())
            if (!ready || start != startMonth) {
                startMonth = start
                fetchedMonths.clear()
                loadedMonths = emptyList()
                endReached = earliest == null || filter.categoryIds?.isEmpty() == true
                expandedYears = expandedYears + start.year
                expandedMonths = expandedMonths + YearMonth.now()
                ready = true
                loadLoopLocked()
                rebuildState(loading = false, loadingMore = false)
            } else {
                reloadFetchedLocked()
                rebuildState(loading = false)
            }
        }
    }

    /** 用户滚动到底部时继续加载更早的月份 */
    fun loadMore() {
        val snapshot = _ui.value
        if (snapshot.loading || snapshot.loadingMore || snapshot.endReached || !ready) return
        _ui.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            mutex.withLock {
                val requestFilter = filter
                val requestVersion = filterVersion
                loadLoopLocked(requestVersion)
                // 加载途中筛选条件已变：交给筛选任务重置，避免旧结果串入
                if (requestFilter == filter) {
                    rebuildState(loadingMore = false)
                }
            }
        }
    }

    /** 循环加载分块，直到本块有数据或到达最早记录（筛选后可能存在大量空月份） */
    private suspend fun loadLoopLocked(version: Int = filterVersion) {
        var guard = 0
        while (!endReached && guard++ < MAX_CHUNKS_PER_LOAD && version == filterVersion) {
            if (loadNextChunkLocked()) break
        }
    }

    private suspend fun loadNextChunkLocked(): Boolean {
        if (endReached) return false
        if (filter.categoryIds?.isEmpty() == true) {
            endReached = true
            return false
        }
        val floor = earliestMonth
        if (floor == null) {
            endReached = true
            return false
        }
        val chunkEnd = fetchedMonths.lastOrNull()?.minusMonths(1) ?: startMonth
        if (chunkEnd < floor) {
            endReached = true
            return false
        }
        val chunkStart = maxOf(chunkEnd.minusMonths((CHUNK_MONTHS - 1).toLong()), floor)
        val records = repo.recordsInRangeOnce(chunkStart, chunkEnd, filter.type, filter.keyword, filter.categoryIds)
        var month = chunkEnd
        while (month >= chunkStart) {
            fetchedMonths.add(month)
            month = month.minusMonths(1)
        }
        if (records.isNotEmpty()) {
            loadedMonths = (loadedMonths + groupByMonth(records)).sortedByDescending { it.month }
        }
        if (chunkStart <= floor) endReached = true
        return records.isNotEmpty()
    }

    /** 记录/分类变更后重读已取过数据的月份，保留用户滚动位置与展开状态 */
    private suspend fun reloadFetchedLocked() {
        if (fetchedMonths.isEmpty()) {
            endReached = earliestMonth == null
            return
        }
        val newest = fetchedMonths.first()
        val oldest = fetchedMonths.last()
        loadedMonths = groupByMonth(
            repo.recordsInRangeOnce(oldest, newest, filter.type, filter.keyword, filter.categoryIds),
        )
        endReached = earliestMonth == null ||
            filter.categoryIds?.isEmpty() == true ||
            oldest <= earliestMonth!!
    }

    private fun groupByMonth(records: List<RecordEntity>): List<LoadedMonth> =
        records.groupBy { YearMonth.from(Dates.ofEpochDay(it.dateEpochDay)) }
            .map { (month, recs) -> LoadedMonth(month, recs) }
            .sortedByDescending { it.month }

    // ---------- 筛选 ----------

    fun setType(t: RecordType?) {
        // 切换收支类型时清空已选分类，避免跨类型的分类条件导致结果恒为空
        applyFilter(
            if (filter.type == t) filter
            else filter.copy(type = t, categoryIds = null),
        )
    }

    fun setKeyword(k: String) {
        applyFilter(filter.copy(keyword = k), debounceMs = KEYWORD_DEBOUNCE_MS)
    }

    fun toggleCategory(id: String) {
        val cur = filter.categoryIds
        applyFilter(
            filter.copy(
                categoryIds = when {
                    cur == null -> listOf(id)
                    id in cur -> if (cur.size == 1) null else cur - id
                    else -> cur + id
                },
            ),
        )
    }

    fun clearCategoryFilter() {
        applyFilter(filter.copy(categoryIds = null))
    }

    fun clearFilter() {
        applyFilter(HomeFilter())
    }

    private fun applyFilter(next: HomeFilter, debounceMs: Long = 0) {
        if (next == filter) return
        filter = next
        filterVersion++
        filterJob?.cancel()
        _ui.update {
            it.copy(
                filter = filter,
                items = emptyList(),
                loading = true,
                loadingMore = false,
                endReached = false,
            )
        }
        filterJob = viewModelScope.launch {
            if (debounceMs > 0) delay(debounceMs)
            mutex.withLock {
                fetchedMonths.clear()
                loadedMonths = emptyList()
                earliestMonth = repo.earliestRecordDay()?.let { YearMonth.from(Dates.ofEpochDay(it)) }
                endReached = earliestMonth == null || filter.categoryIds?.isEmpty() == true
                loadLoopLocked()
                rebuildState(loading = false, loadingMore = false)
            }
        }
    }

    // ---------- 展开 / 折叠 ----------

    fun toggleYear(year: Int) {
        expandedYears = if (year in expandedYears) expandedYears - year else expandedYears + year
        rebuildState()
    }

    fun toggleMonth(month: YearMonth) {
        expandedMonths = if (month in expandedMonths) expandedMonths - month else expandedMonths + month
        rebuildState()
    }

    /** 新建记录保存后：确保目标年月已加载并展开，供页面定位到对应日分组 */
    suspend fun ensureVisible(date: LocalDate) {
        val target = YearMonth.from(date)
        mutex.withLock {
            val earliest = repo.earliestRecordDay()?.let { YearMonth.from(Dates.ofEpochDay(it)) }
            val latest = repo.latestRecordDay()?.let { YearMonth.from(Dates.ofEpochDay(it)) }
            earliestMonth = earliest
            val start = maxOf(YearMonth.now(), latest ?: YearMonth.now(), target)
            if (!ready || start != startMonth || fetchedMonths.isEmpty()) {
                startMonth = start
                fetchedMonths.clear()
                loadedMonths = emptyList()
                endReached = earliest == null || filter.categoryIds?.isEmpty() == true
                ready = true
            }
            var guard = 0
            while (target !in fetchedMonths && !endReached && guard++ < MAX_CHUNKS_PER_LOAD) {
                loadNextChunkLocked()
            }
            expandedYears = expandedYears + target.year
            expandedMonths = expandedMonths + target
            rebuildState(loading = false, loadingMore = false)
        }
    }

    // ---------- 状态拼装 ----------

    private fun rebuildState(loading: Boolean? = null, loadingMore: Boolean? = null) {
        val catById = categories.associateBy { it.id }
        val items = ArrayList<HomeListItem>()
        for ((year, months) in loadedMonths.groupBy { it.month.year }) {
            val yearExpanded = year in expandedYears
            items += HomeListItem.YearHeader(year, yearExpanded)
            if (!yearExpanded) continue
            for (loaded in months) {
                val monthExpanded = loaded.month in expandedMonths
                items += HomeListItem.MonthHeader(
                    month = loaded.month,
                    expenseMinor = loaded.records
                        .filter { it.type == RecordType.EXPENSE }
                        .sumOf { it.amountMinor },
                    incomeMinor = loaded.records
                        .filter { it.type == RecordType.INCOME }
                        .sumOf { it.amountMinor },
                    expanded = monthExpanded,
                )
                if (!monthExpanded) continue
                items += daySections(loaded.records, catById).map { HomeListItem.Day(it) }
            }
        }
        _ui.update {
            it.copy(
                items = items,
                filter = filter,
                categories = categories,
                loading = loading ?: it.loading,
                loadingMore = loadingMore ?: it.loadingMore,
                endReached = endReached,
            )
        }
    }

    private fun daySections(
        records: List<RecordEntity>,
        catById: Map<String, CategoryEntity>,
    ): List<DaySection> =
        records.groupBy { it.dateEpochDay }
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

    private companion object {
        /** 每次向后加载的月份数 */
        const val CHUNK_MONTHS = 6

        /** 单次触发最多连续加载的分块数，避免异常情况下长时间占用 */
        const val MAX_CHUNKS_PER_LOAD = 60

        const val KEYWORD_DEBOUNCE_MS = 300L
    }
}
