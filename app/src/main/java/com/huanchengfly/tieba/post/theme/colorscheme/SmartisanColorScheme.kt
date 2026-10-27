package com.huanchengfly.tieba.post.theme.colorscheme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.huanchengfly.tieba.post.theme.ColorSchemeDayNight

/**
 * 锤子 Smartisan OS 风格。
 *
 * 取值来自社区复刻的 Smartisan OS UI Kit（Figma 1419385240874679870，Paix Chan，CC BY 4.0）
 * 设计 token，颜色语义按 Figma 变量名对齐：灰阶承担文字/分隔/描边，蓝只做操作色。
 * 该 Kit 没有暗色规范（真机 Smartisan R2 也未开系统暗色），darkColor 是按同一灰阶反推的，
 * 不代表官方配色。
 */
object SmartisanPalette {
    val white = Color(0xFFFFFFFF)
    val grey100 = Color(0xFFFAFAFA)   // 页面/容器底
    val grey200 = Color(0xFFF2F2F2)   // 分隔线
    val grey300 = Color(0xFFEEEEEE)   // 按钮描边
    val grey400 = Color(0xFFE2E2E2)   // 列表卡片描边
    val grey500 = Color(0xFFBABABA)   // 禁用文字
    val grey700 = Color(0xFF7F7F7F)   // 列表副标题
    val grey800 = Color(0xFF636363)   // App Bar 标题、按钮文字
    val grey900 = Color(0xFF333333)   // 列表标题、正文

    val blue100 = Color(0xFFE7EDFD)   // Blue/100 图标按钮底
    val blue300 = Color(0xFF5985EB)   // 列表按下
    val blue400 = Color(0xFF5079D9)   // 文字按钮 / 链接
    val blue500 = Color(0xFF3F68E2)   // 主按钮
    val blue600 = Color(0xFF2F54D7)   // 主按钮按下

    val page = Color(0xFFF5F5F5)      // 页面底
    val redStroke = Color(0xFFDA463E)
    val redDeep = Color(0xFFB7241F)
    val redContainer = Color(0xFFFFE7E5)
}

private val smartisanLight = lightColorScheme(
    primary = SmartisanPalette.blue500,
    onPrimary = SmartisanPalette.white,
    primaryContainer = SmartisanPalette.blue100,
    onPrimaryContainer = SmartisanPalette.blue600,
    inversePrimary = SmartisanPalette.blue300,
    secondary = SmartisanPalette.grey800,
    onSecondary = SmartisanPalette.white,
    secondaryContainer = SmartisanPalette.grey200,
    onSecondaryContainer = SmartisanPalette.grey900,
    tertiary = SmartisanPalette.redStroke,
    onTertiary = SmartisanPalette.white,
    tertiaryContainer = SmartisanPalette.redContainer,
    onTertiaryContainer = SmartisanPalette.redDeep,
    error = SmartisanPalette.redStroke,
    onError = SmartisanPalette.white,
    errorContainer = SmartisanPalette.redContainer,
    onErrorContainer = SmartisanPalette.redDeep,
    background = SmartisanPalette.page,
    onBackground = SmartisanPalette.grey900,
    surface = SmartisanPalette.white,
    onSurface = SmartisanPalette.grey900,
    surfaceVariant = SmartisanPalette.grey100,
    onSurfaceVariant = SmartisanPalette.grey700,
    inverseSurface = SmartisanPalette.grey900,
    inverseOnSurface = SmartisanPalette.grey100,
    outline = SmartisanPalette.grey500,
    outlineVariant = SmartisanPalette.grey400,
    scrim = Color.Black,
    surfaceBright = SmartisanPalette.white,
    surfaceDim = SmartisanPalette.grey400,
    surfaceContainerLowest = SmartisanPalette.white,
    surfaceContainerLow = SmartisanPalette.grey100,
    surfaceContainer = Color(0xFFF7F7F7),
    surfaceContainerHigh = SmartisanPalette.grey200,
    surfaceContainerHighest = SmartisanPalette.grey300,
)

// Kit 无暗色规范：按同一灰阶把「白纸/浅页面」翻成「深板/更深页面」，层级关系保持一致
private val smartisanDark = darkColorScheme(
    primary = Color(0xFF5C89F2),
    onPrimary = Color(0xFF10131A),
    primaryContainer = Color(0xFF24334F),
    onPrimaryContainer = Color(0xFFC9D8FF),
    inversePrimary = SmartisanPalette.blue400,
    secondary = Color(0xFFB9B9B9),
    onSecondary = Color(0xFF1E1E1E),
    secondaryContainer = Color(0xFF333333),
    onSecondaryContainer = Color(0xFFE4E4E4),
    tertiary = Color(0xFFE8776F),
    onTertiary = Color(0xFF331110),
    tertiaryContainer = Color(0xFF5A211E),
    onTertiaryContainer = Color(0xFFFFE0DD),
    error = Color(0xFFE8776F),
    onError = Color(0xFF331110),
    errorContainer = Color(0xFF5A211E),
    onErrorContainer = Color(0xFFFFE0DD),
    background = Color(0xFF1B1B1B),
    onBackground = Color(0xFFE6E6E6),
    surface = Color(0xFF242424),
    onSurface = Color(0xFFE6E6E6),
    surfaceVariant = Color(0xFF2E2E2E),
    onSurfaceVariant = Color(0xFFB0B0B0),
    inverseSurface = Color(0xFFE6E6E6),
    inverseOnSurface = Color(0xFF1B1B1B),
    outline = Color(0xFF6E6E6E),
    outlineVariant = Color(0xFF3A3A3A),
    scrim = Color.Black,
    surfaceBright = Color(0xFF333333),
    surfaceDim = Color(0xFF141414),
    surfaceContainerLowest = Color(0xFF121212),
    surfaceContainerLow = Color(0xFF1B1B1B),
    surfaceContainer = Color(0xFF212121),
    surfaceContainerHigh = Color(0xFF2A2A2A),
    surfaceContainerHighest = Color(0xFF333333),
)

val SmartisanColorScheme: ColorSchemeDayNight by lazy {
    ColorSchemeDayNight(lightColor = smartisanLight, darkColor = smartisanDark)
}
