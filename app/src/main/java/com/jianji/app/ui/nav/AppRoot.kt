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
import com.jianji.app.ui.stats.StatsScreen

private data class Tab(val route: String, val icon: ImageVector, val label: String)

@Composable
fun AppRoot(
    openRecordRequest: Boolean = false,
    onOpenRecordConsumed: () -> Unit = {},
    incomingImportUri: Uri? = null,
    onIncomingImportConsumed: () -> Unit = {},
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
            composable(Routes.HOME) {
                HomeScreen(
                    onAdd = { nav.navigate(Routes.record()) },
                    onEdit = { id -> nav.navigate(Routes.record(id)) },
                )
            }
            composable(Routes.STATS) {
                StatsScreen()
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onCategories = { nav.navigate(Routes.CATEGORIES) },
                    incomingImportUri = incomingImportUri,
                    onIncomingImportConsumed = onIncomingImportConsumed,
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
                AddRecordScreen(recordId = recordId, onDone = { nav.popBackStack() })
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
        }
    }
}
