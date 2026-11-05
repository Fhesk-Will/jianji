package com.jianji.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 双套色板（蓝白 + 琥珀跳色 / 深夜蓝 + 琥珀跳色）。
 *
 * 关键设计：对外暴露的 Primary / Ink / Sub / ChartColors 等仍是
 * 「同包同名的 @Composable 属性」，因此所有既有调用点
 * （color = Ink 这类）无需任何改动即可自动适配深色模式。
 * 注意：这些 getter 只能在 Composable 上下文取值；
 * Canvas 绘制 lambda 里要先捕获（val chartColors = ChartColors）。
 */
data class AppColors(
    val primary: Color,
    val onPrimary: Color,
    val primaryDeep: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val accent: Color,
    val onAccent: Color,
    val income: Color,
    val ink: Color,
    val sub: Color,
    val faint: Color,
    val line: Color,
    val lineSoft: Color,
    val bg: Color,
    val surface: Color,
    val danger: Color,
    val chart: List<Color>,
)

val LightAppColors = AppColors(
    primary = Color(0xFF0B57D0),
    onPrimary = Color(0xFFFFFFFF),
    primaryDeep = Color(0xFF0946A8),
    primaryContainer = Color(0xFFDFE9FA),
    onPrimaryContainer = Color(0xFF0946A8),
    accent = Color(0xFFF5A623),          // 琥珀跳色：FAB / 完成键 / 小部件按钮
    onAccent = Color(0xFF1C2433),
    income = Color(0xFF16A34A),
    ink = Color(0xFF1C2433),
    sub = Color(0xFF5C6B80),
    faint = Color(0xFF93A1B5),
    line = Color(0xFFE2E9F1),
    lineSoft = Color(0xFFECF1F8),
    bg = Color(0xFFF5F8FC),
    surface = Color(0xFFFFFFFF),
    danger = Color(0xFFC6402F),
    chart = listOf(
        Color(0xFF0B57D0),   // 餐饮
        Color(0xFFF5A623),   // 购物（跳色）
        Color(0xFF5B8DEF),   // 居住
        Color(0xFFFF8A5C),   // 交通
        Color(0xFF8B7CE8),   // 娱乐
        Color(0xFF2FA8C4),   // 医疗
        Color(0xFF8CA0B5),   // 教育
    ),
)

val DarkAppColors = AppColors(
    primary = Color(0xFFA6C8FF),
    onPrimary = Color(0xFF0B1F3D),
    primaryDeep = Color(0xFFC4D9FF),
    primaryContainer = Color(0xFF123B70),
    onPrimaryContainer = Color(0xFFD6E4FF),
    accent = Color(0xFFF5A623),
    onAccent = Color(0xFF1C2433),
    income = Color(0xFF5FD08F),
    ink = Color(0xFFE7ECF4),
    sub = Color(0xFFA7B2C2),
    faint = Color(0xFF74808F),
    line = Color(0xFF2A3140),
    lineSoft = Color(0xFF232936),
    bg = Color(0xFF0F131B),
    surface = Color(0xFF171C26),
    danger = Color(0xFFFF8177),
    chart = listOf(
        Color(0xFF6EA8FF),
        Color(0xFFFFBE45),
        Color(0xFF7C9CC9),
        Color(0xFFFF9E77),
        Color(0xFFA996F0),
        Color(0xFF3DBFC9),
        Color(0xFF8D9BB0),
    ),
)

val LocalAppColors = staticCompositionLocalOf { LightAppColors }

// ---------- 同名 @Composable getter：既有调用点零改动 ----------

val Primary: Color
    @Composable get() = LocalAppColors.current.primary

val OnPrimary: Color
    @Composable get() = LocalAppColors.current.onPrimary

val PrimaryDeep: Color
    @Composable get() = LocalAppColors.current.primaryDeep

val PrimaryContainer: Color
    @Composable get() = LocalAppColors.current.primaryContainer

val OnPrimaryContainer: Color
    @Composable get() = LocalAppColors.current.onPrimaryContainer

val Accent: Color
    @Composable get() = LocalAppColors.current.accent

val OnAccent: Color
    @Composable get() = LocalAppColors.current.onAccent

val Income: Color
    @Composable get() = LocalAppColors.current.income

val Ink: Color
    @Composable get() = LocalAppColors.current.ink

val Sub: Color
    @Composable get() = LocalAppColors.current.sub

val Faint: Color
    @Composable get() = LocalAppColors.current.faint

val Line: Color
    @Composable get() = LocalAppColors.current.line

val LineSoft: Color
    @Composable get() = LocalAppColors.current.lineSoft

val Bg: Color
    @Composable get() = LocalAppColors.current.bg

val Surface: Color
    @Composable get() = LocalAppColors.current.surface

val Danger: Color
    @Composable get() = LocalAppColors.current.danger

val ChartColors: List<Color>
    @Composable get() = LocalAppColors.current.chart
