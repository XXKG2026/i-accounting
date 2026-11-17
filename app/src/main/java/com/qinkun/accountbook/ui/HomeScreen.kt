package com.qinkun.accountbook.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
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

@Composable
fun HomeScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val ps by vm.periodState.collectAsState()
    val title by vm.rangeTitle.collectAsState()
    val tx by vm.transactions.collectAsState()
    val expense by vm.expense.collectAsState()
    val income by vm.income.collectAsState()
    var showAdd by remember { mutableStateOf(false) }
    var selectedTx by remember { mutableStateOf<Transaction?>(null) }
    var deleteTarget by remember { mutableStateOf<Transaction?>(null) }

    Box(modifier.fillMaxSize().background(AppColor.paper)) {
        Column(Modifier.fillMaxSize()) {
            // 周期切换
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Period.entries.forEach { p ->
                    FilterChip(
                        selected = ps.period == p,
                        onClick = { vm.setPeriod(p) },
                        label = { Text(p.label, fontSize = 12.sp) }
                    )
                }
            }

            // 区间标题 + 前后翻
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = { vm.shift(-1) }) {
                    Icon(Icons.Filled.ChevronLeft, "上一期", tint = AppColor.inkSoft)
                }
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = AppColor.ink)
                IconButton(onClick = { vm.shift(1) }) {
                    Icon(Icons.Filled.ChevronRight, "下一期", tint = AppColor.inkSoft)
                }
            }

            // 三张汇总卡
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SummaryCard("支出", expense, AppColor.expense, Modifier.weight(1f))
                SummaryCard("收入", income, AppColor.income, Modifier.weight(1f))
                SummaryCard("结余", income - expense, AppColor.ink, Modifier.weight(1f))
            }

            Text(
                "共 ${tx.size} 笔 · 点开可修改删除",
                Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                fontSize = 12.sp, color = AppColor.inkSoft
            )

            if (tx.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("这段时间没有记录", color = AppColor.inkSoft, fontSize = 15.sp)
                        Spacer(Modifier.height(8.dp))
                        Text("授权通知读取后会自动记账", color = AppColor.inkSoft, fontSize = 12.sp)
                    }
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 88.dp)
                ) {
                    items(tx, key = { it.id }) { t ->
                        TxRow(t, onClick = { selectedTx = t }, onLongClick = { deleteTarget = t })
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = { showAdd = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            containerColor = AppColor.ink,
        ) {
            Icon(Icons.Filled.Add, "记一笔", tint = AppColor.surface)
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
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除这笔？", fontSize = 16.sp) },
            text = {
                Text(
                    "${t.merchant.ifBlank { t.category }}  ${t.yuanText} 元\n${t.category}",
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.delete(t); deleteTarget = null }) {
                    Text("删除", color = AppColor.expense, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            }
        )
    }

    if (showAdd) {
        AddDialog(
            onDismiss = { showAdd = false },
            onConfirm = { cents, type, cat, merchant, note ->
                vm.addManual(cents, type, cat, merchant, note)
                showAdd = false
            }
        )
    }
}

@Composable
private fun SummaryCard(label: String, cents: Long, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    Surface(modifier, shape = RoundedCornerShape(12.dp), color = AppColor.surface) {
        Column(Modifier.padding(vertical = 14.dp, horizontal = 10.dp)) {
            Text(label, fontSize = 12.sp, color = AppColor.inkSoft)
            Spacer(Modifier.height(6.dp))
            Text(
                String.format("%.2f", cents / 100.0),
                fontSize = 16.sp, fontWeight = FontWeight.Bold, color = color
            )
        }
    }
}

@Composable
private fun AddDialog(onDismiss: () -> Unit, onConfirm: (Long, TxType, String, String, String) -> Unit) {
    var amount by remember { mutableStateOf("") }
    var isExpense by remember { mutableStateOf(true) }
    var category by remember { mutableStateOf("餐饮") }
    var merchant by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val cats = if (isExpense) EXPENSE_CATEGORIES else INCOME_CATEGORIES

    LaunchedEffect(isExpense) { if (category !in cats) category = cats.first() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("记一笔") },
        text = {
            Column {
                Row {
                    FilterChip(isExpense, { isExpense = true }, { Text("支出") })
                    Spacer(Modifier.width(8.dp))
                    FilterChip(!isExpense, { isExpense = false }, { Text("收入") })
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = amount, onValueChange = { amount = it },
                    label = { Text("金额（元）") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal
                    ),
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = merchant, onValueChange = { merchant = it },
                    label = { Text("商户（可空）") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                Spacer(Modifier.height(10.dp))
                Text("分类", fontSize = 12.sp, color = AppColor.inkSoft)
                Spacer(Modifier.height(6.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    cats.chunked(4).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { c ->
                                FilterChip(category == c, { category = c }, { Text(c, fontSize = 11.sp) })
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = note, onValueChange = { note = it },
                    label = { Text("备注（可空）") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val d = amount.toDoubleOrNull()
                if (d != null && d > 0) {
                    onConfirm(Math.round(d * 100), if (isExpense) TxType.EXPENSE else TxType.INCOME, category, merchant, note)
                }
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
