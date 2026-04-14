package com.brainplaner.phone.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class BrainplanerSemanticColors(
    val success: Color,
    val warning: Color,
    val info: Color,
    val critical: Color,
)

@Immutable
data class BrainplanerSurfaceRoles(
    val surface1: Color,
    val surface2: Color,
    val surface3: Color,
    val strokeSubtle: Color,
    val strokeStrong: Color,
    val focusRing: Color,
    val scrim: Color,
)

@Immutable
data class BrainplanerSpacing(
    val xxs: Dp = 4.dp,
    val xs: Dp = 8.dp,
    val sm: Dp = 12.dp,
    val md: Dp = 16.dp,
    val lg: Dp = 20.dp,
    val xl: Dp = 24.dp,
    val xxl: Dp = 32.dp,
)

@Immutable
data class BrainplanerRadius(
    val sm: Dp = 8.dp,
    val md: Dp = 14.dp,
    val lg: Dp = 20.dp,
    val pill: Dp = 999.dp,
)

@Immutable
data class BrainplanerElevation(
    val flat: Dp = 0.dp,
    val card: Dp = 2.dp,
    val floating: Dp = 6.dp,
    val modal: Dp = 12.dp,
)

@Immutable
data class BrainplanerMotion(
    val fastMs: Int = 120,
    val standardMs: Int = 220,
    val emphasizedMs: Int = 320,
)

internal val LocalSemanticColors = staticCompositionLocalOf {
    BrainplanerSemanticColors(
        success = BudgetGreen,
        warning = BudgetYellow,
        info = BrainInfo,
        critical = BrainCritical,
    )
}

internal val LocalSurfaceRoles = staticCompositionLocalOf {
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

internal val LocalSpacing = staticCompositionLocalOf { BrainplanerSpacing() }
internal val LocalRadius = staticCompositionLocalOf { BrainplanerRadius() }
internal val LocalElevation = staticCompositionLocalOf { BrainplanerElevation() }
internal val LocalMotion = staticCompositionLocalOf { BrainplanerMotion() }

object BrainplanerTheme {
    val semanticColors: BrainplanerSemanticColors
        @Composable
        @ReadOnlyComposable
        get() = LocalSemanticColors.current

    val surfaceRoles: BrainplanerSurfaceRoles
        @Composable
        @ReadOnlyComposable
        get() = LocalSurfaceRoles.current

    val spacing: BrainplanerSpacing
        @Composable
        @ReadOnlyComposable
        get() = LocalSpacing.current

    val radius: BrainplanerRadius
        @Composable
        @ReadOnlyComposable
        get() = LocalRadius.current

    val elevation: BrainplanerElevation
        @Composable
        @ReadOnlyComposable
        get() = LocalElevation.current

    val motion: BrainplanerMotion
        @Composable
        @ReadOnlyComposable
        get() = LocalMotion.current
}