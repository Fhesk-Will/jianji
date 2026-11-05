package com.jianji.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

@Composable
fun JianJiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkAppColors else LightAppColors
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = colors.primary,
            onPrimary = colors.onPrimary,
            primaryContainer = colors.primaryContainer,
            onPrimaryContainer = colors.onPrimaryContainer,
            secondary = colors.primaryDeep,
            background = colors.bg,
            onBackground = colors.ink,
            surface = colors.surface,
            onSurface = colors.ink,
            surfaceVariant = colors.line,        // 深色下兼作填充色（分段控件轨道）
            onSurfaceVariant = colors.sub,
            outline = colors.line,
            error = colors.danger,
        )
    } else {
        lightColorScheme(
            primary = colors.primary,
            onPrimary = colors.onPrimary,
            primaryContainer = colors.primaryContainer,
            onPrimaryContainer = colors.onPrimaryContainer,
            secondary = colors.primaryDeep,
            background = colors.bg,
            onBackground = colors.ink,
            surface = colors.surface,
            onSurface = colors.ink,
            surfaceVariant = colors.line,
            onSurfaceVariant = colors.sub,
            outline = colors.line,
            error = colors.danger,
        )
    }
    CompositionLocalProvider(LocalAppColors provides colors) {
        MaterialTheme(
            colorScheme = scheme,
            typography = AppTypography,
            content = content,
        )
    }
}
