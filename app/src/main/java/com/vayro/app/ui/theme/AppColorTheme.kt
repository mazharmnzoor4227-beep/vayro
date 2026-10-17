package com.vayro.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The three accent colors the legacy `Pocket*` tokens resolve to. The theme
 * root provides the active palette; outside a themed composition the local
 * falls back to the original brand colors, so unthemed call sites render
 * exactly as they always have.
 */
data class PocketAccentPalette(
    val accent: Color,
    val success: Color,
    val info: Color,
)

val LocalPocketAccents = compositionLocalOf {
    PocketAccentPalette(accent = BrandLime, success = BrandGreen, info = BrandBlue)
}

/**
 * Full-palette color themes selectable under the Dark / Light / System mode
 * row in Settings → Appearance. [NONE] is the absence of a color theme: the
 * mode row alone drives the palette (the pre-existing behavior). Selecting
 * any other entry applies that theme's complete [ColorScheme] — including
 * its brightness — until the user taps a mode box again, which clears the
 * selection back to [NONE].
 */
enum class AppColorTheme(val displayName: String) {
    NONE("Default"),
    KODEX_LIME("Kodex Lime"),
    SOFT_LIME("Soft Lime"),
    MONO("Mono"),
    PAPER("Paper"),
    OCEAN("Ocean"),
    VIOLET("Violet"),
    AMBER("Amber"),
    TEAL("Teal");

    /** Brightness carried by the palette itself (only Paper is light). */
    val isDark: Boolean get() = this != PAPER

    val colorScheme: ColorScheme by lazy { buildColorScheme(this) }

    val accents: PocketAccentPalette by lazy { buildAccents(this) }

    companion object {
        /** The eight user-selectable color themes, in picker order. */
        val selectable: List<AppColorTheme> =
            listOf(KODEX_LIME, SOFT_LIME, MONO, PAPER, OCEAN, VIOLET, AMBER, TEAL)
    }
}

// Baseline Material 3 error roles, kept identical for every theme so error
// surfaces look the same regardless of the selected palette.
private val DarkError = Color(0xFFF2B8B5)
private val DarkOnError = Color(0xFF601410)
private val DarkErrorContainer = Color(0xFF8C1D18)
private val DarkOnErrorContainer = Color(0xFFF9DEDC)
private val LightError = Color(0xFFBA1A1A)
private val LightOnError = Color(0xFFFFFFFF)
private val LightErrorContainer = Color(0xFFFFDAD6)
private val LightOnErrorContainer = Color(0xFF410002)

private fun buildAccents(theme: AppColorTheme): PocketAccentPalette = when (theme) {
    AppColorTheme.NONE, AppColorTheme.KODEX_LIME ->
        PocketAccentPalette(accent = BrandLime, success = BrandGreen, info = BrandBlue)
    AppColorTheme.SOFT_LIME ->
        PocketAccentPalette(accent = Color(0xFFA8C256), success = Color(0xFF7CC79B), info = Color(0xFF93A7D4))
    AppColorTheme.MONO ->
        PocketAccentPalette(accent = Color(0xFFF4F4F4), success = Color(0xFFDADADA), info = Color(0xFFB9B9B9))
    AppColorTheme.PAPER ->
        PocketAccentPalette(accent = Color(0xFF141412), success = Color(0xFF1E7A46), info = Color(0xFF3D5AA8))
    AppColorTheme.OCEAN ->
        PocketAccentPalette(accent = Color(0xFF7FB0EC), success = Color(0xFF63CFA8), info = Color(0xFF7FC4CE))
    AppColorTheme.VIOLET ->
        PocketAccentPalette(accent = Color(0xFFB79CED), success = Color(0xFF74D3AE), info = Color(0xFFD69CC4))
    AppColorTheme.AMBER ->
        PocketAccentPalette(accent = Color(0xFFEDB45C), success = Color(0xFFA5C96B), info = Color(0xFFD89B6A))
    AppColorTheme.TEAL ->
        PocketAccentPalette(accent = Color(0xFF5CC7B4), success = Color(0xFF8BD49A), info = Color(0xFF8FC3D9))
}

private fun buildColorScheme(theme: AppColorTheme): ColorScheme = when (theme) {
    // Kodex Lime is the original dark palette, role for role: selecting it
    // (or never selecting anything) renders the app exactly as before.
    AppColorTheme.NONE, AppColorTheme.KODEX_LIME -> darkColorScheme(
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
        error = DarkError,
        onError = DarkOnError,
        errorContainer = DarkErrorContainer,
        onErrorContainer = DarkOnErrorContainer,
    )
    AppColorTheme.SOFT_LIME -> darkColorScheme(
        primary = Color(0xFFA8C256),
        onPrimary = Color(0xFF222B00),
        primaryContainer = Color(0xFF37420F),
        onPrimaryContainer = Color(0xFFE4F2BC),
        secondary = Color(0xFF93A7D4),
        onSecondary = Color(0xFF12233F),
        tertiary = Color(0xFF7CC79B),
        onTertiary = Color(0xFF00391E),
        background = PocketBackground,
        onBackground = Color(0xFFE6EDF3),
        surface = PocketSurface,
        onSurface = Color(0xFFE6EDF3),
        surfaceVariant = PocketSurfaceVariant,
        onSurfaceVariant = Color(0xFF9AA0A6),
        outline = PocketOutline,
        outlineVariant = Color(0xFF333B4A),
        error = DarkError,
        onError = DarkOnError,
        errorContainer = DarkErrorContainer,
        onErrorContainer = DarkOnErrorContainer,
    )
    AppColorTheme.MONO -> darkColorScheme(
        primary = Color(0xFFF4F4F4),
        onPrimary = Color(0xFF000000),
        primaryContainer = Color(0xFF2C2C2C),
        onPrimaryContainer = Color(0xFFFFFFFF),
        secondary = Color(0xFFB9B9B9),
        onSecondary = Color(0xFF111111),
        tertiary = Color(0xFFDADADA),
        onTertiary = Color(0xFF111111),
        background = Color(0xFF000000),
        onBackground = Color(0xFFF4F4F4),
        surface = Color(0xFF0B0B0B),
        onSurface = Color(0xFFF4F4F4),
        surfaceVariant = Color(0xFF171717),
        onSurfaceVariant = Color(0xFF9C9C9C),
        outline = Color(0xFF232323),
        outlineVariant = Color(0xFF303030),
        error = DarkError,
        onError = DarkOnError,
        errorContainer = DarkErrorContainer,
        onErrorContainer = DarkOnErrorContainer,
    )
    AppColorTheme.PAPER -> lightColorScheme(
        primary = Color(0xFF141412),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFE7E6DF),
        onPrimaryContainer = Color(0xFF141412),
        secondary = Color(0xFF3D5AA8),
        onSecondary = Color(0xFFFFFFFF),
        tertiary = Color(0xFF1E7A46),
        onTertiary = Color(0xFFFFFFFF),
        background = Color(0xFFFCFCF9),
        onBackground = Color(0xFF141412),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF141412),
        surfaceVariant = Color(0xFFF0EFE9),
        onSurfaceVariant = Color(0xFF5C5C56),
        outline = Color(0xFFD8D7CE),
        outlineVariant = Color(0xFFE3E2D9),
        error = LightError,
        onError = LightOnError,
        errorContainer = LightErrorContainer,
        onErrorContainer = LightOnErrorContainer,
    )
    AppColorTheme.OCEAN -> darkColorScheme(
        primary = Color(0xFF7FB0EC),
        onPrimary = Color(0xFF0A1F3D),
        primaryContainer = Color(0xFF17365E),
        onPrimaryContainer = Color(0xFFD7E8FF),
        secondary = Color(0xFF7FC4CE),
        onSecondary = Color(0xFF06333A),
        tertiary = Color(0xFF63CFA8),
        onTertiary = Color(0xFF00382A),
        background = Color(0xFF050A12),
        onBackground = Color(0xFFE4EDF8),
        surface = Color(0xFF0A141F),
        onSurface = Color(0xFFE4EDF8),
        surfaceVariant = Color(0xFF101E2E),
        onSurfaceVariant = Color(0xFF92A7BE),
        outline = Color(0xFF1C3049),
        outlineVariant = Color(0xFF29435F),
        error = DarkError,
        onError = DarkOnError,
        errorContainer = DarkErrorContainer,
        onErrorContainer = DarkOnErrorContainer,
    )
    AppColorTheme.VIOLET -> darkColorScheme(
        primary = Color(0xFFB79CED),
        onPrimary = Color(0xFF26134A),
        primaryContainer = Color(0xFF3A2566),
        onPrimaryContainer = Color(0xFFE9DEFF),
        secondary = Color(0xFFD69CC4),
        onSecondary = Color(0xFF3D1030),
        tertiary = Color(0xFF74D3AE),
        onTertiary = Color(0xFF00382A),
        background = Color(0xFF0C0714),
        onBackground = Color(0xFFEEE7FA),
        surface = Color(0xFF140D20),
        onSurface = Color(0xFFEEE7FA),
        surfaceVariant = Color(0xFF1D1430),
        onSurfaceVariant = Color(0xFFA99CC4),
        outline = Color(0xFF2B2045),
        outlineVariant = Color(0xFF3A2C58),
        error = DarkError,
        onError = DarkOnError,
        errorContainer = DarkErrorContainer,
        onErrorContainer = DarkOnErrorContainer,
    )
    AppColorTheme.AMBER -> darkColorScheme(
        primary = Color(0xFFEDB45C),
        onPrimary = Color(0xFF3B2500),
        primaryContainer = Color(0xFF4E380C),
        onPrimaryContainer = Color(0xFFFFE3AE),
        secondary = Color(0xFFD89B6A),
        onSecondary = Color(0xFF3A1C02),
        tertiary = Color(0xFFA5C96B),
        onTertiary = Color(0xFF233600),
        background = Color(0xFF0F0A05),
        onBackground = Color(0xFFF6ECDB),
        surface = Color(0xFF1A1108),
        onSurface = Color(0xFFF6ECDB),
        surfaceVariant = Color(0xFF271A0E),
        onSurfaceVariant = Color(0xFFC2AB8A),
        outline = Color(0xFF3B2A15),
        outlineVariant = Color(0xFF4D3820),
        error = DarkError,
        onError = DarkOnError,
        errorContainer = DarkErrorContainer,
        onErrorContainer = DarkOnErrorContainer,
    )
    AppColorTheme.TEAL -> darkColorScheme(
        primary = Color(0xFF5CC7B4),
        onPrimary = Color(0xFF00332A),
        primaryContainer = Color(0xFF12473B),
        onPrimaryContainer = Color(0xFFCBF3E8),
        secondary = Color(0xFF8FC3D9),
        onSecondary = Color(0xFF0B3040),
        tertiary = Color(0xFF8BD49A),
        onTertiary = Color(0xFF0B3818),
        background = Color(0xFF05100D),
        onBackground = Color(0xFFE3F3EE),
        surface = Color(0xFF0A1814),
        onSurface = Color(0xFFE3F3EE),
        surfaceVariant = Color(0xFF11241D),
        onSurfaceVariant = Color(0xFF90B3A7),
        outline = Color(0xFF1B332A),
        outlineVariant = Color(0xFF29463B),
        error = DarkError,
        onError = DarkOnError,
        errorContainer = DarkErrorContainer,
        onErrorContainer = DarkOnErrorContainer,
    )
}
