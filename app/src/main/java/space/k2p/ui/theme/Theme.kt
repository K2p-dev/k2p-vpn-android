package space.k2p.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.materialkolor.DynamicMaterialTheme
import com.materialkolor.PaletteStyle
import space.k2p.core.preferences.AccentMode
import space.k2p.core.preferences.ThemeConfig
import space.k2p.core.preferences.ThemeMode

val BrandDarkColorScheme: ColorScheme = darkColorScheme(
    primary = BrandCrimson,
    onPrimary = Color.White,
    primaryContainer = BrandCrimsonDark,
    onPrimaryContainer = Color(0xFFFFDADA),
    secondary = BrandCrimsonLight,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF381017),
    onSecondaryContainer = Color(0xFFFFDADA),
    tertiary = Color(0xFFFF8595),
    onTertiary = Color(0xFF5A0014),
    background = BrandDarkBackground, // #070506
    onBackground = BrandDarkTextPrimary,
    surface = BrandDarkSurface, // #130D0F
    onSurface = BrandDarkTextPrimary,
    surfaceVariant = BrandDarkSurfaceVariant, // #221519
    onSurfaceVariant = BrandDarkTextSecondary,
    outline = BrandDarkOutline, // #3B1E26
    outlineVariant = Color(0xFF2B181E)
)

val BrandLightColorScheme: ColorScheme = lightColorScheme(
    primary = BrandCrimson,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDADA),
    onPrimaryContainer = Color(0xFF41000A),
    secondary = Color(0xFF9C4146),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDADA),
    onSecondaryContainer = Color(0xFF3B080E),
    tertiary = Color(0xFF825346),
    onTertiary = Color.White,
    background = Color(0xFFF7F3F4), // Clean light background
    onBackground = Color(0xFF1E191A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1E191A),
    surfaceVariant = Color(0xFFF1EAEB),
    onSurfaceVariant = Color(0xFF524345),
    outline = Color(0xFF857375)
)

fun parseHexColor(hex: String, fallback: Color = BrandCrimson): Color {
    return try {
        val clean = hex.removePrefix("#")
        val colorInt = when (clean.length) {
            6 -> (0xFF000000 or clean.toLong(16)).toInt()
            8 -> clean.toLong(16).toInt()
            else -> return fallback
        }
        Color(colorInt)
    } catch (_: Exception) {
        fallback
    }
}

@Composable
fun K2pVpnTheme(
    themeConfig: ThemeConfig = ThemeConfig(),
    content: @Composable () -> Unit
) {
    val isDark = when (themeConfig.themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    val context = LocalContext.current

    when (themeConfig.accentMode) {
        AccentMode.BRAND -> {
            val colorScheme = if (isDark) BrandDarkColorScheme else BrandLightColorScheme
            MaterialTheme(
                colorScheme = colorScheme,
                typography = Typography,
                content = content
            )
        }
        AccentMode.DYNAMIC -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val dynamicScheme = if (isDark) {
                    dynamicDarkColorScheme(context).copy(
                        background = BrandDarkBackground,
                        surface = BrandDarkSurface,
                        surfaceVariant = BrandDarkSurfaceVariant,
                        outline = BrandDarkOutline
                    )
                } else {
                    dynamicLightColorScheme(context)
                }
                MaterialTheme(
                    colorScheme = dynamicScheme,
                    typography = Typography,
                    content = content
                )
            } else {
                // Fallback to Brand theme if dynamic colors are not supported
                val colorScheme = if (isDark) BrandDarkColorScheme else BrandLightColorScheme
                MaterialTheme(
                    colorScheme = colorScheme,
                    typography = Typography,
                    content = content
                )
            }
        }
        AccentMode.CUSTOM -> {
            val seed = parseHexColor(themeConfig.customAccentHex, fallback = BrandCrimson)
            DynamicMaterialTheme(
                seedColor = seed,
                useDarkTheme = isDark,
                style = PaletteStyle.TonalSpot,
                typography = Typography,
                content = content
            )
        }
    }
}
