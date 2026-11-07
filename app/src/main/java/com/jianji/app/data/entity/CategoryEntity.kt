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
    indices = [Index("type"), Index("updatedAtEpochMs"), Index("parentId")],
)
data class CategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    val emoji: String,
    val type: RecordType,
    val sortOrder: Int,
    val updatedAtEpochMs: Long,
    val isDeleted: Boolean = false,
    /** null = 一级分类；否则为所属一级分类 id，记账记录关联二级分类 */
    val parentId: String? = null,
) {
    companion object {
        /** 删除分类时历史记录归入同类型的「其它」二级分类 */
        const val OTHER_EXPENSE_PARENT = "cat-expense-other-group"
        const val OTHER_INCOME_PARENT = "cat-income-other-group"
        const val OTHER_EXPENSE = "cat-expense-other-misc"
        const val OTHER_INCOME = "cat-income-other-misc"
    }
}
