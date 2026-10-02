package com.kivan.tether.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Gruvbox dark (morhetz/gruvbox). The app is always dark, whatever the system setting. */
object Gruvbox {
    val bg0h = Color(0xFF1D2021)
    val bg0 = Color(0xFF282828)
    val bg0s = Color(0xFF32302F)
    val bg1 = Color(0xFF3C3836)
    val bg2 = Color(0xFF504945)
    val bg4 = Color(0xFF7C6F64)
    val fg0 = Color(0xFFFBF1C7)
    val fg1 = Color(0xFFEBDBB2)
    val fg3 = Color(0xFFBDAE93)
    val red = Color(0xFFFB4934)
    val redDim = Color(0xFFCC241D)
    val green = Color(0xFFB8BB26)
    val yellow = Color(0xFFFABD2F)
    val yellowDim = Color(0xFFB57614)
    val blue = Color(0xFF83A598)
    val aqua = Color(0xFF8EC07C)
    val aquaDark = Color(0xFF427B58)
}

private val scheme = darkColorScheme(
    primary = Gruvbox.yellow,
    onPrimary = Gruvbox.bg0,
    primaryContainer = Gruvbox.bg2,
    onPrimaryContainer = Gruvbox.yellow,
    inversePrimary = Gruvbox.yellowDim,
    secondary = Gruvbox.blue,
    onSecondary = Gruvbox.bg0,
    secondaryContainer = Gruvbox.bg2,
    onSecondaryContainer = Gruvbox.fg1,
    tertiary = Gruvbox.aqua,
    onTertiary = Gruvbox.bg0,
    tertiaryContainer = Gruvbox.aquaDark,
    onTertiaryContainer = Gruvbox.fg0,
    error = Gruvbox.red,
    onError = Gruvbox.bg0,
    errorContainer = Gruvbox.redDim,
    onErrorContainer = Gruvbox.fg0,
    background = Gruvbox.bg0,
    onBackground = Gruvbox.fg1,
    surface = Gruvbox.bg0,
    onSurface = Gruvbox.fg1,
    surfaceVariant = Gruvbox.bg1,
    onSurfaceVariant = Gruvbox.fg3,
    surfaceTint = Gruvbox.yellow,
    inverseSurface = Gruvbox.fg1,
    inverseOnSurface = Gruvbox.bg0,
    outline = Gruvbox.bg4,
    outlineVariant = Gruvbox.bg2,
    surfaceDim = Gruvbox.bg0h,
    surfaceBright = Gruvbox.bg1,
    surfaceContainerLowest = Gruvbox.bg0h,
    surfaceContainerLow = Gruvbox.bg0s,
    surfaceContainer = Gruvbox.bg0s,
    surfaceContainerHigh = Gruvbox.bg1,
    surfaceContainerHighest = Gruvbox.bg2,
)

@Composable
fun TetherTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = scheme, content = content)
