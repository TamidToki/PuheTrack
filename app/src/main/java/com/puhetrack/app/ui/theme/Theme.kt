package com.puhetrack.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Google-inspired modern palette — vibrant, clear, high-contrast
private val GoogleBlue = Color(0xFF1A73E8)
private val GoogleBlueDark = Color(0xFF8AB4F8)
private val GoogleBlueLight = Color(0xFFE8F0FE)

private val GoogleGreen = Color(0xFF1E8E3E)
private val GoogleGreenDark = Color(0xFF81C995)
private val GoogleGreenLight = Color(0xFFE6F4EA)

private val GoogleAmber = Color(0xFFE37400)
private val GoogleAmberDark = Color(0xFFFDD663)
private val GoogleAmberLight = Color(0xFFFEF7E0)

private val GoogleRed = Color(0xFFD93025)
private val GoogleRedLight = Color(0xFFFCE8E6)

private val LightFallback = lightColorScheme(
    primary = GoogleBlue,
    onPrimary = Color.White,
    primaryContainer = GoogleBlueLight,
    onPrimaryContainer = Color(0xFF174EA6),
    secondary = GoogleGreen,
    onSecondary = Color.White,
    secondaryContainer = GoogleGreenLight,
    onSecondaryContainer = Color(0xFF0D652D),
    tertiary = GoogleAmber,
    onTertiary = Color.White,
    tertiaryContainer = GoogleAmberLight,
    onTertiaryContainer = Color(0xFF7A3E00),
    error = GoogleRed,
    onError = Color.White,
    errorContainer = GoogleRedLight,
    onErrorContainer = Color(0xFFB0120C),
    background = Color(0xFFF8FAFD),
    onBackground = Color(0xFF1F1F1F),
    surface = Color.White,
    onSurface = Color(0xFF1F1F1F),
    surfaceVariant = Color(0xFFF1F3F4),
    onSurfaceVariant = Color(0xFF444746),
    outline = Color(0xFF747775),
    outlineVariant = Color(0xFFC4C7C5),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF8FAFD),
    surfaceContainer = Color(0xFFF1F3F4),
    surfaceContainerHigh = Color(0xFFE8EAED),
    surfaceContainerHighest = Color(0xFFE0E2E5)
)

private val DarkFallback = darkColorScheme(
    primary = GoogleBlueDark,
    onPrimary = Color(0xFF042B66),
    primaryContainer = Color(0xFF0D47A1),
    onPrimaryContainer = GoogleBlueLight,
    secondary = GoogleGreenDark,
    onSecondary = Color(0xFF0A3818),
    secondaryContainer = Color(0xFF14532D),
    onSecondaryContainer = GoogleGreenLight,
    tertiary = GoogleAmberDark,
    onTertiary = Color(0xFF4A2500),
    tertiaryContainer = Color(0xFF78350F),
    onTertiaryContainer = GoogleAmberLight,
    error = Color(0xFFF28B82),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFFCE8E6),
    background = Color(0xFF121212),
    onBackground = Color(0xFFE3E3E3),
    surface = Color(0xFF1E1E1E),
    onSurface = Color(0xFFE3E3E3),
    surfaceVariant = Color(0xFF2D2F31),
    onSurfaceVariant = Color(0xFFC4C7C5),
    outline = Color(0xFF8E918F),
    outlineVariant = Color(0xFF444746),
    surfaceContainerLowest = Color(0xFF0F0F0F),
    surfaceContainerLow = Color(0xFF1A1A1A),
    surfaceContainer = Color(0xFF222222),
    surfaceContainerHigh = Color(0xFF2C2C2C),
    surfaceContainerHighest = Color(0xFF353535)
)

// M3 Expressive: rounded shapes for cards/buttons.
private val ExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

// Custom typography with refined weights
private val PuheTypography = Typography(
    headlineLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = 0.sp
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.sp
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    )
)

@Composable
fun PuheTrackTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkFallback
        else -> LightFallback
    }
    MaterialTheme(
        colorScheme = colorScheme,
        shapes = ExpressiveShapes,
        typography = PuheTypography,
        content = content
    )
}
