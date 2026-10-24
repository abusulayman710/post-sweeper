package app.sweeper.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import app.sweeper.data.ThemeMode

private val Mint = Color(0xFF12B886)

private val DarkScheme = darkColorScheme(
    primary = Mint,
    onPrimary = Color(0xFF04261C),
    primaryContainer = Color(0xFF0B3D2F),
    onPrimaryContainer = Color(0xFFB8F5E0),
    background = Color(0xFF0E1116),
    onBackground = Color(0xFFE8EAED),
    surface = Color(0xFF12161C),
    onSurface = Color(0xFFE8EAED),
    surfaceVariant = Color(0xFF1B212A),
    onSurfaceVariant = Color(0xFF9AA4B2),
    surfaceContainer = Color(0xFF161B22),
    surfaceContainerHigh = Color(0xFF1B212A),
    outline = Color(0xFF3A4350),
    error = Color(0xFFFF6B6B),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF0B8F68),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC9F4E4),
    onPrimaryContainer = Color(0xFF00382A),
    background = Color(0xFFF7F9FA),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE9EEF1),
    surfaceContainer = Color(0xFFEFF3F5),
    surfaceContainerHigh = Color(0xFFE6ECEF),
    error = Color(0xFFD64545),
)

@Composable
fun SweeperTheme(mode: ThemeMode, dynamic: Boolean, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val ctx = LocalContext.current
    val scheme = when {
        dynamic && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> DarkScheme
        else -> LightScheme
    }
    MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
}

val Warn = Color(0xFFFFA94D)
val Good = Color(0xFF12B886)
