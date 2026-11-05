package com.jianji.app.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

data class Settings(
    val autoBackup: Boolean = true,
    val backupDirUri: String? = null,
    val lastBackupAtMs: Long = 0L,
    val appLock: Boolean = false,
)

/** 单用户：无账号无登录，设置直接落 DataStore */
class SettingsStore(private val context: Context) {

    private object Keys {
        val AUTO_BACKUP = booleanPreferencesKey("auto_backup")
        val BACKUP_DIR = stringPreferencesKey("backup_dir_uri")
        val LAST_BACKUP = longPreferencesKey("last_backup_at")
        val APP_LOCK = booleanPreferencesKey("app_lock")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            autoBackup = p[Keys.AUTO_BACKUP] ?: true,
            backupDirUri = p[Keys.BACKUP_DIR],
            lastBackupAtMs = p[Keys.LAST_BACKUP] ?: 0L,
            appLock = p[Keys.APP_LOCK] ?: false,
        )
    }

    suspend fun setAutoBackup(v: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_BACKUP] = v }
    }

    suspend fun setAppLock(v: Boolean) {
        context.dataStore.edit { it[Keys.APP_LOCK] = v }
    }

    suspend fun setBackupDir(uri: String?) {
        context.dataStore.edit {
            if (uri != null) it[Keys.BACKUP_DIR] = uri else it.remove(Keys.BACKUP_DIR)
        }
    }

    suspend fun setLastBackup(now: Long) {
        context.dataStore.edit { it[Keys.LAST_BACKUP] = now }
    }
}
