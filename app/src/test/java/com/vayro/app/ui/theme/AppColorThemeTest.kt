package com.vayro.app.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppColorThemeTest {

    @Test
    fun selectableThemesAreTheEightDesignedOnes() {
        assertEquals(8, AppColorTheme.selectable.size)
        assertEquals(
            listOf(
                AppColorTheme.KODEX_LIME,
                AppColorTheme.SOFT_LIME,
                AppColorTheme.MONO,
                AppColorTheme.PAPER,
                AppColorTheme.OCEAN,
                AppColorTheme.VIOLET,
                AppColorTheme.AMBER,
                AppColorTheme.TEAL,
            ),
            AppColorTheme.selectable,
        )
        assertFalse(AppColorTheme.selectable.contains(AppColorTheme.NONE))
        assertEquals(8, AppColorTheme.selectable.map { it.displayName }.toSet().size)
        assertEquals(8, AppColorTheme.selectable.map { it.name }.toSet().size)
    }

    @Test
    fun persistedNamesRoundTrip() {
        // MainViewModel restores the selection with valueOf(name.uppercase()).
        AppColorTheme.entries.forEach { theme ->
            assertEquals(theme, AppColorTheme.valueOf(theme.name.lowercase().uppercase()))
        }
    }

    @Test
    fun defaultAccentsAreTheOriginalBrandColors() {
        val accents = AppColorTheme.NONE.accents
        assertEquals(Color(0xFFA7FE28), accents.accent)
        assertEquals(Color(0xFF69D69E), accents.success)
        assertEquals(Color(0xFF8EA8FF), accents.info)
    }

    @Test
    fun kodexLimeMatchesTheOriginalDarkPalette() {
        val scheme = AppColorTheme.KODEX_LIME.colorScheme
        assertEquals(Color(0xFFA7FE28), scheme.primary)
        assertEquals(Color(0xFF0B0E14), scheme.background)
        assertEquals(Color(0xFF131821), scheme.surface)
        assertEquals(Color(0xFF1B222D), scheme.surfaceVariant)
        assertEquals(Color(0xFFE6EDF3), scheme.onBackground)
        assertEquals(Color(0xFFE6EDF3), scheme.onSurface)
        assertEquals(Color(0xFF2A3240), scheme.outline)
        assertEquals(AppColorTheme.NONE.accents, AppColorTheme.KODEX_LIME.accents)
    }

    @Test
    fun everyThemeDefinesACompleteReadablePalette() {
        AppColorTheme.selectable.forEach { theme ->
            val scheme = theme.colorScheme
            assertNotEquals("${theme.displayName} background/onBackground", scheme.background, scheme.onBackground)
            assertNotEquals("${theme.displayName} surface/onSurface", scheme.surface, scheme.onSurface)
            assertNotEquals("${theme.displayName} surfaceVariant/onSurfaceVariant", scheme.surfaceVariant, scheme.onSurfaceVariant)
            assertNotEquals("${theme.displayName} primary/background", scheme.primary, scheme.background)
            assertNotEquals("${theme.displayName} primary/onPrimary", scheme.primary, scheme.onPrimary)
            assertNotEquals("${theme.displayName} secondary/background", scheme.secondary, scheme.background)
            assertNotEquals("${theme.displayName} tertiary/background", scheme.tertiary, scheme.background)
            assertNotEquals("${theme.displayName} outline/background", scheme.outline, scheme.background)
            // The accent tokens and the scheme must agree so token-driven and
            // scheme-driven UI recolor identically.
            assertEquals("${theme.displayName} accent", scheme.primary, theme.accents.accent)
            assertEquals("${theme.displayName} success", scheme.tertiary, theme.accents.success)
            assertEquals("${theme.displayName} info", scheme.secondary, theme.accents.info)
        }
    }

    @Test
    fun paperIsTheOnlyLightTheme() {
        AppColorTheme.selectable.forEach { theme ->
            if (theme == AppColorTheme.PAPER) {
                assertFalse(theme.isDark)
            } else {
                assertTrue("${theme.displayName} should be dark", theme.isDark)
            }
        }
    }
}
