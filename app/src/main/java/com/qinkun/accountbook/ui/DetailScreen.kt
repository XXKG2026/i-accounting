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

    val grouped = tx.groupBy {
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

        if (grouped.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("没有记录", color = AppColor.inkSoft)
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