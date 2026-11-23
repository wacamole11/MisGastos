package com.gastos.app.ui.theme

import android.app.Activity
import android.os.Build
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
    primary = OceanBluePrimary,
    onPrimary = OceanBlueOnPrimary,
    primaryContainer = OceanSoftContainer,
    onPrimaryContainer = TextLight,
    secondary = TealAccent,
    onSecondary = Color(0xFF003737),
    secondaryContainer = Color(0xFF163E48),
    onSecondaryContainer = Color(0xFFB0F2F2),
    tertiary = SlateBlue,
    background = NavyDeep,
    onBackground = TextLight,
    surface = NavySurface,
    onSurface = TextLight,
    surfaceVariant = NavySurfaceVariant,
    onSurfaceVariant = SlateBlue
)

private val LightColorScheme = lightColorScheme(
    primary = NavyPrimaryLight,
    onPrimary = Color.White,
    primaryContainer = SoftContainerLight,
    onPrimaryContainer = NavyPrimaryLight,
    secondary = OceanBlueAccent,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD4E3F5),
    onSecondaryContainer = Color(0xFF0D2547),
    tertiary = TealAccent,
    background = SoftBlueBackground,
    onBackground = Color(0xFF0F1E36),
    surface = SoftWhiteSurface,
    onSurface = Color(0xFF0F1E36),
    surfaceVariant = Color(0xFFE2EAF2),
    onSurfaceVariant = MutedSlate
)

@Composable
fun MisGastosTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Desactivamos dynamicColor para que SIEMPRE predomine la paleta azul marino relajante
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
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