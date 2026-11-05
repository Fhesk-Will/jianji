package com.jianji.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.jianji.app.JianJiApp
import com.jianji.app.MainActivity
import com.jianji.app.R
import com.jianji.app.data.repository.LedgerRepository
import com.jianji.app.util.Money
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.YearMonth

/**
 * 4×1 桌面小部件：本月支出/收入一览，点按任意位置直达「记一笔」。
 * RemoteViews 实现（无 Glance 依赖）；数据刷新：
 *  - JianJiApp 常驻观察月度汇总流，数据一变就推
 *  - 系统重建（onUpdate）时兜底读取一次
 */
class QuickWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val pendingResult = goAsync()
        val app = context.applicationContext as JianJiApp
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val page = app.container.repo
                    .monthPageFlow(YearMonth.now(), null, null, null)
                    .first()
                appWidgetIds.forEach { id -> render(context, id, page) }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val EXTRA_OPEN = "open"
        private const val OPEN_RECORD = "record"

        /** 渲染一个小部件 */
        fun render(context: Context, appWidgetId: Int, page: LedgerRepository.MonthPage) {
            val views = RemoteViews(context.packageName, R.layout.widget_quick)
            views.setTextViewText(R.id.widget_month, "${YearMonth.now().monthValue}月 · 支出")
            views.setTextViewText(R.id.widget_expense, Money.yuan(page.expenseMinor))
            views.setTextViewText(R.id.widget_income, "收入 " + Money.yuan(page.incomeMinor))

            val intent = Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_OPEN, OPEN_RECORD)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pi = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_root, pi)

            AppWidgetManager.getInstance(context).updateAppWidget(appWidgetId, views)
        }

        /** 全部小部件刷新（数据变化时由 App 调用） */
        suspend fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, QuickWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val app = context.applicationContext as JianJiApp
            val page = app.container.repo
                .monthPageFlow(YearMonth.now(), null, null, null)
                .first()
            ids.forEach { id -> render(context, id, page) }
        }
    }
}
