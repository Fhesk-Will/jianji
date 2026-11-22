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
    val syncEnabled: Boolean = false,
    val serverUrl: String = "",
    val syncKey: String = "",
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
        val SYNC_ENABLED = booleanPreferencesKey("sync_enabled")
        val SERVER_URL = stringPreferencesKey("sync_server_url")
        val SYNC_KEY = stringPreferencesKey("sync_key")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            appLock = p[Keys.APP_LOCK] ?: false,
            recentExpenseCategoryIds = p[Keys.RECENT_EXPENSE].toRecentIds(),
            recentIncomeCategoryIds = p[Keys.RECENT_INCOME].toRecentIds(),
            syncEnabled = p[Keys.SYNC_ENABLED] ?: false,
            serverUrl = p[Keys.SERVER_URL].orEmpty(),
            syncKey = p[Keys.SYNC_KEY].orEmpty(),
        )
    }

    suspend fun setAppLock(v: Boolean) {
        context.dataStore.edit { it[Keys.APP_LOCK] = v }
    }

    suspend fun setSyncEnabled(v: Boolean) {
        context.dataStore.edit { it[Keys.SYNC_ENABLED] = v }
    }

    /** 保存完整云同步配置；两项都为空表示清除配置，配置完整时自动开启同步。 */
    suspend fun setSyncConfig(serverUrl: String, syncKey: String) {
        context.dataStore.edit { p ->
            if (serverUrl.isBlank() && syncKey.isBlank()) {
                p.remove(Keys.SERVER_URL)
                p.remove(Keys.SYNC_KEY)
                p[Keys.SYNC_ENABLED] = false
            } else {
                p[Keys.SERVER_URL] = serverUrl.trim()
                p[Keys.SYNC_KEY] = syncKey.trim()
                p[Keys.SYNC_ENABLED] = true
            }
        }
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
