package com.jianji.app.data.repository

import androidx.room.withTransaction
import com.jianji.app.data.DefaultCategories
import com.jianji.app.data.dao.CategoryTotal
import com.jianji.app.data.dao.DailyCategoryTotal
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

    /**
     * 明细页分块加载：按月份区间一次性读取过滤后的记录。
     * categoryIds 为 null 表示不按分类过滤；空列表表示结果必然为空。
     */
    suspend fun recordsInRangeOnce(
        from: YearMonth,
        to: YearMonth,
        type: RecordType?,
        keyword: String?,
        categoryIds: List<String>?,
    ): List<RecordEntity> {
        val (fromDay, _) = Dates.monthRange(from)
        val (_, toDay) = Dates.monthRange(to)
        val kw = keyword?.ifBlank { null }
        return when {
            categoryIds == null -> recordDao.recordsBetweenOnce(fromDay, toDay, type, kw)
            categoryIds.isEmpty() -> emptyList()
            else -> recordDao.recordsBetweenInCategoriesOnce(fromDay, toDay, type, kw, categoryIds)
        }
    }

    suspend fun earliestRecordDay(): Int? = recordDao.earliestEpochDay()

    suspend fun latestRecordDay(): Int? = recordDao.latestEpochDay()

    /** 记录表变更信号：明细页据此刷新已加载分块，不必整体重载 */
    fun lastRecordChangeFlow(): Flow<Long?> = recordDao.lastChangeFlow()

    /** 本月合计（明细页顶部汇总卡，始终整月口径、不受筛选影响） */
    fun monthTotalsFlow(month: YearMonth): Flow<Pair<Long, Long>> {
        val (from, to) = Dates.monthRange(month)
        return combine(
            recordDao.sumBetween(from, to, RecordType.EXPENSE),
            recordDao.sumBetween(from, to, RecordType.INCOME),
        ) { expense, income -> (expense ?: 0L) to (income ?: 0L) }
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
        val dailyCategoryTotals: List<DailyCategoryTotal>,
    )

    fun statsFlow(month: YearMonth, type: RecordType): Flow<StatsPage> {
        val (from, to) = Dates.monthRange(month)
        return combine(
            recordDao.sumBetween(from, to, type),
            recordDao.categoryTotals(from, to, type),
            recordDao.dailyTotals(from, to, type),
            recordDao.dailyCategoryTotals(from, to, type),
        ) { sum, cats, daily, dailyCats ->
            StatsPage(sum ?: 0L, cats, daily, dailyCats)
        }
    }

    /** 单日明细页一屏数据 */
    data class DayPage(
        val records: List<RecordEntity>,
        val categories: List<CategoryEntity>,
    )

    fun dayPageFlow(epochDay: Int): Flow<DayPage> =
        combine(
            recordDao.recordsOnDay(epochDay),
            categoryDao.active(),
        ) { records, cats ->
            DayPage(records, cats)
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
        val next = old.copy(
            type = type,
            amountMinor = amountMinor,
            categoryId = categoryId,
            dateEpochDay = Dates.epochDay(date),
            note = note.trim(),
            updatedAtEpochMs = System.currentTimeMillis(),
        )
        // A1 守卫：语义字段无变化不落库——不顶起 updatedAt，Room 不发变更通知，
        // 备份/小部件/同步下游全部安静（云同步 App 端文档 §2，第一层防线）。
        if (next.type == old.type &&
            next.amountMinor == old.amountMinor &&
            next.categoryId == old.categoryId &&
            next.dateEpochDay == old.dateEpochDay &&
            next.note == old.note
        ) return
        recordDao.upsert(next)
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

    suspend fun renameCategory(id: String, name: String, emoji: String) {
        val old = categoryDao.byId(id) ?: return
        // A1 守卫：名字与图标都没变就不更新（不顶起 updatedAt，理由同 updateRecord）
        if (old.name == name.trim() && old.emoji == emoji) return
        categoryDao.update(id, name.trim(), emoji, System.currentTimeMillis())
    }

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

    /** 云同步全量快照（含软删除墓碑）。 */
    suspend fun snapshotRecords(): List<RecordEntity> = recordDao.snapshot()

    suspend fun snapshotCategories(): List<CategoryEntity> = categoryDao.snapshot()

    fun recordsSnapshotFlow(): Flow<List<RecordEntity>> = recordDao.snapshotFlow()

    fun categoriesSnapshotFlow(): Flow<List<CategoryEntity>> = categoryDao.snapshotFlow()

    suspend fun liveRecordCount(): Int = recordDao.liveCount()

    /**
     * 云恢复：以目标云端版本替换本地账本（不是按 id 合并）。
     * 清表 + 插入在同一 Room 事务中，中断只会整体回滚，不会留下半套数据；
     * 设置项、服务器地址与密钥不在这两表里，天然不受影响。
     */
    suspend fun restoreLedger(records: List<RecordEntity>, categories: List<CategoryEntity>) {
        db.withTransaction {
            recordDao.deleteAll()
            categoryDao.deleteAll()
            recordDao.upsertAll(records)
            categoryDao.upsertAll(categories)
        }
    }
}
