package com.jianji.app.data

import com.jianji.app.data.entity.CategoryEntity
import com.jianji.app.data.model.RecordType

/** 两级预置分类；固定 id 保证升级与重启时不会重复插入 */
object DefaultCategories {

    /** v1 一级分类到 v2 二级分类的迁移映射 */
    val legacyCategoryMap = mapOf(
        "cat-expense-food" to "cat-expense-food-meal",
        "cat-expense-transport" to "cat-expense-travel-bus",
        "cat-expense-shopping" to "cat-expense-daily-goods",
        "cat-expense-housing" to "cat-expense-housing-rent",
        "cat-expense-fun" to "cat-expense-other-fun",
        "cat-expense-medical" to "cat-expense-other-medical",
        "cat-expense-edu" to "cat-expense-other-study",
        "cat-expense-other" to CategoryEntity.OTHER_EXPENSE,
        "cat-income-salary" to "cat-income-main-salary",
        "cat-income-bonus" to "cat-income-main-salary",
        "cat-income-reimburse" to "cat-income-reimburse-company",
        "cat-income-invest" to "cat-income-main-invest",
        "cat-income-side" to "cat-income-main-side",
        "cat-income-redpacket" to CategoryEntity.OTHER_INCOME,
        "cat-income-other" to CategoryEntity.OTHER_INCOME,
    )

    fun list(): List<CategoryEntity> {
        val now = System.currentTimeMillis()
        fun cat(
            id: String,
            name: String,
            emoji: String,
            type: RecordType,
            order: Int,
            parentId: String? = null,
        ) = CategoryEntity(id, name, emoji, type, order, now, parentId = parentId)

        return listOf(
            cat("cat-expense-food-group", "饮食", "🍜", RecordType.EXPENSE, 0),
            cat("cat-expense-food-meal", "餐饮", "🍲", RecordType.EXPENSE, 0, "cat-expense-food-group"),
            cat("cat-expense-food-fruit", "水果", "🍎", RecordType.EXPENSE, 1, "cat-expense-food-group"),
            cat("cat-expense-food-snack", "零食", "🍪", RecordType.EXPENSE, 2, "cat-expense-food-group"),
            cat("cat-expense-food-drink", "饮料", "🥤", RecordType.EXPENSE, 3, "cat-expense-food-group"),
            cat("cat-expense-travel-group", "出行", "🚇", RecordType.EXPENSE, 1),
            cat("cat-expense-travel-bike", "骑车", "🚲", RecordType.EXPENSE, 0, "cat-expense-travel-group"),
            cat("cat-expense-travel-taxi", "打车", "🚕", RecordType.EXPENSE, 1, "cat-expense-travel-group"),
            cat("cat-expense-travel-bus", "公交", "🚌", RecordType.EXPENSE, 2, "cat-expense-travel-group"),
            cat("cat-expense-travel-metro", "地铁", "🚇", RecordType.EXPENSE, 3, "cat-expense-travel-group"),
            cat("cat-expense-travel-flight", "飞机", "✈️", RecordType.EXPENSE, 4, "cat-expense-travel-group"),
            cat("cat-expense-housing-group", "住房", "🏠", RecordType.EXPENSE, 2),
            cat("cat-expense-housing-rent", "房租", "🏠", RecordType.EXPENSE, 0, "cat-expense-housing-group"),
            cat("cat-expense-housing-hotel", "酒店", "🏨", RecordType.EXPENSE, 1, "cat-expense-housing-group"),
            cat("cat-expense-housing-utilities", "水电", "💡", RecordType.EXPENSE, 2, "cat-expense-housing-group"),
            cat("cat-expense-housing-gas", "燃气", "🔥", RecordType.EXPENSE, 3, "cat-expense-housing-group"),
            cat("cat-expense-housing-internet", "网络", "🌐", RecordType.EXPENSE, 4, "cat-expense-housing-group"),
            cat("cat-expense-housing-phone", "话费", "📱", RecordType.EXPENSE, 5, "cat-expense-housing-group"),
            cat("cat-expense-daily-group", "日用", "🛒", RecordType.EXPENSE, 3),
            cat("cat-expense-daily-goods", "日用品", "🧻", RecordType.EXPENSE, 0, "cat-expense-daily-group"),
            cat("cat-expense-daily-clothes", "衣物", "👕", RecordType.EXPENSE, 1, "cat-expense-daily-group"),
            cat("cat-expense-daily-home", "家居用品", "🛋️", RecordType.EXPENSE, 2, "cat-expense-daily-group"),
            cat("cat-expense-daily-haircut", "理发", "💇", RecordType.EXPENSE, 3, "cat-expense-daily-group"),
            cat("cat-expense-electronics-group", "电子产品", "💻", RecordType.EXPENSE, 4),
            cat("cat-expense-electronics-hardware", "硬件", "💻", RecordType.EXPENSE, 0, "cat-expense-electronics-group"),
            cat("cat-expense-electronics-software", "软件", "📱", RecordType.EXPENSE, 1, "cat-expense-electronics-group"),
            cat(CategoryEntity.OTHER_EXPENSE_PARENT, "其它", "✨", RecordType.EXPENSE, 5),
            cat("cat-expense-other-medical", "医疗", "💊", RecordType.EXPENSE, 0, CategoryEntity.OTHER_EXPENSE_PARENT),
            cat("cat-expense-other-fun", "娱乐", "🎬", RecordType.EXPENSE, 1, CategoryEntity.OTHER_EXPENSE_PARENT),
            cat("cat-expense-other-study", "学习", "📚", RecordType.EXPENSE, 2, CategoryEntity.OTHER_EXPENSE_PARENT),
            cat("cat-expense-other-hobby", "爱好", "🎨", RecordType.EXPENSE, 3, CategoryEntity.OTHER_EXPENSE_PARENT),
            cat("cat-expense-other-finance", "理财", "📈", RecordType.EXPENSE, 4, CategoryEntity.OTHER_EXPENSE_PARENT),
            cat("cat-expense-other-gift", "人情", "🎁", RecordType.EXPENSE, 5, CategoryEntity.OTHER_EXPENSE_PARENT),
            cat("cat-expense-other-insurance", "保险", "🛡️", RecordType.EXPENSE, 6, CategoryEntity.OTHER_EXPENSE_PARENT),
            cat("cat-expense-other-fitness", "健身", "🏃", RecordType.EXPENSE, 7, CategoryEntity.OTHER_EXPENSE_PARENT),
            cat("cat-expense-other-work", "工作", "💼", RecordType.EXPENSE, 8, CategoryEntity.OTHER_EXPENSE_PARENT),
            cat(CategoryEntity.OTHER_EXPENSE, "其它", "✨", RecordType.EXPENSE, 9, CategoryEntity.OTHER_EXPENSE_PARENT),
            cat("cat-income-main-group", "收入", "💰", RecordType.INCOME, 0),
            cat("cat-income-main-salary", "工资收入", "💰", RecordType.INCOME, 0, "cat-income-main-group"),
            cat("cat-income-main-side", "兼职收入", "💼", RecordType.INCOME, 1, "cat-income-main-group"),
            cat("cat-income-main-invest", "理财收入", "📈", RecordType.INCOME, 2, "cat-income-main-group"),
            cat("cat-income-reimburse-group", "报销", "🧾", RecordType.INCOME, 1),
            cat("cat-income-reimburse-medical", "医保报销", "💊", RecordType.INCOME, 0, "cat-income-reimburse-group"),
            cat("cat-income-reimburse-insurance", "保险理赔", "🛡️", RecordType.INCOME, 1, "cat-income-reimburse-group"),
            cat("cat-income-reimburse-company", "公司报销", "🧾", RecordType.INCOME, 2, "cat-income-reimburse-group"),
            cat(CategoryEntity.OTHER_INCOME_PARENT, "其它", "✨", RecordType.INCOME, 2),
            cat("cat-income-other-refund", "购物退款", "🛒", RecordType.INCOME, 0, CategoryEntity.OTHER_INCOME_PARENT),
            cat(CategoryEntity.OTHER_INCOME, "其它", "✨", RecordType.INCOME, 1, CategoryEntity.OTHER_INCOME_PARENT),
        )
    }
}
