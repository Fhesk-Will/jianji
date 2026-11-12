package com.jianji.app.ui.nav

import android.net.Uri
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.jianji.app.ui.categories.CategoryManageScreen
import com.jianji.app.ui.home.HomeScreen
import com.jianji.app.ui.record.AddRecordScreen
import com.jianji.app.ui.settings.SettingsScreen
import com.jianji.app.ui.stats.DayDetailScreen
import com.jianji.app.ui.stats.StatsScreen
import com.jianji.app.util.Dates

private data class Tab(val route: String, val icon: ImageVector, val label: String)

@Composable
fun AppRoot(
    openRecordRequest: Boolean = false,
    onOpenRecordConsumed: () -> Unit = {},
    incomingImportUri: Uri? = null,
    onIncomingImportConsumed: () -> Unit = {},
    onPickImportFile: () -> Unit = {},
) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    // 桌面小部件点按 → 直达记一笔
    LaunchedEffect(openRecordRequest) {
        if (openRecordRequest) {
            nav.navigate(Routes.record())
            onOpenRecordConsumed()
        }
    }

    // 微信等应用传入文件 → 进入我的页面，由用户确认后导入
    LaunchedEffect(incomingImportUri) {
        if (incomingImportUri != null) {
            nav.navigate(Routes.SETTINGS) {
                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    val tabs = listOf(
        Tab(Routes.HOME, Icons.AutoMirrored.Filled.List, "明细"),
        Tab(Routes.STATS, Icons.Filled.PieChart, "统计"),
        Tab(Routes.SETTINGS, Icons.Filled.Person, "我的"),
    )
    val showBottomBar = tabs.any { it.route == currentRoute }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(padding),
            // 记账要快：默认（Tab 间切换）零动画瞬切，覆盖 700ms 默认淡入淡出
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None },
        ) {
            composable(Routes.HOME) { entry ->
                // 新建记录保存后由记一笔页写入落点，这里消费一次（编辑/取消不会写）
                val locateDay by entry.savedStateHandle
                    .getStateFlow<Int?>(Routes.SAVED_LOCATE_DAY, null)
                    .collectAsStateWithLifecycle()
                HomeScreen(
                    onAdd = { nav.navigate(Routes.record()) },
                    onEdit = { id -> nav.navigate(Routes.record(id)) },
                    locateEpochDay = locateDay,
                    onLocateConsumed = { entry.savedStateHandle[Routes.SAVED_LOCATE_DAY] = null },
                )
            }
            composable(Routes.STATS) {
                StatsScreen(
                    onOpenDay = { date -> nav.navigate(Routes.dayDetail(Dates.epochDay(date))) },
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onCategories = { nav.navigate(Routes.CATEGORIES) },
                    incomingImportUri = incomingImportUri,
                    onIncomingImportConsumed = onIncomingImportConsumed,
                    onPickImportFile = onPickImportFile,
                )
            }
            composable(
                route = Routes.RECORD,
                arguments = listOf(
                    navArgument(Routes.RECORD_ARG) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
                // 记一笔 = 高频核心路径：底部快速上滑 200ms，返回 160ms
                enterTransition = {
                    slideInVertically(tween(200)) { it } + fadeIn(tween(120))
                },
                exitTransition = { fadeOut(tween(90)) },
                popEnterTransition = { fadeIn(tween(120)) },
                popExitTransition = {
                    slideOutVertically(tween(160)) { it } + fadeOut(tween(90))
                },
            ) { entry ->
                val recordId = entry.arguments?.getString(Routes.RECORD_ARG)
                AddRecordScreen(
                    recordId = recordId,
                    onLocate = { epochDay ->
                        // 回传给明细页（当前页的上一页就是 HOME）
                        nav.previousBackStackEntry
                            ?.savedStateHandle
                            ?.set(Routes.SAVED_LOCATE_DAY, epochDay)
                    },
                    onDone = { nav.popBackStack() },
                )
            }
            composable(
                route = Routes.CATEGORIES,
                // 二级页：右侧推入 220ms
                enterTransition = {
                    slideInHorizontally(tween(220)) { it } + fadeIn(tween(120))
                },
                exitTransition = { fadeOut(tween(90)) },
                popEnterTransition = { fadeIn(tween(120)) },
                popExitTransition = {
                    slideOutHorizontally(tween(220)) { it } + fadeOut(tween(90))
                },
            ) {
                CategoryManageScreen(onBack = { nav.popBackStack() })
            }
            composable(
                route = Routes.DAY_DETAIL,
                arguments = listOf(
                    navArgument(Routes.DAY_DETAIL_ARG) { type = NavType.IntType },
                ),
                // 二级页：右侧推入 220ms
                enterTransition = {
                    slideInHorizontally(tween(220)) { it } + fadeIn(tween(120))
                },
                exitTransition = { fadeOut(tween(90)) },
                popEnterTransition = { fadeIn(tween(120)) },
                popExitTransition = {
                    slideOutHorizontally(tween(220)) { it } + fadeOut(tween(90))
                },
            ) { entry ->
                DayDetailScreen(
                    epochDay = entry.arguments?.getInt(Routes.DAY_DETAIL_ARG) ?: 0,
                    onBack = { nav.popBackStack() },
                    onEdit = { id -> nav.navigate(Routes.record(id)) },
                )
            }
        }
    }
}
