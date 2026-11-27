package com.jianji.app

import android.app.Application
import android.content.Context
import com.jianji.app.data.csv.CsvManager
import com.jianji.app.data.db.AppDatabase
import com.jianji.app.data.prefs.SettingsStore
import com.jianji.app.data.repository.LedgerRepository
import com.jianji.app.data.sync.CloudSyncManager
import com.jianji.app.data.sync.SnapshotCodec
import com.jianji.app.data.sync.SyncApiClient
import com.jianji.app.data.update.UpdateManager
import com.jianji.app.widget.QuickWidgetProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.YearMonth

/** 手工依赖容器：单用户 App 不引 DI 框架，够用且零成本 */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val db: AppDatabase = AppDatabase.get(appContext)
    val repo = LedgerRepository(db)
    val settings = SettingsStore(appContext)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val sync = CloudSyncManager(repo, settings, SyncApiClient(), scope)
    val csv = CsvManager(appContext, repo)
    val update = UpdateManager(appContext)

    init {
        observeForAutoSync()
    }

    /**
     * 自动同步触发器：账本任一表变化 → 内容指纹变化 → 去抖后上传。
     * 指纹与上传内容一致（排除时间戳），恢复后的发射不会触发重复上传。
     */
    private fun observeForAutoSync() {
        scope.launch {
            combine(repo.recordsSnapshotFlow(), repo.categoriesSnapshotFlow()) { r, c -> r to c }
                .collect { (records, categories) ->
                    sync.onLocalDataChanged(SnapshotCodec.fingerprint(records, categories))
                }
        }
    }
}

class JianJiApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.scope.launch {
            container.repo.seedDefaults()
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
}
