package com.john.assistant.presentation.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColors = darkColorScheme(
    primary = Violet60,
    onPrimary = Ink,
    primaryContainer = Violet20,
    onPrimaryContainer = Violet80,
    secondary = Mint60,
    onSecondary = Ink,
    tertiary = Amber60,
    error = Rose60,
    background = Ink,
    onBackground = Paper,
    surface = Ink,
    onSurface = Paper,
    surfaceVariant = InkElevated,
    onSurfaceVariant = PaperMuted,
    outline = InkOutline,
)

private val LightColors = lightColorScheme(
    primary = Violet40,
    onPrimary = Paper,
    primaryContainer = Violet80,
    onPrimaryContainer = Violet20,
    secondary = Mint40,
    onSecondary = Paper,
    tertiary = Amber60,
    error = Rose60,
    background = Paper,
    onBackground = Ink,
    surface = PaperElevated,
    onSurface = Ink,
    surfaceVariant = Paper,
    onSurfaceVariant = InkOutline,
    outline = PaperOutline,
)

/**
 * @param dynamicColor follow the wallpaper on Android 12+. On by default: an
 *   assistant sits alongside the system UI and looks out of place ignoring it.
 */
@Composable
fun JohnTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = JohnTypography,
        content = content,
    )
}
