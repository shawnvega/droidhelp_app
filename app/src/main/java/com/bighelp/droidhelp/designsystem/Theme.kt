package com.bighelp.droidhelp.designsystem

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color

enum class ColorMode {
    Dynamic, Light, Dark
}

@Composable
fun getColorScheme(
    context: Context,
    isDarkTheme: Boolean,
    colorMode: ColorMode
): ColorScheme {
    return when (colorMode) {
        ColorMode.Dynamic -> {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                if (isDarkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            } else {
                if (isDarkTheme) DarkColorScheme else LightColorScheme
            }
        }
        ColorMode.Light -> LightColorScheme
        ColorMode.Dark -> DarkColorScheme
    }
}

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF6750A4),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEADDFF),
    onPrimaryContainer = Color(0xFF21005D),
    secondary = Color(0xFF625B71),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE8DEF8),
    onSecondaryContainer = Color(0xFF1D192B),
    tertiary = Color(0xFF7D5260),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFECDD3),
    onTertiaryContainer = Color(0xFF41000D),
    background = Color(0xFFFFFBFE),
    onBackground = Color(0xFF1C1B1F),
    surface = Color(0xFFFFFBFE),
    onSurface = Color(0xFF1C1B1F),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDE),
    onErrorContainer = Color(0xFF410002)
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFD0BCFF),
    onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFF4F378B),
    onPrimaryContainer = Color(0xFFEADDFF),
    secondary = Color(0xFFCCC2DC),
    onSecondary = Color(0xFF332D41),
    secondaryContainer = Color(0xFF4A4458),
    onSecondaryContainer = Color(0xFFE8DEF8),
    tertiary = Color(0xFFEFB8C8),
    onTertiary = Color(0xFF3E001D),
    tertiaryContainer = Color(0xFF6C545C),
    onTertiaryContainer = Color(0xFFFECDD3),
    background = Color(0xFF1C1B1F),
    onBackground = Color(0xFFE6E1E5),
    surface = Color(0xFF1C1B1F),
    onSurface = Color(0xFFE6E1E5),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDE)
)

@Composable
fun Theme(
    colorMode: ColorMode = ColorMode.Dynamic,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val darkTheme = isSystemInDarkTheme()
    val colorScheme = getColorScheme(context, darkTheme, colorMode)

    val appTypography = Typography(
        displayLarge = Typography.DisplayLarge,
        displayMedium = Typography.DisplayMedium,
        displaySmall = Typography.DisplaySmall,
        headlineLarge = Typography.HeadlineLarge,
        headlineMedium = Typography.HeadlineMedium,
        headlineSmall = Typography.HeadlineSmall,
        titleLarge = Typography.TitleLarge,
        titleMedium = Typography.TitleMedium,
        titleSmall = Typography.TitleSmall,
        bodyLarge = Typography.BodyLarge,
        bodyMedium = Typography.BodyMedium,
        bodySmall = Typography.BodySmall,
        labelLarge = Typography.LabelLarge,
        labelMedium = Typography.LabelMedium,
        labelSmall = Typography.LabelSmall
    )

    MaterialTheme(
        colorScheme = colorScheme,
        typography = appTypography,
        content = content
    )
}

@Composable
fun ThemeWrapper(
    colorMode: ColorMode = ColorMode.Dynamic,
    content: @Composable () -> Unit
) {
    Theme(colorMode = colorMode, content = content)
}
