package com.jianji.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.jianji.app.data.entity.RecordEntity
import com.jianji.app.data.model.RecordType
import kotlinx.coroutines.flow.Flow

/** 统计页：分类合计 */
data class CategoryTotal(
    val categoryId: String,
    val name: String,
    val emoji: String,
    val totalMinor: Long,
)

/** 统计页：单日合计 */
data class DailyTotal(
    val epochDay: Int,
    val totalMinor: Long,
)

/** CSV 导出行 */
data class CsvRow(
    val id: String,
    val type: RecordType,
    val amountMinor: Long,
    val dateEpochDay: Int,
    val note: String,
    val categoryName: String,
    val categoryEmoji: String,
)

@Dao
interface RecordDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: RecordEntity)

    /** 备份恢复：按 id 幂等合并 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(records: List<RecordEntity>)

    @Query("SELECT * FROM records WHERE id = :id")
    suspend fun byId(id: String): RecordEntity?

    /** 明细列表（无分类筛选版；type/keyword 传 null 表示不过滤） */
    @Query(
        """
        SELECT * FROM records
        WHERE dateEpochDay BETWEEN :from AND :to
          AND isDeleted = 0
          AND (:type IS NULL OR type = :type)
          AND (:keyword IS NULL OR note LIKE '%' || :keyword || '%')
        ORDER BY dateEpochDay DESC, createdAtEpochMs DESC
        """,
    )
    fun recordsBetween(
        from: Int,
        to: Int,
        type: RecordType?,
        keyword: String?,
    ): Flow<List<RecordEntity>>

    /** 明细列表（按分类多选筛选版；空列表由 Repository 拦截） */
    @Query(
        """
        SELECT * FROM records
        WHERE dateEpochDay BETWEEN :from AND :to
          AND isDeleted = 0
          AND (:type IS NULL OR type = :type)
          AND (:keyword IS NULL OR note LIKE '%' || :keyword || '%')
          AND categoryId IN (:categoryIds)
        ORDER BY dateEpochDay DESC, createdAtEpochMs DESC
        """,
    )
    fun recordsBetweenInCategories(
        from: Int,
        to: Int,
        type: RecordType?,
        keyword: String?,
        categoryIds: List<String>,
    ): Flow<List<RecordEntity>>

    @Query(
        "SELECT SUM(amountMinor) FROM records " +
            "WHERE dateEpochDay BETWEEN :from AND :to AND isDeleted = 0 AND type = :type",
    )
    fun sumBetween(from: Int, to: Int, type: RecordType): Flow<Long?>

    @Query(
        """
        SELECT r.categoryId AS categoryId, c.name AS name, c.emoji AS emoji,
               SUM(r.amountMinor) AS totalMinor
        FROM records r JOIN categories c ON c.id = r.categoryId
        WHERE r.dateEpochDay BETWEEN :from AND :to AND r.isDeleted = 0 AND r.type = :type
        GROUP BY r.categoryId, c.name, c.emoji
        ORDER BY totalMinor DESC
        """,
    )
    fun categoryTotals(from: Int, to: Int, type: RecordType): Flow<List<CategoryTotal>>

    @Query(
        """
        SELECT dateEpochDay AS epochDay, SUM(amountMinor) AS totalMinor
        FROM records
        WHERE dateEpochDay BETWEEN :from AND :to AND isDeleted = 0 AND type = :type
        GROUP BY dateEpochDay
        """,
    )
    fun dailyTotals(from: Int, to: Int, type: RecordType): Flow<List<DailyTotal>>

    /** 软删除（同步口子：保留墓碑） */
    @Query("UPDATE records SET isDeleted = 1, updatedAtEpochMs = :now WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)

    /** 删除分类时，其历史记录归入兜底分类 */
    @Query(
        "UPDATE records SET categoryId = :to, updatedAtEpochMs = :now " +
            "WHERE categoryId = :from AND isDeleted = 0",
    )
    suspend fun reassignCategory(from: String, to: String, now: Long)

    /** 全量快照（含软删除墓碑），用于 JSON 备份 */
    @Query("SELECT * FROM records")
    suspend fun snapshot(): List<RecordEntity>

    @Query(
        """
        SELECT r.id AS id, r.type AS type, r.amountMinor AS amountMinor,
               r.dateEpochDay AS dateEpochDay, r.note AS note,
               c.name AS categoryName, c.emoji AS categoryEmoji
        FROM records r JOIN categories c ON c.id = r.categoryId
        WHERE r.isDeleted = 0 AND r.dateEpochDay BETWEEN :from AND :to
        ORDER BY r.dateEpochDay, r.createdAtEpochMs
        """,
    )
    suspend fun csvRows(from: Int, to: Int): List<CsvRow>
}
