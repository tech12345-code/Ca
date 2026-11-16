package com.smartexpense.manager.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object SmartColors {
    val Background = Color(0xFF0B0D0F)
    val Surface = Color(0xFF121519)
    val SurfaceElevated = Color(0xFF181C22)
    val Border = Color(0xFF262B33)
    val NeonLime = Color(0xFFC6FF33)
    val ExpenseOrange = Color(0xFFFF6B2C)
    val IncomeMint = Color(0xFF2CE59B)
    val TextPrimary = Color(0xFFF3F5F7)
    val TextMuted = Color(0xFF9CA3AF)
    val WarningYellow = Color(0xFFFACC15)
    val DangerRed = Color(0xFFEF4444)
}

private val DarkScheme = darkColorScheme(
    primary = SmartColors.NeonLime,
    onPrimary = SmartColors.Background,
    secondary = SmartColors.ExpenseOrange,
    onSecondary = Color.White,
    background = SmartColors.Background,
    onBackground = SmartColors.TextPrimary,
    surface = SmartColors.Surface,
    onSurface = SmartColors.TextPrimary,
    surfaceVariant = SmartColors.SurfaceElevated,
    onSurfaceVariant = SmartColors.TextMuted,
    outline = SmartColors.Border,
    error = SmartColors.DangerRed
)

private val SmartTypography = Typography(
    headlineLarge = TextStyle(
        fontSize = 30.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.5).sp,
        color = SmartColors.TextPrimary
    ),
    titleLarge = TextStyle(
        fontSize = 20.sp,
        fontWeight = FontWeight.SemiBold,
        color = SmartColors.TextPrimary
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
        color = SmartColors.TextPrimary
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.Normal,
        color = SmartColors.TextPrimary
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        color = SmartColors.TextMuted
    )
)

@Composable
fun SmartExpenseTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkScheme,
        typography = SmartTypography,
        content = content
    )
}
