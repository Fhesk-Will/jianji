package com.jianji.app.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

data class Settings(
    val appLock: Boolean = false,
)

/** 单用户：无账号无登录，设置直接落 DataStore */
class SettingsStore(private val context: Context) {

    private object Keys {
        val APP_LOCK = booleanPreferencesKey("app_lock")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(appLock = p[Keys.APP_LOCK] ?: false)
    }

    suspend fun setAppLock(v: Boolean) {
        context.dataStore.edit { it[Keys.APP_LOCK] = v }
    }
}
