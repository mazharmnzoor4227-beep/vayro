package com.vayro.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.vayro.app.R
import androidx.core.view.WindowCompat

// Raw brand colors. The public Pocket* tokens below resolve through
// LocalPocketAccents so a selected color theme recolors every call site;
// these constants are the unthemed (default) values and the building blocks
// of the base schemes.
internal val BrandLime = Color(0xFFA7FE28)
internal val BrandBlue = Color(0xFF8EA8FF)
internal val BrandGreen = Color(0xFF69D69E)

val PocketOrange: Color
    @Composable get() = LocalPocketAccents.current.accent

val PocketBlue: Color
    @Composable get() = LocalPocketAccents.current.info

val PocketGreen: Color
    @Composable get() = LocalPocketAccents.current.success

val PocketBackground = Color(0xFF0B0E14)
val PocketSurface = Color(0xFF131821)
val PocketSurfaceVariant = Color(0xFF1B222D)
val PocketOutline = Color(0xFF2A3240)

private val DarkColors = darkColorScheme(
    primary = BrandLime,
    onPrimary = Color(0xFF1A2600),
    primaryContainer = Color(0xFF2C4210),
    onPrimaryContainer = Color(0xFFE6FFB8),
    secondary = BrandBlue,
    onSecondary = Color(0xFF001F58),
    tertiary = BrandGreen,
    onTertiary = Color(0xFF00391E),
    background = PocketBackground,
    onBackground = Color(0xFFE6EDF3),
    surface = PocketSurface,
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = PocketSurfaceVariant,
    onSurfaceVariant = Color(0xFF9AA0A6),
    outline = PocketOutline,
    outlineVariant = Color(0xFF333B4A),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF4E8A00),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE2F3C2),
    onPrimaryContainer = Color(0xFF223600),
    secondary = Color(0xFF3366CC),
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFF1B8A5A),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFF6F8FA),
    onBackground = Color(0xFF1F2328),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1F2328),
    surfaceVariant = Color(0xFFEAEFF5),
    onSurfaceVariant = Color(0xFF57606A),
    outline = Color(0xFFD0D7DE),
    outlineVariant = Color(0xFFD8DEE4),
)

val JetBrainsMonoFontFamily = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_semibold, FontWeight.SemiBold),
)

val InterFontFamily = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

private val BaseTypography = Typography()

val AppTypography = Typography(
    displayLarge = BaseTypography.displayLarge.copy(fontFamily = InterFontFamily),
    displayMedium = BaseTypography.displayMedium.copy(fontFamily = InterFontFamily),
    displaySmall = BaseTypography.displaySmall.copy(fontFamily = InterFontFamily),
    headlineLarge = BaseTypography.headlineLarge.copy(fontFamily = InterFontFamily),
    headlineMedium = BaseTypography.headlineMedium.copy(fontFamily = InterFontFamily),
    headlineSmall = BaseTypography.headlineSmall.copy(fontFamily = InterFontFamily),
    titleLarge = BaseTypography.titleLarge.copy(fontFamily = InterFontFamily),
    titleMedium = BaseTypography.titleMedium.copy(fontFamily = InterFontFamily),
    titleSmall = BaseTypography.titleSmall.copy(fontFamily = InterFontFamily),
    bodyLarge = BaseTypography.bodyLarge.copy(fontFamily = InterFontFamily),
    bodyMedium = BaseTypography.bodyMedium.copy(fontFamily = InterFontFamily),
    bodySmall = BaseTypography.bodySmall.copy(fontFamily = InterFontFamily),
    labelLarge = BaseTypography.labelLarge.copy(fontFamily = InterFontFamily),
    labelMedium = BaseTypography.labelMedium.copy(fontFamily = InterFontFamily),
    labelSmall = BaseTypography.labelSmall.copy(fontFamily = InterFontFamily),
)

enum class AppThemeMode { SYSTEM, DARK, LIGHT }

@Composable
fun PocketTheme(
    themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    colorTheme: AppColorTheme = AppColorTheme.NONE,
    content: @Composable () -> Unit,
) {
    // A selected color theme carries its own full palette and brightness and
    // wins over the mode row; with no color theme (NONE) the Dark / Light /
    // System mode drives the base Kodex schemes exactly as before.
    val systemDark = isSystemInDarkTheme()
    val isDark: Boolean
    val colorScheme: ColorScheme
    val accents: PocketAccentPalette
    if (colorTheme != AppColorTheme.NONE) {
        isDark = colorTheme.isDark
        colorScheme = colorTheme.colorScheme
        accents = colorTheme.accents
    } else {
        isDark = when (themeMode) {
            AppThemeMode.DARK -> true
            AppThemeMode.LIGHT -> false
            AppThemeMode.SYSTEM -> systemDark
        }
        colorScheme = if (isDark) DarkColors else LightColors
        accents = PocketAccentPalette(accent = BrandLime, success = BrandGreen, info = BrandBlue)
    }

    // Sync the system status/navigation bar icons with the ACTUAL app theme.
    // The XML theme is Theme.Material.Light, so without this the system draws
    // dark icons even when the app renders dark (invisible on dark background).
    // Light icons in dark theme, dark icons in light theme.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !isDark
            insetsController.isAppearanceLightNavigationBars = !isDark
        }
    }

    // Kodex: text selection follows the app accent (lime), never the
    // default system blue highlight, in every theme.
    val selectionColors = TextSelectionColors(
        handleColor = accents.accent,
        backgroundColor = accents.accent.copy(alpha = 0.35f),
    )
    CompositionLocalProvider(
        LocalPocketAccents provides accents,
        LocalTextSelectionColors provides selectionColors,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            content = content,
        )
    }
}
