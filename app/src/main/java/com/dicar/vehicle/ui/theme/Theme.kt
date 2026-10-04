package com.dicar.vehicle.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 车机风格配色：以深色为主（车内夜间不刺眼），青蓝作主强调色，
 * 回收/电量用青绿、提醒用琥珀、告警用红。浅色模式同一套语义。
 */
private val Cyan = Color(0xFF35C7F2)
private val CyanDeep = Color(0xFF0E7FA8)
private val Mint = Color(0xFF4ADE9B)
private val Amber = Color(0xFFFFB457)
private val Crimson = Color(0xFFFF6B6B)

private val DarkColors = darkColorScheme(
    primary = Cyan,
    onPrimary = Color(0xFF00242F),
    primaryContainer = Color(0xFF0D3344),
    onPrimaryContainer = Color(0xFFBFE9F8),
    secondary = Mint,
    onSecondary = Color(0xFF00301B),
    secondaryContainer = Color(0xFF103A2A),
    onSecondaryContainer = Color(0xFFBDF0D6),
    tertiary = Amber,
    onTertiary = Color(0xFF3A2300),
    error = Crimson,
    onError = Color(0xFF3A0000),
    errorContainer = Color(0xFF45191B),
    onErrorContainer = Color(0xFFFFD9D9),
    background = Color(0xFF07090C),
    onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF07090C),
    onSurface = Color(0xFFE6EDF3),
    onSurfaceVariant = Color(0xFF93A1B0),
    surfaceContainerLowest = Color(0xFF05070A),
    surfaceContainerLow = Color(0xFF0C1016),
    surfaceContainer = Color(0xFF11161D),
    surfaceContainerHigh = Color(0xFF1A212A),
    surfaceContainerHighest = Color(0xFF232C37),
    outline = Color(0xFF3A4654),
    outlineVariant = Color(0xFF263039),
)

private val LightColors = lightColorScheme(
    primary = CyanDeep,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCCECF8),
    onPrimaryContainer = Color(0xFF002634),
    secondary = Color(0xFF12855A),
    secondaryContainer = Color(0xFFC6F0DC),
    onSecondaryContainer = Color(0xFF00291A),
    tertiary = Color(0xFFB06A00),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF3F6F9),
    surface = Color(0xFFF3F6F9),
    onSurface = Color(0xFF131A21),
    onSurfaceVariant = Color(0xFF53626F),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFAFCFE),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFE8EDF2),
    surfaceContainerHighest = Color(0xFFDDE4EA),
    outline = Color(0xFFB4C0CB),
    outlineVariant = Color(0xFFD5DEE5),
)

/** 车机上手指操作，圆角大一些、字大一些更好按也更好读。 */
private val CarShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

private val CarTypography = Typography().run {
    copy(
        titleLarge = titleLarge.copy(fontSize = 22.sp, fontWeight = FontWeight.Bold),
        titleMedium = titleMedium.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = bodyLarge.copy(fontSize = 16.sp),
        bodyMedium = bodyMedium.copy(fontSize = 15.sp),
        labelLarge = labelLarge.copy(fontSize = 14.sp),
        labelMedium = labelMedium.copy(fontSize = 13.sp),
    )
}

/** 深浅色跟随系统（DiLink 昼夜模式会切换系统 UI mode）。 */
@Composable
fun DiCarTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        shapes = CarShapes,
        typography = CarTypography,
        content = content,
    )
}

/** 卡片等容器统一的描边色，低对比但能勾出边界，深色下尤其需要。 */
val MaterialTheme.hairline: Color
    @Composable get() = colorScheme.outlineVariant
