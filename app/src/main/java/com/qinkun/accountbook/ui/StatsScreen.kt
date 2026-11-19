package com.qinkun.accountbook.ui

import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.Icons
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qinkun.accountbook.MainViewModel
import com.qinkun.accountbook.data.Period

@Composable
fun StatsScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val ps by vm.periodState.collectAsState()
    val title by vm.rangeTitle.collectAsState()
    val expCats by vm.expenseByCategory.collectAsState()
    val incCats by vm.incomeByCategory.collectAsState()
    // 整期合计：给「结余」用。下面的支出/收入切换只影响分类明细，不影响这里
    val totalExpense by vm.expense.collectAsState()
    val totalIncome by vm.income.collectAsState()
    val balance = totalIncome - totalExpense
    var showExpense by remember { mutableStateOf(true) }

    val data = if (showExpense) expCats else incCats
    val total = data.sumOf { it.total }
    val label = if (showExpense) "支出" else "收入"

    Column(modifier.fillMaxSize().background(AppColor.paper)) {
        // 周期切换
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

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChip(showExpense, { showExpense = true }, { Text("支出", fontSize = 12.sp) })
            FilterChip(!showExpense, { showExpense = false }, { Text("收入", fontSize = 12.sp) })
        }

        // 整期三个数：支出 / 收入 / 结余。
        // 放在空状态判断之前 —— 某个月只有收入没有支出时，也要能看到结余。
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            SummaryNum("支出", totalExpense, AppColor.expense)
            SummaryNum("收入", totalIncome, AppColor.income)
            SummaryNum("结余", balance, if (balance < 0) AppColor.expense else AppColor.ink)
        }

        if (data.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("这段时间没有" + label + "记录", color = AppColor.inkSoft, fontSize = 14.sp)
            }
            return@Column
        }

        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
            DonutChart(data.map { it.total }, total, Modifier.size(190.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(label, fontSize = 12.sp, color = AppColor.inkSoft)
                    Spacer(Modifier.height(4.dp))
                    Text(String.format("%.2f", total / 100.0), fontSize = 19.sp, fontWeight = FontWeight.Bold, color = AppColor.ink)
                }
            }
        }

        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            items(data) { c ->
                val pct = if (total > 0) c.total * 100.0 / total else 0.0
                Surface(shape = RoundedCornerShape(10.dp), color = AppColor.surface) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(c.category, fontSize = 14.sp, color = AppColor.ink)
                            Spacer(Modifier.height(4.dp))
                            LinearProgressIndicator(
                                progress = { (pct / 100.0).toFloat() },
                                modifier = Modifier.fillMaxWidth(0.7f).height(4.dp),
                                color = AppColor.accent,
                                trackColor = AppColor.line
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(String.format("%.2f", c.total / 100.0), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppColor.ink)
                            Text(String.format("%.1f%%", pct), fontSize = 11.sp, color = AppColor.inkSoft)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DonutChart(values: List<Long>, total: Long, modifier: Modifier, center: @Composable () -> Unit) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier.fillMaxSize()) {
            val stroke = size.minDimension * 0.16f
            val diameter = size.minDimension - stroke
            var start = -90f
            values.forEachIndexed { i, v ->
                val sweep = if (total > 0) v.toFloat() / total * 360f else 0f
                if (sweep <= 0f) return@forEachIndexed
                drawArc(
                    color = AppColor.palette[i % AppColor.palette.size],
                    startAngle = start,
                    sweepAngle = sweep - 1.2f,
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

/** 统计页顶部的一个数字（支出 / 收入 / 结余），三栏等宽排开 */
@Composable
private fun SummaryNum(label: String, cents: Long, color: androidx.compose.ui.graphics.Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 11.sp, color = AppColor.inkSoft)
        Spacer(Modifier.height(2.dp))
        Text(
            String.format("%.2f", cents / 100.0),
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = color,
        )
    }
}
