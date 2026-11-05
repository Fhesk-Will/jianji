package com.jianji.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.jianji.app.data.entity.CategoryEntity
import com.jianji.app.data.model.RecordType
import kotlinx.coroutines.flow.Flow

/** 分类管理页：每个分类的记录条数 */
data class CategoryCount(
    val categoryId: String,
    val count: Int,
)

@Dao
interface CategoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(category: CategoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(categories: List<CategoryEntity>)

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun byId(id: String): CategoryEntity?

    @Query("SELECT * FROM categories WHERE type = :type AND isDeleted = 0 ORDER BY sortOrder, name")
    fun byType(type: RecordType): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE isDeleted = 0 ORDER BY type, sortOrder, name")
    fun active(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories")
    suspend fun snapshot(): List<CategoryEntity>

    @Query("UPDATE categories SET isDeleted = 1, updatedAtEpochMs = :now WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)

    @Query("UPDATE categories SET name = :name, emoji = :emoji, updatedAtEpochMs = :now WHERE id = :id")
    suspend fun update(id: String, name: String, emoji: String, now: Long)

    @Query(
        "SELECT categoryId AS categoryId, COUNT(*) AS count " +
            "FROM records WHERE isDeleted = 0 GROUP BY categoryId",
    )
    fun countsByCategory(): Flow<List<CategoryCount>>
}
