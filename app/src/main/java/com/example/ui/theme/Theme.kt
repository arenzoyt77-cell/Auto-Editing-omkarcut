package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val OmkarAutoCutColorScheme = darkColorScheme(
    primary = ElectricCyan,
    onPrimary = Color(0xFF04080E),
    primaryContainer = Color(0xFF062E38),
    onPrimaryContainer = ElectricCyan,
    secondary = HyperViolet,
    onSecondary = TextPrimary,
    secondaryContainer = Color(0xFF281344),
    onSecondaryContainer = Color(0xFFE0B0FF),
    tertiary = KeyframeAmber,
    onTertiary = Color(0xFF1A1100),
    tertiaryContainer = Color(0xFF3A2800),
    onTertiaryContainer = KeyframeAmber,
    background = ObsidianBg,
    onBackground = TextPrimary,
    surface = CarbonSurface,
    onSurface = TextPrimary,
    surfaceVariant = ElevatedCardBg,
    onSurfaceVariant = TextSecondary,
    outline = GlassBorder,
    error = SplitCrimson,
    onError = Color.White
)

val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    // Always enforce the dark cyber-studio gaming/editor color scheme
    MaterialTheme(
        colorScheme = OmkarAutoCutColorScheme,
        typography = Typography,
        shapes = AppShapes,
        content = content
    )
}
