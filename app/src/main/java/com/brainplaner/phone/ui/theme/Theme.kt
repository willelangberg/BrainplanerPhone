package com.brainplaner.phone.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = BrainTeal,
    onPrimary = Color.Black,
    primaryContainer = BrainTealDark,
    onPrimaryContainer = BrainTealLight,
    secondary = BrainDeepLight,
    onSecondary = Color.White,
    secondaryContainer = BrainDeep,
    onSecondaryContainer = Color(0xFFE8EAF6),
    tertiary = BrainWarmLight,
    onTertiary = Color.Black,
    error = BudgetRed,
    background = DarkSurface,
    onBackground = Color(0xFFE6E1E5),
    surface = DarkSurface,
    onSurface = Color(0xFFE6E1E5),
    surfaceVariant = DarkSurfaceCard,
    onSurfaceVariant = Color(0xFFCAC4D0),
    outline = Color(0xFF938F99),
    inverseSurface = Color(0xFFE6E1E5),
    inverseOnSurface = Color(0xFF1A1C1E),
    inversePrimary = BrainTealDark,
    surfaceTint = BrainTeal,
    scrim = OverlayScrim,
)

private val LightColorScheme = lightColorScheme(
    primary = BrainTealDark,
    onPrimary = Color.White,
    primaryContainer = BrainTealLight.copy(alpha = 0.3f),
    onPrimaryContainer = BrainTealDark,
    secondary = BrainDeep,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE8EAF6),
    onSecondaryContainer = BrainDeep,
    tertiary = BrainWarm,
    onTertiary = Color.White,
    error = BudgetRed,
    background = LightSurface,
    onBackground = Color(0xFF1C1B1F),
    surface = LightSurface,
    onSurface = Color(0xFF1C1B1F),
    surfaceVariant = LightSurfaceCard,
    onSurfaceVariant = Color(0xFF49454F),
    outline = Color(0xFF79747E),
    inverseSurface = Color(0xFF313033),
    inverseOnSurface = Color(0xFFF4EFF4),
    inversePrimary = BrainTeal,
    surfaceTint = BrainTealDark,
    scrim = OverlayScrim,
)

@Composable
fun BrainplanerPhoneTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val semanticColors = if (darkTheme) {
        BrainplanerSemanticColors(
            success = BudgetGreen,
            warning = BudgetYellow,
            info = BrainInfo,
            critical = BrainCritical,
        )
    } else {
        BrainplanerSemanticColors(
            success = BudgetGreen,
            warning = Color(0xFFFFB300),
            info = BrainInfo,
            critical = BrainCritical,
        )
    }
    val surfaceRoles = if (darkTheme) {
        BrainplanerSurfaceRoles(
            surface1 = DarkSurface1,
            surface2 = DarkSurface2,
            surface3 = DarkSurface3,
            strokeSubtle = StrokeSubtleDark,
            strokeStrong = StrokeStrongDark,
            focusRing = FocusRing,
            scrim = OverlayScrim,
        )
    } else {
        BrainplanerSurfaceRoles(
            surface1 = LightSurface1,
            surface2 = LightSurface2,
            surface3 = LightSurface3,
            strokeSubtle = StrokeSubtleLight,
            strokeStrong = StrokeStrongLight,
            focusRing = FocusRing,
            scrim = OverlayScrim,
        )
    }

    // Tint the status bar to match the theme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    CompositionLocalProvider(
        LocalSemanticColors provides semanticColors,
        LocalSurfaceRoles provides surfaceRoles,
        LocalSpacing provides BrainplanerSpacing(),
        LocalRadius provides BrainplanerRadius(),
        LocalElevation provides BrainplanerElevation(),
        LocalMotion provides BrainplanerMotion(),
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}