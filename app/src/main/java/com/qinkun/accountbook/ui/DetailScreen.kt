package com.qinkun.accountbook.ui

import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.Icons
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import com.qinkun.accountbook.data.StatsCalc
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qinkun.accountbook.MainViewModel
import com.qinkun.accountbook.data.Period
import com.qinkun.accountbook.data.Transaction
import com.qinkun.accountbook.data.TxType
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun DetailScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val ps by vm.periodState.collectAsState()
    val title by vm.rangeTitle.collectAsState()
    val tx by vm.transactions.collectAsState()
    var selectedTx by remember { mutableStateOf<Transaction?>(null) }
    var deleteTarget by remember { mutableStateOf<Transaction?>(null) }
    val dayFmt = remember { SimpleDateFormat("M 月 d 日 EEEE", Locale.CHINA) }

    // v4.0：搜索 + 收支 / 来源筛选
    var query by remember { mutableStateOf("") }
    var typeFilter by remember { mutableStateOf<TxType?>(null) }
    var sourceFilter by remember { mutableStateOf<String?>(null) }
    val sources = remember(tx) {
        tx.groupingBy { it.sourceApp.ifBlank { "手动" } }.eachCount()
            .entries.sortedByDescending { it.value }.map { it.key }
    }
    // 翻到别的周期后，原来选的来源可能已经不存在了
    LaunchedEffect(sources) {
        if (sourceFilter != null && sourceFilter !in sources) sourceFilter = null
    }
    val shown = remember(tx, query, typeFilter, sourceFilter) {
        StatsCalc.filter(tx, query, typeFilter, sourceFilter)
    }
    val filtering = query.isNotBlank() || typeFilter != null || sourceFilter != null

    val grouped = shown.groupBy {
        val c = Calendar.getInstance().apply { timeInMillis = it.occurredAt }
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        c.timeInMillis
    }.toSortedMap(compareByDescending { it })

    Column(modifier.fillMaxSize().background(AppColor.paper)) {
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
                Icon(androidx.compose.material.icons.Icons.Filled.ChevronLeft, "上一期", tint = AppColor.inkSoft)
            }
            Text("$title · 明细", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppColor.ink)
            IconButton(onClick = { vm.shift(1) }) {
                Icon(androidx.compose.material.icons.Icons.Filled.ChevronRight, "下一期", tint = AppColor.inkSoft)
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            singleLine = true,
            placeholder = { Text("搜索商户 / 备注 / 分类 / 金额", fontSize = 13.sp) },
            leadingIcon = { Icon(Icons.Filled.Search, null, tint = AppColor.inkSoft) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Filled.Close, "清空", tint = AppColor.inkSoft)
                    }
                }
            },
            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilterChip(typeFilter == null, { typeFilter = null }, { Text("全部", fontSize = 12.sp) })
            FilterChip(typeFilter == TxType.EXPENSE, {
                typeFilter = if (typeFilter == TxType.EXPENSE) null else TxType.EXPENSE
            }, { Text("支出", fontSize = 12.sp) })
            FilterChip(typeFilter == TxType.INCOME, {
                typeFilter = if (typeFilter == TxType.INCOME) null else TxType.INCOME
            }, { Text("收入", fontSize = 12.sp) })
            sources.forEach { s ->
                FilterChip(sourceFilter == s, {
                    sourceFilter = if (sourceFilter == s) null else s
                }, { Text(s, fontSize = 12.sp) })
            }
        }
        if (filtering) {
            val e = shown.filter { it.type == TxType.EXPENSE }.sumOf { it.amountCents }
            val i = shown.filter { it.type == TxType.INCOME }.sumOf { it.amountCents }
            Text(
                "筛选出 ${shown.size} 笔 · 支 ${String.format("%.2f", e / 100.0)} · 收 ${String.format("%.2f", i / 100.0)}",
                fontSize = 11.sp, color = AppColor.inkSoft,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
            )
        }

        if (grouped.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (filtering) "没有符合条件的记录" else "没有记录", color = AppColor.inkSoft)
            }
            return@Column
        }

        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            grouped.forEach { (day, list) ->
                item(key = "h$day") {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(dayFmt.format(Date(day)), fontSize = 12.sp, color = AppColor.inkSoft)
                        val e = list.filter { it.type == TxType.EXPENSE }.sumOf { it.amountCents }
                        val i = list.filter { it.type == TxType.INCOME }.sumOf { it.amountCents }
                        Text(
                            buildString {
                                if (e > 0) append("支 ").append(String.format("%.2f", e / 100.0))
                                if (e > 0 && i > 0) append("  ")
                                if (i > 0) append("收 ").append(String.format("%.2f", i / 100.0))
                            },
                            fontSize = 11.sp, color = AppColor.inkSoft
                        )
                    }
                }
                items(list, key = { it.id }) { t ->
                    Box(Modifier.padding(bottom = 8.dp)) {
                        TxRow(t, onClick = { selectedTx = t }, onLongClick = { deleteTarget = t })
                    }
                }
            }
        }
    }

    selectedTx?.let { t ->
        TxDetailDialog(
            tx = t,
            onDismiss = { selectedTx = null },
            onSave = { vm.updateTx(it) },
            onDelete = { vm.delete(t) },
        )
    }

    deleteTarget?.let { t ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { androidx.compose.material3.Text("删除这笔？", fontSize = 16.sp) },
            text = {
                androidx.compose.material3.Text("${t.merchant.ifBlank { t.category }}  ${t.yuanText} 元", fontSize = 14.sp)
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { vm.delete(t); deleteTarget = null }) {
                    androidx.compose.material3.Text("删除", color = AppColor.expense, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { deleteTarget = null }) {
                    androidx.compose.material3.Text("取消")
                }
            }
        )
    }
}