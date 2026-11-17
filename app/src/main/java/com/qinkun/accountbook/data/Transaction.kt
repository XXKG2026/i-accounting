package com.qinkun.accountbook.data

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class TxType { EXPENSE, INCOME }

@Entity(tableName = "transactions")
data class Transaction(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 金额，单位为「分」。用整数存避免浮点误差 */
    val amountCents: Long,
    val type: TxType,
    /** 分类：餐饮/购物/交通/转账/其他 */
    val category: String = "其他",
    /** 商户名，从通知标题提取 */
    val merchant: String = "",
    /** 来源 App 显示名，如 美团/京东 */
    val sourceApp: String = "",
    val note: String = "",
    /** 发生时间（毫秒） */
    val occurredAt: Long = System.currentTimeMillis(),
    /** 原始通知文本，便于回溯纠错 */
    val rawText: String = "",
    val createdAt: Long = System.currentTimeMillis(),
) {
    val yuan: Double get() = amountCents / 100.0
    val yuanText: String get() = String.format("%.2f", amountCents / 100.0)
}
