package com.harrydatahub.groundkit.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.harrydatahub.groundkit.data.ThemeMode

// Fixed schemes rather than wallpaper-based dynamic colour: outdoors, contrast matters more
// than matching the launcher.
private val LightScheme = lightColorScheme(
    primary = Navy,
    onPrimary = Color.White,
    primaryContainer = NavyContainer,
    onPrimaryContainer = Color(0xFF001A43),
    secondary = Color(0xFF4A5568),
    secondaryContainer = Color(0xFFFFE08A),
    onSecondaryContainer = Color(0xFF261A00),
    background = Color(0xFFF5F6F8),
    onBackground = Color(0xFF111418),
    surface = Color(0xFFF5F6F8),
    onSurface = Color(0xFF111418),
    surfaceVariant = Color(0xFFE3E6EB),
    onSurfaceVariant = Color(0xFF454B54),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color(0xFFEDEFF2),
    surfaceContainerHigh = Color(0xFFE6E9ED),
    surfaceContainerHighest = Color(0xFFDFE2E7),
    outline = Color(0xFF8A919B),
    outlineVariant = Color(0xFFCBD0D7),
    error = Color(0xFFC81E1E),
)

private val DarkScheme = darkColorScheme(
    primary = HiVis,
    onPrimary = Color(0xFF241A00),
    primaryContainer = HiVisContainer,
    onPrimaryContainer = Color(0xFFFFE08A),
    secondary = Color(0xFFB8C2D0),
    secondaryContainer = Color(0xFF3A3000),
    onSecondaryContainer = Color(0xFFFFE08A),
    background = Color(0xFF0E1013),
    onBackground = Color(0xFFECEDEF),
    surface = Color(0xFF0E1013),
    onSurface = Color(0xFFECEDEF),
    surfaceVariant = Color(0xFF262A31),
    onSurfaceVariant = Color(0xFFB4BAC3),
    surfaceContainerLowest = Color(0xFF0A0B0D),
    surfaceContainerLow = Color(0xFF16191D),
    surfaceContainer = Color(0xFF1A1D22),
    surfaceContainerHigh = Color(0xFF22262C),
    surfaceContainerHighest = Color(0xFF2B3037),
    outline = Color(0xFF6B727C),
    outlineVariant = Color(0xFF353A42),
    error = Color(0xFFF87171),
)

/**
 * `sunDark` is whether the sun is down at the selected airport, for [ThemeMode.SUNSET];
 * null before an airport is known, when it follows the phone.
 */
@Composable
fun RampTheme(mode: ThemeMode = ThemeMode.SYSTEM, sunDark: Boolean? = null, content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (mode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.SUNSET -> sunDark ?: systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    // Status and navigation bar icons follow the app's theme, not the phone's.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            (view.context as? Activity)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
    }
    CompositionLocalProvider(LocalStatusColors provides if (dark) DarkStatusColors else LightStatusColors) {
        MaterialTheme(
            colorScheme = if (dark) DarkScheme else LightScheme,
            typography = Typography,
            content = content,
        )
    }
}

val isDarkTheme: Boolean
    @Composable get() = MaterialTheme.colorScheme.background.luminanceBelowHalf()

private fun Color.luminanceBelowHalf() = (0.299 * red + 0.587 * green + 0.114 * blue) < 0.5
