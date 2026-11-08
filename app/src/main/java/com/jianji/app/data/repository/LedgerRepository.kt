package com.jianji.app.data.repository

import com.jianji.app.data.DefaultCategories
import com.jianji.app.data.dao.CategoryTotal
import com.jianji.app.data.dao.DailyTotal
import com.jianji.app.data.db.AppDatabase
import com.jianji.app.data.entity.CategoryEntity
import com.jianji.app.data.entity.RecordEntity
import com.jianji.app.data.model.RecordType
import com.jianji.app.util.Dates
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

/**
 * 仓库层：DAO 之上薄封装。
 * UI 只跟 Repository 打交道，便于将来替换数据源（如接入同步层）。
 */
class LedgerRepository(private val db: AppDatabase) {

    private val recordDao = db.recordDao()
    private val categoryDao = db.categoryDao()

    // ---------- 读 ----------

    /**
     * 明细流。categoryIds 为 null 表示不按分类过滤；
     * 空列表表示"选中了分类但一个都没选"——结果为空。
     */
    fun recordsFlow(
        month: YearMonth,
        type: RecordType?,
        keyword: String?,
        categoryIds: List<String>?,
    ): Flow<List<RecordEntity>> {
        val (from, to) = Dates.monthRange(month)
        val kw = keyword?.ifBlank { null }
        return when {
            categoryIds == null -> recordDao.recordsBetween(from, to, type, kw)
            categoryIds.isEmpty() -> flowOf(emptyList())
            else -> recordDao.recordsBetweenInCategories(from, to, type, kw, categoryIds)
        }
    }

    fun categoriesFlow(type: RecordType? = null): Flow<List<CategoryEntity>> =
        if (type == null) categoryDao.active() else categoryDao.byType(type)

    fun categoryCountsFlow(): Flow<Map<String, Int>> =
        categoryDao.countsByCategory().map { list -> list.associate { it.categoryId to it.count } }

    /** 明细页一屏数据：过滤后的流水 + 全部分类 + 本月口径合计（合计不受筛选影响） */
    data class MonthPage(
        val records: List<RecordEntity>,
        val categories: List<CategoryEntity>,
        val expenseMinor: Long,
        val incomeMinor: Long,
    )

    fun monthPageFlow(
        month: YearMonth,
        type: RecordType?,
        keyword: String?,
        categoryIds: List<String>?,
    ): Flow<MonthPage> {
        val (from, to) = Dates.monthRange(month)
        return combine(
            recordsFlow(month, type, keyword, categoryIds),
            categoryDao.active(),
            recordDao.sumBetween(from, to, RecordType.EXPENSE),
            recordDao.sumBetween(from, to, RecordType.INCOME),
        ) { records, cats, expense, income ->
            MonthPage(records, cats, expense ?: 0L, income ?: 0L)
        }
    }

    /** 统计页一屏数据 */
    data class StatsPage(
        val totalMinor: Long,
        val categoryTotals: List<CategoryTotal>,
        val dailyTotals: List<DailyTotal>,
    )

    fun statsFlow(month: YearMonth, type: RecordType): Flow<StatsPage> {
        val (from, to) = Dates.monthRange(month)
        return combine(
            recordDao.sumBetween(from, to, type),
            recordDao.categoryTotals(from, to, type),
            recordDao.dailyTotals(from, to, type),
        ) { sum, cats, daily ->
            StatsPage(sum ?: 0L, cats, daily)
        }
    }

    suspend fun recordOnce(id: String): RecordEntity? = recordDao.byId(id)

    // ---------- 写 ----------

    suspend fun addRecord(
        type: RecordType,
        amountMinor: Long,
        categoryId: String,
        date: LocalDate,
        note: String,
    ) {
        val now = System.currentTimeMillis()
        recordDao.upsert(
            RecordEntity(
                id = UUID.randomUUID().toString(),
                type = type,
                amountMinor = amountMinor,
                categoryId = categoryId,
                dateEpochDay = Dates.epochDay(date),
                note = note.trim(),
                createdAtEpochMs = now,
                updatedAtEpochMs = now,
            ),
        )
    }

    suspend fun updateRecord(
        id: String,
        type: RecordType,
        amountMinor: Long,
        categoryId: String,
        date: LocalDate,
        note: String,
    ) {
        val old = recordDao.byId(id) ?: return
        recordDao.upsert(
            old.copy(
                type = type,
                amountMinor = amountMinor,
                categoryId = categoryId,
                dateEpochDay = Dates.epochDay(date),
                note = note.trim(),
                updatedAtEpochMs = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun softDeleteRecord(id: String) =
        recordDao.softDelete(id, System.currentTimeMillis())

    // ---------- 分类 ----------

    suspend fun addCategory(name: String, emoji: String, type: RecordType, parentId: String? = null) {
        val now = System.currentTimeMillis()
        val nextOrder = categoryDao.snapshot()
            .filter { !it.isDeleted && it.type == type && it.parentId == parentId }
            .maxOfOrNull { it.sortOrder }?.plus(1) ?: 0
        categoryDao.upsert(
            CategoryEntity(
                id = "cat-" + UUID.randomUUID().toString(),
                name = name.trim(),
                emoji = emoji,
                type = type,
                sortOrder = nextOrder,
                updatedAtEpochMs = now,
                parentId = parentId,
            ),
        )
    }

    suspend fun renameCategory(id: String, name: String, emoji: String) =
        categoryDao.update(id, name.trim(), emoji, System.currentTimeMillis())

    suspend fun moveCategory(id: String, direction: Int) = categoryDao.move(id, direction)

    /** 删除一级分类会同时删除其子分类；历史记录归入同类型的「其它」 */
    suspend fun deleteCategory(id: String) {
        val cat = categoryDao.byId(id) ?: return
        val protectedIds = setOf(
            CategoryEntity.OTHER_EXPENSE,
            CategoryEntity.OTHER_INCOME,
            CategoryEntity.OTHER_EXPENSE_PARENT,
            CategoryEntity.OTHER_INCOME_PARENT,
        )
        if (cat.id in protectedIds) return
        val ids = if (cat.parentId == null) {
            listOf(cat.id) + categoryDao.snapshot().filter { it.parentId == cat.id }.map { it.id }
        } else {
            listOf(cat.id)
        }
        val fallback =
            if (cat.type == RecordType.EXPENSE) CategoryEntity.OTHER_EXPENSE else CategoryEntity.OTHER_INCOME
        val now = System.currentTimeMillis()
        recordDao.reassignCategories(ids, fallback, now)
        categoryDao.softDeleteAll(ids, now)
    }

    /** 启动时幂等补齐预置分类，不覆盖用户改名或恢复已删除分类 */
    suspend fun seedDefaults() {
        categoryDao.insertMissing(DefaultCategories.list())
    }

    // ---------- 导出 / 导入 ----------

    suspend fun csvRows(fromDay: Int, toDay: Int) = recordDao.csvRows(fromDay, toDay)

    suspend fun snapshotCategories(): List<CategoryEntity> = categoryDao.snapshot()
}
