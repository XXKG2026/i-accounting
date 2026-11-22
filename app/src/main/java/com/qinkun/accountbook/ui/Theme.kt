package com.qinkun.accountbook.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object AppColor {
    val paper = Color(0xFFF7F5F0)
    val surface = Color(0xFFFFFFFF)
    val ink = Color(0xFF1F2022)
    val inkSoft = Color(0xFF5A5D60)
    val line = Color(0xFFE4E1DA)
    val expense = Color(0xFFB83B2B)
    val income = Color(0xFF2E7D5B)
    val accent = Color(0xFF7B9B8E)
    /** v4.0：强调色的浅底，用于选中态、头像底色 */
    val accentSoft = Color(0xFFE3ECE7)
    /** v4.0：概览卡片的深色底 */
    val accentDeep = Color(0xFF4F6F63)

    val palette = listOf(
        Color(0xFF7B9B8E), Color(0xFFB83B2B), Color(0xFFD8A657),
        Color(0xFF6B8E9F), Color(0xFF9E7E9B), Color(0xFF8FA96B),
        Color(0xFFC98B6B), Color(0xFF7A8B99),
    )
}

/**
 * v4.0：统一的 Material3 主题。
 *
 * 以前根本没有套 MaterialTheme —— 各页面自己手写的颜色是纸色+薄荷绿，
 * 但 FilterChip、底栏、对话框按钮用的是 Material 默认紫色，整体看起来像两套 App 拼的。
 */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = AppColor.accentDeep,
            onPrimary = Color.White,
            primaryContainer = AppColor.accentSoft,
            onPrimaryContainer = AppColor.ink,
            secondary = AppColor.accent,
            onSecondary = Color.White,
            secondaryContainer = AppColor.accentSoft,
            onSecondaryContainer = AppColor.ink,
            background = AppColor.paper,
            onBackground = AppColor.ink,
            surface = AppColor.surface,
            onSurface = AppColor.ink,
            surfaceVariant = AppColor.paper,
            onSurfaceVariant = AppColor.inkSoft,
            surfaceContainer = AppColor.surface,
            surfaceContainerHigh = AppColor.surface,
            outline = AppColor.line,
            outlineVariant = AppColor.line,
            error = AppColor.expense,
        ),
        content = content,
    )
}

/** v4.0：分类的图标（emoji，不引入额外资源）。没收录的分类一律用 📝 */
object CategoryStyle {
    private val EMOJI = mapOf(
        "餐饮" to "🍜", "购物" to "🛍", "交通" to "🚇", "跑腿快递" to "📦",
        "日用" to "🧴", "娱乐" to "🎮", "医疗" to "💊", "住房" to "🏠",
        "通讯" to "📱", "数码" to "💻", "教育" to "📚", "人情" to "🎁",
        "宠物" to "🐾", "经营" to "💼", "转账" to "🔁", "工资" to "💰",
        "红包" to "🧧", "退款" to "↩", "理财" to "📈", "报销" to "🧾", "其他" to "📝",
    )

    fun emoji(category: String): String = EMOJI[category] ?: "📝"

    /** 按列表位置取色：饼图和下面的排行用同一个下标，颜色一一对应 */
    fun colorAt(index: Int): Color = AppColor.palette[index % AppColor.palette.size]
}
