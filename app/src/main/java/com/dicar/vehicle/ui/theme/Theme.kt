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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 「极简白昼」配色：浅灰底、白卡片、细描边、大留白，蓝色作强调色；
 * 电量永远是绿、油量永远是橙、告警永远是红——同一个量在哪一页都是同一个颜色。
 *
 * 夜间版不是把浅色反相，而是同一套结构换一组色：底色更深、卡片比底色亮一级、
 * 描边降对比，强调色按深色背景重新取（同色相、更亮一点，保证在黑底上也够跳）。
 */
private object Day {
    val Accent = Color(0xFF0A84FF)       // 强调：车速、转速、驱动功率
    val Green = Color(0xFF34C759)        // 电量
    val Orange = Color(0xFFFF9F0A)       // 油量
    val Red = Color(0xFFFF3B30)          // 告警

    val Background = Color(0xFFEEF1F5)
    val Card = Color(0xFFFFFFFF)
    val CardAlt = Color(0xFFF7F9FB)
    val Track = Color(0xFFE8ECF1)        // 进度条/表盘底环
    val TrackDeep = Color(0xFFDDE3EA)
    val Text = Color(0xFF0F1419)
    val Dim = Color(0xFF8A94A3)          // 标签、单位、次要文字
    val Border = Color(0xFFE2E6EC)
    val BorderStrong = Color(0xFFC7CEd8)
}

private object Night {
    val Accent = Color(0xFF4DA3FF)
    val Green = Color(0xFF30D158)
    val Orange = Color(0xFFFFB340)
    val Red = Color(0xFFFF6961)

    val Background = Color(0xFF121417)
    val Card = Color(0xFF1C1F23)
    val CardAlt = Color(0xFF16191D)
    val Track = Color(0xFF2A2F36)
    val TrackDeep = Color(0xFF343A42)
    val Text = Color(0xFFECEDEE)
    val Dim = Color(0xFF9AA1AC)
    val Border = Color(0xFF2E3238)
    val BorderStrong = Color(0xFF454C55)
}

private val LightColors = lightColorScheme(
    primary = Day.Accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCEBFF),
    onPrimaryContainer = Color(0xFF00345F),
    secondary = Day.Green,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD7F5E0),
    onSecondaryContainer = Color(0xFF0B3D1E),
    tertiary = Day.Orange,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFECCF),
    onTertiaryContainer = Color(0xFF4A2D00),
    error = Day.Red,
    onError = Color.White,
    errorContainer = Color(0xFFFFE0DE),
    onErrorContainer = Color(0xFF5C0F0B),
    background = Day.Background,
    onBackground = Day.Text,
    surface = Day.Background,
    onSurface = Day.Text,
    surfaceVariant = Day.Track,
    onSurfaceVariant = Day.Dim,
    surfaceContainerLowest = Day.Card,
    surfaceContainerLow = Day.CardAlt,
    surfaceContainer = Day.Card,
    surfaceContainerHigh = Day.Track,
    surfaceContainerHighest = Day.TrackDeep,
    outline = Day.BorderStrong,
    outlineVariant = Day.Border,
)

private val DarkColors = darkColorScheme(
    primary = Night.Accent,
    onPrimary = Color(0xFF00243F),
    primaryContainer = Color(0xFF123A5E),
    onPrimaryContainer = Color(0xFFCFE4FF),
    secondary = Night.Green,
    onSecondary = Color(0xFF00210E),
    secondaryContainer = Color(0xFF143C23),
    onSecondaryContainer = Color(0xFFCCF2D7),
    tertiary = Night.Orange,
    onTertiary = Color(0xFF3A2300),
    tertiaryContainer = Color(0xFF4E3410),
    onTertiaryContainer = Color(0xFFFFE6C2),
    error = Night.Red,
    onError = Color(0xFF3A0000),
    errorContainer = Color(0xFF4D1F1D),
    onErrorContainer = Color(0xFFFFDAD7),
    background = Night.Background,
    onBackground = Night.Text,
    surface = Night.Background,
    onSurface = Night.Text,
    surfaceVariant = Night.Track,
    onSurfaceVariant = Night.Dim,
    surfaceContainerLowest = Color(0xFF101316),
    surfaceContainerLow = Night.CardAlt,
    surfaceContainer = Night.Card,
    surfaceContainerHigh = Night.Track,
    surfaceContainerHighest = Night.TrackDeep,
    outline = Night.BorderStrong,
    outlineVariant = Night.Border,
)

/** 车机上手指操作，圆角大一些、字大一些更好按也更好读。 */
private val CarShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * 关键数字用大字号细字重，标签用小字号浅色。
 *
 * display/headline 一律开 `tnum`（等宽数字）：默认字体里 1 比其他数字窄，
 * 车速从 111 跳到 100 整行宽度会变，后面的单位跟着左右抖。开了这个特性
 * 数字按等宽排版，字形还是正常的无衬线体，不会变成「代码感」的等宽字体。
 */
private const val TABULAR = "tnum"

private val CarTypography = Typography().run {
    copy(
        displayLarge = TextStyle(fontWeight = FontWeight.Light, fontSize = 64.sp, fontFeatureSettings = TABULAR),
        displayMedium = TextStyle(fontWeight = FontWeight.Light, fontSize = 46.sp, fontFeatureSettings = TABULAR),
        displaySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 34.sp, fontFeatureSettings = TABULAR),
        headlineMedium = headlineMedium.copy(fontSize = 28.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = TABULAR),
        headlineSmall = headlineSmall.copy(fontSize = 22.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = TABULAR),
        titleLarge = titleLarge.copy(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
        titleSmall = titleSmall.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = TABULAR),
        bodyLarge = bodyLarge.copy(fontSize = 16.sp, fontFeatureSettings = TABULAR),
        bodyMedium = bodyMedium.copy(fontSize = 15.sp),
        labelLarge = labelLarge.copy(fontSize = 14.sp),
        labelMedium = labelMedium.copy(fontSize = 13.sp),
        labelSmall = labelSmall.copy(fontSize = 11.sp),
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

/** 卡片等容器统一的描边色，低对比但能勾出边界。 */
val MaterialTheme.hairline: Color
    @Composable get() = colorScheme.outlineVariant

/** 车身在孪生图里的填充色：白昼是白车身，夜间是比卡片亮一级的深灰。 */
val MaterialTheme.carBody: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xFF2C3238) else Color.White

/** 玻璃（风挡、天窗、侧窗）：比车身深一档的中性灰。 */
val MaterialTheme.carGlass: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xFF454D56) else Color(0xFFD4DBE3)

/**
 * 轮胎：两套主题下都是全图最深的一块，保证四个角一眼能定位。
 * 夜间不能真用纯黑——车身外侧那半个轮子正好压在深色背景上，纯黑等于隐形，
 * 所以取一个比背景略亮的值，再配上描边（见 CarDiagram）才看得出轮廓。
 */
val MaterialTheme.carTyre: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xFF05070A) else Color(0xFF2B3038)
