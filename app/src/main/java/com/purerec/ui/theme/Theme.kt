package com.purerec.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val White = Color(0xFFFFFFFF)

private val PureRecColors = darkColorScheme(
    primary = IndigoBright,
    onPrimary = White,
    primaryContainer = IndigoDeep,
    onPrimaryContainer = TextPrimary,
    secondary = Mint,
    onSecondary = DeepNight,
    tertiary = RecordRed,
    onTertiary = White,
    background = DeepNight,
    onBackground = TextPrimary,
    surface = SurfaceDark,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceElevated,
    onSurfaceVariant = TextSecondary,
    outline = SurfaceStroke,
    outlineVariant = SurfaceStroke,
    error = RecordRed,
    onError = White
)

@Composable
fun PureRecTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = DeepNight.toArgb()
            window.navigationBarColor = DeepNight.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = false
        }
    }
    MaterialTheme(
        colorScheme = PureRecColors,
        typography = PureRecTypography,
        content = content
    )
}
