package com.jianji.app.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.jianji.app.data.model.RecordType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

data class Settings(
    val appLock: Boolean = false,
    val recentExpenseCategoryIds: List<String> = emptyList(),
    val recentIncomeCategoryIds: List<String> = emptyList(),
) {
    fun recentCategoryIds(type: RecordType): List<String> =
        if (type == RecordType.EXPENSE) recentExpenseCategoryIds else recentIncomeCategoryIds
}

/** 单用户：无账号无登录，设置直接落 DataStore */
class SettingsStore(private val context: Context) {

    private object Keys {
        val APP_LOCK = booleanPreferencesKey("app_lock")
        val RECENT_EXPENSE = stringPreferencesKey("recent_expense_categories")
        val RECENT_INCOME = stringPreferencesKey("recent_income_categories")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            appLock = p[Keys.APP_LOCK] ?: false,
            recentExpenseCategoryIds = p[Keys.RECENT_EXPENSE].toRecentIds(),
            recentIncomeCategoryIds = p[Keys.RECENT_INCOME].toRecentIds(),
        )
    }

    suspend fun setAppLock(v: Boolean) {
        context.dataStore.edit { it[Keys.APP_LOCK] = v }
    }

    /** 成功保存记录后更新最近使用；收支各保留最多两排（8 个） */
    suspend fun recordCategoryUse(type: RecordType, categoryId: String) {
        context.dataStore.edit { p ->
            val key = if (type == RecordType.EXPENSE) Keys.RECENT_EXPENSE else Keys.RECENT_INCOME
            val updated = listOf(categoryId) + p[key].toRecentIds().filterNot { it == categoryId }
            p[key] = updated.take(8).joinToString(",")
        }
    }

    private fun String?.toRecentIds(): List<String> = orEmpty().split(',').filter { it.isNotBlank() }
}
