package com.ted.shouhuan.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/** 主色上的文字用近黑色 —— 薄荷绿/蓝色底上压白字读起来发飘。 */
private val Color0D1418 = androidx.compose.ui.graphics.Color(0xFF0D1418)

private val DarkColors = darkColorScheme(
    primary = Mint,
    onPrimary = Color0D1418,
    primaryContainer = MintDim,
    onPrimaryContainer = InkTextPrimary,
    secondary = StepBlue,
    onSecondary = Color0D1418,
    tertiary = SleepIndigo,
    onTertiary = InkTextPrimary,
    background = InkBg,
    onBackground = InkTextPrimary,
    surface = InkSurface,
    onSurface = InkTextPrimary,
    surfaceVariant = InkSurfaceHigh,
    onSurfaceVariant = InkTextSecondary,
    outline = InkOutline,
    error = PulseRed,
    onError = InkTextPrimary,
)

private val LightColors = lightColorScheme(
    primary = MintDim,
    onPrimary = PaperSurface,
    primaryContainer = Mint,
    onPrimaryContainer = PaperTextPrimary,
    secondary = StepBlue,
    onSecondary = PaperSurface,
    tertiary = SleepIndigo,
    onTertiary = PaperSurface,
    background = PaperBg,
    onBackground = PaperTextPrimary,
    surface = PaperSurface,
    onSurface = PaperTextPrimary,
    surfaceVariant = PaperSurfaceHigh,
    onSurfaceVariant = PaperTextSecondary,
    outline = PaperOutline,
    error = PulseRed,
    onError = PaperSurface,
)

@Composable
fun ShouhuanTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /** 默认关掉动态取色：我们的功能色（心率红/睡眠靛）有语义，不能被壁纸颜色冲掉。 */
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = ShouhuanTypography,
        content = content,
    )
}
