package com.flareaward.serendip.presentation.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors: ColorScheme = darkColorScheme(
    primary = Gold,
    onPrimary = GoldOn,
    primaryContainer = GoldDeep,
    onPrimaryContainer = GoldPale,
    inversePrimary = GoldDeep,
    secondary = Sand,
    onSecondary = SandOn,
    secondaryContainer = SandDeep,
    onSecondaryContainer = SandPale,
    tertiary = Sage,
    onTertiary = SageOn,
    tertiaryContainer = SageDeep,
    onTertiaryContainer = SagePale,
    error = Coral,
    onError = CoralOn,
    errorContainer = CoralDeep,
    onErrorContainer = CoralPale,
    background = Ink,
    onBackground = Mist,
    surface = Graphite,
    onSurface = Mist,
    surfaceVariant = SlateHigh,
    onSurfaceVariant = MistDim,
    surfaceTint = Gold,
    inverseSurface = Mist,
    inverseOnSurface = Ink,
    outline = Outline,
    outlineVariant = OutlineFaint,
    scrim = Color.Black,
    surfaceBright = SlateHighest,
    surfaceDim = Ink,
    surfaceContainerLowest = Color(0xFF07080B),
    surfaceContainerLow = Color(0xFF15161B),
    surfaceContainer = Slate,
    surfaceContainerHigh = SlateHigh,
    surfaceContainerHighest = SlateHighest,
)

/**
 * The app is dark-only by design (a camera that works at night should not
 * flash a white UI). There is no light theme and no theme switch.
 */
@Composable
fun SerendipTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = SerendipTypography,
        shapes = SerendipShapes,
        content = content,
    )
}
