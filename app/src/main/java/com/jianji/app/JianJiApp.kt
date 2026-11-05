package com.jianji.app

import android.app.Application
import android.content.Context
import com.jianji.app.data.backup.BackupManager
import com.jianji.app.data.db.AppDatabase
import com.jianji.app.data.prefs.SettingsStore
import com.jianji.app.data.repository.LedgerRepository
import com.jianji.app.widget.QuickWidgetProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

/** 手工依赖容器：单用户 App 不引 DI 框架，够用且零成本 */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val db: AppDatabase = AppDatabase.get(appContext)
    val repo = LedgerRepository(db)
    val settings = SettingsStore(appContext)
    val backup = BackupManager(appContext, repo)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}

class JianJiApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.scope.launch {
            container.repo.seedIfEmpty()
            autoBackupIfDue()
        }
        observeForWidget()
    }

    /** 小部件数据源：月度汇总一变就推给桌面小部件 */
    private fun observeForWidget() {
        container.scope.launch {
            container.repo.monthPageFlow(YearMonth.now(), null, null, null)
                .map { it.expenseMinor to it.incomeMinor }
                .distinctUntilChanged()
                .collect {
                    runCatching { QuickWidgetProvider.refreshAll(this@JianJiApp) }
                }
        }
    }

    /**
     * 每日一次自动备份（需已选择备份目录）。
     * 备份落在用户指定的网盘同步目录时，即等于"上云"，无需服务器。
     */
    private suspend fun autoBackupIfDue() {
        val s = container.settings.settings.first()
        val dir = s.backupDirUri ?: return
        if (!s.autoBackup) return
        val lastDay = LocalDate.ofEpochDay(s.lastBackupAtMs / 86_400_000L)
        if (!LocalDate.now().isAfter(lastDay)) return
        val file = BackupManager.newBackupFile(container.appContext, dir) ?: return
        container.backup.backupTo(file).onSuccess {
            container.settings.setLastBackup(System.currentTimeMillis())
            BackupManager.prune(container.appContext, dir)
        }
    }
}
