package com.qinkun.accountbook.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qinkun.accountbook.MainViewModel
import com.qinkun.accountbook.data.GroupSum
import com.qinkun.accountbook.data.Period
import com.qinkun.accountbook.data.Repo
import com.qinkun.accountbook.data.StatsCalc
import com.qinkun.accountbook.data.TrendPoint
import com.qinkun.accountbook.data.TxType

/** 统计维度：分类 / 来源 App / 商户 */
private enum class StatsDim(val label: String) {
    CATEGORY("分类"), SOURCE("来源 App"), MERCHANT("商户 Top10")
}

private fun fmtYuan(cents: Long): String = String.format("%.2f", cents / 100.0)

/**
 * 统计页（v4.0 重做）。
 *
 * 结构自上而下：周期切换 → 概览卡（结余 / 支出 / 收入 / 日均 / 笔数）
 * → 收支 + 维度切换 → 趋势柱状图 → 环形图 → 排行。
 *
 * 整页一个 LazyColumn：以前环形图固定在上半屏，下面的列表只剩一小截能滚，
 * 小屏手机上几乎看不到排行。
 */
@Composable
fun StatsScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val ps by vm.periodState.collectAsState()
    val title by vm.rangeTitle.collectAsState()
    val tx by vm.transactions.collectAsState()
    // 整期合计来自数据库聚合，和首页口径一致
    val totalExpense by vm.expense.collectAsState()
    val totalIncome by vm.income.collectAsState()
    val balance = totalIncome - totalExpense

    var showExpense by remember { mutableStateOf(true) }
    var dim by remember { mutableStateOf(StatsDim.CATEGORY) }
    val type = if (showExpense) TxType.EXPENSE else TxType.INCOME
    val label = if (showExpense) "支出" else "收入"
    val typeColor = if (showExpense) AppColor.expense else AppColor.income

    val groups: List<GroupSum> = remember(tx, type, dim) {
        when (dim) {
            StatsDim.CATEGORY -> StatsCalc.byCategory(tx, type)
            StatsDim.SOURCE -> StatsCalc.bySource(tx, type)
            StatsDim.MERCHANT -> StatsCalc.topMerchants(tx, type, 10)
        }
    }
    val typeTotal = remember(tx, type) { tx.filter { it.type == type }.sumOf { it.amountCents } }
    val expCount = remember(tx) { tx.count { it.type == TxType.EXPENSE } }
    val incCount = remember(tx) { tx.count { it.type == TxType.INCOME } }
    val trend: List<TrendPoint> = remember(tx, ps) {
        val (start, _) = Repo.rangeFor(ps.period, ps.offset)
        StatsCalc.trend(ps.period, start, tx)
    }
    val days = remember(ps, tx) {
        val (f, t) = Repo.rangeForStats(ps.period, ps.offset)
        StatsCalc.elapsedDays(f, t)
    }

    LazyColumn(
        modifier.fillMaxSize().background(AppColor.paper),
        contentPadding = PaddingValues(bottom = 28.dp),
    ) {
        // ---- 周期切换 + 翻页 ----
        item(key = "period") {
            Column {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Period.entries.forEach { p ->
                        FilterChip(ps.period == p, { vm.setPeriod(p) }, { Text(p.label, fontSize = 12.sp) })
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(onClick = { vm.shift(-1) }) {
                        Icon(Icons.Filled.ChevronLeft, "上一期", tint = AppColor.inkSoft)
                    }
                    Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppColor.ink)
                    IconButton(onClick = { vm.shift(1) }) {
                        Icon(Icons.Filled.ChevronRight, "下一期", tint = AppColor.inkSoft)
                    }
                }
            }
        }

        // ---- 概览卡 ----
        item(key = "overview") {
            Surface(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                shape = RoundedCornerShape(18.dp),
                color = AppColor.accentDeep,
            ) {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
                    Text("结余", fontSize = 12.sp, color = Color.White.copy(alpha = 0.75f))
                    Spacer(Modifier.height(2.dp))
                    Text(
                        (if (balance < 0) "-" else "") + fmtYuan(kotlin.math.abs(balance)),
                        fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth()) {
                        OverviewNum("支出", fmtYuan(totalExpense), "$expCount 笔", Modifier.weight(1f))
                        OverviewNum("收入", fmtYuan(totalIncome), "$incCount 笔", Modifier.weight(1f))
                        OverviewNum(
                            "日均支出",
                            if (days > 0) fmtYuan(totalExpense / days) else "0.00",
                            if (days > 0) "按 $days 天" else "—",
                            Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        // ---- 收支 + 维度切换 ----
        item(key = "switch") {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(showExpense, { showExpense = true }, { Text("支出", fontSize = 12.sp) })
                    FilterChip(!showExpense, { showExpense = false }, { Text("收入", fontSize = 12.sp) })
                }
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    StatsDim.entries.forEach { d ->
                        FilterChip(dim == d, { dim = d }, { Text("按" + d.label, fontSize = 12.sp) })
                    }
                }
            }
        }

        // ---- 趋势 ----
        item(key = "trend") {
            Card16 {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label + "趋势", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppColor.ink)
                    Spacer(Modifier.weight(1f))
                    val peak = trend.maxOfOrNull { if (showExpense) it.expense else it.income } ?: 0L
                    if (peak > 0) Text("峰值 " + fmtYuan(peak), fontSize = 11.sp, color = AppColor.inkSoft)
                }
                Spacer(Modifier.height(10.dp))
                TrendBars(trend.map { if (showExpense) it.expense else it.income }, typeColor)
                Spacer(Modifier.height(4.dp))
                TrendLabels(trend.map { it.label })
            }
        }

        if (groups.isEmpty()) {
            item(key = "empty") {
                Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                    Text("这段时间没有" + label + "记录", color = AppColor.inkSoft, fontSize = 14.sp)
                }
            }
            return@LazyColumn
        }

        // ---- 环形图（商户 Top10 只是局部，画饼没有意义）----
        if (dim != StatsDim.MERCHANT) {
            item(key = "donut") {
                Card16 {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        DonutChart(groups.map { it.total }, typeTotal, Modifier.size(180.dp)) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(label + " · " + dim.label, fontSize = 11.sp, color = AppColor.inkSoft)
                                Spacer(Modifier.height(4.dp))
                                Text(fmtYuan(typeTotal), fontSize = 19.sp, fontWeight = FontWeight.Bold, color = AppColor.ink)
                            }
                        }
                    }
                }
            }
        }

        // ---- 排行 ----
        itemsIndexed(groups, key = { _, g -> "g_" + dim.name + "_" + g.key }) { i, g ->
            val pct = if (typeTotal > 0) g.total * 100.0 / typeTotal else 0.0
            val dot = CategoryStyle.colorAt(i)
            Surface(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp),
                shape = RoundedCornerShape(12.dp),
                color = AppColor.surface,
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (dim == StatsDim.CATEGORY) {
                        Box(
                            Modifier.size(32.dp).background(dot.copy(alpha = 0.16f), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { Text(CategoryStyle.emoji(g.key), fontSize = 15.sp) }
                    } else {
                        Box(
                            Modifier.size(32.dp).background(dot.copy(alpha = 0.16f), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("${i + 1}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = dot)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                g.key, fontSize = 14.sp, color = AppColor.ink,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("${g.count} 笔", fontSize = 11.sp, color = AppColor.inkSoft)
                        }
                        Spacer(Modifier.height(5.dp))
                        LinearProgressIndicator(
                            progress = { (pct / 100.0).toFloat().coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(0.85f).height(4.dp),
                            color = dot,
                            trackColor = AppColor.line,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        Text(fmtYuan(g.total), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppColor.ink)
                        Text(String.format("%.1f%%", pct), fontSize = 11.sp, color = AppColor.inkSoft)
                    }
                }
            }
        }
    }
}

/** 白底圆角卡片，统计页里所有区块共用 */
@Composable
private fun Card16(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        color = AppColor.surface,
    ) {
        Column(Modifier.padding(14.dp), content = content)
    }
}

@Composable
private fun OverviewNum(label: String, value: String, sub: String, modifier: Modifier) {
    Column(modifier) {
        Text(label, fontSize = 11.sp, color = Color.White.copy(alpha = 0.7f))
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1)
        Text(sub, fontSize = 10.sp, color = Color.White.copy(alpha = 0.6f))
    }
}

/** 趋势柱状图。全为 0 时画一排浅色底柱，让人知道「这段时间是空的」而不是「图没加载」 */
@Composable
private fun TrendBars(values: List<Long>, color: Color) {
    val max = values.maxOrNull() ?: 0L
    Canvas(Modifier.fillMaxWidth().height(110.dp)) {
        if (values.isEmpty()) return@Canvas
        val slot = size.width / values.size
        val barW = (slot * 0.62f).coerceAtLeast(2f)
        val radius = CornerRadius(barW / 2.5f, barW / 2.5f)
        values.forEachIndexed { i, v ->
            val x = i * slot + (slot - barW) / 2f
            // 底柱
            drawRoundRect(
                color = AppColor.line.copy(alpha = 0.6f),
                topLeft = Offset(x, 0f),
                size = Size(barW, size.height),
                cornerRadius = radius,
            )
            if (v > 0 && max > 0) {
                val h = (v.toFloat() / max * size.height).coerceAtLeast(3f)
                drawRoundRect(
                    color = color,
                    topLeft = Offset(x, size.height - h),
                    size = Size(barW, h),
                    cornerRadius = radius,
                )
            }
        }
    }
}

/** 柱子下面的刻度。柱子多（一个月 31 根）时只标首、尾和大约每 1/6 处，避免挤成一团 */
@Composable
private fun TrendLabels(labels: List<String>) {
    if (labels.isEmpty()) return
    val step = if (labels.size <= 12) 1 else (labels.size + 5) / 6
    Row(Modifier.fillMaxWidth()) {
        labels.forEachIndexed { i, l ->
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                if (i % step == 0 || i == labels.lastIndex) {
                    Text(l, fontSize = 9.sp, color = AppColor.inkSoft, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

@Composable
private fun DonutChart(values: List<Long>, total: Long, modifier: Modifier, center: @Composable () -> Unit) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = size.minDimension * 0.16f
            val diameter = size.minDimension - stroke
            var start = -90f
            values.forEachIndexed { i, v ->
                val sweep = if (total > 0) v.toFloat() / total * 360f else 0f
                if (sweep <= 0f) return@forEachIndexed
                drawArc(
                    color = CategoryStyle.colorAt(i),
                    startAngle = start,
                    // 只有一块时不留缝，否则 12 点方向会有一道白线
                    sweepAngle = if (values.size == 1) sweep else (sweep - 1.2f).coerceAtLeast(0.5f),
                    useCenter = false,
                    topLeft = Offset(stroke / 2, stroke / 2),
                    size = Size(diameter, diameter),
                    style = Stroke(width = stroke)
                )
                start += sweep
            }
        }
        center()
    }
}
