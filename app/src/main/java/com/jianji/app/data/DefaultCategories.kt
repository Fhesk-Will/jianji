package com.jianji.app.data

import com.jianji.app.data.entity.CategoryEntity
import com.jianji.app.data.model.RecordType

/** 预置分类。报销是收入分类之一（需求：不单独做报销模块） */
object DefaultCategories {

    fun list(): List<CategoryEntity> {
        val now = System.currentTimeMillis()
        fun cat(id: String, name: String, emoji: String, type: RecordType, order: Int) =
            CategoryEntity(id = id, name = name, emoji = emoji, type = type, sortOrder = order, updatedAtEpochMs = now)

        return listOf(
            cat("cat-expense-food", "餐饮", "🍜", RecordType.EXPENSE, 0),
            cat("cat-expense-transport", "交通", "🚇", RecordType.EXPENSE, 1),
            cat("cat-expense-shopping", "购物", "🛒", RecordType.EXPENSE, 2),
            cat("cat-expense-housing", "居住", "🏠", RecordType.EXPENSE, 3),
            cat("cat-expense-fun", "娱乐", "🎬", RecordType.EXPENSE, 4),
            cat("cat-expense-medical", "医疗", "💊", RecordType.EXPENSE, 5),
            cat("cat-expense-edu", "教育", "📚", RecordType.EXPENSE, 6),
            cat(CategoryEntity.OTHER_EXPENSE, "其他", "✨", RecordType.EXPENSE, 7),
            cat("cat-income-salary", "工资", "💰", RecordType.INCOME, 0),
            cat("cat-income-bonus", "奖金", "🏆", RecordType.INCOME, 1),
            cat("cat-income-reimburse", "报销", "🧾", RecordType.INCOME, 2),
            cat("cat-income-invest", "理财", "📈", RecordType.INCOME, 3),
            cat("cat-income-side", "兼职", "💼", RecordType.INCOME, 4),
            cat("cat-income-redpacket", "红包", "🧧", RecordType.INCOME, 5),
            cat(CategoryEntity.OTHER_INCOME, "其他收入", "✨", RecordType.INCOME, 6),
        )
    }
}
