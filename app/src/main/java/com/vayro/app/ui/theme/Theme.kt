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

// ---------------------------------------------------------------------------
// Claude palette (v3.2.32, extraction-verified tokens — see
// kodex-chat-preview/CLAUDE-DESIGN-TOKENS.md). Warm monochrome grays, one
// chromatic accent (Clay), hairline borders, almost no shadow. The old
// 8-theme system is retired: PocketTheme always uses these two schemes.
// ---------------------------------------------------------------------------

/** Product accent: send button, avatar, highlights. */
val KodexClay = Color(0xFFD97757)

/** Accent pressed/hover state. */
val KodexClayPressed = Color(0xFFC9674A)

/** Switch-ON + selected-row blue (Claude Android estimate, ~iOS system blue). */
val KodexBlue = Color(0xFF0A84FF)

private val ClaudeSuccess = Color(0xFF5DB872)
private val ClaudeInfo = Color(0xFF0A84FF)

// Legacy brand constants kept for AppColorTheme.kt (now inert) and any
// remaining references; the live palette comes from the schemes below.
internal val BrandLime = Color(0xFFA7FE28)
internal val BrandBlue = Color(0xFF8EA8FF)
internal val BrandGreen = Color(0xFF69D69E)

val PocketOrange: Color
    @Composable get() = LocalPocketAccents.current.accent

val PocketBlue: Color
    @Composable get() = LocalPocketAccents.current.info

val PocketGreen: Color
    @Composable get() = LocalPocketAccents.current.success

val PocketBackground = Color(0xFF262624)
val PocketSurface = Color(0xFF2E2E2B)
val PocketSurfaceVariant = Color(0xFF35332F)
val PocketOutline = Color(0xFF55534C)

private val DarkColors = darkColorScheme(
    primary = KodexClay,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF46322A),
    onPrimaryContainer = Color(0xFFF0C4B2),
    secondary = KodexBlue,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFF26384D),
    onSecondaryContainer = Color(0xFFBCD6FF),
    tertiary = ClaudeSuccess,
    onTertiary = Color(0xFF0C2413),
    tertiaryContainer = Color(0xFF1E3325),
    onTertiaryContainer = Color(0xFFBCE8C6),
    background = Color(0xFF262624),
    onBackground = Color(0xFFF5F4EF),
    surface = Color(0xFF2E2E2B),
    onSurface = Color(0xFFF5F4EF),
    surfaceVariant = Color(0xFF35332F),
    onSurfaceVariant = Color(0xFFB8B5A9),
    surfaceContainerLowest = Color(0xFF1F1E1D),
    surfaceContainerLow = Color(0xFF252523),
    surfaceContainer = Color(0xFF2A2A28),
    surfaceContainerHigh = Color(0xFF2E2E2B),
    surfaceContainerHighest = Color(0xFF35332F),
    outline = Color(0xFF57554E),
    outlineVariant = Color(0xFF3E3C37),
    error = Color(0xFFFF6B80),
    onError = Color(0xFF3D0A12),
    errorContainer = Color(0xFF4A1520),
    onErrorContainer = Color(0xFFFFD9DE),
)

private val LightColors = lightColorScheme(
    primary = KodexClay,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFF7E3D8),
    onPrimaryContainer = Color(0xFF7C3A22),
    secondary = KodexBlue,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDCEBFF),
    onSecondaryContainer = Color(0xFF0A4E9E),
    tertiary = ClaudeSuccess,
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFDDF2E2),
    onTertiaryContainer = Color(0xFF14532A),
    background = Color(0xFFFAF9F5),
    onBackground = Color(0xFF141413),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF141413),
    surfaceVariant = Color(0xFFEFE9DE),
    onSurfaceVariant = Color(0xFF6C6A64),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFF5F0E8),
    surfaceContainerHigh = Color(0xFFEFE9DE),
    surfaceContainerHighest = Color(0xFFE8E0D2),
    outline = Color(0xFFCFC9BE),
    outlineVariant = Color(0xFFE6DFD8),
    error = Color(0xFFC64545),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF5F1414),
)

/**
 * Claude-style reading serif (Source Serif 4, OFL) — literally in Claude's
 * extracted fallback stack; used for display titles, the home greeting and
 * agent message prose. UI stays Inter, code stays JetBrains Mono.
 */
val SourceSerifFontFamily = FontFamily(
    Font(R.font.source_serif_regular, FontWeight.Normal),
    Font(R.font.source_serif_semibold, FontWeight.SemiBold),
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

/** Settings -> Font style: Default = the bundled Claude pairing; System = the device font. */
enum class AppFontStyle { DEFAULT, SYSTEM }

@Composable
fun PocketTheme(
    themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    colorTheme: AppColorTheme = AppColorTheme.NONE,
    fontStyle: AppFontStyle = AppFontStyle.DEFAULT,
    content: @Composable () -> Unit,
) {
    // The 8 custom color themes are retired (v3.2.32): [colorTheme] is still
    // accepted so existing call sites compile, but the palette always comes
    // from the Color mode (System / Light / Dark) over the Claude schemes.
    @Suppress("UNUSED_VARIABLE")
    val retiredColorTheme = colorTheme
    val systemDark = isSystemInDarkTheme()
    val isDark = when (themeMode) {
        AppThemeMode.DARK -> true
        AppThemeMode.LIGHT -> false
        AppThemeMode.SYSTEM -> systemDark
    }
    val colorScheme: ColorScheme = if (isDark) DarkColors else LightColors
    val accents = PocketAccentPalette(
        accent = KodexClay,
        success = ClaudeSuccess,
        info = ClaudeInfo,
    )

    // Sync the system status/navigation bar icons with the ACTUAL app theme.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !isDark
            insetsController.isAppearanceLightNavigationBars = !isDark
        }
    }

    // Text selection follows the Clay accent — never the default blue
    // web-style highlight.
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
            typography = if (fontStyle == AppFontStyle.SYSTEM) BaseTypography else AppTypography,
            content = content,
        )
    }
}
