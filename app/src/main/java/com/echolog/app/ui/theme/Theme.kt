package com.echolog.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColorScheme = lightColorScheme(
    primary = MonitorBlue,
    onPrimary = Color.White,
    primaryContainer = MonitorBlueContainer,
    onPrimaryContainer = MonitorBlueOnContainer,
    secondary = RecordRed,
    onSecondary = Color.White,
    secondaryContainer = RecordRedContainer,
    onSecondaryContainer = RecordRedOnContainer,
    background = BackgroundLight,
    onBackground = OnBackgroundLight,
    surface = BackgroundLight,
    onSurface = OnBackgroundLight,
    surfaceVariant = NeutralGrayLight,
    onSurfaceVariant = NeutralGray,
    outline = NeutralGray,
)

private val DarkColorScheme = darkColorScheme(
    primary = MonitorBlueDark,
    onPrimary = Color(0xFF00277A),
    primaryContainer = MonitorBlueContainerDark,
    onPrimaryContainer = MonitorBlueOnContainerDark,
    secondary = RecordRedDark,
    onSecondary = Color(0xFF690005),
    secondaryContainer = RecordRedContainerDark,
    onSecondaryContainer = RecordRedOnContainerDark,
    background = BackgroundDark,
    onBackground = OnBackgroundDark,
    surface = BackgroundDark,
    onSurface = OnBackgroundDark,
    surfaceVariant = NeutralGrayDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    outline = NeutralOutlineDark,
)

/**
 * EchoLog Material3 主题（架构 2.1）。
 *
 * - Android 12+ 默认开启动态取色，与品牌色板降级方案共存；
 * - 跟随系统深色模式；
 * - 字体规范见 [EchoTypography]。
 */
@Composable
fun EchoLogTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = EchoTypography,
        content = content,
    )
}
