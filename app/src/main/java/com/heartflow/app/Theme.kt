package com.heartflow.app

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.darkColorScheme as m3DarkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.lightColorScheme as m3LightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.heartflow.data.ThemeMode
import com.heartflow.data.ThemeVariant

// ── 主题色定义 ──────────────────────────────────

// 极光紫
val AuroraPurple = Color(0xFF6B4C9A)
val AuroraPurpleLight = Color(0xFF9C7BC8)
val AuroraPurpleDark = Color(0xFF4A2D74)
val AuroraPurpleContainer = Color(0xFFEDE7F6)
val AuroraPurpleOnContainer = Color(0xFF3E2670)

// 海洋蓝
val OceanBlue = Color(0xFF2E7D8F)
val OceanBlueLight = Color(0xFF5BA3B8)
val OceanBlueDark = Color(0xFF1B5667)
val OceanBlueContainer = Color(0xFFD4F0F5)
val OceanBlueOnContainer = Color(0xFF1A4D5C)

// 森林绿
val ForestGreen = Color(0xFF3E8E41)
val ForestGreenLight = Color(0xFF6ABF6E)
val ForestGreenDark = Color(0xFF2A6E2D)
val ForestGreenContainer = Color(0xFFD4EDDA)
val ForestGreenOnContainer = Color(0xFF1F5422)

// 落日橙
val SunsetOrange = Color(0xFFD4764E)
val SunsetOrangeLight = Color(0xFFFF9F7A)
val SunsetOrangeDark = Color(0xFFA85A36)
val SunsetOrangeContainer = Color(0xFFFFE0CC)
val SunsetOrangeOnContainer = Color(0xFF8B481F)

// 暗夜黑
val DarkNightPrimary = Color(0xFFBB86FC)
val DarkNightPrimaryDark = Color(0xFF6200EE)
val DarkNightSurface = Color(0xFF1D1D2E)
val DarkNightBackground = Color(0xFF121218)
val DarkNightContainer = Color(0xFF2A2A3E)

// ── 表面/背景色 ─────────────────────────────────

val SurfaceLight = Color(0xFFF8F8FA)
val SurfaceDark = Color(0xFF1A1A2E)
val SurfaceVariantLight = Color(0xFFF0F0F4)
val SurfaceVariantDark = Color(0xFF24243A)
val BackgroundLight = Color(0xFFF5F5F7)
val BackgroundDark = Color(0xFF0F0F1A)

// ── 系统色 ──────────────────────────────────────

val ErrorRed = Color(0xFFDC3545)
val ErrorDark = Color(0xFFEF9A9A)
val OnErrorLight = Color.White
val OnErrorDark = Color(0xFF690005)
val OutlineLight = Color(0xFFC8C8D0)
val OutlineDark = Color(0xFF3E3E54)

// ── 主题方案数据类 ───────────────────────────────

data class ThemeScheme(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondary: Color,
    val onSecondary: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val tertiary: Color,
    val onTertiary: Color,
    val error: Color,
    val onError: Color,
    val background: Color,
    val onBackground: Color,
    val surface: Color,
    val onSurface: Color,
    val surfaceVariant: Color,
    val onSurfaceVariant: Color,
    val outline: Color
)

// ── 每个变体的浅色和深色方案 ───────────────────────

fun getThemeScheme(variant: ThemeVariant, isDark: Boolean): ThemeScheme {
    return when (variant) {
        ThemeVariant.AURORA_PURPLE -> if (isDark) auroraPurpleDark() else auroraPurpleLight()
        ThemeVariant.OCEAN_BLUE -> if (isDark) oceanBlueDark() else oceanBlueLight()
        ThemeVariant.FOREST_GREEN -> if (isDark) forestGreenDark() else forestGreenLight()
        ThemeVariant.SUNSET_ORANGE -> if (isDark) sunsetOrangeDark() else sunsetOrangeLight()
        ThemeVariant.DARK_NIGHT -> if (isDark) darkNightDark() else darkNightLight()
    }
}

private fun auroraPurpleLight() = ThemeScheme(
    primary = AuroraPurple,
    onPrimary = Color.White,
    primaryContainer = AuroraPurpleContainer,
    onPrimaryContainer = AuroraPurpleOnContainer,
    secondary = Color(0xFF7C6B9A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEFE6FF),
    onSecondaryContainer = Color(0xFF4A3B6B),
    tertiary = Color(0xFF9A6B8E),
    onTertiary = Color.White,
    error = ErrorRed,
    onError = OnErrorLight,
    background = BackgroundLight,
    onBackground = Color(0xFF1C1B1F),
    surface = Color.White,
    onSurface = Color(0xFF1C1B1F),
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = Color(0xFF49454F),
    outline = OutlineLight
)

private fun auroraPurpleDark() = ThemeScheme(
    primary = Color(0xFFBB86FC),
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF4A2D74),
    onPrimaryContainer = AuroraPurpleContainer,
    secondary = Color(0xFFCFBFFF),
    onSecondary = Color(0xFF332555),
    secondaryContainer = Color(0xFF4B3C6E),
    onSecondaryContainer = Color(0xFFEFE6FF),
    tertiary = Color(0xFFD4A0C8),
    onTertiary = Color(0xFF3A1F35),
    error = ErrorDark,
    onError = OnErrorDark,
    background = BackgroundDark,
    onBackground = Color(0xFFE6E1E5),
    surface = SurfaceDark,
    onSurface = Color(0xFFE6E1E5),
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = Color(0xFFCAC4D0),
    outline = OutlineDark
)

private fun oceanBlueLight() = ThemeScheme(
    primary = OceanBlue,
    onPrimary = Color.White,
    primaryContainer = OceanBlueContainer,
    onPrimaryContainer = OceanBlueOnContainer,
    secondary = Color(0xFF4E8C9E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD0EEF5),
    onSecondaryContainer = Color(0xFF1B5C6B),
    tertiary = Color(0xFF3E7B8A),
    onTertiary = Color.White,
    error = ErrorRed,
    onError = OnErrorLight,
    background = BackgroundLight,
    onBackground = Color(0xFF1B1F20),
    surface = Color.White,
    onSurface = Color(0xFF1B1F20),
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = Color(0xFF44474A),
    outline = OutlineLight
)

private fun oceanBlueDark() = ThemeScheme(
    primary = Color(0xFF7ECDDC),
    onPrimary = Color.Black,
    primaryContainer = OceanBlueDark,
    onPrimaryContainer = OceanBlueContainer,
    secondary = Color(0xFFA6D6E3),
    onSecondary = Color(0xFF1A3C47),
    secondaryContainer = Color(0xFF2A5C68),
    onSecondaryContainer = Color(0xFFD0EEF5),
    tertiary = Color(0xFF86C2D0),
    onTertiary = Color(0xFF1A3F4A),
    error = ErrorDark,
    onError = OnErrorDark,
    background = BackgroundDark,
    onBackground = Color(0xFFE0E3E5),
    surface = SurfaceDark,
    onSurface = Color(0xFFE0E3E5),
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = Color(0xFFC4C7CA),
    outline = OutlineDark
)

private fun forestGreenLight() = ThemeScheme(
    primary = ForestGreen,
    onPrimary = Color.White,
    primaryContainer = ForestGreenContainer,
    onPrimaryContainer = ForestGreenOnContainer,
    secondary = Color(0xFF5A9E5E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFC8E6C9),
    onSecondaryContainer = Color(0xFF2D6B30),
    tertiary = Color(0xFF3D8B40),
    onTertiary = Color.White,
    error = ErrorRed,
    onError = OnErrorLight,
    background = BackgroundLight,
    onBackground = Color(0xFF1B201B),
    surface = Color.White,
    onSurface = Color(0xFF1B201B),
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = Color(0xFF444844),
    outline = OutlineLight
)

private fun forestGreenDark() = ThemeScheme(
    primary = Color(0xFF81C784),
    onPrimary = Color.Black,
    primaryContainer = ForestGreenDark,
    onPrimaryContainer = ForestGreenContainer,
    secondary = Color(0xFFA5D6A7),
    onSecondary = Color(0xFF1D3B1F),
    secondaryContainer = Color(0xFF2E5F32),
    onSecondaryContainer = Color(0xFFC8E6C9),
    tertiary = Color(0xFF95C897),
    onTertiary = Color(0xFF1A3B1D),
    error = ErrorDark,
    onError = OnErrorDark,
    background = BackgroundDark,
    onBackground = Color(0xFFE1E6E1),
    surface = SurfaceDark,
    onSurface = Color(0xFFE1E6E1),
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = Color(0xFFC5C8C5),
    outline = OutlineDark
)

private fun sunsetOrangeLight() = ThemeScheme(
    primary = SunsetOrange,
    onPrimary = Color.White,
    primaryContainer = SunsetOrangeContainer,
    onPrimaryContainer = SunsetOrangeOnContainer,
    secondary = Color(0xFFD48D6E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFD8C0),
    onSecondaryContainer = Color(0xFF8B4E2F),
    tertiary = Color(0xFFC47E5E),
    onTertiary = Color.White,
    error = ErrorRed,
    onError = OnErrorLight,
    background = BackgroundLight,
    onBackground = Color(0xFF201B18),
    surface = Color.White,
    onSurface = Color(0xFF201B18),
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = Color(0xFF4D4744),
    outline = OutlineLight
)

private fun sunsetOrangeDark() = ThemeScheme(
    primary = Color(0xFFFFB088),
    onPrimary = Color.Black,
    primaryContainer = SunsetOrangeDark,
    onPrimaryContainer = SunsetOrangeContainer,
    secondary = Color(0xFFE8B094),
    onSecondary = Color(0xFF4A2816),
    secondaryContainer = Color(0xFF6D3C22),
    onSecondaryContainer = Color(0xFFFFD8C0),
    tertiary = Color(0xFFDEA888),
    onTertiary = Color(0xFF402216),
    error = ErrorDark,
    onError = OnErrorDark,
    background = BackgroundDark,
    onBackground = Color(0xFFE8E2DE),
    surface = SurfaceDark,
    onSurface = Color(0xFFE8E2DE),
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = Color(0xFFCDC8C4),
    outline = OutlineDark
)

private fun darkNightLight() = ThemeScheme(
    primary = DarkNightPrimary,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFFE8DEFF),
    onPrimaryContainer = Color(0xFF3B2080),
    secondary = Color(0xFF7C6BA0),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEADEFF),
    onSecondaryContainer = Color(0xFF3E2B60),
    tertiary = Color(0xFF9E7BB8),
    onTertiary = Color.White,
    error = ErrorRed,
    onError = OnErrorLight,
    background = Color.White,
    onBackground = Color(0xFF1C1B1F),
    surface = Color.White,
    onSurface = Color(0xFF1C1B1F),
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = Color(0xFF49454F),
    outline = OutlineLight
)

private fun darkNightDark() = ThemeScheme(
    primary = DarkNightPrimary,
    onPrimary = Color.Black,
    primaryContainer = DarkNightPrimaryDark,
    onPrimaryContainer = Color(0xFFE8DEFF),
    secondary = Color(0xFFCEC0E8),
    onSecondary = Color(0xFF332555),
    secondaryContainer = Color(0xFF4B3B6E),
    onSecondaryContainer = Color(0xFFEADEFF),
    tertiary = Color(0xFFD5B8EE),
    onTertiary = Color(0xFF3B2055),
    error = ErrorDark,
    onError = OnErrorDark,
    background = DarkNightBackground,
    onBackground = Color(0xFFE6E1E5),
    surface = DarkNightSurface,
    onSurface = Color(0xFFE6E1E5),
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = Color(0xFFCAC4D0),
    outline = OutlineDark
)

// ── 从 ThemeScheme 转换为 Material3 ColorScheme ──

fun ThemeScheme.toLightColorScheme() = m3LightColorScheme(
    primary = primary,
    onPrimary = onPrimary,
    primaryContainer = primaryContainer,
    onPrimaryContainer = onPrimaryContainer,
    secondary = secondary,
    onSecondary = onSecondary,
    secondaryContainer = secondaryContainer,
    onSecondaryContainer = onSecondaryContainer,
    tertiary = tertiary,
    onTertiary = onTertiary,
    error = error,
    onError = onError,
    background = background,
    onBackground = onBackground,
    surface = surface,
    onSurface = onSurface,
    surfaceVariant = surfaceVariant,
    onSurfaceVariant = onSurfaceVariant,
    outline = outline
)

fun ThemeScheme.toDarkColorScheme() = m3DarkColorScheme(
    primary = primary,
    onPrimary = onPrimary,
    primaryContainer = primaryContainer,
    onPrimaryContainer = onPrimaryContainer,
    secondary = secondary,
    onSecondary = onSecondary,
    secondaryContainer = secondaryContainer,
    onSecondaryContainer = onSecondaryContainer,
    tertiary = tertiary,
    onTertiary = onTertiary,
    error = error,
    onError = onError,
    background = background,
    onBackground = onBackground,
    surface = surface,
    onSurface = onSurface,
    surfaceVariant = surfaceVariant,
    onSurfaceVariant = onSurfaceVariant,
    outline = outline
)

/**
 * 根据主题模式和变体返回 Material3 ColorScheme
 */
@Composable
fun appliedColorScheme(mode: ThemeMode, variant: ThemeVariant, isDark: Boolean = false): androidx.compose.material3.ColorScheme {
    val darkTheme = when (mode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isDark
    }
    val scheme = getThemeScheme(variant, darkTheme)
    return if (darkTheme) scheme.toDarkColorScheme() else scheme.toLightColorScheme()
}
