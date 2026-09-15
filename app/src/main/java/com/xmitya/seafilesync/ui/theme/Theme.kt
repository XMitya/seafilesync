package com.xmitya.seafilesync.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = Amber80,
    onPrimary = Amber20,
    primaryContainer = Amber30,
    onPrimaryContainer = Amber90,
    secondary = Sand80,
    onSecondary = Sand20,
    secondaryContainer = Sand30,
    onSecondaryContainer = Sand90,
    tertiary = Navy80,
    onTertiary = Navy20,
    tertiaryContainer = Navy30,
    onTertiaryContainer = Navy90,
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    background = Neutral6,
    onBackground = Neutral90,
    surface = Neutral6,
    onSurface = Neutral90,
    surfaceVariant = NeutralVariant30,
    onSurfaceVariant = NeutralVariant80,
    outline = NeutralVariant60,
    outlineVariant = NeutralVariant30,
    inverseSurface = Neutral90,
    inverseOnSurface = Neutral20,
    inversePrimary = Amber40,
)

private val LightColorScheme = lightColorScheme(
    // Brand-forward rather than the Material default of tone 40 on white: tone 40 of this hue is
    // a brown that does not read as the amber the icon is drawn in. Amber70 on Amber10 measures
    // 7.48:1, so it clears AAA while actually matching the icon.
    primary = Amber70,
    onPrimary = Amber10,
    primaryContainer = Amber90,
    onPrimaryContainer = Amber10,
    secondary = Sand40,
    onSecondary = Sand100,
    secondaryContainer = Sand90,
    onSecondaryContainer = Sand10,
    tertiary = Navy40,
    onTertiary = Navy100,
    tertiaryContainer = Navy90,
    onTertiaryContainer = Navy10,
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    background = Neutral98,
    onBackground = Neutral10,
    surface = Neutral98,
    onSurface = Neutral10,
    surfaceVariant = NeutralVariant90,
    onSurfaceVariant = NeutralVariant30,
    outline = NeutralVariant50,
    outlineVariant = NeutralVariant80,
    inverseSurface = Neutral20,
    inverseOnSurface = Neutral95,
    inversePrimary = Amber80,
)

/**
 * @param dynamicColor take the palette from the wallpaper instead of the app's own.
 *   Off by default: the app has a brand of its own now, and an icon drawn in it, so following the
 *   wallpaper would leave the two disagreeing on every device. Still exposed as a parameter for
 *   anyone who prefers Material You.
 */
@Composable
fun SeafileSyncTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
