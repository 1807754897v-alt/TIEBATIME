package com.huanchengfly.tieba.post.theme.colorscheme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.huanchengfly.tieba.post.theme.ColorSchemeDayNight

/**
 * 默认主题：暗红（TiebaLocal 新增内置主题）
 * Primary:  0xFF924040
 * Tertiary: 0xFF75565B
 * Neutral:  0xFFA08C8C
 * */
val DarkRedColorScheme: ColorSchemeDayNight = ColorSchemeDayNight(
    lightColor = lightColorScheme(
        primary = Color(0xFF924040),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFFFDAD6),
        onPrimaryContainer = Color(0xFF5C1A1A),
        inversePrimary = Color(0xFFFFB4AB),
        secondary = Color(0xFF775654),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFFFDAD6),
        onSecondaryContainer = Color(0xFF2C1517),
        tertiary = Color(0xFF75565B),
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFFFD9DE),
        onTertiaryContainer = Color(0xFF2C1519),
        background = Color(0xFFFFFFFF),
        onBackground = Color(0xFF201A1A),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF201A1A),
        surfaceVariant = Color(0xFFF0F0F0),
        onSurfaceVariant = Color(0xFF444444),
        inverseSurface = Color(0xFF303030),
        inverseOnSurface = Color(0xFFF2F2F2),
        outline = Color(0xFF757575),
        outlineVariant = Color(0xFFCFCFCF),
        surfaceBright = Color(0xFFFFFFFF),
        surfaceContainer = Color(0xFFF2F2F2),
        surfaceContainerHigh = Color(0xFFECECEC),
        surfaceContainerHighest = Color(0xFFE6E6E6),
        surfaceContainerLow = Color(0xFFFAFAFA),
        surfaceContainerLowest = Color.White,
        surfaceDim = Color(0xFFDBDBDB)
    ),
    darkColor = darkColorScheme(
        primary = Color(0xFFFFB4AB),
        onPrimary = Color(0xFF5C1A1A),
        primaryContainer = Color(0xFF7F1D1D),
        onPrimaryContainer = Color(0xFFFFDAD6),
        inversePrimary = Color(0xFF924040),
        secondary = Color(0xFFE7BDBB),
        onSecondary = Color(0xFF442929),
        secondaryContainer = Color(0xFF5D3F3F),
        onSecondaryContainer = Color(0xFFFFDAD6),
        tertiary = Color(0xFFE7C0C4),
        onTertiary = Color(0xFF48252A),
        tertiaryContainer = Color(0xFF613B40),
        onTertiaryContainer = Color(0xFFFFD9DE),
        background = Color(0xFF1A1111),
        onBackground = Color(0xFFF1DEDD),
        surface = Color(0xFF1A1111),
        onSurface = Color(0xFFF1DEDD),
        surfaceVariant = Color(0xFF534344),
        onSurfaceVariant = Color(0xFFD7C1C1),
        inverseSurface = Color(0xFFF1DEDD),
        inverseOnSurface = Color(0xFF382E2E),
        outline = Color(0xFFA08C8C),
        outlineVariant = Color(0xFF534344),
        scrim = Color.Black,
        surfaceBright = Color(0xFF3B2F2F),
        surfaceContainer = Color(0xFF261919),
        surfaceContainerHigh = Color(0xFF332323),
        surfaceContainerHighest = Color(0xFF3E2D2D),
        surfaceContainerLow = Color(0xFF201414),
        surfaceContainerLowest = Color(0xFF140C0C),
        surfaceDim = Color(0xFF1A1111)
    )
)
