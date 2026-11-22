package com.qinkun.accountbook.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qinkun.accountbook.data.Transaction
import com.qinkun.accountbook.data.TxType
import com.qinkun.accountbook.util.parseYuanInput
import java.text.SimpleDateFormat
import java.util.*

/**
 * 支出分类。
 *
 * 必须和 CategoryRules 能产出的分类一一对应 —— 少一个，自动记账抓到那一类后，
 * 点开编辑就会因为它不在可选列表里而被静默改成「餐饮」（B 那一版就这样坑过）。
 * 新增「经营」：差旅、进货、办公采购这类生意开支，不该混进个人消费统计里。
 */
val EXPENSE_CATEGORIES = listOf(
    "餐饮", "购物", "交通", "跑腿快递", "日用", "娱乐",
    "医疗", "住房", "通讯", "数码", "教育", "人情", "宠物",
    "经营", "转账", "其他"
)

/** 收入分类 */
val INCOME_CATEGORIES = listOf(
    "经营", "转账", "工资", "红包", "退款", "理财", "报销", "其他"
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TxRow(
    t: Transaction,
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = {},
) {
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.CHINA) }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = AppColor.surface,
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // v4.0：分类图标，扫一眼就知道是吃饭还是打车
            Box(
                Modifier
                    .size(36.dp)
                    .background(
                        if (t.type == TxType.EXPENSE) AppColor.accentSoft else AppColor.income.copy(alpha = 0.12f),
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(CategoryStyle.emoji(t.category), fontSize = 16.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        t.merchant.ifBlank { t.category },
                        fontSize = 15.sp, color = AppColor.ink,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (t.sourceApp.isNotBlank()) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            t.sourceApp,
                            fontSize = 10.sp, color = AppColor.inkSoft,
                            modifier = Modifier
                                .background(AppColor.paper, RoundedCornerShape(4.dp))
                                .padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    "${t.category} · ${fmt.format(Date(t.occurredAt))}" +
                        if (t.note.isNotBlank()) " · ${t.note}" else "",
                    fontSize = 11.sp, color = AppColor.inkSoft
                )
            }
            Text(
                (if (t.type == TxType.EXPENSE) "-" else "+") + t.yuanText,
                fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                color = if (t.type == TxType.EXPENSE) AppColor.expense else AppColor.income
            )
        }
    }
}

/**
 * 详情 + 编辑 + 删除。
 * 上半部分显示原始通知，用来核对 AI 有没有认错。
 */
@Composable
fun TxDetailDialog(
    tx: Transaction,
    onDismiss: () -> Unit,
    onSave: (Transaction) -> Unit,
    onDelete: () -> Unit,
) {
    var amount by remember(tx.id) { mutableStateOf(tx.yuanText) }
    var isExpense by remember(tx.id) { mutableStateOf(tx.type == TxType.EXPENSE) }
    var category by remember(tx.id) { mutableStateOf(tx.category) }
    var merchant by remember(tx.id) { mutableStateOf(tx.merchant) }
    var note by remember(tx.id) { mutableStateOf(tx.note) }
    var confirmDelete by remember(tx.id) { mutableStateOf(false) }

    var cats = if (isExpense) EXPENSE_CATEGORIES else INCOME_CATEGORIES
    // 原分类可能不在当前列表里（老数据 / 外部导入）。
        // 以前这里直接改成第一项「餐饮」，等于用户只改个金额就把分类弄丢了。
        // 现在保留原值，并把它塞进列表，让用户看得见、能改。
        if (category !in cats && category.isNotBlank()) {
            cats = cats + category
        }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("这笔账", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (tx.sourceApp.isNotBlank()) tx.sourceApp else "手动",
                    fontSize = 11.sp, color = AppColor.inkSoft
                )
            }
        },
        text = {
            Column {
                // 原始通知（核对用）
                if (tx.rawText.isNotBlank()) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = AppColor.paper,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            Text("原始通知", fontSize = 10.sp, color = AppColor.inkSoft)
                            Spacer(Modifier.height(3.dp))
                            Text(tx.rawText, fontSize = 11.sp, color = AppColor.ink)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }

                Row {
                    // v4.0：切换收支方向时，原分类不属于新方向就回落到「其他」。
                    // 以前切成收入后分类仍是「餐饮」，保存后收入统计里冒出一个餐饮。
                    FilterChip(isExpense, {
                        if (!isExpense) {
                            isExpense = true
                            if (category !in EXPENSE_CATEGORIES) category = "其他"
                        }
                    }, { Text("支出") })
                    Spacer(Modifier.width(8.dp))
                    FilterChip(!isExpense, {
                        if (isExpense) {
                            isExpense = false
                            if (category !in INCOME_CATEGORIES) category = "其他"
                        }
                    }, { Text("收入") })
                }
                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = amount, onValueChange = { amount = it },
                    label = { Text("金额（元）") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = merchant, onValueChange = { merchant = it },
                    label = { Text("商户") },
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
                    label = { Text("备注") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )

                Spacer(Modifier.height(12.dp))

                // 删除：整行大按钮，点一次弹确认
                if (!confirmDelete) {
                    OutlinedButton(
                        onClick = { confirmDelete = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AppColor.expense),
                        border = androidx.compose.foundation.BorderStroke(1.dp, AppColor.expense.copy(alpha = 0.5f))
                    ) {
                        Text("删除这笔记录", fontSize = 14.sp)
                    }
                } else {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = AppColor.expense.copy(alpha = 0.1f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text("删了就找不回来了，确定？", fontSize = 13.sp, color = AppColor.expense)
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = { confirmDelete = false },
                                    modifier = Modifier.weight(1f)
                                ) { Text("不了", fontSize = 13.sp) }
                                Button(
                                    onClick = { onDelete(); onDismiss() },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = AppColor.expense)
                                ) { Text("确认删除", fontSize = 13.sp) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (confirmDelete) return@AlertDialog
            TextButton(onClick = {
                // 允许「1,299.00」「¥35」这种从别处粘过来的写法
                val d = parseYuanInput(amount)
                if (d != null && d > 0) {
                    onSave(
                        tx.copy(
                            amountCents = Math.round(d * 100),
                            type = if (isExpense) TxType.EXPENSE else TxType.INCOME,
                            category = category,
                            merchant = merchant,
                            note = note,
                        )
                    )
                    onDismiss()
                }
            }) { Text("保存", fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

