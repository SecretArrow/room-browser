package com.roombrowser.ui.common

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.roombrowser.domain.model.ThemeMode

/**
 * Room Browser theme: Material 3 with per-profile accent color, AMOLED
 * dark mode and high-contrast support. System / Light / Dark / AMOLED.
 */
private fun scheme(accent: Color, dark: Boolean, amoled: Boolean, highContrast: Boolean): ColorScheme {
    val bg = when {
        amoled -> Color.Black
        dark -> Color(0xFF121218)
        else -> Color(0xFFFBFBFE)
    }
    val surface = when {
        amoled -> Color(0xFF0A0A0F)
        dark -> Color(0xFF1B1B24)
        else -> Color(0xFFFFFFFF)
    }
    return (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = accent,
        onPrimary = if (dark) Color(0xFF0B0B10) else Color.White,
        primaryContainer = accent.copy(alpha = 0.25f),
        secondary = accent.copy(alpha = 0.8f),
        background = bg,
        onBackground = if (dark) Color(0xFFE6E1EC) else Color(0xFF1B1B24),
        surface = surface,
        onSurface = if (dark) Color(0xFFE6E1EC) else Color(0xFF1B1B24),
        surfaceVariant = if (dark) Color(0xFF26262F) else Color(0xFFEEEDF3),
        onSurfaceVariant = if (dark) Color(0xFFC8C4D0) else Color(0xFF49454F),
        outline = if (highContrast) (if (dark) Color.White else Color.Black) else Color(0xFF7A7585)
    )
}

private val RoomTypography = Typography(
    headlineSmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 24.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.Default, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.Default, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp)
)

@Composable
fun RoomBrowserTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    accentArgb: Long = 0xFF6750A4,
    highContrast: Boolean = false,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.AMOLED -> true
    }

    // Keep system-bar icon contrast in sync with the APP's effective theme.
    // enableEdgeToEdge() only knows the SYSTEM dark mode — when the app runs
    // dark while the system is light (or vice versa) the clock/battery icons
    // and the 3 navigation buttons would otherwise become invisible.
    val view = LocalView.current
    if (!view.isInEditMode) {
        DisposableEffect(dark) {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !dark
                controller.isAppearanceLightNavigationBars = !dark
            }
            onDispose { }
        }
    }

    MaterialTheme(
        colorScheme = scheme(
            accent = Color(accentArgb.toInt()),
            dark = dark,
            amoled = themeMode == ThemeMode.AMOLED,
            highContrast = highContrast
        ),
        typography = RoomTypography,
        content = content
    )
}

/** Convenience overload with a raw accent (used by BrowserActivity). */
@Composable
fun RoomBrowserTheme(
    profileAccent: Long,
    content: @Composable () -> Unit
) {
    RoomBrowserTheme(themeMode = ThemeMode.SYSTEM, accentArgb = profileAccent, content = content)
}
