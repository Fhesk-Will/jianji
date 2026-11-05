package com.jianji.app.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.jianji.app.data.model.RecordType

/**
 * 分类。内置分类用固定可读 id（如 cat-expense-food），
 * 便于备份合并时保持稳定；用户自建分类用 "cat-" + UUID。
 */
@Entity(
    tableName = "categories",
    indices = [Index("type"), Index("updatedAtEpochMs")],
)
data class CategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    val emoji: String,
    val type: RecordType,
    val sortOrder: Int,
    val updatedAtEpochMs: Long,
    val isDeleted: Boolean = false,
) {
    companion object {
        /** 「其他」兜底分类：删除分类时历史记录归入这里 */
        const val OTHER_EXPENSE = "cat-expense-other"
        const val OTHER_INCOME = "cat-income-other"
    }
}
